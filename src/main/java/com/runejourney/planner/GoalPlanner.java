package com.runejourney.planner;

import com.runejourney.model.*;
import java.time.*;
import java.time.temporal.*;
import java.util.*;
import net.runelite.api.*;

public final class GoalPlanner
{
	public static final String COUNT_KEY = "__count";

	private static final long DAY_MILLIS = 86_400_000L;
	private static final double MIN_PACE_DAYS = 2;

	private GoalPlanner()
	{
	}

	public static int hoursPerWeek(Goal goal, int defaultHours)
	{
		return goal.getHoursPerWeek() > 0 ? goal.getHoursPerWeek() : defaultHours;
	}

	public static long counter(Map<String, Long> state, String key)
	{
		Long v = key == null ? null : state.get(key);
		return v == null ? 0 : v;
	}

	public static Map<Skill, Long> resolveTargets(Goal goal, Map<String, Long> xp, RateSource rates)
	{
		Map<Skill, Long> targets = new LinkedHashMap<>();
		switch (goal.getType())
		{
			case SKILL:
			{
				Skill skill = Skills.parse(goal.getSkill());
				if (skill != null)
				{
					targets.put(skill, goal.getTargetXp());
				}
				break;
			}
			case BASE_LEVEL:
			case MAX_CAPE:
			{
				long target = Skills.xpForLevel(baseLevel(goal));
				for (Skill s : Skills.ALL)
				{
					targets.put(s, target);
				}
				break;
			}
			case TOTAL_LEVEL:
				targets.putAll(cheapestLevels(goal.getTargetLevel() - Skills.totalLevel(xp), xp, rates));
				break;
			default:
				break;
		}
		return targets;
	}

	public static boolean helps(Goal goal, Skill skill, long xpBefore)
	{
		switch (goal.getType())
		{
			case SKILL:
				return skill == Skills.parse(goal.getSkill()) && xpBefore < goal.getTargetXp();
			case BASE_LEVEL:
			case MAX_CAPE:
				return xpBefore < Skills.xpForLevel(baseLevel(goal));
			case TOTAL_LEVEL:
				return Skills.level(xpBefore) < Experience.MAX_REAL_LEVEL;
			default:
				return false;
		}
	}

	private static int baseLevel(Goal goal)
	{
		return goal.getType() == GoalType.MAX_CAPE ? Experience.MAX_REAL_LEVEL : goal.getTargetLevel();
	}

	private static Map<Skill, Long> cheapestLevels(int levelsNeeded, Map<String, Long> xp, RateSource rates)
	{
		Map<Skill, Long> virtual = new HashMap<>();
		for (Skill s : Skills.ALL)
		{
			virtual.put(s, Skills.xp(xp, s));
		}

		Comparator<Skill> byCost = Comparator.comparingDouble(s ->
		{
			long cur = virtual.get(s);
			return rates.hours(s, cur, Skills.xpForLevel(Skills.level(cur) + 1));
		});
		PriorityQueue<Skill> queue = new PriorityQueue<>(byCost);
		for (Skill s : Skills.ALL)
		{
			if (Skills.level(virtual.get(s)) < Experience.MAX_REAL_LEVEL)
			{
				queue.add(s);
			}
		}

		while (levelsNeeded > 0 && !queue.isEmpty())
		{
			Skill s = queue.poll();
			long next = Skills.xpForLevel(Skills.level(virtual.get(s)) + 1);
			virtual.put(s, next);
			levelsNeeded--;
			if (Skills.level(next) < Experience.MAX_REAL_LEVEL)
			{
				queue.add(s);
			}
		}

		Map<Skill, Long> targets = new LinkedHashMap<>();
		for (Skill s : Skills.ALL)
		{
			if (virtual.get(s) > Skills.xp(xp, s))
			{
				targets.put(s, virtual.get(s));
			}
		}
		return targets;
	}

	public static boolean isComplete(Goal goal, Map<String, Long> state)
	{
		switch (goal.getType())
		{
			case SKILL:
			{
				Skill skill = Skills.parse(goal.getSkill());
				return skill != null && Skills.xp(state, skill) >= goal.getTargetXp();
			}
			case TOTAL_LEVEL:
				return Skills.totalLevel(state) >= goal.getTargetLevel();
			case BASE_LEVEL:
			case MAX_CAPE:
			{
				int base = baseLevel(goal);
				for (Skill s : Skills.ALL)
				{
					if (Skills.level(Skills.xp(state, s)) < base)
					{
						return false;
					}
				}
				return true;
			}
			case ITEMS:
				return !goal.getItems().isEmpty() && goal.getItems().stream().allMatch(GoalItem::isObtained);
			case PURCHASE:
				return goal.isComplete();
			default:
				if (goal.getType().isCounter())
				{
					return goal.getTargetCount() > 0 && counter(state, goal.getCounter()) >= goal.getTargetCount();
				}
				return goal.isComplete();
		}
	}

