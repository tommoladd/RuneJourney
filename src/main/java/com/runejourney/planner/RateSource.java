package com.runejourney.planner;

import net.runelite.api.Skill;

public interface RateSource
{
	double hours(Skill skill, long fromXp, long toXp);

	double rate(Skill skill, long xp);

	String methodName(Skill skill, long xp);

	double counterHours(String counter, long from, long to);

	boolean isPersonal(Skill skill);
}
