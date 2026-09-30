package com.runejourney.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class Goal
{
	private String id;
	private GoalType type;
	private String name;
	/**
	 * Skill name for {@link GoalType#SKILL} goals.
	 */
	private String skill;
	/**
	 * Target XP for {@link GoalType#SKILL} goals.
	 */
	private long targetXp;
	/**
	 * Target level for TOTAL_LEVEL and BASE_LEVEL goals.
	 */
	private int targetLevel;
	/**
	 * Optional ISO-8601 target date.
	 */
	private String targetDate;
	/**
	 * Hours per week the player expects to spend on this goal, or 0 to use the configured default.
	 */
	private int hoursPerWeek;
	private String notes;

	/**
	 * Counter key for counter goals, see {@link com.runejourney.planner.Counters}.
	 */
	private String counter;
	private long targetCount;
	private long startCount;
	/**
	 * True when the target was set as "N more from now", so progress is measured from the start.
	 */
	private boolean relative;
	private List<GoalItem> items = new ArrayList<>();
	/**
	 * How many of the item to buy, for purchase goals (the item is the first entry in {@code items}).
	 */
	private int quantity;
	/**
	 * True when the purchase price was entered by the player rather than taken from the GE.
	 */
	private boolean fixedPrice;
	/**
	 * How many of the item have been bought on the Grand Exchange, and what they cost.
	 */
	private int purchasedQuantity;
	private long purchaseSpent;
	/**
	 * Set once the cash stack first covers the purchase, so "you can afford it" is only said once.
	 */
	private boolean affordable;
	private long createdAt;
	private long completedAt;

	private Map<String, Long> startXp = new HashMap<>();
	private int startTotalLevel;

	// Weekly plan, regenerated at the start of every week from the remaining XP
	private String weekStart;
	private Map<String, Long> weekStartXp = new HashMap<>();
	private Map<String, Long> weekTargets = new HashMap<>();
	/**
	 * Previous week's results per skill: [target, achieved].
	 */
	private Map<String, long[]> lastWeekResults = new HashMap<>();
	private int weeksPlanned;
	private int weeksMet;
	/**
	 * Consecutive weekly plans met, and the best run.
	 */
	private int planStreak;
	private int bestPlanStreak;

	/**
	 * "Your journey to ..." summary lines, written when the goal is completed.
	 */
	private List<String> story = new ArrayList<>();

	public boolean isComplete()
	{
		return completedAt > 0;
	}
}