	public static double percent(Goal goal, Map<String, Long> state)
	{
		if (goal.isComplete())
		{
			return 1;
		}
		switch (goal.getType())
		{
			case SKILL:
			{
				Skill skill = Skills.parse(goal.getSkill());
				if (skill == null || goal.getTargetXp() <= 0)
				{
					return 0;
				}
				return clamp(Skills.xp(state, skill) / (double) goal.getTargetXp());
			}
			case TOTAL_LEVEL:
			{
				int span = goal.getTargetLevel() - goal.getStartTotalLevel();
				if (span <= 0)
				{
					return 1;
				}
				return clamp((Skills.totalLevel(state) - goal.getStartTotalLevel()) / (double) span);
			}
			case BASE_LEVEL:
			case MAX_CAPE:
			{
				long target = Skills.xpForLevel(baseLevel(goal));
				long have = 0;
				for (Skill s : Skills.ALL)
				{
					have += Math.min(Skills.xp(state, s), target);
				}
				return clamp(have / (double) (target * Skills.ALL.size()));
			}
			case ITEMS:
			{
				if (goal.getItems().isEmpty())
				{
					return 0;
				}
				long got = goal.getItems().stream().filter(GoalItem::isObtained).count();
				return got / (double) goal.getItems().size();
			}
			default:
				if (goal.getType().isCounter() && goal.getTargetCount() > 0)
				{
					long cur = counter(state, goal.getCounter());
					if (goal.isRelative())
					{
						long span = goal.getTargetCount() - goal.getStartCount();
						return span <= 0 ? 1 : clamp((cur - goal.getStartCount()) / (double) span);
					}
					return clamp(cur / (double) goal.getTargetCount());
				}
				return 0;
		}
	}

	private static double clamp(double v)
	{
		return Math.max(0, Math.min(1, v));
	}

