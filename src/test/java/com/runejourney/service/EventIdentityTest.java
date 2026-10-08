package com.runejourney.service;

import com.google.gson.Gson;
import com.runejourney.RuneJourneyConfig;
import com.runejourney.model.DayRecord;
import com.runejourney.model.EventType;
import com.runejourney.model.Goal;
import com.runejourney.model.GoalItem;
import com.runejourney.model.GoalType;
import com.runejourney.model.JourneyEvent;
import com.runejourney.model.ProfileData;
import com.runejourney.planner.BossData;
import com.runejourney.planner.Skills;
import com.runejourney.planner.TrainingMethods;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class EventIdentityTest
{
	private static final String TODAY = LocalDate.now().toString();

	private final Gson gson = new Gson();
	private final RuneJourneyConfig config = new RuneJourneyConfig()
	{
		@Override
		public boolean chatAnnouncements()
		{
			return false;
		}
	};

	private JourneyService service(ProfileData profile, TreeMap<String, DayRecord> days)
	{
		JourneyService service = new JourneyService(null, null, config, gson, null, new TrainingMethods(gson), new BossData(gson));
		service.install("test", new JourneyStore.Loaded(profile, days));
		return service;
	}

	private static Map<String, Long> xp(Skill skill, long value)
	{
		return Collections.singletonMap(skill.name(), value);
	}

	private static List<JourneyEvent> events(JourneyService service)
	{
		return service.daysBetween(LocalDate.now(), LocalDate.now()).stream()
			.flatMap(d -> d.getEvents().stream())
			.collect(Collectors.toList());
	}

	private static TreeMap<String, DayRecord> savedBeforeIds()
	{
		DayRecord d = new DayRecord(TODAY);
		d.getEvents().add(new JourneyEvent(1_000, EventType.DEATH, "Oh dear, you are dead!", null, null, null, false, 0));
		d.getEvents().add(new JourneyEvent(1_000, EventType.DEATH, "Oh dear, you are dead!", null, null, null, false, 0));
		TreeMap<String, DayRecord> days = new TreeMap<>();
		days.put(TODAY, d);
		return days;
	}

	@Test
	public void eventsSavedBeforeIdsGetTheSameOnesEachLoad()
	{
		List<JourneyEvent> first = events(service(null, savedBeforeIds()));
		List<JourneyEvent> again = events(service(null, savedBeforeIds()));
		assertNotNull(first.get(0).getId());
		assertEquals(first.get(0).getId(), again.get(0).getId());
		assertEquals(first.get(1).getId(), again.get(1).getId());
	}

	@Test
	public void removingAnEventLeavesAnIdenticalOne()
	{
		JourneyService service = service(null, savedBeforeIds());
		service.deleteEvent(TODAY, events(service).get(0).getId());
		assertEquals(1, events(service).size());
	}

	@Test
	public void newEventsGetIds()
	{
		JourneyService service = service(null, new TreeMap<>());
		service.onDeath(1);
		service.onDeath(2);
		List<JourneyEvent> events = events(service);
		assertEquals(2, events.size());
		assertNotNull(events.get(0).getId());
		assertFalse(events.get(0).getId().equals(events.get(1).getId()));
	}

	@Test
	public void levelUpsHaveFixedIds()
	{
		JourneyService service = service(null, new TreeMap<>());
		service.setBaseline(xp(Skill.ATTACK, Skills.xpForLevel(81) - 10), 1);
		service.onXp(Skill.ATTACK, Skills.xpForLevel(81), 2);
		assertTrue(events(service).stream().anyMatch(e -> "level|ATTACK|81".equals(e.getId())));
	}

	/**
	 * Another device saw the level live; this one finds the same XP at login.
	 */
	@Test
	public void aLevelUpAlreadyRecordedIsNotRecordedAgain()
	{
		JourneyEvent live = new JourneyEvent(1_000, EventType.LEVEL, "Level 96 Runecraft", null, "RUNECRAFT", null, false, 96);
		live.setId("level|RUNECRAFT|96");
		TreeMap<String, DayRecord> days = new TreeMap<>();
		days.put(TODAY, new DayRecord(TODAY));
		days.get(TODAY).getEvents().add(live);
		ProfileData profile = new ProfileData();
		profile.setLastXp(xp(Skill.RUNECRAFT, Skills.xpForLevel(96) - 100));
		profile.setLastXpAt(System.currentTimeMillis());

		JourneyService service = service(profile, days);
		service.setBaseline(xp(Skill.RUNECRAFT, Skills.xpForLevel(96) + 100), 1);
		assertEquals(1, events(service).stream().filter(e -> e.getType() == EventType.LEVEL).count());
	}

	@Test
	public void goalItemsAreTickedByItemNotPosition()
	{
		JourneyService service = service(null, new TreeMap<>());
		service.setBaseline(xp(Skill.ATTACK, Skills.xpForLevel(80)), 1);
		Goal goal = new Goal();
		goal.setType(GoalType.ITEMS);
		goal.setName("Barrows gloves");
		goal.getItems().add(new GoalItem(7462, "Barrows gloves"));
		goal.getItems().add(new GoalItem(7461, "Dragon gloves"));
		service.createGoal(goal);
		String id = service.goalProgress().get(0).getGoal().getId();

		service.setItemObtained(id, new GoalItem(7461, "Dragon gloves"), true);
		List<GoalItem> items = service.goal(id).getItems();
		assertFalse(items.get(0).isObtained());
		assertTrue(items.get(1).isObtained());
	}
}
