package com.runejourney.ui;

import java.time.LocalDateTime;
import java.time.ZoneId;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ScreenshotNameTest
{
	@Test
	public void screenshotsWithoutAJourneyEntryAreNamedFromTheFile()
	{
		assertEquals("Oh dear you are dead", ScreenshotWindow.titleFromName("2026-10-01_21-47-41_oh-dear-you-are-dead_1.png"));
		assertEquals("Guardian s eye", ScreenshotWindow.titleFromName("2026-10-01_09-24-40_guardian-s-eye_1.png"));

		long expected = LocalDateTime.of(2026, 10, 1, 21, 47, 41).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
		assertEquals(expected, ScreenshotWindow.timeFromName("2026-10-01_21-47-41_oh-dear-you-are-dead_1.png", 0));
		// Anything else falls back to the file's own time
		assertEquals(42, ScreenshotWindow.timeFromName("holiday.png", 42));
	}
}