	public static double exactLevel(long xp)
	{
		int level = Skills.level(xp);
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return level;
		}
		long start = Skills.xpForLevel(level);
		long next = Skills.xpForLevel(level + 1);
		return level + (xp - start) / (double) (next - start);
	}

	public static GoalProgress compute(Goal goal, Map<String, Long> state, RateSource rates, int defaultHoursPerWeek,
		boolean fromPace, LocalDate today, long nowMillis)
	{
		int hoursPerWeek = hoursPerWeek(goal, defaultHoursPerWeek);
		GoalProgress p = new GoalProgress();
		p.setGoal(goal);
		p.setComplete(goal.isComplete());
		p.setPercent(percent(goal, state));
		p.setAvailableHoursPerWeek(hoursPerWeek);

		if (goal.getType().isSkilling())
		{
			skillingProgress(p, goal, state, rates);
		}
		else if (goal.getType().isCounter())
		{
			long cur = counter(state, goal.getCounter());
			p.setUnit(Counters.unit(goal.getCounter()));
			p.setCountCurrent(cur);
			p.setCountTarget(goal.getTargetCount());
			p.setCountRemaining(Math.max(0, goal.getTargetCount() - cur));
			p.setCountGained(Math.max(0, cur - goal.getStartCount()));
			double hours = goal.isComplete() ? 0 : rates.counterHours(goal.getCounter(), cur, goal.getTargetCount());
			p.setHoursRemaining(hours);
		}
		else if (goal.getType() == GoalType.ITEMS)
		{
			p.setItemsTotal(goal.getItems().size());
			p.setItemsObtained((int) goal.getItems().stream().filter(GoalItem::isObtained).count());
			p.setHoursRemaining(-1);
		}

		projection(p, goal, today, nowMillis, hoursPerWeek, fromPace);
		weekRows(p, goal, state, rates);
		return p;
	}

	private static void skillingProgress(GoalProgress p, Goal goal, Map<String, Long> xp, RateSource rates)
	{
		Map<Skill, Long> targets = goal.isComplete() ? new LinkedHashMap<>() : resolveTargets(goal, xp, rates);
		long remaining = 0;
		long gained = 0;
		double hours = 0;
		for (Map.Entry<Skill, Long> e : targets.entrySet())
		{
			Skill s = e.getKey();
			long cur = Skills.xp(xp, s);
			long target = e.getValue();
			Long start = goal.getStartXp().get(s.name());
			if (start != null)
			{
				gained += Math.max(0, Math.min(cur, target) - Math.min(start, target));
			}
			if (cur >= target)
			{
				continue;
			}

			GoalProgress.SkillRow row = new GoalProgress.SkillRow();
			row.setSkill(s);
			row.setCurrentXp(cur);
			row.setTargetXp(target);
			row.setCurrentLevel(Skills.level(cur));
			row.setTargetLevel(Math.min(Experience.MAX_VIRT_LEVEL, Experience.getLevelForXp((int) Math.min(target, Experience.MAX_SKILL_XP))));
			row.setRemainingXp(target - cur);
			row.setHours(rates.hours(s, cur, target));
			row.setRate(row.getRemainingXp() / Math.max(0.01, row.getHours()));
			row.setPersonalRate(rates.isPersonal(s));
			row.setMethodName(rates.methodName(s, cur));
			p.getSkills().add(row);
			remaining += row.getRemainingXp();
			hours += row.getHours();
		}
		p.getSkills().sort(Comparator.comparingDouble(GoalProgress.SkillRow::getHours).reversed());

		int total = Skills.totalLevel(xp);
		p.setLevelsGained(Math.max(0, total - goal.getStartTotalLevel()));
		if (goal.getType() == GoalType.TOTAL_LEVEL)
		{
			p.setLevelsRemaining(Math.max(0, goal.getTargetLevel() - total));
			gained = 0;
			for (Skill s : Skills.ALL)
			{
				Long start = goal.getStartXp().get(s.name());
				if (start != null)
				{
					gained += Math.max(0, Skills.xp(xp, s) - start);
				}
			}
		}
		p.setXpGained(gained);
		p.setXpRemaining(remaining);
		p.setHoursRemaining(hours);
	}

	private static void projection(GoalProgress p, Goal goal, LocalDate today, long nowMillis, int hoursPerWeek, boolean fromPace)
	{
		LocalDate target = parseDate(goal.getTargetDate());
		p.setTargetDate(target);
		if (target != null)
		{
			p.setDaysLeft(ChronoUnit.DAYS.between(today, target));
		}

		if (goal.isComplete())
		{
			p.setStatus(GoalProgress.Status.COMPLETE);
			return;
		}
		if (goal.getType() == GoalType.CUSTOM)
		{
			p.setStatus(GoalProgress.Status.NO_TARGET);
			return;
		}
		if (goal.getType() == GoalType.PURCHASE && p.getCountRemaining() <= 0)
		{
			p.setStatus(GoalProgress.Status.READY);
			p.setProjectedCompletion(today);
			return;
		}

		double doneUnits;
		double remainingUnits;
		switch (goal.getType())
		{
			case TOTAL_LEVEL:
				doneUnits = p.getLevelsGained();
				remainingUnits = p.getLevelsRemaining();
				break;
			case ITEMS:
				doneUnits = p.getItemsObtained();
				remainingUnits = p.getItemsTotal() - p.getItemsObtained();
				break;
			default:
				if (goal.getType().isCounter())
				{
					doneUnits = p.getCountGained();
					remainingUnits = p.getCountRemaining();
				}
				else
				{
					doneUnits = p.getXpGained();
					remainingUnits = p.getXpRemaining();
				}
		}
		double elapsedDays = (nowMillis - goal.getCreatedAt()) / (double) DAY_MILLIS;
		boolean hoursKnown = p.getHoursRemaining() > 0;
		boolean byHours = hoursKnown && hoursPerWeek > 0;

		LocalDate projected = null;
		if (remainingUnits <= 0)
		{
			projected = today;
		}
		else if ((fromPace || !byHours) && goal.getType() != GoalType.ITEMS && elapsedDays >= MIN_PACE_DAYS && doneUnits > 0)
		{
			double perDay = doneUnits / elapsedDays;
			projected = today.plusDays((long) Math.ceil(remainingUnits / perDay));
			p.setProjectionFromPace(true);
		}
		else if (byHours)
		{
			projected = today.plusDays((long) Math.ceil(p.getHoursRemaining() / hoursPerWeek * 7));
		}
		p.setProjectedCompletion(projected);

		if (target == null)
		{
			p.setStatus(projected == null ? GoalProgress.Status.TRACKING : GoalProgress.Status.NO_TARGET);
			return;
		}

		long daysLeft = p.getDaysLeft();
		if (daysLeft < 0)
		{
			p.setStatus(GoalProgress.Status.OVERDUE);
			return;
		}

		double weeksLeft = Math.max(1, daysLeft) / 7.0;
		p.setRequiredPerWeek(remainingUnits / weeksLeft);
		if (hoursKnown)
		{
			p.setRequiredHoursPerWeek(p.getHoursRemaining() / weeksLeft);
		}

		if (projected == null)
		{
			p.setStatus(GoalProgress.Status.TRACKING);
		}
		else if (!projected.isAfter(target))
		{
			p.setStatus(GoalProgress.Status.ON_TRACK);
		}
		else
		{
			long grace = Math.max(7, (long) (daysLeft * 0.15));
			p.setStatus(!projected.isAfter(target.plusDays(grace))
				? GoalProgress.Status.SLIGHTLY_BEHIND
				: GoalProgress.Status.BEHIND);
		}
	}

	private static void weekRows(GoalProgress p, Goal goal, Map<String, Long> state, RateSource rates)
	{
		Long countTarget = goal.getWeekTargets().get(COUNT_KEY);
		if (countTarget != null && goal.getCounter() != null)
		{
			long cur = counter(state, goal.getCounter());
			long start = goal.getWeekStartXp().getOrDefault(COUNT_KEY, cur);
			p.setCountWeekTarget(countTarget);
			p.setCountWeekAchieved(Math.max(0, cur - start));
			p.setWeekHours(Math.max(0, rates.counterHours(goal.getCounter(), start, start + countTarget)));
			return;
		}

		long sumTarget = 0;
		long sumAchieved = 0;
		double weekHours = 0;
		for (Map.Entry<String, Long> e : goal.getWeekTargets().entrySet())
		{
			Skill s = Skills.parse(e.getKey());
			if (s == null)
			{
				continue;
			}
			long startXp = goal.getWeekStartXp().getOrDefault(e.getKey(), 0L);
			long cur = Skills.xp(state, s);
			long achieved = Math.max(0, cur - startXp);
			long target = e.getValue();

			GoalProgress.WeekRow row = new GoalProgress.WeekRow();
			row.setSkill(s);
			row.setTarget(target);
			row.setAchieved(achieved);
			row.setFromLevel(Skills.level(startXp));
			row.setToLevel(Skills.level(startXp + target));
			row.setFromLevelExact(exactLevel(startXp));
			row.setToLevelExact(exactLevel(startXp + target));
			row.setCurrentLevelExact(exactLevel(cur));
			row.setHoursLeft(achieved >= target ? 0 : rates.hours(s, cur, startXp + target));
			p.getWeek().add(row);
			sumTarget += target;
			sumAchieved += Math.min(achieved, target);
			weekHours += rates.hours(s, startXp, startXp + target);
		}
		p.getWeek().sort(Comparator.comparingDouble(GoalProgress.WeekRow::getHoursLeft).reversed());
		p.setWeekTarget(sumTarget);
		p.setWeekAchieved(sumAchieved);
		p.setWeekHours(weekHours);
	}

	public static LocalDate weekStart(LocalDate day)
	{
		return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}

	private static boolean plannable(Goal goal)
	{
		return !goal.isComplete() && (goal.getType().isSkilling() || goal.getType().isCounter());
	}

	public static long[] rollWeek(Goal goal, Map<String, Long> state, RateSource rates, int defaultHoursPerWeek, LocalDate today)
	{
		if (!plannable(goal))
		{
			return null;
		}
		LocalDate monday = weekStart(today);
		if (monday.toString().equals(goal.getWeekStart()))
		{
			return null;
		}

		boolean first = goal.getWeekStart() == null;
		long[] ended = null;

		if (!first && !goal.getWeekTargets().isEmpty())
		{
			Map<String, long[]> results = new LinkedHashMap<>();
			long sumTarget = 0;
			long sumAchieved = 0;
			if (goal.getType().isCounter())
			{
				long target = goal.getWeekTargets().getOrDefault(COUNT_KEY, 0L);
				long start = goal.getWeekStartXp().getOrDefault(COUNT_KEY, counter(state, goal.getCounter()));
				long achieved = Math.max(0, counter(state, goal.getCounter()) - start);
				results.put(COUNT_KEY, new long[]{target, achieved});
				sumTarget = target;
				sumAchieved = achieved;
			}
			else
			{
				Set<String> keys = new LinkedHashSet<>(goal.getWeekTargets().keySet());
				for (Skill s : Skills.ALL)
				{
					long before = goal.getWeekStartXp().getOrDefault(s.name(), Long.MAX_VALUE);
					if (Skills.xp(state, s) > before && helps(goal, s, before))
					{
						keys.add(s.name());
					}
				}
				for (String key : keys)
				{
					Skill s = Skills.parse(key);
					if (s == null)
					{
						continue;
					}
					long target = goal.getWeekTargets().getOrDefault(key, 0L);
					long achieved = Math.max(0, Skills.xp(state, s) - goal.getWeekStartXp().getOrDefault(key, Skills.xp(state, s)));
					results.put(key, new long[]{target, achieved});
					sumTarget += target;
					sumAchieved += achieved;
				}
			}
			goal.setLastWeekResults(results);
			goal.setWeeksPlanned(goal.getWeeksPlanned() + 1);
			if (sumAchieved >= sumTarget)
			{
				goal.setWeeksMet(goal.getWeeksMet() + 1);
				goal.setPlanStreak(goal.getPlanStreak() + 1);
				goal.setBestPlanStreak(Math.max(goal.getBestPlanStreak(), goal.getPlanStreak()));
			}
			else
			{
				goal.setPlanStreak(0);
			}
			ended = new long[]{sumTarget, sumAchieved};
		}

		goal.setWeekStart(monday.toString());
		Map<String, Long> start = new HashMap<>();
		for (Skill s : Skills.ALL)
		{
			start.put(s.name(), Skills.xp(state, s));
		}
		if (goal.getCounter() != null)
		{
			start.put(COUNT_KEY, counter(state, goal.getCounter()));
		}
		goal.setWeekStartXp(start);
		planWeek(goal, rates, defaultHoursPerWeek, first ? today : monday);
		return ended;
	}

	public static void replan(Goal goal, RateSource rates, int defaultHoursPerWeek, long nowMillis)
	{
		if (!plannable(goal) || goal.getWeekStart() == null)
		{
			return;
		}
		LocalDate monday = LocalDate.parse(goal.getWeekStart());
		LocalDate created = Instant.ofEpochMilli(goal.getCreatedAt()).atZone(ZoneId.systemDefault()).toLocalDate();
		planWeek(goal, rates, defaultHoursPerWeek, created.isAfter(monday) ? created : monday);
	}

	private static void planWeek(Goal goal, RateSource rates, int defaultHoursPerWeek, LocalDate planFrom)
	{
		Map<String, Long> base = goal.getWeekStartXp();
		LocalDate monday = weekStart(planFrom);

		Map<String, Long> remaining = new LinkedHashMap<>();
		double totalHours = 0;
		if (goal.getType().isCounter())
		{
			long cur = base.getOrDefault(COUNT_KEY, 0L);
			if (goal.getTargetCount() > cur)
			{
				remaining.put(COUNT_KEY, goal.getTargetCount() - cur);
				totalHours = rates.counterHours(goal.getCounter(), cur, goal.getTargetCount());
			}
		}
		else
		{
			for (Map.Entry<Skill, Long> e : resolveTargets(goal, base, rates).entrySet())
			{
				long cur = Skills.xp(base, e.getKey());
				if (e.getValue() > cur)
				{
					remaining.put(e.getKey().name(), e.getValue() - cur);
					totalHours += rates.hours(e.getKey(), cur, e.getValue());
				}
			}
		}

		double fraction;
		LocalDate targetDate = parseDate(goal.getTargetDate());
		if (targetDate != null)
		{
			long days = ChronoUnit.DAYS.between(monday, targetDate) + 1;
			fraction = 1.0 / Math.max(1, Math.ceil(days / 7.0));
		}
		else
		{
			int hours = hoursPerWeek(goal, defaultHoursPerWeek);
			fraction = totalHours <= 0 ? 0 : Math.min(1, hours / totalHours);
		}
		long daysPlanned = 7 - ChronoUnit.DAYS.between(monday, planFrom);
		fraction = Math.min(1, fraction * daysPlanned / 7.0);

		Map<String, Long> weekTargets = new LinkedHashMap<>();
		for (Map.Entry<String, Long> e : remaining.entrySet())
		{
			long t = COUNT_KEY.equals(e.getKey())
				? (long) Math.ceil(e.getValue() * fraction)
				: Math.round(e.getValue() * fraction);
			if (t > 0)
			{
				weekTargets.put(e.getKey(), t);
			}
		}
		goal.setWeekTargets(weekTargets);
	}

	public static LocalDate parseDate(String date)
	{
		if (date == null || date.isEmpty())
		{
			return null;
		}
		try
		{
			return LocalDate.parse(date);
		}
		catch (java.time.format.DateTimeParseException e)
		{
			return null;
		}
	}
}
