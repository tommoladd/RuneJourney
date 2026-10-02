package com.runejourney.report;

import com.runejourney.model.DayRecord;
import com.runejourney.model.EventType;
import com.runejourney.model.LootSource;
import com.runejourney.planner.Counters;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Something that can be charted over time from daily records.
 */
@Getter
@RequiredArgsConstructor
public enum Metric
{
	XP("XP gained", Unit.COUNT, Filter.SKILL, false, EnumSet.of(EventType.LEVEL, EventType.XP_MILESTONE, EventType.TOTAL_LEVEL)),
	PLAYTIME("Time played", Unit.HOURS, Filter.NONE, false, EnumSet.of(EventType.RECORD, EventType.SESSION)),
	LEVELS("Levels gained", Unit.COUNT, Filter.NONE, false, EnumSet.of(EventType.LEVEL, EventType.TOTAL_LEVEL)),
	NET_WORTH("Net worth", Unit.GP, Filter.NONE, false, EnumSet.of(EventType.RECORD)),
	INCOME("Total income", Unit.GP, Filter.NONE, false, EnumSet.of(EventType.DROP, EventType.CLUE)),
	LOOT("Loot value", Unit.GP, Filter.SOURCE, false, EnumSet.of(EventType.DROP, EventType.CLUE, EventType.PET)),
	SKILLING_INCOME("Skilling income", Unit.GP, Filter.SKILL, false, EnumSet.noneOf(EventType.class)),
	SUPPLIES("Supplies used", Unit.GP, Filter.NONE, false, EnumSet.noneOf(EventType.class)),
	PROFIT("Profit after supplies", Unit.GP, Filter.NONE, false, EnumSet.of(EventType.DROP, EventType.CLUE)),
	KILLS("Boss kills", Unit.COUNT, Filter.BOSS, false, EnumSet.of(EventType.BOSS_KC, EventType.PERSONAL_BEST)),
	CLUES("Clues completed", Unit.COUNT, Filter.CLUE_TIER, false, EnumSet.of(EventType.CLUE)),
	CLUE_LOOT("Clue loot", Unit.GP, Filter.NONE, false, EnumSet.of(EventType.CLUE)),
	COLLECTION_LOG("Collection log slots", Unit.COUNT, Filter.NONE, false, EnumSet.of(EventType.COLLECTION_LOG)),
	COMBAT_TASKS("Combat tasks", Unit.COUNT, Filter.NONE, false, EnumSet.of(EventType.COMBAT_TASK)),
	CA_POINTS("CA points earned", Unit.COUNT, Filter.NONE, false, EnumSet.of(EventType.COMBAT_TASK)),
	QUESTS("Quests completed", Unit.COUNT, Filter.NONE, false, EnumSet.of(EventType.QUEST, EventType.DIARY)),
	PERSONAL_BESTS("Personal bests", Unit.COUNT, Filter.NONE, false, EnumSet.of(EventType.PERSONAL_BEST)),
	PETS("Pets", Unit.COUNT, Filter.NONE, false, EnumSet.of(EventType.PET)),
	SLAYER_TASKS("Slayer tasks", Unit.COUNT, Filter.NONE, false, EnumSet.noneOf(EventType.class)),
	DEATHS("Deaths", Unit.COUNT, Filter.NONE, false, EnumSet.of(EventType.DEATH)),
	XP_PER_HOUR("XP per hour played", Unit.RATE, Filter.SKILL, true, EnumSet.of(EventType.SESSION)),
	GP_PER_HOUR("Income per hour played", Unit.RATE, Filter.NONE, true, EnumSet.of(EventType.DROP)),
	KILLS_PER_HOUR("Kills per hour played", Unit.RATE, Filter.BOSS, true, EnumSet.of(EventType.BOSS_KC));

	/**
	 * What a metric can be narrowed down to.
	 */
	public enum Filter
	{
		NONE, SKILL, BOSS, CLUE_TIER, SOURCE
	}

	private final String label;
	private final Unit unit;
	private final Filter filter;
	/**
	 * Per-hour metrics: the value is the metric divided by hours played.
	 */
	private final boolean perHour;
	/**
	 * Journey events worth listing alongside this metric.
	 */
	private final Set<EventType> relatedEvents;

