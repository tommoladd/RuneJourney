package com.runejourney.service;

import com.google.gson.Gson;
import com.runejourney.RuneJourneyConfig;
import com.runejourney.model.ClogItem;
import com.runejourney.model.DayRecord;
import com.runejourney.model.EventType;
import com.runejourney.model.Goal;
import com.runejourney.model.GoalItem;
import com.runejourney.model.GoalType;
import com.runejourney.planner.BossData;
import com.runejourney.planner.Counters;
import com.runejourney.planner.GoalProgress;
import com.runejourney.planner.Skills;
import com.runejourney.planner.TrainingMethods;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class NewFeaturesTest
{
	private JourneyService service;
	private TreeMap<String, DayRecord> history;

	private final RuneJourneyConfig config = new RuneJourneyConfig()
	{
		@Override
		public boolean chatAnnouncements()
		{
			return false;
		}

		@Override
		public boolean screenshotGoals()
		{
			return false;
		}

		@Override
		public boolean screenshotCollectionLog()
		{
			return false;
		}

		@Override
		public boolean showOverlay()
		{
			return true;
		}

		@Override
		public boolean overlayNetWorth()
		{
			return true;
		}
	};

	@Before
	public void setUp()
	{
		Gson gson = new Gson();
		service = new JourneyService(null, null, config, gson, null, new TrainingMethods(gson), new BossData(gson));
		history = new TreeMap<>();
		// Played the 3 days before today
		for (int i = 3; i >= 1; i--)
		{
			DayRecord d = new DayRecord(LocalDate.now().minusDays(i).toString());
			d.setPlayMillis(3_600_000L);
			d.setXpGained(100_000L * i);
			history.put(d.getDate(), d);
		}
		service.install("test", new JourneyStore.Loaded(null, history));
		Map<String, Long> xp = new HashMap<>();
		for (Skill s : Skills.ALL)
		{
			xp.put(s.name(), Skills.xpForLevel(80));
		}
		service.setBaseline(xp, 1);
	}

	private long events(EventType type)
	{
		return service.journeyDays(10, null, false).stream()
			.flatMap(d -> d.getEvents().stream()).filter(e -> e.getType() == type).count();
	}

	@Test
	public void firstBankViewDoesNotFireEveryMilestone()
	{
		service.onWealth("inventory", 5_000_000);
		service.onWealth("bank", 900_000_000);
		assertEquals(0, events(EventType.RECORD));
		assertEquals(905_000_000L, service.netWorthToday()[0]);

		// Crossing 1b later is celebrated once
		service.onWealth("bank", 1_000_000_000);
		assertEquals(1, events(EventType.RECORD));
		service.onWealth("bank", 950_000_000);
		service.onWealth("bank", 1_010_000_000);
		assertEquals(1, events(EventType.RECORD));
	}

	@Test
	public void collectionLogImportTicksGoals()
	{
		Goal items = new Goal();
		items.setType(GoalType.ITEMS);
		items.setName("Vorkath uniques");
		items.getItems().add(new GoalItem(11286, "Draconic visage"));
		items.getItems().add(new GoalItem(22106, "Jar of decay"));
		assertNull(service.createGoal(items));

		List<ClogItem> page = Arrays.asList(
			new ClogItem(22106, "Jar of decay", true),
			new ClogItem(11286, "Draconic visage", false),
			new ClogItem(21907, "Vorkath's head", true),
			new ClogItem(22111, "Vorki", false));
		service.onCollectionLogPage("Vorkath", page);
		assertEquals(2, service.collectionLogPages().get("Vorkath")[0]);
		Goal after = service.goal(items.getId());
		assertTrue(after.getItems().get(1).isObtained());
		assertEquals("Collection log", after.getItems().get(1).getSource());

		Goal log = new Goal();
		log.setType(GoalType.CLOG_CATEGORY);
		log.setName("Complete the Vorkath log");
		log.setCounter(Counters.clogPage("Vorkath"));
		log.setTargetCount(4);
		assertNull(service.createGoal(log));
		// A new slot from chat updates the page
		service.onCollectionLog("Vorki", 2);
		GoalProgress p = service.goalProgress().stream().filter(g -> g.getGoal().getId().equals(log.getId())).findFirst().get();
		assertEquals(3, p.getCountCurrent());
	}

	@Test
	public void streaksAndRecords()
	{
		int[] streaks = service.playStreaks();
		assertEquals(3, streaks[0]);
		assertEquals(3, streaks[1]);

		List<JourneyService.PersonalRecord> records = service.records();
		JourneyService.PersonalRecord xp = records.stream().filter(r -> r.getTitle().equals("Most XP in a day")).findFirst().get();
		assertEquals("300k XP", xp.getValue());
		assertEquals(LocalDate.now().minusDays(3), xp.getDate());
	}

	@Test
	public void overlayShowsPinnedGoal()
	{
		Goal a = new Goal();
		a.setType(GoalType.SKILL);
		a.setSkill(Skill.SLAYER.name());
		a.setTargetXp(Skills.xpForLevel(99));
		a.setName("99 Slayer");
		assertNull(service.createGoal(a));
		Goal b = new Goal();
		b.setType(GoalType.SKILL);
		b.setSkill(Skill.MINING.name());
		b.setTargetXp(Skills.xpForLevel(90));
		b.setName("90 Mining");
		assertNull(service.createGoal(b));

		assertEquals("99 Slayer", service.overlayData().getGoalName());
		service.setOverlayGoal(b.getId());
		OverlayData data = service.overlayData();
		assertEquals("90 Mining", data.getGoalName());
		assertNotNull(data.getGoalDetail());
		// Net worth is hidden until the bank has been seen
		assertNull(data.getNetWorth());
	}
}
