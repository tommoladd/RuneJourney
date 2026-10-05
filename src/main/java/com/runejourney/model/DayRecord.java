package com.runejourney.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Everything that happened on a single calendar day. Days persist across logins so
 * logging out for dinner continues the same day's record.
 */
@Data
@NoArgsConstructor
public class DayRecord
{
	private String date;
	private long playMillis;
	private long xpGained;
	private Map<String, Long> skillXp = new HashMap<>();
	private int levelsGained;
	private long lootValue;
	private Map<String, Integer> bossKills = new HashMap<>();
	private int deaths;
	private int collectionLogSlots;
	private int questsCompleted;
	private int personalBests;
	private int slayerTasks;
	private int cluesCompleted;
	private int pets;
	/**
	 * Clue tier to completions.
	 */
	private Map<String, Integer> clues = new HashMap<>();
	private long clueLootValue;
	/**
	 * Net value of items gained or used while skilling (thieving, fishing, alching...).
	 */
	private long skillingIncome;
	/**
	 * XP gained while RuneJourney wasn't running (e.g. on mobile) earlier the same day, included in
	 * xpGained and skillXp.
	 */
	private long offlineXp;
	private Map<String, Long> offlineSkillXp = new HashMap<>();
	/**
	 * XP gained while RuneJourney wasn't running in a gap that began on an earlier day (e.g. a
	 * weekend on mobile), found when logging in on this day. Which day it was gained isn't known,
	 * so it isn't in xpGained or skillXp; ranges covering the whole gap, from awayFrom to this
	 * day, count it.
	 */
	private long awayXp;
	private Map<String, Long> awaySkillXp = new HashMap<>();
	private int awayLevels;
	/**
	 * The first day the away XP could have been gained.
	 */
	private String awayFrom;
	private Map<String, Long> skillingIncomeBySkill = new HashMap<>();
	/**
	 * Loot split by where it came from. Days recorded before this was added have lootValue
	 * without a breakdown.
	 */
	private Map<String, LootSource> lootBySource = new HashMap<>();
	/**
	 * GP value of food and potions eaten or drunk.
	 */
	private long suppliesCost;
	/**
	 * Item name to how much of it was used.
	 */
	private Map<String, ItemTotal> suppliesUsed = new HashMap<>();
	private int combatTasks;
	private int combatTaskPoints;
	/**
	 * Skill XP and account counters as they stood at the end of the day, for "since" comparisons.
	 */
	private Map<String, Long> snapshot = new HashMap<>();
	private List<JourneyEvent> events = new ArrayList<>();

	public DayRecord(String date)
	{
		this.date = date;
	}
}
