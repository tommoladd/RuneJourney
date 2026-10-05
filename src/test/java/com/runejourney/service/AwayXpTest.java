package com.runejourney.service;

import com.google.gson.Gson;
import com.runejourney.RuneJourneyConfig;
import com.runejourney.model.DayRecord;
import com.runejourney.model.EventType;
import com.runejourney.model.JourneyEvent;
import com.runejourney.model.ProfileData;
import com.runejourney.planner.BossData;
import com.runejourney.planner.TrainingMethods;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AwayXpTest
{
	// Runecraft over a weekend on mobile: level 94 to 96
	private static final long RC_BEFORE = 8_659_400;
	private static final long RC_AFTER = 9_761_580;
	private static final long RC_GAINED = RC_AFTER - RC_BEFORE;

	private final RuneJourneyConfig config = new RuneJourneyConfig()
	{
		@Override
		public boolean chatAnnouncements()
		{
			return false;
		}
	};
	private final LocalDate today = LocalDate.now();

	private JourneyService service(ProfileData profile, TreeMap<String, DayRecord> days)
	{
		Gson gson = new Gson();
		JourneyService service = new JourneyService(null, null, config, gson, null, new TrainingMethods(gson), new BossData(gson));
		service.install("test", new JourneyStore.Loaded(profile, days));
		return service;
	}

	private static Map<String, Long> runecraft(long xp)
	{
		Map<String, Long> m = new HashMap<>();
		m.put("RUNECRAFT", xp);
		return m;
	}

	private static ProfileData lastSeen(long at)
	{
		ProfileData profile = new ProfileData();
		profile.setLastXp(runecraft(RC_BEFORE));
		profile.setLastXpAt(at);
		return profile;
	}

	private long millis(LocalDate date)
	{
		return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 60_000;
	}

	private static DayRecord played(LocalDate date)
	{
		DayRecord d = new DayRecord(date.toString());
		d.setPlayMillis(3_600_000);
		return d;
	}

	@Test
	public void xpFromAnEarlierDayIsNotToday()
	{
		JourneyService service = service(lastSeen(millis(today.minusDays(3))), new TreeMap<>());
		service.setBaseline(runecraft(RC_AFTER), 1);

		RangeSummary day = service.summarize(today, today);
		assertEquals(0, day.getXpGained());
		assertEquals(0, day.getLevelsGained());
		assertFalse(day.getLevelRanges().containsKey("RUNECRAFT"));
		assertEquals(RC_GAINED, day.getAwayXp());
		assertEquals(today.minusDays(3), day.getAwayFrom());
		assertEquals(today, day.getAwayTo());

		// A range covering the whole gap counts it
		RangeSummary span = service.summarize(today.minusDays(3), today);
		assertEquals(RC_GAINED, span.getXpGained());
		assertEquals(RC_GAINED, (long) span.getSkillXp().get("RUNECRAFT"));
		assertEquals(2, span.getLevelsGained());
		assertTrue(span.getLevelRanges().containsKey("RUNECRAFT"));
		assertEquals(0, span.getAwayXp());

		// One starting inside it doesn't
		assertEquals(0, service.summarize(today.minusDays(2), today).getXpGained());
	}

	@Test
	public void xpFromEarlierTodayIsToday()
	{
		JourneyService service = service(lastSeen(millis(today)), new TreeMap<>());
		service.setBaseline(runecraft(RC_AFTER), 1);

		RangeSummary day = service.summarize(today, today);
		assertEquals(RC_GAINED, day.getXpGained());
		assertEquals(2, day.getLevelsGained());
		assertEquals(0, day.getAwayXp());
	}

	@Test
	public void olderProfilesUseTheLastDayPlayed()
	{
		TreeMap<String, DayRecord> days = new TreeMap<>();
		days.put(today.minusDays(4).toString(), played(today.minusDays(4)));
		JourneyService service = service(lastSeen(0), days);
		service.setBaseline(runecraft(RC_AFTER), 1);

		RangeSummary day = service.summarize(today, today);
		assertEquals(0, day.getXpGained());
		assertEquals(today.minusDays(4), day.getAwayFrom());
	}

	/**
	 * A day saved before away XP was kept aside, as the login on {@code date} would have left it.
	 */
	private DayRecord oldLoginDay(LocalDate date, long noteTime)
	{
		DayRecord d = new DayRecord(date.toString());
		d.setPlayMillis(3_600_000);
		d.setXpGained(RC_GAINED + 50_000);
		d.getSkillXp().put("RUNECRAFT", RC_GAINED);
		d.getSkillXp().put("WOODCUTTING", 50_000L);
		d.setOfflineXp(RC_GAINED);
		d.getOfflineSkillXp().put("RUNECRAFT", RC_GAINED);
		d.setLevelsGained(2);
		// Written by the same login a few milliseconds before the note
		d.getEvents().add(new JourneyEvent(noteTime - 5, EventType.WEEKLY_PLAN, "Week complete", null, null, null, false, 0));
		JourneyEvent level = new JourneyEvent(noteTime - 2, EventType.LEVEL, "Level 96 Runecraft",
			"9,761,580 XP (gained while away)", "RUNECRAFT", null, false, 96);
		d.getEvents().add(level);
		d.getEvents().add(new JourneyEvent(noteTime, EventType.NOTE, "While you were away: +1.1m XP",
			"Runecraft +1.1m", null, null, false, 0));
		d.getEvents().add(new JourneyEvent(noteTime + 60_000, EventType.DROP, "Log brace", null, null, null, true, 0));
		return d;
	}

	@Test
	public void savedDaysAreMovedOutOfTheLoginDay()
	{
		TreeMap<String, DayRecord> days = new TreeMap<>();
		days.put(today.minusDays(3).toString(), played(today.minusDays(3)));
		DayRecord login = oldLoginDay(today, millis(today));
		days.put(today.toString(), login);
		JourneyService service = service(lastSeen(millis(today)), days);

		assertEquals(50_000, login.getXpGained());
		assertNull(login.getSkillXp().get("RUNECRAFT"));
		assertEquals(0, login.getLevelsGained());
		assertEquals(RC_GAINED, login.getAwayXp());
		assertEquals(2, login.getAwayLevels());
		assertEquals(today.minusDays(3).toString(), login.getAwayFrom());
		assertEquals(0, login.getOfflineXp());

		RangeSummary day = service.summarize(today, today);
		assertEquals(50_000, day.getXpGained());
		assertFalse(day.getLevelRanges().containsKey("RUNECRAFT"));
	}

	@Test
	public void savedDaysAreLeftAloneWhenUnsure()
	{
		TreeMap<String, DayRecord> days = new TreeMap<>();
		days.put(today.minusDays(3).toString(), played(today.minusDays(3)));

		// Something happened earlier that day, so the gap may have been from earlier today
		DayRecord earlier = oldLoginDay(today, millis(today) + 120_000);
		earlier.getEvents().add(new JourneyEvent(millis(today), EventType.DROP, "Dragon bones", null, null, null, false, 0));
		days.put(today.toString(), earlier);
		assertFalse(AwayXp.migrate(earlier, days));

		// Two gaps were added together
		DayRecord twice = oldLoginDay(today, millis(today));
		twice.getEvents().add(new JourneyEvent(millis(today) + 120_000, EventType.NOTE, "While you were away: +5k XP",
			null, null, null, false, 0));
		days.put(today.toString(), twice);
		assertFalse(AwayXp.migrate(twice, days));

		// Nothing played before it
		TreeMap<String, DayRecord> first = new TreeMap<>();
		DayRecord only = oldLoginDay(today, millis(today));
		first.put(today.toString(), only);
		assertFalse(AwayXp.migrate(only, first));
		assertEquals(RC_GAINED + 50_000, only.getXpGained());
	}
}
