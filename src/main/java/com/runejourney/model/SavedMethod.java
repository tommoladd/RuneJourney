package com.runejourney.model;

import lombok.Data;

/**
 * A training method the player saved from their own XP rate: the XP gained over the training time it
 * was measured from.
 */
@Data
public class SavedMethod
{
	private String name;
	private long xp;
	private long millis;
	/**
	 * Epoch millis this method was last trained (or saved). With no method chosen for a skill, plans
	 * use the most recent.
	 */
	private long lastUsed;

	public double xpPerHour()
	{
		return millis <= 0 ? 0 : xp * 3_600_000d / millis;
	}
}
