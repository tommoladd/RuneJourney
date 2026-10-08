package com.runejourney.service;

import com.google.gson.Gson;
import com.runejourney.RuneJourneyConfig;
import com.runejourney.model.DayRecord;
import com.runejourney.model.EventType;
import com.runejourney.model.JourneyEvent;
import com.runejourney.model.ProfileData;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PublicPageTest
{
	private static final String TODAY = LocalDate.now().toString();
	private static final Set<String> ALL = new HashSet<>(Arrays.asList(
		"skills", "kills", "collection", "timeline", "notes", "goals", "records", "wealth"));

	private final Gson gson = new Gson();
	private final RuneJourneyConfig config = new RuneJourneyConfig()
	{
	};

	private JourneyService service(String key, String name, TreeMap<String, DayRecord> days)
	{
		ProfileData profile = new ProfileData();
		profile.setPlayerName(name);
		JourneyService service = TestServices.journey(config, gson);
		service.install(key, new JourneyStore.Loaded(profile, days));
		return service;
	}

	@Test
	public void eachGameModeHasItsOwnPage()
	{
		assertEquals("main", JourneyService.world("123"));
		assertEquals("seasonal", JourneyService.world("123-seasonal"));
		assertEquals("deadman", JourneyService.world("123-deadman"));
		assertEquals("fresh-start", JourneyService.world("123-fsw"));
	}

	@Test
	public void theGamesSpacesInNamesAreOrdinarySpacesOnThePage()
	{
		JourneyService service = service("1", "Iron Man", new TreeMap<>());
		assertEquals("Iron Man", service.publicSnapshot("1", service.getGeneration(), ALL).getName());
	}

	@Test
	public void nothingIsPublishedWithoutAValidName()
	{
		JourneyService unnamed = service("1", null, new TreeMap<>());
		assertNull(unnamed.publicSnapshot("1", unnamed.getGeneration(), ALL));
		JourneyService odd = service("1", "Not<a>name", new TreeMap<>());
		assertNull(odd.publicSnapshot("1", odd.getGeneration(), ALL));
	}

	@Test
	public void onlyTheChosenSectionsAreFilledIn()
	{
		JourneyService service = service("1", "Zezima", new TreeMap<>());
		PublicSnapshot page = service.publicSnapshot("1", service.getGeneration(), new HashSet<>(Arrays.asList("skills", "goals")));
		assertTrue(page.getSkills() != null && page.getGoals() != null);
		assertNull(page.getKills());
		assertNull(page.getTimeline());
		assertNull(page.getCollection());
		assertNull(page.getRecords());
		assertNull(page.getWealth());
	}

	/**
	 * Memories added before they were marked are found by their time: the player types it to the
	 * minute, while recorded events have milliseconds.
	 */
	@Test
	public void olderMemoriesAreFoundByTheirWholeMinuteTimes()
	{
		DayRecord d = new DayRecord(TODAY);
		JourneyEvent memory = new JourneyEvent(1_700_000_040_000L, EventType.DROP, "Twisted bow", null, null, null, true, 0);
		JourneyEvent recorded = new JourneyEvent(1_700_000_040_123L, EventType.DEATH, "Oh dear, you are dead!", null, null, null, false, 0);
		d.getEvents().add(memory);
		d.getEvents().add(recorded);
		TreeMap<String, DayRecord> days = new TreeMap<>();
		days.put(TODAY, d);

		JourneyService service = service("1", "Zezima", days);
		PublicSnapshot page = service.publicSnapshot("1", service.getGeneration(), new HashSet<>(Arrays.asList("timeline")));
		assertEquals(1, page.getTimeline().size());
		assertEquals("Oh dear, you are dead!", page.getTimeline().get(0).getTitle());
		assertNull(page.getTimeline().get(0).getMemory());
	}
}
