package com.runejourney.service;

import com.google.gson.Gson;
import com.runejourney.RuneJourneyConfig;
import com.runejourney.model.DayRecord;
import com.runejourney.model.ProfileData;
import com.runejourney.planner.BossData;
import com.runejourney.planner.TrainingMethods;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.api.Skill;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class XpRangesTest
{
	private final Gson gson = new Gson();
	private final RuneJourneyConfig config = new RuneJourneyConfig()
	{
		@Override
		public boolean chatAnnouncements()
		{
			return false;
		}
	};

	private JourneyService service(ProfileData profile)
	{
		JourneyService service = new JourneyService(null, null, config, gson, null, new TrainingMethods(gson), new BossData(gson));
		service.install("test", new JourneyStore.Loaded(profile, new TreeMap<>()));
		return service;
	}

	private static Map<String, Long> agility(long xp)
	{
		return Collections.singletonMap(Skill.AGILITY.name(), xp);
	}

	private static ProfileData lastSeen(long xp, long at)
	{
		ProfileData profile = new ProfileData();
		profile.setLastXp(agility(xp));
		profile.setLastXpAt(at);
		return profile;
	}

	private static DayRecord today(JourneyService service)
	{
		return service.daysBetween(LocalDate.now(), LocalDate.now()).get(0);
	}

	private static void assertRanges(List<long[]> ranges, long[]... expected)
	{
		assertEquals(expected.length, ranges.size());
		for (int i = 0; i < expected.length; i++)
		{
			assertArrayEquals(expected[i], ranges.get(i));
		}
	}

	@Test
	public void trainingIsOneRangeAcrossRelogs()
	{
		JourneyService service = service(lastSeen(12_000_000, System.currentTimeMillis()));
		service.setBaseline(agility(12_000_000), 1);
		service.onXp(Skill.AGILITY, 12_000_500, 2);
		service.onXp(Skill.AGILITY, 12_001_000, 3);

		// Logging out and back in with nothing gained elsewhere carries on the same range
		service.resetSessionState();
		service.setBaseline(agility(12_001_000), 10);
		service.onXp(Skill.AGILITY, 12_001_500, 11);

		DayRecord d = today(service);
		assertRanges(d.getXpRanges().get("AGILITY"), new long[]{12_000_000, 12_001_500});
		assertTrue(d.getOfflineRanges().isEmpty());
	}

	@Test
	public void xpGainedElsewhereTodaySplitsTheRanges()
	{
		JourneyService service = service(lastSeen(12_000_000, System.currentTimeMillis()));
		service.setBaseline(agility(12_000_000), 1);
		service.onXp(Skill.AGILITY, 12_001_000, 2);

		// Played on mobile, then back here
		service.resetSessionState();
		service.setBaseline(agility(12_050_000), 10);
		service.onXp(Skill.AGILITY, 12_051_000, 11);

		DayRecord d = today(service);
		assertRanges(d.getXpRanges().get("AGILITY"), new long[]{12_000_000, 12_001_000}, new long[]{12_050_000, 12_051_000});
		assertRanges(d.getOfflineRanges().get("AGILITY"), new long[]{12_001_000, 12_050_000});
		assertTrue(d.getAwayRanges().isEmpty());
	}

	@Test
	public void xpFromAGapThatBeganEarlierIsAway()
	{
		long threeDaysAgo = LocalDate.now().minusDays(3).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
		JourneyService service = service(lastSeen(12_000_000, threeDaysAgo));
		service.setBaseline(agility(12_200_000), 1);

		DayRecord d = today(service);
		assertRanges(d.getAwayRanges().get("AGILITY"), new long[]{12_000_000, 12_200_000});
		assertTrue(d.getXpRanges().isEmpty());
		assertTrue(d.getOfflineRanges().isEmpty());
	}

	@Test
	public void copiesDoNotShareRanges()
	{
		JourneyService service = service(lastSeen(12_000_000, System.currentTimeMillis()));
		service.setBaseline(agility(12_000_000), 1);
		service.onXp(Skill.AGILITY, 12_001_000, 2);
		today(service).getXpRanges().get("AGILITY").get(0)[1] = 0;
		assertEquals(12_001_000, today(service).getXpRanges().get("AGILITY").get(0)[1]);
	}
}
