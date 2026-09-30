package com.runejourney;

import com.runejourney.service.JourneyService;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

/**
 * Works out money made from skilling (thieving, gathering, alching, processing) by watching the
 * inventory. When items change on the same tick as a non-combat XP drop, the net value change is
 * credited to that skill. Must only be used on the client thread.
 */
@Singleton
class IncomeTracker
{
	/**
	 * XP in these skills means the player is fighting; inventory changes then are loot, supplies or
	 * ammo, which are tracked elsewhere.
	 */
	private static final Set<Skill> COMBAT = EnumSet.of(Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.HITPOINTS,
		Skill.RANGED, Skill.SLAYER);
	/**
	 * Interfaces where items move for reasons other than skilling.
	 */
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

	/**
	 * Other loot sources (e.g. the Loot Tracker's pickpocket or Herbiboar events) already counted
	 * this tick's items.
	 */
	void suppress(int tick)
	{
		suppressedTick = tick;
	}

	void onTick(int tick)
	{
		try
		{
			process(tick);
		}
		finally
		{
			pendingDelta.clear();
			pendingXp.clear();
		}
	}

	private void process(int tick)
	{
		pendingDelta.values().removeIf(v -> v == 0);
		if (pendingDelta.isEmpty() || !openBlocking.isEmpty() || tick <= blockedUntil
			|| suppressedTick >= tick - 1)
		{
			return;
		}

		long gainedValue = 0;
		long lostValue = 0;
		long coinsGained = 0;
		boolean pouchOpened = false;
		List<JourneyService.LootItem> gained = new ArrayList<>();
		for (Map.Entry<Integer, Integer> e : pendingDelta.entrySet())
		{
			int id = e.getKey();
			int qty = e.getValue();
			long price = id == ItemID.COINS ? 1 : itemManager.getItemPrice(id);
			if (qty > 0)
			{
				gainedValue += price * qty;
				if (id == ItemID.COINS)
				{
					coinsGained += qty;
				}
				gained.add(new JourneyService.LootItem(id, itemManager.getItemComposition(id).getName(), qty, price * qty));
			}
			else
			{
				lostValue += price * -qty;
				String name = itemManager.getItemComposition(id).getName();
				if (name != null && name.toLowerCase(Locale.ENGLISH).contains("coin pouch"))
				{
					pouchOpened = true;
				}
			}
		}

		if (pendingXp.isEmpty())
		{
			// Opening coin pouches gives coins without XP; they come from pickpocketing
			if (pouchOpened && coinsGained > 0)
			{
				service.onSkillingIncome(Skill.THIEVING, coinsGained, gained);
			}
			return;
		}
		for (Skill s : pendingXp.keySet())
		{
			if (COMBAT.contains(s))
			{
				return;
			}
		}

		Skill skill = pendingXp.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
		long net = gainedValue - lostValue;
		if (net != 0 || !gained.isEmpty())
		{
			service.onSkillingIncome(skill, net, gained);
		}
	}
}
