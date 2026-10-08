package com.runejourney.service;

import com.google.gson.Gson;
import com.runejourney.model.DayRecord;
import com.runejourney.model.ProfileData;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class JourneyStoreTest
{
	private final Gson gson = new Gson();

	@Test
	public void damagedFilesAreRecognised()
	{
		assertNull(JourneyStore.parse(gson, "", ProfileData.class));
		assertNull(JourneyStore.parse(gson, "{\"playerName\":\"Zezima\",\"lastXp\":{", ProfileData.class));
		assertNull(JourneyStore.parse(gson, "\u0000\u0000\u0000", DayRecord.class));
		assertNull(JourneyStore.parse(gson, "{\"playMillis\":\"not a number\"}", DayRecord.class));
	}

	@Test
	public void goodFilesAreRead()
	{
		assertEquals("Zezima", JourneyStore.parse(gson, "{\"playerName\":\"Zezima\"}", ProfileData.class).getPlayerName());
	}
}
