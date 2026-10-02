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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ScreenshotGalleryTest
{
	@Test
	public void onlyOurOwnFileNamesAreTouched()
	{
		assertTrue(JourneyStore.isScreenshotName("2026-10-01_09-24-40_guardian-s-eye_1.png"));
		assertFalse(JourneyStore.isScreenshotName("../profile.json"));
		assertFalse(JourneyStore.isScreenshotName("..\\..\\profile.png"));
		assertFalse(JourneyStore.isScreenshotName("sub/dir.png"));
		assertFalse(JourneyStore.isScreenshotName(".hidden.png"));
		assertFalse(JourneyStore.isScreenshotName("notes.txt"));
		assertFalse(JourneyStore.isScreenshotName(null));
	}

	@Test
	public void deletingAScreenshotUnlinksItsJourneyEntry()
	{
		Gson gson = new Gson();
		JourneyService service = new JourneyService(null, null, new RuneJourneyConfig()
		{
		}, gson, null, new TrainingMethods(gson), new BossData(gson));
		String today = LocalDate.now().toString();
		DayRecord d = new DayRecord(today);
		d.setPlayMillis(60_000);
		JourneyEvent e = new JourneyEvent(1_000, EventType.COLLECTION_LOG, "Guardian's eye", null, null,
			"2026-10-01_09-24-40_guardian-s-eye_1.png", true, 0);
		d.getEvents().add(e);
		TreeMap<String, DayRecord> days = new TreeMap<>();
		days.put(today, d);
		service.install("test", new JourneyStore.Loaded(null, days));

		assertEquals("Guardian's eye", service.screenshotEvents().get("2026-10-01_09-24-40_guardian-s-eye_1.png").getTitle());

		service.forgetScreenshot("2026-10-01_09-24-40_guardian-s-eye_1.png");
		assertTrue(service.screenshotEvents().isEmpty());
		// The entry itself stays in the Journey
		assertNull(d.getEvents().get(0).getScreenshot());
		assertEquals(1, d.getEvents().size());
	}
}
