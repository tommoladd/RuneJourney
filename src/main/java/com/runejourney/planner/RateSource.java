package com.runejourney.planner;

import net.runelite.api.Skill;

public interface RateSource
{
	/**
	 * Estimated hours to train a skill from one XP amount to another.
	 */
	double hours(Skill skill, long fromXp, long toXp);

	/**
	 * Estimated XP/hr for the skill at the given XP.
	 */
	double rate(Skill skill, long xp);

	/**
	 * Name of the training method the estimate is based on.
	 */
	String methodName(Skill skill, long xp);

	/**
	 * Estimated hours to raise a counter (kill count, clues) from one value to another, or -1 when
	 * it can't be estimated.
	 */
	double counterHours(String counter, long from, long to);

	/**
	 * Whether estimates are based on the player's own observed training.
	 */
	boolean isPersonal(Skill skill);
}
