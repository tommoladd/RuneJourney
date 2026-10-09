package com.runejourney;

import com.runejourney.service.JourneyService;
import java.util.*;
import javax.inject.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

@Slf4j
@Singleton
class IncomeTracker
{
	private enum Outcome
	{
		COUNTED,
		UNMATCHED,
		SKIPPED
	}

	private static final Set<Skill> COMBAT = EnumSet.of(Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.HITPOINTS,
		Skill.RANGED, Skill.SLAYER);
	private static final Set<Integer> BLOCKING_INTERFACES = new HashSet<>();
	private static final int BLOCK_GRACE_TICKS = 2;

	static
	{
		BLOCKING_INTERFACES.add(InterfaceID.BANKMAIN);
		BLOCKING_INTERFACES.add(InterfaceID.BANK_DEPOSITBOX);
		BLOCKING_INTERFACES.add(InterfaceID.GE_OFFERS);
		BLOCKING_INTERFACES.add(InterfaceID.GE_COLLECT);
		BLOCKING_INTERFACES.add(InterfaceID.SHOPMAIN);
		BLOCKING_INTERFACES.add(InterfaceID.TRADEMAIN);
		BLOCKING_INTERFACES.add(InterfaceID.TRADECONFIRM);
	}

	private final ItemManager itemManager;
	private final JourneyService service;

	private Map<Integer, Integer> lastInventory;
	private final Map<Integer, Integer> pendingDelta = new HashMap<>();
	private final Map<Skill, Long> pendingXp = new EnumMap<>(Skill.class);
	private final Map<Integer, Integer> carriedDelta = new HashMap<>();
	private final Map<Skill, Long> carriedXp = new EnumMap<>(Skill.class);
	private final Set<Integer> openBlocking = new HashSet<>();
	private int blockedUntil;
	private int suppressedTick = -1;

	@Inject
	IncomeTracker(ItemManager itemManager, JourneyService service)
	{
		this.itemManager = itemManager;
		this.service = service;
	}

	void reset()
	{
		lastInventory = null;
		pendingDelta.clear();
		pendingXp.clear();
		carriedDelta.clear();
		carriedXp.clear();
		openBlocking.clear();
		blockedUntil = 0;
		suppressedTick = -1;
	}

	void onInventory(ItemContainer container)
	{
		Map<Integer, Integer> now = new HashMap<>();
		for (Item item : container.getItems())
		{
			if (item.getId() > 0 && item.getQuantity() > 0)
			{
				now.merge(itemManager.canonicalize(item.getId()), item.getQuantity(), Integer::sum);
			}
		}
		if (lastInventory != null)
		{
			Set<Integer> ids = new HashSet<>(now.keySet());
			ids.addAll(lastInventory.keySet());
			for (int id : ids)
			{
				int change = now.getOrDefault(id, 0) - lastInventory.getOrDefault(id, 0);
				if (change != 0)
				{
					pendingDelta.merge(id, change, Integer::sum);
				}
			}
		}
		lastInventory = now;
	}

	void discard(Map<Integer, Integer> changes)
	{
		changes.forEach((id, change) -> pendingDelta.merge(id, -change, Integer::sum));
	}

	void onXp(Skill skill, long delta)
	{
		if (delta > 0)
		{
			pendingXp.merge(skill, delta, Long::sum);
		}
	}

	void onInterfaceOpened(int groupId)
	{
		if (BLOCKING_INTERFACES.contains(groupId))
		{
			openBlocking.add(groupId);
		}
	}

	void onInterfaceClosed(int groupId, int tick)
	{
		if (openBlocking.remove(groupId))
		{
			blockedUntil = tick + BLOCK_GRACE_TICKS;
		}
	}

	void suppress(int tick)
	{
		suppressedTick = tick;
	}

	void onTick(int tick)
	{
		boolean carried = !carriedDelta.isEmpty() || !carriedXp.isEmpty();
		carriedDelta.forEach((id, change) -> pendingDelta.merge(id, change, Integer::sum));
		carriedXp.forEach((skill, xp) -> pendingXp.merge(skill, xp, Long::sum));
		carriedDelta.clear();
		carriedXp.clear();
		try
		{
			if (process(tick) == Outcome.UNMATCHED && !carried)
			{
				carriedDelta.putAll(pendingDelta);
				carriedXp.putAll(pendingXp);
			}
		}
		finally
		{
			pendingDelta.clear();
			pendingXp.clear();
		}
	}

	private Outcome process(int tick)
	{
		pendingDelta.values().removeIf(v -> v == 0);
		if ((pendingDelta.isEmpty() && pendingXp.isEmpty()) || !openBlocking.isEmpty() || tick <= blockedUntil
			|| suppressedTick >= tick - 1)
		{
			return Outcome.SKIPPED;
		}
		if (pendingDelta.isEmpty())
		{
			return isCombat(pendingXp.keySet()) ? Outcome.SKIPPED : Outcome.UNMATCHED;
		}

		long coinsGained = 0;
		boolean pouchOpened = false;
		List<SkillingRules.Change> changes = new ArrayList<>();
		Map<String, Integer> ids = new HashMap<>();
		for (Map.Entry<Integer, Integer> e : pendingDelta.entrySet())
		{
			int id = e.getKey();
			int qty = e.getValue();
			String name = itemManager.getItemComposition(id).getName();
			long price = id == ItemID.COINS ? 1 : id == ItemID.PLATINUM ? 1000 : itemManager.getItemPrice(id);
			changes.add(new SkillingRules.Change(name, qty, price));
			ids.put(name, id);
			if (qty > 0 && id == ItemID.COINS)
			{
				coinsGained += qty;
			}
			if (qty < 0 && name != null && name.toLowerCase(Locale.ENGLISH).contains("coin pouch"))
			{
				pouchOpened = true;
			}
		}

		if (pendingXp.isEmpty())
		{
			if (pouchOpened && coinsGained > 0)
			{
				service.onSkillingIncome(Skill.THIEVING, coinsGained,
					Collections.singletonList(new JourneyService.LootItem(ItemID.COINS, "Coins", (int) coinsGained, coinsGained)));
				return Outcome.COUNTED;
			}
			return Outcome.UNMATCHED;
		}
		if (isCombat(pendingXp.keySet()))
		{
			return Outcome.SKIPPED;
		}

		List<Skill> skills = new ArrayList<>(pendingXp.keySet());
		skills.sort((a, b) -> Long.compare(pendingXp.get(b), pendingXp.get(a)));
		SkillingRules.Income income = SkillingRules.evaluate(skills, changes);
		log.debug("Tick {}: XP {}, items {} -> {}", tick, pendingXp, changes, income);
		if (income == null)
		{
			return Outcome.UNMATCHED;
		}
		List<JourneyService.LootItem> products = new ArrayList<>();
		for (SkillingRules.Change c : income.getProducts())
		{
			products.add(new JourneyService.LootItem(ids.getOrDefault(c.getName(), -1), c.getName(), c.getQuantity(),
				c.getUnitPrice() * c.getQuantity()));
		}
		service.onSkillingIncome(income.getSkill(), income.getValue(), products);
		return Outcome.COUNTED;
	}

	static boolean isCombat(Set<Skill> skills)
	{
		for (Skill s : skills)
		{
			if (COMBAT.contains(s) && !(s == Skill.STRENGTH && skills.contains(Skill.FISHING)))
			{
				return true;
			}
		}
		return false;
	}
}
