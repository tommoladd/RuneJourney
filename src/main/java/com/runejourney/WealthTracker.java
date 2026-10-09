package com.runejourney;

import com.runejourney.service.JourneyService;
import java.util.*;
import javax.inject.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

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
	private final Map<Integer, int[]> lastSeen = new HashMap<>();
	private long[] lastOffers;
	private Map<Integer, Integer> lastPotions;
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

	static void addOffer(Map<Integer, Long> held, boolean buy, int itemId, int total, int sold, long price,
		long spent)
	{
		if (buy)
		{
			held.merge(itemId, (long) sold, Long::sum);
			held.merge(ItemID.COINS, Math.max(0, total * price - spent), Long::sum);
		}
		else
		{
			held.merge(itemId, (long) Math.max(0, total - sold), Long::sum);
			held.merge(ItemID.COINS, spent, Long::sum);
		}
	}

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
		if ((held.isEmpty() && !bankOpen) || held.equals(lastPotions))
		{
			return;
		}
		lastPotions = held;
		service.onHoldings(POTION_STORAGE, held);
		dirty = true;
	}

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
