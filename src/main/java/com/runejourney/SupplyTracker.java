package com.runejourney;

import com.runejourney.service.JourneyService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/**
 * Values the food and potions the player eats and drinks. An Eat or Drink click is matched to the
 * next inventory change that removes that item; whatever it leaves behind (a lower-dose potion,
 * half a pie, an empty vial) is deducted, so a potion costs one dose rather than the whole potion.
 * Must only be used on the client thread.
 */
@Singleton
class SupplyTracker
{
	/**
	 * Clicks are matched to inventory changes for this long; the server applies them a tick later.
	 */
	private static final int PENDING_TICKS = 3;
	private static final Pattern DOSE = Pattern.compile("\\s*\\(\\d\\)$");
	private static final String[] PORTION_PREFIXES = {"half a ", "half an ", "1/2 ", "2/3 ", "slice of "};
	private static final Set<String> CONTAINERS = Set.of("vial", "pie dish", "bowl", "jug", "empty cup",
		"beer glass", "cocktail glass", "empty gourd vial");

	private final ItemManager itemManager;
	private final JourneyService service;

	private Map<Integer, Integer> lastInventory;
	private final List<Pending> pending = new ArrayList<>();

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

	/**
	 * Records anything eaten or drunk in this inventory change.
	 *
	 * @return the item quantity changes that were supplies being used, so they aren't also counted
	 * as skilling income
	 */
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

	/**
	 * Potions are grouped by name across doses ("Prayer potion"), food keeps its own name.
	 */
	static String supplyName(String itemName)
	{
		return DOSE.matcher(itemName).replaceFirst("");
	}

	/**
	 * Whether an item that appeared when something was eaten or drunk is what it left behind.
	 */
	static boolean isLeftover(String consumed, String gained)
	{
		String g = gained.toLowerCase(Locale.ENGLISH);
		return CONTAINERS.contains(g) || core(consumed).equals(core(gained));
	}

	/**
	 * "Prayer potion(3)" and "Prayer potion(4)" share "prayer potion"; "Half a summer pie" and
	 * "Summer pie" share "summer pie"; "2/3 cake" and "Slice of cake" share "cake".
	 */
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
