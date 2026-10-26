package com.runejourney;

import com.runejourney.service.JourneyService;
import java.util.*;
import java.util.regex.Pattern;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

@Singleton
class SupplyTracker
{
	private static final int PENDING_TICKS = 3;
	private static final Pattern DOSE = Pattern.compile("\\s*\\(\\d\\)$");
	private static final String[] PORTION_PREFIXES = {"half a ", "half an ", "1/2 ", "2/3 ", "slice of "};
	private static final Set<String> CONTAINERS = Set.of("vial", "pie dish", "bowl", "jug", "empty cup",
		"beer glass", "cocktail glass", "empty gourd vial");
	private static final Map<Integer, Charge> CHARGED = new HashMap<>();

	static
	{
		charge(new int[]{ItemID.SCYTHE_OF_VITUR, ItemID.SCYTHE_OF_VITUR_OR, ItemID.SCYTHE_OF_VITUR_BL},
			new int[]{AnimationID.SCYTHE_OF_VITUR_ATTACK}, ItemID.BLOODRUNE, 2, ItemID.VIAL_BLOOD, 0.01);
		charge(new int[]{ItemID.TUMEKENS_SHADOW}, new int[]{AnimationID.TOA_SOT_CAST_B}, ItemID.SOULRUNE, 2, ItemID.CHAOSRUNE, 5);
		charge(new int[]{ItemID.EYE_OF_AYAK}, new int[]{AnimationID.HUMAN_EYE_OF_AYAK_NORMAL, AnimationID.HUMAN_EYE_OF_AYAK_SPECIAL},
			ItemID.DEMON_TEAR, 1);
		charge(new int[]{ItemID.VENATOR_BOW, ItemID.VENATOR_BOW_ORNAMENT}, new int[]{AnimationID.HUMAN_WEAPON_BOW_VENATOR01_SHOOT},
			ItemID.ANCIENT_ESSENCE, 1);
		charge(new int[]{ItemID.WILD_CAVE_BOW_CHARGED, ItemID.WILD_CAVE_WEBWEAVER_CHARGED},
			new int[]{AnimationID.HUMAN_BOW, AnimationID.HUMAN_SPECIAL01_WEBWEAVER}, ItemID.WILD_CAVE_SHARD, 1);
		charge(new int[]{ItemID.ABYSSAL_TENTACLE}, new int[]{AnimationID.SLAYER_ABYSSAL_WHIP_ATTACK}, ItemID.ABYSSAL_WHIP, 0.0001);
	}

	private final ItemManager itemManager;
	private final JourneyService service;

	private Map<Integer, Integer> lastInventory;
	private final List<Pending> pending = new ArrayList<>();
	private int lastAttackTick = -1;

	@Inject
	SupplyTracker(ItemManager itemManager, JourneyService service)
	{
		this.itemManager = itemManager;
		this.service = service;
	}

	void reset()
	{
		lastInventory = null;
		pending.clear();
	}

	void onMenuOptionClicked(String option, int itemId, int tick)
	{
		if (itemId <= 0 || option == null)
		{
			return;
		}
		String o = Text.removeTags(option);
		if (o.equalsIgnoreCase("Eat") || o.equalsIgnoreCase("Drink"))
		{
			pending.add(new Pending(itemManager.canonicalize(itemId), tick));
		}
	}

	void onAttack(int weaponId, int animation, int tick)
	{
		Charge c = CHARGED.get(weaponId);
		if (c == null || !c.animations.contains(animation) || tick == lastAttackTick)
		{
			return;
		}
		lastAttackTick = tick;
		double cost = 0;
		for (int i = 0; i < c.items.length; i++)
		{
			cost += (c.items[i] == ItemID.COINS ? 1 : itemManager.getItemPrice(c.items[i])) * c.perCharge[i];
		}
		service.onSupplyUsed(name(weaponId) + " charges", 1, Math.round(cost));
	}

	private static void charge(int[] weapons, int[] animations, double... cost)
	{
		Charge c = new Charge();
		for (int a : animations)
		{
			c.animations.add(a);
		}
		c.items = new int[cost.length / 2];
		c.perCharge = new double[cost.length / 2];
		for (int i = 0; i < c.items.length; i++)
		{
			c.items[i] = (int) cost[i * 2];
			c.perCharge[i] = cost[i * 2 + 1];
		}
		for (int w : weapons)
		{
			CHARGED.put(w, c);
		}
	}

	Map<Integer, Integer> onInventory(ItemContainer container, int tick)
	{
		Map<Integer, Integer> now = new HashMap<>();
		for (Item item : container.getItems())
		{
			if (item.getId() > 0 && item.getQuantity() > 0)
			{
				now.merge(itemManager.canonicalize(item.getId()), item.getQuantity(), Integer::sum);
			}
		}
		Map<Integer, Integer> previous = lastInventory;
		lastInventory = now;
		pending.removeIf(p -> tick - p.tick > PENDING_TICKS);
		Map<Integer, Integer> used = new HashMap<>();
		if (previous == null || pending.isEmpty())
		{
			return used;
		}

		Map<Integer, Integer> delta = new HashMap<>();
		for (Map.Entry<Integer, Integer> e : now.entrySet())
		{
			delta.put(e.getKey(), e.getValue() - previous.getOrDefault(e.getKey(), 0));
		}
		for (Map.Entry<Integer, Integer> e : previous.entrySet())
		{
			delta.putIfAbsent(e.getKey(), -e.getValue());
		}

		for (Iterator<Pending> it = pending.iterator(); it.hasNext(); )
		{
			int id = it.next().itemId;
			if (delta.getOrDefault(id, 0) >= 0)
			{
				continue;
			}
			it.remove();
			delta.merge(id, 1, Integer::sum);
			used.merge(id, -1, Integer::sum);

			String name = name(id);
			long cost = itemManager.getItemPrice(id);
			for (Map.Entry<Integer, Integer> e : delta.entrySet())
			{
				if (e.getValue() > 0 && isLeftover(name, name(e.getKey())))
				{
					cost -= itemManager.getItemPrice(e.getKey());
					e.setValue(e.getValue() - 1);
					used.merge(e.getKey(), 1, Integer::sum);
					break;
				}
			}
			service.onSupplyUsed(supplyName(name), 1, Math.max(0, cost));
		}
		return used;
	}

	private String name(int id)
	{
		String name = itemManager.getItemComposition(id).getName();
		return name != null ? name : "";
	}

	static String supplyName(String itemName)
	{
		return DOSE.matcher(itemName).replaceFirst("");
	}

	static boolean isLeftover(String consumed, String gained)
	{
		String g = gained.toLowerCase(Locale.ENGLISH);
		return CONTAINERS.contains(g) || core(consumed).equals(core(gained));
	}

	private static String core(String name)
	{
		String c = DOSE.matcher(name.toLowerCase(Locale.ENGLISH)).replaceFirst("");
		for (String prefix : PORTION_PREFIXES)
		{
			if (c.startsWith(prefix))
			{
				return c.substring(prefix.length());
			}
		}
		return c;
	}

	private static final class Charge
	{
		final Set<Integer> animations = new HashSet<>();
		int[] items;
		double[] perCharge;
	}

	private static final class Pending
	{
		final int itemId;
		final int tick;

		Pending(int itemId, int tick)
		{
			this.itemId = itemId;
			this.tick = tick;
		}
	}
}
