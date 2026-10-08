package com.runejourney.sync;

import java.util.function.LongSupplier;

/**
 * A logical clock for ordering edits made on different PCs. It follows the computer's clock, but
 * never goes backwards and always moves past every clock it has seen from another PC, so an edit
 * made after seeing another one is always newer even if the computers' clocks disagree.
 */
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

	/**
	 * A clock that always reads 0, for copies that should lose to any real edit.
	 */
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
