package com.runejourney;

import com.runejourney.util.Format;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FormatTest
{
	@Test
	public void killTimesReadAsClocks()
	{
		assertEquals("1:12", Format.clock(1.2));
		assertEquals("0:45", Format.clock(0.75));
		assertEquals("12:05", Format.clock(12 + 5 / 60d));
		assertEquals("1:05:30", Format.clock(65.5));
	}

	@Test
	public void shortDurationsSpellOutMinutes()
	{
		// "50m" reads like 50 million next to gp values
		assertEquals("50 min", Format.hours(50 / 60d));
		assertEquals("37 min", Format.duration(37 * 60_000L));
		assertEquals("1.5h", Format.hours(1.5));
		assertEquals("4h 37m", Format.duration((4 * 60 + 37) * 60_000L));
	}
}
