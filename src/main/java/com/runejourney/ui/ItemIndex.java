package com.runejourney.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

/**
 * Searchable names of every item, including untradeables such as pets. Built once, in chunks on the
 * client thread so it never stalls the game.
 */
@Singleton
class ItemIndex
{
	private static final int CHUNK = 4000;

	@Value
	static class Entry
	{
		int id;
		String name;
	}

	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;

	private volatile List<Entry> entries;
	/**
	 * Grand Exchange price per item id, captured while building the index (prices can only be
	 * looked up on the client thread).
	 */
	private final Map<Integer, Long> prices = new ConcurrentHashMap<>();
	private boolean building;
	private final List<Runnable> waiting = new ArrayList<>();

	@Inject
	ItemIndex(Client client, ClientThread clientThread, ItemManager itemManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
	}

	boolean isReady()
	{
		return entries != null;
	}

	/**
	 * Builds the index if needed, then runs {@code onReady} on the Swing thread.
	 */
	synchronized void load(Runnable onReady)
	{
		if (entries != null)
		{
			SwingUtilities.invokeLater(onReady);
			return;
		}
		waiting.add(onReady);
		if (building)
		{
			return;
		}
		building = true;

		List<Entry> list = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		int[] next = {0};
		clientThread.invoke(() ->
		{
			int count = client.getItemCount();
			int end = Math.min(count, next[0] + CHUNK);
			for (int id = next[0]; id < end; id++)
			{
				ItemComposition comp = itemManager.getItemComposition(id);
				String name = comp.getName();
				if (name == null || name.isEmpty() || "null".equalsIgnoreCase(name)
					|| comp.getNote() != -1 || comp.getPlaceholderTemplateId() != -1)
				{
					continue;
				}
				if (seen.add(name.toLowerCase(Locale.ENGLISH)))
				{
					list.add(new Entry(id, name));
					long price = itemManager.getItemPrice(id);
					if (price > 0)
					{
						prices.put(id, price);
					}
				}
			}
			next[0] = end;
			if (end < count)
			{
				// Not done: run again next tick
				return false;
			}
			finish(list);
			return true;
		});
	}

	private synchronized void finish(List<Entry> list)
	{
		entries = list;
		building = false;
		for (Runnable r : waiting)
		{
			SwingUtilities.invokeLater(r);
		}
		waiting.clear();
	}

	/**
	 * Grand Exchange price of an item, or 0 if unknown or untradeable.
	 */
	long price(int id)
	{
		return prices.getOrDefault(id, 0L);
	}

	/**
	 * Items whose name contains the query, names starting with it first.
	 */
	List<Entry> search(String query, int limit)
	{
		List<Entry> all = entries;
		List<Entry> result = new ArrayList<>();
		if (all == null || query == null || query.trim().length() < 2)
		{
			return result;
		}
		String q = query.trim().toLowerCase(Locale.ENGLISH);
		for (Entry e : all)
		{
			if (e.getName().toLowerCase(Locale.ENGLISH).contains(q))
			{
				result.add(e);
			}
		}
		result.sort(Comparator
			.comparing((Entry e) -> !e.getName().toLowerCase(Locale.ENGLISH).startsWith(q))
			.thenComparingInt(e -> e.getName().length())
			.thenComparing(Entry::getName));
		return result.size() > limit ? result.subList(0, limit) : result;
	}
}
