package com.runejourney.model;

import java.util.regex.Pattern;

/**
 * Older versions read "Your Ardougne Rooftop lap count is" and "Your herbiboar harvest count is"
 * as boss kills, saving them as "Ardougne Rooftop lap" and "Herbiboar harvest". These strip them back out.
 */
public final class NonBossCounts
{
	private static final Pattern NAME = Pattern.compile("(?i).+ (?:lap|harvest)$");
	private static final Pattern EVENT_TITLE = Pattern.compile("(?i).+ (?:lap|harvest) (?:kills?|KC)$");

	private NonBossCounts()
	{
	}

	public static boolean is(String name)
	{
		return name != null && NAME.matcher(name).matches();
	}

	public static boolean strip(ProfileData p)
	{
		boolean changed = p.getKillCounts().keySet().removeIf(NonBossCounts::is);
		changed |= p.getKillTimes().keySet().removeIf(NonBossCounts::is);
		return changed;
	}

	public static boolean strip(DayRecord d)
	{
		boolean changed = d.getBossKills().keySet().removeIf(NonBossCounts::is);
		changed |= d.getEvents().removeIf(e -> e.getType() == EventType.BOSS_KC
			&& e.getTitle() != null && EVENT_TITLE.matcher(e.getTitle()).matches());
		return changed;
	}
}
