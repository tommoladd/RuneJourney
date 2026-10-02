package com.runejourney;

import com.runejourney.service.JourneyService;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

/**
 * Keeps a ledger of every item the account holds, per container, and values it at GE prices.
 *
 * Containers are read directly at the end of each tick rather than from change events: items
 * moving between the bank and equipment arrive as several events, and not every bank change
 * reliably produces one, which left the bank's value stale and counted gear twice. Reading the
 * containers once everything has settled always matches what the game shows.
 * Must only be used on the client thread.
 */
@Slf4j
@Singleton
class WealthTracker
{
	private static final Map<Integer, String> PARTS = new LinkedHashMap<>();

	static
	{
		PARTS.put(InventoryID.BANK, JourneyService.BANK);
		PARTS.put(InventoryID.INV, "inventory");
		PARTS.put(InventoryID.WORN, "equipment");
		PARTS.put(InventoryID.LOOTING_BAG, "looting bag");
		PARTS.put(InventoryID.SEED_VAULT, "seed vault");
	}

	private final Client client;
	private final ItemManager itemManager;
	private final JourneyService service;
	/**
	 * Container contents as last read, to skip re-pricing when nothing moved.
	 */
	private final Map<Integer, int[]> lastSeen = new HashMap<>();
	private boolean dirty;

	@Inject
	WealthTracker(Client client, ItemManager itemManager, JourneyService service)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.service = service;
	}

	/**
	 * Re-price everything on the next tick, e.g. after logging in with holdings from last time.
	 */
	void invalidate()
	{
		dirty = true;
	}

	void reset()
	{
		lastSeen.clear();
		dirty = false;
	}

	/**
	 * Call on every game tick while logged in. Containers the game hasn't sent this session (the
	 * bank before it's opened, an unchecked looting bag) keep what was saved last time.
	 */
	void onTick()
	{
		for (Map.Entry<Integer, String> part : PARTS.entrySet())
		{
			ItemContainer container = client.getItemContainer(part.getKey());
			if (container == null)
			{
				continue;
			}
			Item[] items = container.getItems();
			int[] contents = new int[items.length * 2];
			for (int i = 0; i < items.length; i++)
			{
				contents[i * 2] = items[i].getId();
				contents[i * 2 + 1] = items[i].getQuantity();
			}
			if (Arrays.equals(contents, lastSeen.get(part.getKey())))
			{
				continue;
			}
			lastSeen.put(part.getKey(), contents);
			Map<Integer, Integer> held = holdings(items);
			service.onHoldings(part.getValue(), held);
			if (part.getKey() == InventoryID.BANK || part.getKey() == InventoryID.INV)
			{
				long cash = held.getOrDefault(ItemID.COINS, 0) + held.getOrDefault(ItemID.PLATINUM, 0) * 1000L;
				service.onCash(part.getKey() == InventoryID.BANK, cash);
			}
			dirty = true;
		}

		if (!dirty)
		{
			return;
		}
		dirty = false;
		Map<String, Long> values = new HashMap<>();
		service.holdings().forEach((part, held) -> values.put(part, value(held)));
		log.debug("Net worth parts: {}", values);
		service.onWealth(values);
	}

	/**
	 * Real items only: bank placeholders and fillers mark empty slots, so they're skipped.
	 */
	private Map<Integer, Integer> holdings(Item[] items)
	{
		Map<Integer, Integer> held = new HashMap<>();
		for (Item item : items)
		{
			int id = item.getId();
			if (id <= 0 || item.getQuantity() <= 0 || id == ItemID.BANK_FILLER
				|| itemManager.getItemComposition(id).getPlaceholderTemplateId() != -1)
			{
				continue;
			}
			held.merge(itemManager.canonicalize(id), item.getQuantity(), Integer::sum);
		}
		return held;
	}

	private long value(Map<Integer, Integer> items)
	{
		long total = 0;
		for (Map.Entry<Integer, Integer> e : items.entrySet())
		{
			total += unitPrice(e.getKey()) * e.getValue();
		}
		return total;
	}

	private long unitPrice(int id)
	{
		if (id == ItemID.COINS)
		{
			return 1;
		}
		if (id == ItemID.PLATINUM)
		{
			return 1000;
		}
		return itemManager.getItemPrice(id);
	}
}
