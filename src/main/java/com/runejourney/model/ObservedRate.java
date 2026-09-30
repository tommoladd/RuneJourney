package com.runejourney.model;

import lombok.Data;

/**
 * XP gained while actively training a skill, used to personalise XP/hr estimates.
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
