package com.runejourney.report;

import com.runejourney.model.DayRecord;
import java.time.*;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.Value;

public final class Analytics
{
	private Analytics()
	{
	}

	@Value
	public static class Bucket
	{
		LocalDate start;
		LocalDate end;
		String label;
		double value;
		double numerator;
		double hours;
		int activeDays;
	}

	@Value
	public static class Entry
	{
		String key;
		String label;
		double value;
	}

	@Value
	public static class Stats
	{
		double total;
		int activeDays;
		double perActiveDay;
		Bucket best;
		double previousTotal;

		public double change()
		{
			if (Double.isNaN(previousTotal) || previousTotal == 0)
			{
				return Double.NaN;
			}
			return (total - previousTotal) / Math.abs(previousTotal);
		}
	}

	public static LocalDate[] previousPeriod(LocalDate from, LocalDate to)
	{
		long days = ChronoUnit.DAYS.between(from, to) + 1;
		return new LocalDate[]{from.minusDays(days), from.minusDays(1)};
	}

	private static Map<LocalDate, DayRecord> byDate(List<DayRecord> days)
	{
		Map<LocalDate, DayRecord> map = new HashMap<>();
		for (DayRecord d : days)
		{
			map.put(LocalDate.parse(d.getDate()), d);
		}
		return map;
	}

	public static List<Bucket> series(List<DayRecord> days, LocalDate from, LocalDate to, Metric metric, String filter,
		Granularity granularity)
	{
		Granularity g = granularity.resolve(from, to);
		Map<LocalDate, DayRecord> map = byDate(days);
		List<Bucket> buckets = new ArrayList<>();
		for (LocalDate start = g.bucketStart(from); !start.isAfter(to); start = g.next(start))
		{
			LocalDate end = g.next(start).minusDays(1);
			if (end.isAfter(to))
			{
				end = to;
			}
			double num = 0;
			double hours = 0;
			int active = 0;
			for (LocalDate d = start.isBefore(from) ? from : start; !d.isAfter(end); d = d.plusDays(1))
			{
				DayRecord r = map.get(d);
				if (r == null)
				{
					continue;
				}
				num += metric.value(r, filter);
				hours += Metric.hours(r);
				if (r.getPlayMillis() > 0)
				{
					active++;
				}
			}
			double value = metric.isPerHour() ? (hours > 0 ? num / hours : 0) : num;
			buckets.add(new Bucket(start, end, g.label(start), value, num, hours, active));
		}
		return buckets;
	}

	public static List<Bucket> cumulative(List<Bucket> series, Metric metric)
	{
		if (metric.isPerHour())
		{
			return series;
		}
		List<Bucket> out = new ArrayList<>();
		double sum = 0;
		for (Bucket b : series)
		{
			sum += b.getValue();
			out.add(new Bucket(b.getStart(), b.getEnd(), b.getLabel(), sum, b.getNumerator(), b.getHours(), b.getActiveDays()));
		}
		return out;
	}

	public static Stats stats(List<DayRecord> days, LocalDate from, LocalDate to, Metric metric, String filter,
		Granularity granularity, List<DayRecord> previousDays)
	{
		double num = 0;
		double hours = 0;
		int active = 0;
		for (DayRecord r : days)
		{
			LocalDate d = LocalDate.parse(r.getDate());
			if (d.isBefore(from) || d.isAfter(to))
			{
				continue;
			}
			num += metric.value(r, filter);
			hours += Metric.hours(r);
			if (r.getPlayMillis() > 0)
			{
				active++;
			}
		}
		double total = metric.isPerHour() ? (hours > 0 ? num / hours : 0) : num;
		double perDay = metric.isPerHour() ? total : (active > 0 ? num / active : 0);

		Bucket best = null;
		for (Bucket b : series(days, from, to, metric, filter, granularity))
		{
			if (b.getValue() > 0 && (best == null || b.getValue() > best.getValue()))
			{
				best = b;
			}
		}

		double previous = Double.NaN;
		if (previousDays != null && !previousDays.isEmpty())
		{
			LocalDate[] prev = previousPeriod(from, to);
			Stats p = stats(previousDays, prev[0], prev[1], metric, filter, granularity, null);
			previous = p.getTotal();
		}
		return new Stats(total, active, perDay, best, previous);
	}

	public static List<Entry> breakdown(List<DayRecord> days, LocalDate from, LocalDate to, Metric metric)
	{
		Map<String, Double> totals = new LinkedHashMap<>();
		for (DayRecord r : days)
		{
			LocalDate d = LocalDate.parse(r.getDate());
			if (d.isBefore(from) || d.isAfter(to))
			{
				continue;
			}
			metric.parts(r).forEach((k, v) -> totals.merge(k, v, Double::sum));
		}
		List<Entry> entries = new ArrayList<>();
		totals.forEach((k, v) ->
		{
			if (v != 0)
			{
				entries.add(new Entry(k, k, v));
			}
		});
		entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
		return entries;
	}

	public static List<Entry> byWeekday(List<DayRecord> days, LocalDate from, LocalDate to, Metric metric, String filter)
	{
		double[] num = new double[7];
		double[] hours = new double[7];
		int[] occurrences = new int[7];
		for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1))
		{
			occurrences[d.getDayOfWeek().getValue() - 1]++;
		}
		Map<LocalDate, DayRecord> map = byDate(days);
		for (Map.Entry<LocalDate, DayRecord> e : map.entrySet())
		{
			LocalDate d = e.getKey();
			if (d.isBefore(from) || d.isAfter(to))
			{
				continue;
			}
			int i = d.getDayOfWeek().getValue() - 1;
			num[i] += metric.value(e.getValue(), filter);
			hours[i] += Metric.hours(e.getValue());
		}
		List<Entry> entries = new ArrayList<>();
		for (int i = 0; i < 7; i++)
		{
			String name = DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
			double v = metric.isPerHour() ? (hours[i] > 0 ? num[i] / hours[i] : 0) : (occurrences[i] > 0 ? num[i] / occurrences[i] : 0);
			entries.add(new Entry(name, name, v));
		}
		return entries;
	}

	public static List<Bucket> previousSeries(List<DayRecord> previousDays, LocalDate from, LocalDate to, Metric metric,
		String filter, Granularity granularity)
	{
		LocalDate[] prev = previousPeriod(from, to);
		Granularity g = granularity.resolve(from, to);
		return series(previousDays, prev[0], prev[1], metric, filter, g);
	}
}
