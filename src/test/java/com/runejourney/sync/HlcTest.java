package com.runejourney.sync;

import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class HlcTest
{
	private static final long DAY = 24 * 60 * 60_000L;

	@Test
	public void neverGoesBackwards()
	{
		AtomicLong wall = new AtomicLong(1_000);
		Hlc hlc = new Hlc(0, wall::get);
		long a = hlc.next();
		wall.set(500);
		long b = hlc.next();
		assertTrue(b > a);
	}

	/**
	 * A PC whose clock is a day behind still makes edits newer than one it has already seen from a
	 * PC whose clock is a day ahead.
	 */
	@Test
	public void editsAfterSeeingAnotherAreNewerDespiteClockSkew()
	{
		long now = 1_700_000_000_000L;
		Hlc ahead = new Hlc(0, () -> now + DAY);
		Hlc behind = new Hlc(0, () -> now - DAY);
		long first = ahead.next();
		behind.observe(first);
		long second = behind.next();
		assertTrue(second > first);
		ahead.observe(second);
		assertTrue(ahead.next() > second);
	}

	@Test
	public void zeroAlwaysLoses()
	{
		Hlc zero = Hlc.zero();
		zero.observe(5_000);
		assertEquals(0, zero.next());
	}
}
