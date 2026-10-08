package com.runejourney.service;

import com.google.gson.Gson;
import com.runejourney.RuneJourneyConfig;
import com.runejourney.model.DayRecord;
import com.runejourney.model.EventType;
import com.runejourney.model.JourneyEvent;
import com.runejourney.planner.BossData;
import com.runejourney.planner.TrainingMethods;
import java.time.LocalDate;
import java.util.TreeMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Before;
import org.junit.Test;

public class EventNoteTest
{
	private static final String TODAY = LocalDate.now().toString();
	private static final long TIME = 1_000L;

	private JourneyService service;
	private final Gson gson = new Gson();

	@Before
	public void setUp()
	{
		RuneJourneyConfig config = new RuneJourneyConfig()
		{
		};
		service = new JourneyService(null, null, config, gson, null, new TrainingMethods(gson), new BossData(gson));
		TreeMap<String, DayRecord> days = new TreeMap<>();
		DayRecord d = new DayRecord(TODAY);
		d.setPlayMillis(60_000);
		d.getEvents().add(new JourneyEvent(TIME, EventType.DEATH, "Oh dear, you are dead!", null, null, null, false, 0));
		d.getEvents().add(new JourneyEvent(TIME, EventType.COLLECTION_LOG, "Guardian's eye", null, null, null, true, 0));
		days.put(TODAY, d);
		service.install("test", new JourneyStore.Loaded(null, days));
	}

	private JourneyEvent shown(String title)
	{
		return service.journeyDays(5, null, false).stream()
			.flatMap(d -> d.getEvents().stream())
			.filter(e -> e.getTitle().equals(title))
			.findFirst().orElseThrow(AssertionError::new);
	}

	@Test
	public void notesAreAddedEditedAndRemoved()
	{
		String death = shown("Oh dear, you are dead!").getId();
		service.setEventNote(TODAY, death, "  Forgot to pray at Zuk  ");
		assertEquals("Forgot to pray at Zuk", shown("Oh dear, you are dead!").getNote());
		// Only the matching event gets it, even at the same time
		assertNull(shown("Guardian's eye").getNote());

		service.setEventNote(TODAY, death, "Forgot to pray at Zuk.\nNext time!");
		assertEquals("Forgot to pray at Zuk.\nNext time!", shown("Oh dear, you are dead!").getNote());

		service.setEventNote(TODAY, death, "   ");
		assertNull(shown("Oh dear, you are dead!").getNote());
	}

	@Test
	public void notesSurviveSaving()
	{
		JourneyEvent e = new JourneyEvent(TIME, EventType.PET, "Tangleroot", null, null, null, true, 0);
		e.setNote("Finally!");
		JourneyEvent loaded = gson.fromJson(gson.toJson(e), JourneyEvent.class);
		assertEquals("Finally!", loaded.getNote());

		// Events saved before notes existed load without one
		JourneyEvent old = gson.fromJson("{\"time\":1,\"type\":\"PET\",\"title\":\"Tangleroot\"}", JourneyEvent.class);
		assertNull(old.getNote());
	}
}
