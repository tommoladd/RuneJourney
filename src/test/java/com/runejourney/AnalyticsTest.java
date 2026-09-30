package com.runejourney;

import com.runejourney.model.DayRecord;
import com.runejourney.report.Analytics;
import com.runejourney.report.CsvExport;
import com.runejourney.report.Granularity;
import com.runejourney.report.Metric;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AnalyticsTest
{
	private static DayRecord day(LocalDate date, long xp, double hours, int zulrahKills)
	{
		DayRecord d = new DayRecord(date.toString());
		d.setXpGained(xp);
		d.getSkillXp().put("SLAYER", xp);
		d.setPlayMillis((long) (hours * 3_600_000));
		if (zulrahKills > 0)
		{
			d.getBossKills().put("Zulrah", zulrahKills);
		}
		return d;
	}

	// Monday 7 Sep 2026 to Sunday 20 Sep 2026: two full weeks
	private static final LocalDate FROM = LocalDate.of(2026, 9, 7);
	private static final LocalDate TO = LocalDate.of(2026, 9, 20);

	private static List<DayRecord> sample()
	{
		List<DayRecord> days = new ArrayList<>();
		days.add(day(FROM, 100_000, 2, 10));
		days.add(day(FROM.plusDays(1), 50_000, 1, 0));
		days.add(day(FROM.plusDays(8), 300_000, 3, 20));
		return days;
	}

	@Test
	public void dailySeriesFillsGaps()
	{
		List<Analytics.Bucket> series = Analytics.series(sample(), FROM, TO, Metric.XP, null, Granularity.DAY);
		assertEquals(14, series.size());
		assertEquals(100_000, series.get(0).getValue(), 0);
		assertEquals(0, series.get(2).getValue(), 0);
		assertEquals(300_000, series.get(8).getValue(), 0);
	}

	@Test
	public void weeklyBucketsAndPerHourRates()
	{
		List<Analytics.Bucket> weeks = Analytics.series(sample(), FROM, TO, Metric.XP, null, Granularity.WEEK);
		assertEquals(2, weeks.size());
		assertEquals(150_000, weeks.get(0).getValue(), 0);
		assertEquals(300_000, weeks.get(1).getValue(), 0);

		// 150k over 3 hours in week one
		List<Analytics.Bucket> rates = Analytics.series(sample(), FROM, TO, Metric.XP_PER_HOUR, null, Granularity.WEEK);
		assertEquals(50_000, rates.get(0).getValue(), 0.001);

		List<Analytics.Bucket> cumulative = Analytics.cumulative(weeks, Metric.XP);
		assertEquals(450_000, cumulative.get(1).getValue(), 0);
	}

	@Test
	public void statsCompareWithPreviousPeriod()
	{
		List<DayRecord> previous = new ArrayList<>();
		previous.add(day(FROM.minusDays(3), 225_000, 2, 0));
		Analytics.Stats stats = Analytics.stats(sample(), FROM, TO, Metric.XP, null, Granularity.DAY, previous);
		assertEquals(450_000, stats.getTotal(), 0);
		assertEquals(3, stats.getActiveDays());
		assertEquals(150_000, stats.getPerActiveDay(), 0);
		assertEquals(300_000, stats.getBest().getValue(), 0);
		assertEquals(1.0, stats.change(), 0.0001);
	}

	@Test
	public void breakdownsAndWeekdays()
	{
		List<Analytics.Entry> bosses = Analytics.breakdown(sample(), FROM, TO, Metric.KILLS);
		assertEquals(1, bosses.size());
		assertEquals(30, bosses.get(0).getValue(), 0);

		// Two Mondays in range: 100k and 0 -> average 50k; Tuesday 50k + 300k over 2 Tuesdays
		List<Analytics.Entry> weekdays = Analytics.byWeekday(sample(), FROM, TO, Metric.XP, null);
		assertEquals("Mon", weekdays.get(0).getLabel());
		assertEquals(50_000, weekdays.get(0).getValue(), 0);
		assertEquals(175_000, weekdays.get(1).getValue(), 0);
	}

	@Test
	public void awayXpIsExcludedFromRates()
	{
		DayRecord d = day(FROM, 100_000, 1, 0);
		d.setOfflineXp(40_000);
		d.getOfflineSkillXp().put("SLAYER", 40_000L);
		List<DayRecord> days = new ArrayList<>();
		days.add(d);
		assertEquals(100_000, Analytics.series(days, FROM, FROM, Metric.XP, null, Granularity.DAY).get(0).getValue(), 0);
		assertEquals(60_000, Analytics.series(days, FROM, FROM, Metric.XP_PER_HOUR, null, Granularity.DAY).get(0).getValue(), 0.001);
		assertEquals(60_000, Analytics.series(days, FROM, FROM, Metric.XP_PER_HOUR, "SLAYER", Granularity.DAY).get(0).getValue(), 0.001);
	}

	@Test
	public void netWorthCarriesForwardAndIsNotSummed()
	{
		List<DayRecord> days = new ArrayList<>();
		DayRecord a = day(FROM, 0, 1, 0);
		a.getSnapshot().put("wealth", 500_000_000L);
		DayRecord b = day(FROM.plusDays(9), 0, 1, 0);
		b.getSnapshot().put("wealth", 650_000_000L);
		days.add(a);
		days.add(b);

		List<Analytics.Bucket> daily = Analytics.series(days, FROM, TO, Metric.NET_WORTH, null, Granularity.DAY);
		assertEquals(500_000_000, daily.get(5).getValue(), 0);
		assertEquals(650_000_000, daily.get(13).getValue(), 0);
		List<Analytics.Bucket> weekly = Analytics.series(days, FROM, TO, Metric.NET_WORTH, null, Granularity.WEEK);
		assertEquals(500_000_000, weekly.get(0).getValue(), 0);
		assertEquals(650_000_000, weekly.get(1).getValue(), 0);

		Analytics.Stats stats = Analytics.stats(days, FROM, TO, Metric.NET_WORTH, null, Granularity.DAY, null);
		assertEquals(650_000_000, stats.getTotal(), 0);
		assertEquals(150_000_000, stats.getPerActiveDay(), 0);
	}

	@Test
	public void csvHasOneRowPerDay()
	{
		String csv = CsvExport.export(sample(), FROM, TO);
		String[] lines = csv.split("\n");
		assertEquals(15, lines.length);
		assertTrue(lines[0].contains("\"Zulrah kills\""));
		assertTrue(lines[0].contains("\"Slayer XP\""));
		assertTrue(lines[1].startsWith("2026-09-07,2.00,100000"));
	}
}
