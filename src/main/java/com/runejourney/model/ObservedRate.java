package com.runejourney.model;

import lombok.Data;

/**
 * An amount observed over active time, such as boss kills and the time spent on them.
 */
@Data
public class ObservedRate
{
	private long xp;
	private long millis;

	public double xpPerHour()
	{
		return millis <= 0 ? 0 : xp * 3_600_000d / millis;
	}
}
