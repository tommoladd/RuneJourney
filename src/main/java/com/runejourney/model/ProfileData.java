package com.runejourney.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * Long-lived per-account state. Day-by-day history is stored separately in {@link DayRecord}s.
 */
@Data
public class ProfileData
{
	private String playerName;
	private long createdAt;
	private Map<String, Long> lastXp = new HashMap<>();
	private Map<String, Integer> killCounts = new HashMap<>();
	private Map<String, ObservedRate> observedRates = new HashMap<>();
	/**
	 * Boss name to observed kills (stored in {@code xp}) and time spent.
	 */
	private Map<String, ObservedRate> killTimes = new HashMap<>();
	/**
	 * Clue tier to total completions.
	 */
	private Map<String, Integer> clueCounts = new HashMap<>();
	private long combatAchievementPoints;
	private int combatTasks;
	/**
	 * True once the completed-task total has been read from the game, which is then authoritative.
	 */
	private boolean combatTasksFromGame;
	private int questPoints;
	private int collectionLogSlots;
	/**
	 * Coins plus platinum tokens (in gp) last seen in the bank, which is only visible while open.
	 */
	private long bankCash;
	private boolean bankCashKnown;
	private long inventoryCash;
	/**
	 * Last seen state of each Grand Exchange slot: [item id, quantity bought, gp spent]. The game
	 * replays offers on every login, so this stops purchases being counted twice.
	 */
	private Map<Integer, long[]> geSlots = new HashMap<>();

	// Wealth: GE value of the bank (seen when open), inventory and worn equipment
	private long bankValue;
	private boolean bankValueKnown;
	private long inventoryValue;
	private long equipmentValue;
	/**
	 * Highest net worth seen, so wealth milestones are only celebrated once.
	 */
	private long bestWealth;

	/**
	 * Collection log pages the player has viewed: category name to its items.
	 */
	private Map<String, List<ClogItem>> collectionLog = new HashMap<>();

	/**
	 * Goal shown on the in-game overlay.
	 */
	private String overlayGoalId;

	// Records that can't be recomputed from daily history
	private long longestSessionMillis;
	private String longestSessionDate;
	private int bestPlayStreak;

	/**
	 * Week (Monday) of the last Wrapped watched, and the last one announced in chat.
	 */
	private String wrappedSeen;
	private String wrappedNotified;
	private List<Goal> goals = new ArrayList<>();
	private GoalSession session;
	private long bestXpDay;
	private long bestLootDay;
	/**
	 * Skill name to preferred training method name.
	 */
	private Map<String, String> preferredMethods = new HashMap<>();
}
