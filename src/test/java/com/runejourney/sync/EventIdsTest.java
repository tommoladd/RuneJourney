package com.runejourney.sync;

import com.google.gson.Gson;
import com.runejourney.model.DayRecord;
import com.runejourney.model.EventType;
import com.runejourney.model.JourneyEvent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class EventIdsTest
{
	private final Gson gson = new Gson();

	private static DayRecord oldDay()
	{
		DayRecord d = new DayRecord("2026-09-14");
		d.getEvents().add(new JourneyEvent(1_000, EventType.DROP, "Dragon claws", "52m gp · Tekton", null, null, true, 52_000_000));
		// The same memory added twice by hand
		d.getEvents().add(new JourneyEvent(2_000, EventType.NOTE, "Finished the fight caves", null, null, null, false, 0));
		d.getEvents().add(new JourneyEvent(2_000, EventType.NOTE, "Finished the fight caves", null, null, null, false, 0));
		return d;
	}

	@Test
	public void oldEventsGetTheSameIdsOnEveryDevice()
	{
		DayRecord here = oldDay();
		DayRecord there = gson.fromJson(gson.toJson(oldDay()), DayRecord.class);
		assertTrue(EventIds.assignMissing(here));
		assertTrue(EventIds.assignMissing(there));
		for (int i = 0; i < here.getEvents().size(); i++)
		{
			assertEquals(here.getEvents().get(i).getId(), there.getEvents().get(i).getId());
		}
	}

	@Test
	public void identicalEventsGetDifferentIds()
	{
		DayRecord d = oldDay();
		EventIds.assignMissing(d);
		assertNotEquals(d.getEvents().get(1).getId(), d.getEvents().get(2).getId());
	}

	@Test
	public void idsAreOnlyGivenOnce()
	{
		DayRecord d = oldDay();
		d.getEvents().get(0).setId("kept");
		assertTrue(EventIds.assignMissing(d));
		assertEquals("kept", d.getEvents().get(0).getId());
		assertFalse(EventIds.assignMissing(d));
	}

	@Test
	public void fixedIdsAreJoinedParts()
	{
		assertEquals("level|ATTACK|80", EventIds.fixed("level", "ATTACK", 80));
		assertNotEquals(EventIds.random(), EventIds.random());
	}
}
