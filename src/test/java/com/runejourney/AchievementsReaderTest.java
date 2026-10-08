package com.runejourney;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class AchievementsReaderTest
{
	@Test
	public void gameTextIsCleanedForTheWebsite()
	{
		assertEquals("", AchievementsReader.text(null, 100));
		assertEquals("", AchievementsReader.text("  ", 100));
		assertEquals("Kill Zulrah", AchievementsReader.text("<col=ff0000>Kill</col> Zulrah", 100));
		assertEquals("Line one line two", AchievementsReader.text("Line one<br>line two", 100));
		assertEquals("Tab and new line", AchievementsReader.text("Tab\tand\nnew line", 100));
		assertEquals("Kree'arra", AchievementsReader.text("Kree'arra", 100));
		assertEquals("abc", AchievementsReader.text("abcdef", 3));
	}
}
