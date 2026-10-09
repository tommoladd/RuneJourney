package com.runejourney.service;

import com.runejourney.model.*;
import com.runejourney.planner.Skills;
import java.time.*;
import java.util.*;

final class AwayXp
{
	static final String NOTE_PREFIX = "While you were away";
	static final String LEVEL_SUFFIX = "(gained while away)";
	private static final long LOGIN_MILLIS = 10_000;

	private AwayXp()
	{
	}

	static LocalDate windowStart(long lastXpAt, NavigableMap<String, DayRecord> days, LocalDate today)
	{
		if (lastXpAt > 0)
		{
			return Instant.ofEpochMilli(lastXpAt).atZone(ZoneId.systemDefault()).toLocalDate();
		}
		DayRecord last = lastPlayed(days, today, true);
		return last == null ? today : LocalDate.parse(last.getDate());
	}

	static boolean counted(DayRecord day, LocalDate from)
	{
		return day.getAwayFrom() != null && day.getAwayFrom().compareTo(from.toString()) >= 0;
	}

	static boolean migrate(DayRecord day, NavigableMap<String, DayRecord> days)
	{
		if (day.getOfflineXp() <= 0 || day.getAwayFrom() != null || !day.getOfflineRanges().isEmpty())
		{
			return false;
		}
		JourneyEvent note = null;
		for (JourneyEvent e : day.getEvents())
		{
			if (e.getType() == EventType.NOTE && e.getTitle() != null && e.getTitle().startsWith(NOTE_PREFIX))
			{
				if (note != null)
				{
					return false;
				}
				note = e;
			}
		}
		DayRecord last = lastPlayed(days, LocalDate.parse(day.getDate()), false);
		if (note == null || last == null)
		{
			return false;
		}
		for (JourneyEvent e : day.getEvents())
		{
			if (e.getTime() < note.getTime() - LOGIN_MILLIS)
			{
				return false;
			}
		}

		List<JourneyEvent> levels = new ArrayList<>();
		for (JourneyEvent e : day.getEvents())
		{
			if (e.getType() == EventType.LEVEL && e.getDetail() != null && e.getDetail().endsWith(LEVEL_SUFFIX))
			{
				levels.add(e);
			}
		}
		int levelsGained = 0;
		for (JourneyEvent e : levels)
		{
			e.setAway(true);
			levelsGained += levelsGained(e, day.getOfflineSkillXp());
		}

		day.setXpGained(day.getXpGained() - day.getOfflineXp());
		for (Map.Entry<String, Long> e : day.getOfflineSkillXp().entrySet())
		{
			day.getSkillXp().computeIfPresent(e.getKey(), (k, v) -> v - e.getValue() > 0 ? v - e.getValue() : null);
		}
		day.setLevelsGained(Math.max(0, day.getLevelsGained() - levelsGained));
		day.setAwayXp(day.getOfflineXp());
		day.getAwaySkillXp().putAll(day.getOfflineSkillXp());
		day.setAwayLevels(levelsGained);
		day.setAwayFrom(last.getDate());
		day.setOfflineXp(0);
		day.getOfflineSkillXp().clear();
		return true;
	}

	private static int levelsGained(JourneyEvent e, Map<String, Long> awaySkillXp)
	{
		try
		{
			long now = Long.parseLong(e.getDetail().substring(0, e.getDetail().indexOf(' ')).replace(",", ""));
			long gained = awaySkillXp.getOrDefault(e.getSkill(), 0L);
			return Math.max(1, Skills.level(now) - Skills.level(now - gained));
		}
		catch (RuntimeException ex)
		{
			return 1;
		}
	}

	private static DayRecord lastPlayed(NavigableMap<String, DayRecord> days, LocalDate before, boolean inclusive)
	{
		for (DayRecord d : days.headMap(before.toString(), inclusive).descendingMap().values())
		{
			if (d.getPlayMillis() > 0)
			{
				return d;
			}
		}
		return null;
	}
}
