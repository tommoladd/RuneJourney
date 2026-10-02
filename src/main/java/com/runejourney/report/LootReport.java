package com.runejourney.report;

import com.runejourney.model.DayRecord;
import com.runejourney.model.ItemTotal;
import com.runejourney.model.LootSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import lombok.Value;

/**
 * Which bosses and activities earned the most loot over a period. Loot only: supplies aren't
 * recorded per activity, so this is before supplies.
 */
public final class LootReport
{
	@Value
	public static class Row
	{
		String source;
		/**
		 * Kills, reward claims, caskets opened...
		 */
		int times;
		long total;
		double perTime;
		/**
		 * Fraction of all loot in the period.
		 */
		double share;
		/**
		 * GP per hour from the player's own kill times, or NaN when there aren't enough timed kills.
		 */
		double gpPerHour;
		/**
		 * Loot from this source in the previous period of the same length.
		 */
		long previousTotal;
		String bestItem;
		long bestItemValue;
		/**
		 * Every item it gave in the period, for searching.
		 */
		Map<String, ItemTotal> items;
	}

	/**
	 * One item's share of a source's loot.
	 */
	@Value
	public static class ItemRow
	{
		String name;
		long quantity;
		long value;
		/**
		 * Average value per kill or trip.
		 */
		double perTime;
		/**
		 * Fraction of the source's loot.
		 */
		double share;
	}

	/**
	 * Everything one source paid out over some days.
	 */
	@Value
	public static class SourceDetail
	{
		String source;
		long total;
		int times;
		/**
		 * First and last days it paid out, or null if it never did in these days.
		 */
		String firstDay;
		String lastDay;
		/**
		 * Most valuable first.
		 */
		List<ItemRow> items;
	}

	private LootReport()
	{
	}

	public static SourceDetail detail(List<DayRecord> days, String source)
	{
		LootSource combined = new LootSource();
		String first = null;
		String last = null;
		for (DayRecord d : days)
		{
			LootSource s = d.getLootBySource().get(source);
			if (s == null || (s.getTimes() == 0 && s.getValue() == 0))
			{
				continue;
			}
			combined.add(s);
			if (first == null || d.getDate().compareTo(first) < 0)
			{
				first = d.getDate();
			}
			if (last == null || d.getDate().compareTo(last) > 0)
			{
				last = d.getDate();
			}
		}
		List<ItemRow> items = new ArrayList<>();
		for (Map.Entry<String, ItemTotal> e : combined.getItems().entrySet())
		{
			ItemTotal t = e.getValue();
			items.add(new ItemRow(e.getKey(), t.getQuantity(), t.getValue(),
				combined.getTimes() > 0 ? (double) t.getValue() / combined.getTimes() : t.getValue(),
				combined.getValue() > 0 ? (double) t.getValue() / combined.getValue() : 0));
		}
		// Most valuable first; worthless items (pearls, untradeables) by how many
		items.sort((a, b) -> a.getValue() != b.getValue() ? Long.compare(b.getValue(), a.getValue())
			: Long.compare(b.getQuantity(), a.getQuantity()));
		return new SourceDetail(source, combined.getValue(), combined.getTimes(), first, last, items);
	}

	/**
	 * @param minutesPerKill the player's own average minutes per kill for a source, or NaN / <= 0
	 * when it isn't known
	 * @return one row per source that paid out, most loot first
	 */
	public static List<Row> build(List<DayRecord> days, List<DayRecord> previousDays, ToDoubleFunction<String> minutesPerKill)
	{
		Map<String, LootSource> current = combine(days);
		Map<String, LootSource> previous = combine(previousDays);
		long all = current.values().stream().mapToLong(LootSource::getValue).filter(v -> v > 0).sum();

		List<Row> rows = new ArrayList<>();
		for (Map.Entry<String, LootSource> e : current.entrySet())
		{
			LootSource s = e.getValue();
			if (s.getValue() <= 0)
			{
				continue;
			}
			double perTime = s.getTimes() > 0 ? (double) s.getValue() / s.getTimes() : s.getValue();
			double minutes = minutesPerKill.applyAsDouble(e.getKey());
			double gpPerHour = minutes > 0 ? perTime * 60 / minutes : Double.NaN;

			String bestItem = null;
			long bestValue = 0;
			for (Map.Entry<String, ItemTotal> item : s.getItems().entrySet())
			{
				if (item.getValue().getValue() > bestValue)
				{
					bestItem = item.getKey();
					bestValue = item.getValue().getValue();
				}
			}

			LootSource before = previous.get(e.getKey());
			rows.add(new Row(e.getKey(), s.getTimes(), s.getValue(), perTime, all > 0 ? (double) s.getValue() / all : 0,
				gpPerHour, before == null ? 0 : before.getValue(), bestItem, bestValue, s.getItems()));
		}
		rows.sort((a, b) -> Long.compare(b.getTotal(), a.getTotal()));
		return rows;
	}

	/**
	 * How a source matched a search.
	 */
	@Value
	public static class Match
	{
		/**
		 * The source's own name matched, so all its items are relevant.
		 */
		boolean bySource;
		/**
		 * Items whose names matched, most valuable first.
		 */
		List<String> items;
	}

	/**
	 * Matches a source by its name or the names of items it gave, ignoring case.
	 *
	 * @return null if neither matches; any match for a blank search
	 */
	public static Match match(Row row, String search)
	{
		String q = search == null ? "" : search.trim().toLowerCase(Locale.ENGLISH);
		if (q.isEmpty() || row.getSource().toLowerCase(Locale.ENGLISH).contains(q))
		{
			return new Match(true, new ArrayList<>());
		}
		List<String> items = new ArrayList<>();
		row.getItems().entrySet().stream()
			.filter(e -> e.getKey().toLowerCase(Locale.ENGLISH).contains(q))
			.sorted((a, b) -> Long.compare(b.getValue().getValue(), a.getValue().getValue()))
			.forEach(e -> items.add(e.getKey()));
		return items.isEmpty() ? null : new Match(false, items);
	}

	/**
	 * Whether an item belongs in a search's results.
	 */
	public static boolean itemMatches(String item, String search)
	{
		String q = search == null ? "" : search.trim().toLowerCase(Locale.ENGLISH);
		return q.isEmpty() || item.toLowerCase(Locale.ENGLISH).contains(q);
	}

	private static Map<String, LootSource> combine(List<DayRecord> days)
	{
		Map<String, LootSource> sources = new HashMap<>();
		for (DayRecord d : days)
		{
			d.getLootBySource().forEach((k, v) -> sources.computeIfAbsent(k, x -> new LootSource()).add(v));
		}
		return sources;
	}
}
