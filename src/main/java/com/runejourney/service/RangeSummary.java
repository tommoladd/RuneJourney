package com.runejourney.service;

import com.runejourney.model.JourneyEvent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * Aggregated activity over a date range, used for the Today page, weekly/monthly recaps and
 * "since" comparisons.
 */
@Data
public class RangeSummary
{
	private LocalDate from;
	private LocalDate to;
	private int daysPlayed;
	private long playMillis;
	private long xpGained;
	private int levelsGained;
	private long lootValue;
	private int bossKills;
	private int deaths;
	private int collectionLogSlots;
	private int questsCompleted;
	private int personalBests;
	private int pets;
	private int slayerTasks;
	private int cluesCompleted;
	private Map<String, Long> skillXp = new HashMap<>();
	private Map<String, Integer> bossKillsByName = new HashMap<>();
	/**
	 * Skill name to [lowest level before, highest level reached] from level events.
	 */
	private Map<String, int[]> levelRanges = new HashMap<>();
	private long clueLootValue;
	private long skillingIncome;
	private Map<String, Long> skillingIncomeBySkill = new HashMap<>();
	private int combatTasks;
	private int combatTaskPoints;
	private Map<String, Integer> clues = new HashMap<>();
	/**
	 * Skill XP and account counters at the start and end of the range (may be empty for old data).
	 */
	private Map<String, Long> startSnapshot = new HashMap<>();
	private Map<String, Long> endSnapshot = new HashMap<>();
	/**
	 * Valuable drops, most valuable first.
	 */
	private List<JourneyEvent> drops = new ArrayList<>();
	private List<String> collectionLogItems = new ArrayList<>();
	private List<String> quests = new ArrayList<>();
	private List<String> diaries = new ArrayList<>();
	private List<String> combatTaskNames = new ArrayList<>();
	private List<String> personalBestList = new ArrayList<>();
	private List<String> petList = new ArrayList<>();
	private List<String> goalsCompleted = new ArrayList<>();
	private List<JourneyEvent> highlights = new ArrayList<>();
	private JourneyEvent bestMoment;
	private String biggestDay;
	private long biggestDayXp;
}
