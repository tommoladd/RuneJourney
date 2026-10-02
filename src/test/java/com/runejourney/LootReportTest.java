package com.runejourney;

import com.runejourney.model.DayRecord;
import com.runejourney.model.ItemTotal;
import com.runejourney.model.LootSource;
import com.runejourney.report.LootReport;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class LootReportTest
{
	private static DayRecord day(String date, Object... sources)
	{
		DayRecord d = new DayRecord(date);
		for (int i = 0; i < sources.length; i += 3)
		{
			LootSource s = new LootSource();
			s.setTimes((Integer) sources[i + 1]);
			s.setValue((Long) sources[i + 2]);
			d.getLootBySource().put((String) sources[i], s);
		}
		return d;
	}

	@Test
	public void ranksSourcesAcrossDays()
	{
		DayRecord a = day("2026-10-01", "Vorkath", 10, 2_000_000L, "Guardians of the Rift", 23, 895_689L);
		a.getLootBySource().get("Vorkath").getItems().put("Draconic visage", new ItemTotal(1, 1_500_000));
		a.getLootBySource().get("Vorkath").getItems().put("Dragon bones", new ItemTotal(20, 50_000));
		DayRecord b = day("2026-10-02", "Vorkath", 10, 1_000_000L, "Xarpus", 2, 0L);

		List<LootReport.Row> rows = LootReport.build(Arrays.asList(a, b), Collections.emptyList(),
			s -> s.equals("Vorkath") ? 2.0 : Double.NaN);

		// Sources that paid nothing (raid sub-bosses) are left out
		assertEquals(2, rows.size());
		LootReport.Row vork = rows.get(0);
		assertEquals("Vorkath", vork.getSource());
		assertEquals(20, vork.getTimes());
		assertEquals(3_000_000, vork.getTotal());
		assertEquals(150_000, vork.getPerTime(), 0.01);
		// 150k a kill at 2 minutes a kill
		assertEquals(4_500_000, vork.getGpPerHour(), 0.01);
		assertEquals("Draconic visage", vork.getBestItem());
		assertEquals(3_000_000.0 / 3_895_689, vork.getShare(), 1e-9);

		LootReport.Row gotr = rows.get(1);
		assertTrue(Double.isNaN(gotr.getGpPerHour()));
		assertEquals(895_689 / 23.0, gotr.getPerTime(), 0.01);
	}

	@Test
	public void comparesWithThePreviousPeriod()
	{
		List<LootReport.Row> rows = LootReport.build(
			Collections.singletonList(day("2026-10-02", "Zulrah", 5, 900_000L)),
			Collections.singletonList(day("2026-09-25", "Zulrah", 4, 600_000L)),
			s -> Double.NaN);
		assertEquals(600_000, rows.get(0).getPreviousTotal());
	}

	@Test
	public void listsEverythingASourceGave()
	{
		DayRecord a = day("2026-09-30", "Guardians of the Rift", 10, 300_000L);
		a.getLootBySource().get("Guardians of the Rift").getItems().put("Blood rune", new ItemTotal(500, 200_000));
		a.getLootBySource().get("Guardians of the Rift").getItems().put("Abyssal pearls", new ItemTotal(140, 0));
		a.getLootBySource().get("Guardians of the Rift").getItems().put("Nature rune", new ItemTotal(700, 100_000));
		DayRecord b = day("2026-10-02", "Guardians of the Rift", 15, 411_000L, "Vorkath", 1, 150_000L);
		b.getLootBySource().get("Guardians of the Rift").getItems().put("Blood rune", new ItemTotal(600, 240_000));
		b.getLootBySource().get("Guardians of the Rift").getItems().put("Abyssal pearls", new ItemTotal(200, 0));
		b.getLootBySource().get("Guardians of the Rift").getItems().put("Death rune", new ItemTotal(900, 171_000));

		LootReport.SourceDetail d = LootReport.detail(Arrays.asList(b, a), "Guardians of the Rift");
		assertEquals(711_000, d.getTotal());
		assertEquals(25, d.getTimes());
		assertEquals("2026-09-30", d.getFirstDay());
		assertEquals("2026-10-02", d.getLastDay());

		assertEquals(4, d.getItems().size());
		LootReport.ItemRow blood = d.getItems().get(0);
		assertEquals("Blood rune", blood.getName());
		assertEquals(1_100, blood.getQuantity());
		assertEquals(440_000, blood.getValue());
		assertEquals(440_000 / 25.0, blood.getPerTime(), 0.01);
		assertEquals(440_000 / 711_000.0, blood.getShare(), 1e-9);
		// Worthless items still listed, last
		assertEquals("Abyssal pearls", d.getItems().get(3).getName());
		assertEquals(340, d.getItems().get(3).getQuantity());
	}

	@Test
	public void unknownSourceIsEmpty()
	{
		LootReport.SourceDetail d = LootReport.detail(Collections.singletonList(day("2026-10-02", "Zulrah", 1, 1L)), "Vorkath");
		assertEquals(0, d.getTotal());
		assertEquals(null, d.getFirstDay());
		assertTrue(d.getItems().isEmpty());
	}

	@Test
	public void searchFindsSourcesByNameOrItem()
	{
		DayRecord d = day("2026-10-02", "Tombs of Amascut", 1, 486_000L, "Theatre of Blood", 1, 163_000L, "Zulrah", 2, 90_000L);
		d.getLootBySource().get("Tombs of Amascut").getItems().put("Soul rune", new ItemTotal(1_000, 446_000));
		d.getLootBySource().get("Theatre of Blood").getItems().put("Rune chainbody", new ItemTotal(3, 88_000));
		d.getLootBySource().get("Theatre of Blood").getItems().put("Rune 2h sword", new ItemTotal(1, 38_000));
		d.getLootBySource().get("Zulrah").getItems().put("Zulrah's scales", new ItemTotal(500, 90_000));
		List<LootReport.Row> rows = LootReport.build(Collections.singletonList(d), Collections.emptyList(), s -> Double.NaN);
		LootReport.Row toa = rows.get(0);
		LootReport.Row tob = rows.get(1);
		LootReport.Row zulrah = rows.get(2);

		// Blank search keeps everything
		assertTrue(LootReport.match(toa, "  ").isBySource());

		// By source name, any case
		assertTrue(LootReport.match(toa, "TOMBS").isBySource());
		assertEquals(null, LootReport.match(tob, "tombs"));

		// By item: the matching items, most valuable first
		LootReport.Match rune = LootReport.match(tob, "rune");
		assertFalse(rune.isBySource());
		assertEquals(Arrays.asList("Rune chainbody", "Rune 2h sword"), rune.getItems());
		assertEquals(Collections.singletonList("Soul rune"), LootReport.match(toa, "rune").getItems());
		assertEquals(null, LootReport.match(zulrah, "rune"));

		assertTrue(LootReport.itemMatches("Rune chainbody", "chain"));
		assertFalse(LootReport.itemMatches("Soul rune", "chain"));
	}
}
