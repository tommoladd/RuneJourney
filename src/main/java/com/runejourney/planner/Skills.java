package com.runejourney.planner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.api.Experience;
import net.runelite.api.Skill;

public final class Skills
{
	/**
	 * All trainable skills, excluding the OVERALL pseudo-skill.
	 */
	@SuppressWarnings("deprecation")
	public static final List<Skill> ALL = Collections.unmodifiableList(Arrays.asList(
		Arrays.stream(Skill.values()).filter(s -> s != Skill.OVERALL).toArray(Skill[]::new)));

	public static final int MAX_TOTAL_LEVEL = ALL.size() * Experience.MAX_REAL_LEVEL;

	private Skills()
	{
	}

	public static Skill parse(String name)
	{
		if (name == null)
		{
			return null;
		}
		try
		{
			return Skill.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	public static long xp(Map<String, Long> xp, Skill skill)
	{
		Long v = xp.get(skill.name());
		return v == null ? 0 : v;
	}

	/**
	 * Real level (1-99) for an XP amount.
	 */
	public static int level(long xp)
	{
		return Math.min(Experience.MAX_REAL_LEVEL, Experience.getLevelForXp((int) Math.min(xp, Experience.MAX_SKILL_XP)));
	}

	public static int totalLevel(Map<String, Long> xp)
	{
		int total = 0;
		for (Skill s : ALL)
		{
			total += level(xp(xp, s));
		}
		return total;
	}

	public static long xpForLevel(int level)
	{
		return Experience.getXpForLevel(Math.max(1, Math.min(level, Experience.MAX_VIRT_LEVEL)));
	}
}