	@Override
	public String toString()
	{
		return label;
	}

	/**
	 * Balances such as net worth: a period's value is where it ended, not the sum of its days.
	 */
	public boolean isBalance()
	{
		return this == NET_WORTH;
	}

	/**
	 * End-of-day balance, or NaN when it wasn't recorded that day.
	 */
	public double balance(DayRecord d)
	{
		Long v = d.getSnapshot().get(Counters.WEALTH);
		return v == null || v <= 0 ? Double.NaN : v;
	}

	/**
	 * The raw daily amount; for per-hour metrics, the numerator.
	 */
	public double value(DayRecord d, String filter)
	{
		switch (this)
		{
			case XP:
				return filter == null ? d.getXpGained() : d.getSkillXp().getOrDefault(filter, 0L);
			case XP_PER_HOUR:
				// XP gained on other devices has no matching play time here
				if (filter == null)
				{
					return d.getXpGained() - d.getOfflineXp();
				}
				return d.getSkillXp().getOrDefault(filter, 0L) - d.getOfflineSkillXp().getOrDefault(filter, 0L);
			case INCOME:
			case GP_PER_HOUR:
				return d.getLootValue() + d.getSkillingIncome();
			case SKILLING_INCOME:
				return filter == null ? d.getSkillingIncome() : d.getSkillingIncomeBySkill().getOrDefault(filter, 0L);
			case PLAYTIME:
				return hours(d);
			case LEVELS:
				return d.getLevelsGained();
			case LOOT:
				if (filter != null)
				{
					LootSource s = d.getLootBySource().get(filter);
					return s == null ? 0 : s.getValue();
				}
				return d.getLootValue();
			case SUPPLIES:
				return d.getSuppliesCost();
			case PROFIT:
				return d.getLootValue() + d.getSkillingIncome() - d.getSuppliesCost();
			case KILLS:
			case KILLS_PER_HOUR:
				if (filter != null)
				{
					return d.getBossKills().getOrDefault(filter, 0);
				}
				return d.getBossKills().values().stream().mapToInt(Integer::intValue).sum();
			case CLUES:
				return filter == null ? d.getCluesCompleted() : d.getClues().getOrDefault(filter, 0);
			case CLUE_LOOT:
				return d.getClueLootValue();
			case COLLECTION_LOG:
				return d.getCollectionLogSlots();
			case COMBAT_TASKS:
				return d.getCombatTasks();
			case CA_POINTS:
				return d.getCombatTaskPoints();
			case QUESTS:
				return d.getQuestsCompleted();
			case PERSONAL_BESTS:
				return d.getPersonalBests();
			case PETS:
				return d.getPets();
			case SLAYER_TASKS:
				return d.getSlayerTasks();
			case DEATHS:
				return d.getDeaths();
			case NET_WORTH:
			{
				double v = balance(d);
				return Double.isNaN(v) ? 0 : v;
			}
			default:
				return 0;
		}
	}

	public static double hours(DayRecord d)
	{
		return d.getPlayMillis() / 3_600_000d;
	}

	/**
	 * The day's amounts split by skill, boss or clue tier, for metrics that support it.
	 */
	public Map<String, Double> parts(DayRecord d)
	{
		Map<String, Double> parts = new HashMap<>();
		switch (filter)
		{
			case SKILL:
				Map<String, Long> source = this == SKILLING_INCOME ? d.getSkillingIncomeBySkill() : d.getSkillXp();
				source.forEach((k, v) -> parts.put(k, v.doubleValue()));
				return parts;
			case BOSS:
				d.getBossKills().forEach((k, v) -> parts.put(k, v.doubleValue()));
				return parts;
			case CLUE_TIER:
				d.getClues().forEach((k, v) -> parts.put(k, v.doubleValue()));
				return parts;
			case SOURCE:
				d.getLootBySource().forEach((k, v) -> parts.put(k, (double) v.getValue()));
				return parts;
			default:
				return Collections.emptyMap();
		}
	}
}
