package com.runejourney.model;

import lombok.Data;

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
