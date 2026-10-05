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
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
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
	static final String GRAND_EXCHANGE = "grand exchange";
	static final String POTION_STORAGE = "potion storage";

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
	private long[] lastOffers;
	private Map<Integer, Integer> lastPotions;
	/**
	 * Set once the login has settled, so Grand Exchange offers and the potion store have arrived.
	 */
	private boolean ready;
	private boolean potionsDue;
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
		ready = true;
		potionsDue = true;
	}

	void reset()
	{
		lastSeen.clear();
		lastOffers = null;
		lastPotions = null;
		ready = false;
		potionsDue = false;
		dirty = false;
	}

	/**
	 * Call on every game tick while logged in. Containers the game hasn't sent this session (the
	 * bank before it's opened, an unchecked looting bag) keep what was saved last time.
	 */
	void onTick()
	{
		boolean bankOpen = client.getWidget(InterfaceID.Bankmain.ITEMS) != null;
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
			// Potions only go in or out of storage through the bank
			potionsDue |= bankOpen;
			dirty = true;
		}
		if (ready)
		{
			readOffers();
			if (potionsDue)
			{
				potionsDue = false;
				readPotionStorage(bankOpen);
			}
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

	private void readOffers()
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		long[] contents = new long[offers.length * 6];
		for (int i = 0; i < offers.length; i++)
		{
			GrandExchangeOffer o = offers[i];
			if (o != null && o.getState() != GrandExchangeOfferState.EMPTY)
			{
				contents[i * 6] = o.getState().ordinal() + 1;
				contents[i * 6 + 1] = o.getItemId();
				contents[i * 6 + 2] = o.getTotalQuantity();
				contents[i * 6 + 3] = o.getQuantitySold();
				contents[i * 6 + 4] = o.getPrice();
				contents[i * 6 + 5] = o.getSpent();
			}
		}
		if (Arrays.equals(contents, lastOffers))
		{
			return;
		}
		lastOffers = contents;
		Map<Integer, Long> held = new HashMap<>();
		for (GrandExchangeOffer o : offers)
		{
			if (o == null || o.getState() == GrandExchangeOfferState.EMPTY || o.getItemId() <= 0)
			{
				continue;
			}
			GrandExchangeOfferState s = o.getState();
			boolean buy = s == GrandExchangeOfferState.BUYING || s == GrandExchangeOfferState.BOUGHT
				|| s == GrandExchangeOfferState.CANCELLED_BUY;
			addOffer(held, buy, itemManager.canonicalize(o.getItemId()), o.getTotalQuantity(), o.getQuantitySold(),
				o.getPrice(), o.getSpent());
		}
		service.onHoldings(GRAND_EXCHANGE, toHoldings(held));
		dirty = true;
	}

	/**
	 * Adds what one Grand Exchange offer holds: the coins or items still waiting to trade, plus
	 * what has traded so far. The game doesn't say what's been collected, so anything bought or
	 * sold is counted as still in the offer; that's only off if part of an offer is collected
	 * before it finishes.
	 */
	static void addOffer(Map<Integer, Long> held, boolean buy, int itemId, int total, int sold, long price,
		long spent)
	{
		if (buy)
		{
			// Coins not yet spent, including the change when items sell for less than offered
			held.merge(itemId, (long) sold, Long::sum);
			held.merge(ItemID.COINS, Math.max(0, total * price - spent), Long::sum);
		}
		else
		{
			held.merge(itemId, (long) Math.max(0, total - sold), Long::sum);
			held.merge(ItemID.COINS, spent, Long::sum);
		}
	}

	/**
	 * Coins can add up past what one stack holds across eight offers, so they're kept as platinum
	 * tokens plus change.
	 */
	static Map<Integer, Integer> toHoldings(Map<Integer, Long> held)
	{
		Map<Integer, Integer> out = new HashMap<>();
		held.forEach((id, qty) ->
		{
			if (id == ItemID.COINS)
			{
				out.merge(ItemID.PLATINUM, (int) Math.min(Integer.MAX_VALUE, qty / 1000), Integer::sum);
				out.merge(ItemID.COINS, (int) (qty % 1000), Integer::sum);
			}
			else
			{
				out.merge(id, (int) Math.min(Integer.MAX_VALUE, qty), Integer::sum);
			}
		});
		out.values().removeIf(q -> q <= 0);
		return out;
	}

	/**
	 * The bank's potion store keeps doses rather than potions; the game's own script counts them.
	 */
	private void readPotionStorage(boolean bankOpen)
	{
		Map<Integer, Integer> held = new HashMap<>();
		for (int list : new int[]{EnumID.POTIONSTORE_POTIONS, EnumID.POTIONSTORE_UNFINISHED_POTIONS})
		{
			for (int potionEnumId : client.getEnum(list).getIntVals())
			{
				EnumComposition potion = client.getEnum(potionEnumId);
				client.runScript(ScriptID.POTIONSTORE_DOSES, potionEnumId);
				int doses = client.getIntStack()[0];
				int[] byDose = new int[5];
				for (int d = 1; d <= 4; d++)
				{
					byDose[d] = potion.getIntValue(d);
				}
				addDoses(held, doses, byDose);
			}
		}
		int vials = client.getVarpValue(VarPlayerID.POTIONSTORE_VIALS);
		if (vials > 0)
		{
			held.merge(ItemID.VIAL_EMPTY, vials, Integer::sum);
		}
		// An empty store outside the bank may just not have been sent yet; keep what was saved
		if ((held.isEmpty() && !bankOpen) || held.equals(lastPotions))
		{
			return;
		}
		lastPotions = held;
		service.onHoldings(POTION_STORAGE, held);
		dirty = true;
	}

	/**
	 * Stored doses as whole potions of the largest size, plus one potion holding what's left
	 * over: 7 doses of prayer potion are a Prayer potion(4) and a Prayer potion(3).
	 *
	 * @param byDose the item for each number of doses (indexes 1-4), or -1 where there isn't one
	 */
	static void addDoses(Map<Integer, Integer> held, int doses, int[] byDose)
	{
		int most = 0;
		for (int d = 1; d < byDose.length; d++)
		{
			if (byDose[d] > 0)
			{
				most = d;
			}
		}
		if (doses <= 0 || most == 0)
		{
			return;
		}
		if (doses >= most)
		{
			held.merge(byDose[most], doses / most, Integer::sum);
		}
		int rest = doses % most;
		if (rest > 0 && byDose[rest] > 0)
		{
			held.merge(byDose[rest], 1, Integer::sum);
		}
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
