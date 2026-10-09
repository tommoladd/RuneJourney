package com.runejourney.planner;

import com.runejourney.model.Goal;
import java.time.LocalDate;
import java.util.*;
import lombok.*;
import net.runelite.api.Skill;

@Data
public class GoalProgress
{
	@Getter
	@RequiredArgsConstructor
	public enum Status
	{
		COMPLETE("Complete"),
		NO_TARGET("No target date"),
		ON_TRACK("On track"),
		SLIGHTLY_BEHIND("Slightly behind"),
		BEHIND("Behind"),
		TRACKING("Tracking"),
		READY("Ready to buy"),
		OVERDUE("Target date passed");

		private final String label;
	}

	@Data
	public static class SkillRow
	{
		private Skill skill;
		private int currentLevel;
		private int targetLevel;
		private long currentXp;
		private long targetXp;
		private long remainingXp;
		private double rate;
		private boolean personalRate;
		private String methodName;
		private double hours;
	}

	@Data
	public static class WeekRow
	{
		private Skill skill;
		private int fromLevel;
		private int toLevel;
		private double fromLevelExact;
		private double toLevelExact;
		private double currentLevelExact;
		private long target;
		private long achieved;
		private double hoursLeft;
	}

	private Goal goal;
	private double percent;
	private boolean complete;

	private long xpGained;
	private long xpRemaining;
	private int levelsGained;
	private int levelsRemaining;
	private double hoursRemaining;
	private List<SkillRow> skills = new ArrayList<>();

	private LocalDate targetDate;
	private long daysLeft;
	private double requiredHoursPerWeek;
	private int availableHoursPerWeek;
	private Status status;
	private LocalDate projectedCompletion;
	private boolean projectionFromPace;

	private List<WeekRow> week = new ArrayList<>();
	private long weekTarget;
	private long weekAchieved;
	private double weekHours;

	private String unit;
	private long countCurrent;
	private long countTarget;
	private long countRemaining;
	private long countGained;
	private double requiredPerWeek;
	private long countWeekTarget;
	private long countWeekAchieved;

	private int itemsObtained;
	private int itemsTotal;
}
