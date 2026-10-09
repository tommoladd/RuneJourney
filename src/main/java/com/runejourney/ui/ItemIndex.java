package com.runejourney.ui;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.*;
import javax.swing.SwingUtilities;
import lombok.Value;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

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

	long price(int id)
	{
		return prices.getOrDefault(id, 0L);
	}

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
