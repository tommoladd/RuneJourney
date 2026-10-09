package com.runejourney.model;

import java.util.*;
import lombok.Data;

@Data
public class ProfileData
{
	private String playerName;
	private long createdAt;
	private Map<String, Long> lastXp = new HashMap<>();
	private long lastXpAt;
	private Map<String, Integer> killCounts = new HashMap<>();
	private Map<String, List<SavedMethod>> savedMethods = new HashMap<>();
	private Map<String, SavedMethod> detectedMethods = new HashMap<>();
	private Map<String, List<Long>> dismissedRates = new HashMap<>();
	private Map<String, ObservedRate> killTimes = new HashMap<>();
	private Map<String, Integer> clueCounts = new HashMap<>();
	private long combatAchievementPoints;
	private int combatTasks;
	private boolean combatTasksFromGame;
	private int questPoints;
	private int collectionLogSlots;
	private int collectionLogTotal;
	private long bankCash;
	private boolean bankCashKnown;
	private long inventoryCash;
	private Map<Integer, long[]> geSlots = new HashMap<>();

	private Map<String, Map<Integer, Integer>> holdings = new HashMap<>();
	private Map<String, Long> wealthParts = new HashMap<>();
	private boolean bankValueKnown;
	private long bankValue;
	private long inventoryValue;
	private long equipmentValue;
	private long bestWealth;

	private Map<String, List<ClogItem>> collectionLog = new HashMap<>();
	private Map<String, List<String>> collectionLogTabs = new LinkedHashMap<>();
	private long collectionLogSyncedAt;

	private String overlayGoalId;

	private long longestSessionMillis;
	private String longestSessionDate;
	private int bestPlayStreak;

	private String wrappedSeen;
	private String wrappedNotified;
	private List<Goal> goals = new ArrayList<>();
	private GoalSession session;
	private long bestXpDay;
	private long bestLootDay;
	private Map<String, String> preferredMethods = new HashMap<>();
	private ProfileSync sync;
}
