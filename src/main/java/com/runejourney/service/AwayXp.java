package com.runejourney.service;

import com.runejourney.model.DayRecord;
import com.runejourney.model.EventType;
import com.runejourney.model.JourneyEvent;
import com.runejourney.planner.Skills;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * XP gained while RuneJourney wasn't running, e.g. on mobile. The game only shows the new totals at
 * login, so all that's known is that it was gained between the last XP RuneJourney saw and now. A
 * gap that's all today is today's XP; one that began on an earlier day is kept aside rather than
 * landing on today, and only counted by ranges covering the whole gap.
 */
final class AwayXp
{
	static final String NOTE_PREFIX = "While you were away";
	static final String LEVEL_SUFFIX = "(gained while away)";
	private static final long LOGIN_MILLIS = 10_000;

	private AwayXp()
	{
	}

	/**
	 * The first day XP found at login could have been gained.
	 *
	 * @param lastXpAt when RuneJourney last read the player's XP, or 0 if it was never saved
	 */
	static LocalDate windowStart(long lastXpAt, NavigableMap<String, DayRecord> days, LocalDate today)
	{
		if (lastXpAt > 0)
		{
			return Instant.ofEpochMilli(lastXpAt).atZone(ZoneId.systemDefault()).toLocalDate();
		}
		// Saved before this was tracked: the last day anything was played
		DayRecord last = lastPlayed(days, today, true);
		return last == null ? today : LocalDate.parse(last.getDate());
	}

	/**
	 * Whether a range starting on {@code from} covers the whole gap the day's away XP was gained in.
	 * Ranges are only ever asked about days up to their end, so only the start needs checking.
	 */
	static boolean counted(DayRecord day, LocalDate from)
	{
		return day.getAwayFrom() != null && day.getAwayFrom().compareTo(from.toString()) >= 0;
	}

	/**
	 * Days saved before away XP was kept aside have it added to the day of the login that found it.
	 * Moves it back out where the day shows that's what it was: a single "while you were away" note
	 * that came before anything else that day, with the previous play on an earlier day.
	 *
	 * @return whether the day changed
	 */
	static boolean migrate(DayRecord day, NavigableMap<String, DayRecord> days)
	{
		// Days with XP ranges were saved after away XP was kept aside
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
			// The login that wrote the note also wrote its level-ups and any new week just before it
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

	/**
	 * Levels behind an away level-up event: its detail holds the new XP total, and the XP gained
	 * while away gives the level it started from.
	 */
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
