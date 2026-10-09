package com.runejourney.report;

import com.runejourney.model.*;
import java.util.*;
import java.util.function.ToDoubleFunction;
import lombok.Value;

public final class LootReport
{
	@Value
	public static class Row
	{
		String source;
		int times;
		long total;
		double perTime;
		double share;
		double gpPerHour;
		long previousTotal;
		String bestItem;
		long bestItemValue;
		Map<String, ItemTotal> items;
	}

	@Value
	public static class ItemRow
	{
		String name;
		long quantity;
		long value;
		double perTime;
		double share;
	}

	@Value
	public static class SourceDetail
	{
		String source;
		long total;
		int times;
		String firstDay;
		String lastDay;
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
		items.sort((a, b) -> a.getValue() != b.getValue() ? Long.compare(b.getValue(), a.getValue())
			: Long.compare(b.getQuantity(), a.getQuantity()));
		return new SourceDetail(source, combined.getValue(), combined.getTimes(), first, last, items);
	}

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

	@Value
	public static class Match
	{
		boolean bySource;
		List<String> items;
	}

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
