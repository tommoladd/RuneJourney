package com.runejourney.service;

import com.google.gson.Gson;
import com.runejourney.RuneJourneyConfig;
import com.runejourney.model.DayRecord;
import com.runejourney.model.ItemTotal;
import com.runejourney.model.LootSource;
import com.runejourney.planner.BossData;
import com.runejourney.planner.Skills;
import com.runejourney.planner.TrainingMethods;
import com.runejourney.report.Metric;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class LootAndSuppliesTest
{
	private JourneyService service;
	private boolean trackSupplies = true;

	private final RuneJourneyConfig config = new RuneJourneyConfig()
	{
		@Override
		public boolean chatAnnouncements()
		{
			return false;
		}

		@Override
		public boolean trackSupplies()
		{
			return trackSupplies;
		}
	};

	@Before
	public void setUp()
	{
		Gson gson = new Gson();
		service = new JourneyService(null, null, config, gson, null, new TrainingMethods(gson), new BossData(gson));

		TreeMap<String, DayRecord> history = new TreeMap<>();
		DayRecord yesterday = new DayRecord(LocalDate.now().minusDays(1).toString());
		LootSource gotr = new LootSource();
		gotr.setValue(500_000);
		gotr.setTimes(10);
		gotr.getItems().put("Blood rune", new ItemTotal(1_000, 400_000));
		yesterday.getLootBySource().put("Guardians of the Rift", gotr);
		yesterday.setLootValue(500_000);
		yesterday.setSuppliesCost(30_000);
		yesterday.getSuppliesUsed().put("Shark", new ItemTotal(30, 30_000));
		history.put(yesterday.getDate(), yesterday);

		service.install("test", new JourneyStore.Loaded(null, history));
		Map<String, Long> xp = new HashMap<>();
		for (Skill s : Skills.ALL)
		{
			xp.put(s.name(), Skills.xpForLevel(80));
		}
		service.setBaseline(xp, 1);
	}

	private RangeSummary today()
	{
		return service.summarize(LocalDate.now(), LocalDate.now());
	}

	@Test
	public void lootIsSplitBySource()
	{
		service.onLoot("Guardians of the Rift", Arrays.asList(
			new JourneyService.LootItem(565, "Blood rune", 110, 36_850),
			new JourneyService.LootItem(561, "Nature rune", 149, 20_860)), 10);
		service.onLoot("Guardians of the Rift", Arrays.asList(
			new JourneyService.LootItem(565, "Blood rune", 115, 38_525)), 12);
		service.onLoot("Intricate pouch", Arrays.asList(
			new JourneyService.LootItem(989, "Crystal key", 1, 20_061)), 14);

		RangeSummary r = today();
		assertEquals(116_296, r.getLootValue());
		LootSource gotr = r.getLootBySource().get("Guardians of the Rift");
		assertEquals(96_235, gotr.getValue());
		assertEquals(2, gotr.getTimes());
		assertEquals(225, gotr.getItems().get("Blood rune").getQuantity());
		assertEquals(75_375, gotr.getItems().get("Blood rune").getValue());
		assertEquals(20_061, r.getLootBySource().get("Intricate pouch").getValue());
	}

	@Test
	public void sourcesAndSuppliesAddUpAcrossDays()
	{
		service.onLoot("Guardians of the Rift", Arrays.asList(
			new JourneyService.LootItem(565, "Blood rune", 100, 33_500)), 10);
		service.onSupplyUsed("Shark", 1, 1_000);

		RangeSummary r = service.summarize(LocalDate.now().minusDays(1), LocalDate.now());
		LootSource gotr = r.getLootBySource().get("Guardians of the Rift");
		assertEquals(533_500, gotr.getValue());
		assertEquals(11, gotr.getTimes());
		assertEquals(1_100, gotr.getItems().get("Blood rune").getQuantity());
		assertEquals(31_000, r.getSuppliesCost());
		assertEquals(31, r.getSuppliesUsed().get("Shark").getQuantity());
	}

	@Test
	public void suppliesAreRecorded()
	{
		service.onSupplyUsed("Prayer potion", 1, 2_400);
		service.onSupplyUsed("Prayer potion", 1, 2_400);
		service.onSupplyUsed("Shark", 1, 900);

		RangeSummary r = today();
		assertEquals(5_700, r.getSuppliesCost());
		assertEquals(2, r.getSuppliesUsed().get("Prayer potion").getQuantity());
		assertEquals(4_800, r.getSuppliesUsed().get("Prayer potion").getValue());
	}

	@Test
	public void suppliesAreIgnoredWhenTurnedOff()
	{
		trackSupplies = false;
		service.onSupplyUsed("Shark", 1, 900);
		RangeSummary r = today();
		assertEquals(0, r.getSuppliesCost());
		assertTrue(r.getSuppliesUsed().isEmpty());
	}

	@Test
	public void metricsUseSourcesAndSupplies()
	{
		DayRecord d = new DayRecord("2026-10-01");
		d.setLootValue(963_354);
		d.setSkillingIncome(59_237);
		d.setSuppliesCost(22_591);
		LootSource gotr = new LootSource();
		gotr.setValue(895_689);
		d.getLootBySource().put("Guardians of the Rift", gotr);

		assertEquals(963_354, Metric.LOOT.value(d, null), 0);
		assertEquals(895_689, Metric.LOOT.value(d, "Guardians of the Rift"), 0);
		assertEquals(0, Metric.LOOT.value(d, "Vorkath"), 0);
		assertEquals(22_591, Metric.SUPPLIES.value(d, null), 0);
		assertEquals(1_000_000, Metric.PROFIT.value(d, null), 0);
		assertEquals(895_689, Metric.LOOT.parts(d).get("Guardians of the Rift"), 0);
	}
}
