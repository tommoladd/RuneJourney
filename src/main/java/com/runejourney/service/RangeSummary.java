package com.runejourney.service;

import com.runejourney.model.*;
import java.time.LocalDate;
import java.util.*;
import lombok.Data;

@Data
public class RangeSummary
{
	private LocalDate from;
	private LocalDate to;
	private int daysPlayed;
	private long playMillis;
	private long xpGained;
	private long awayXp;
	private LocalDate awayFrom;
	private LocalDate awayTo;
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
	private Map<String, int[]> levelRanges = new HashMap<>();
	private long clueLootValue;
	private long skillingIncome;
	private Map<String, Long> skillingIncomeBySkill = new HashMap<>();
	private Map<String, LootSource> lootBySource = new HashMap<>();
	private long suppliesCost;
	private Map<String, ItemTotal> suppliesUsed = new HashMap<>();
	private int combatTasks;
	private int combatTaskPoints;
	private Map<String, Integer> clues = new HashMap<>();
	private Map<String, Long> startSnapshot = new HashMap<>();
	private Map<String, Long> endSnapshot = new HashMap<>();
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
