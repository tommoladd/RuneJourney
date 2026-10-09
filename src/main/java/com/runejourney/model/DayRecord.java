package com.runejourney.model;

import java.util.*;
import lombok.*;

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
	private Map<String, Integer> clues = new HashMap<>();
	private long clueLootValue;
	private long skillingIncome;
	private long offlineXp;
	private Map<String, Long> offlineSkillXp = new HashMap<>();
	private long awayXp;
	private Map<String, Long> awaySkillXp = new HashMap<>();
	private int awayLevels;
	private String awayFrom;
	private Map<String, Long> skillingIncomeBySkill = new HashMap<>();
	private Map<String, LootSource> lootBySource = new HashMap<>();
	private long suppliesCost;
	private Map<String, ItemTotal> suppliesUsed = new HashMap<>();
	private int combatTasks;
	private int combatTaskPoints;
	private Map<String, List<long[]>> xpRanges = new HashMap<>();
	private Map<String, List<long[]>> offlineRanges = new HashMap<>();
	private Map<String, List<long[]>> awayRanges = new HashMap<>();
	private Map<String, Long> snapshot = new HashMap<>();
	private List<JourneyEvent> events = new ArrayList<>();
	private DaySync sync;

	public DayRecord(String date)
	{
		this.date = date;
	}
}
