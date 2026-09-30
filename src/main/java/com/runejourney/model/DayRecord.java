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
	 * XP gained while RuneJourney wasn't running (e.g. on mobile), included in xpGained and skillXp.
	 */
	private long offlineXp;
	private Map<String, Long> offlineSkillXp = new HashMap<>();
	private Map<String, Long> skillingIncomeBySkill = new HashMap<>();
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
