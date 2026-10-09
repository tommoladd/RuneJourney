package com.runejourney.model;

import java.util.*;
import lombok.Data;

@Data
public class Goal
{
	private String id;
	private GoalType type;
	private String name;
	private String skill;
	private long targetXp;
	private int targetLevel;
	private String targetDate;
	private int hoursPerWeek;
	private String notes;

	private String counter;
	private long targetCount;
	private long startCount;
	private boolean relative;
	private List<GoalItem> items = new ArrayList<>();
	private int quantity;
	private boolean fixedPrice;
	private int purchasedQuantity;
	private long purchaseSpent;
	private boolean affordable;
	private long createdAt;
	private long completedAt;

	private Map<String, Long> startXp = new HashMap<>();
	private int startTotalLevel;

	private String weekStart;
	private Map<String, Long> weekStartXp = new HashMap<>();
	private Map<String, Long> weekTargets = new HashMap<>();
	private Map<String, long[]> lastWeekResults = new HashMap<>();
	private int weeksPlanned;
	private int weeksMet;
	private int planStreak;
	private int bestPlanStreak;

	private List<String> story = new ArrayList<>();

	public boolean isComplete()
	{
		return completedAt > 0;
	}
}
