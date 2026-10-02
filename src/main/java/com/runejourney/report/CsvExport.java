package com.runejourney.report;

import com.runejourney.model.DayRecord;
import com.runejourney.model.LootSource;
import com.runejourney.planner.Skills;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import net.runelite.api.Skill;

/**
 * One row per day with every tracked metric, plus per-skill XP, per-boss kills and loot per source, for spreadsheets.
 */
public final class CsvExport
{
	private static final Metric[] COLUMNS = {
		Metric.PLAYTIME, Metric.XP, Metric.LEVELS, Metric.INCOME, Metric.LOOT, Metric.SKILLING_INCOME, Metric.SUPPLIES,
		Metric.PROFIT, Metric.KILLS, Metric.CLUES, Metric.CLUE_LOOT, Metric.COLLECTION_LOG, Metric.COMBAT_TASKS,
		Metric.CA_POINTS, Metric.QUESTS, Metric.PERSONAL_BESTS, Metric.PETS, Metric.SLAYER_TASKS, Metric.DEATHS,
		Metric.NET_WORTH,
	};

	private CsvExport()
	{
	}

	public static String export(List<DayRecord> days, LocalDate from, LocalDate to)
	{
		TreeSet<String> bosses = new TreeSet<>();
		TreeSet<String> skills = new TreeSet<>();
		TreeSet<String> sources = new TreeSet<>();
		for (DayRecord d : days)
		{
			bosses.addAll(d.getBossKills().keySet());
			skills.addAll(d.getSkillXp().keySet());
			sources.addAll(d.getLootBySource().keySet());
		}

		StringBuilder sb = new StringBuilder();
		sb.append("Date");
		for (Metric m : COLUMNS)
		{
			sb.append(',').append(quote(m.getLabel() + (m.getUnit() == Unit.HOURS ? " (hours)" : "")));
		}
		for (Skill s : Skills.ALL)
		{
			if (skills.contains(s.name()))
			{
				sb.append(',').append(quote(s.getName() + " XP"));
			}
		}
		for (String b : bosses)
		{
			sb.append(',').append(quote(b + " kills"));
		}
		for (String s : sources)
		{
			sb.append(',').append(quote(s + " loot"));
		}
		sb.append('\n');

		Map<String, DayRecord> byDate = new java.util.HashMap<>();
		for (DayRecord d : days)
		{
			byDate.put(d.getDate(), d);
		}
		DayRecord empty = new DayRecord();
		for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1))
		{
			DayRecord d = byDate.getOrDefault(date.toString(), empty);
			sb.append(date);
			for (Metric m : COLUMNS)
			{
				double v = m.value(d, null);
				sb.append(',').append(m.getUnit() == Unit.HOURS ? String.format(Locale.ENGLISH, "%.2f", v) : String.valueOf(Math.round(v)));
			}
			for (Skill s : Skills.ALL)
			{
				if (skills.contains(s.name()))
				{
					sb.append(',').append(d.getSkillXp().getOrDefault(s.name(), 0L));
				}
			}
			for (String b : bosses)
			{
				sb.append(',').append(d.getBossKills().getOrDefault(b, 0));
			}
			for (String s : sources)
			{
				LootSource loot = d.getLootBySource().get(s);
				sb.append(',').append(loot == null ? 0 : loot.getValue());
			}
			sb.append('\n');
		}
		return sb.toString();
	}

	private static String quote(String s)
	{
		return "\"" + s.replace("\"", "\"\"") + "\"";
	}
}
