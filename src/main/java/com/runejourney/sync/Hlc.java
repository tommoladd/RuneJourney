package com.runejourney.sync;

import java.util.function.LongSupplier;

public final class Hlc
{
	private final LongSupplier wallClock;
	private final boolean frozen;
	private long last;

	public Hlc(long last)
	{
		this(last, System::currentTimeMillis);
	}

	Hlc(long last, LongSupplier wallClock)
	{
		this(last, wallClock, false);
	}

	private Hlc(long last, LongSupplier wallClock, boolean frozen)
	{
		this.last = last;
		this.wallClock = wallClock;
		this.frozen = frozen;
	}

	public static Hlc zero()
	{
		return new Hlc(0, () -> 0, true);
	}

	public synchronized long next()
	{
		if (frozen)
		{
			return 0;
		}
		last = Math.max(wallClock.getAsLong(), last + 1);
		return last;
	}

	public synchronized void observe(long clock)
	{
		if (!frozen)
		{
			last = Math.max(last, clock);
		}
	}

	public synchronized long last()
	{
		return last;
	}
}
