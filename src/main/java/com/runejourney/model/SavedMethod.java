package com.runejourney.model;

import lombok.Data;

@Data
public class SavedMethod
{
	private String name;
	private long xp;
	private long millis;
	private long lastUsed;

	public double xpPerHour()
	{
		return millis <= 0 ? 0 : xp * 3_600_000d / millis;
	}
}
