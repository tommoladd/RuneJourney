package com.runejourney;

import com.google.gson.Gson;
import com.runejourney.model.Goal;
import com.runejourney.model.GoalType;
import com.runejourney.model.GoalItem;
import com.runejourney.planner.BossData;
import com.runejourney.planner.Counters;
import com.runejourney.planner.GoalPlanner;
import com.runejourney.planner.GoalProgress;
import com.runejourney.planner.Intensity;
import com.runejourney.planner.RateSource;
import com.runejourney.planner.Skills;
import com.runejourney.planner.TrainingMethod;
import com.runejourney.planner.TrainingMethods;
import com.runejourney.planner.XpRates;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class GoalPlannerTest
{
	private static final RateSource RATES = new RateSource()
	{
		@Override
		public double hours(Skill skill, long fromXp, long toXp)
		{
			return Math.max(0, toXp - fromXp) / XpRates.defaultRate(skill, Intensity.BALANCED);
		}

		@Override
		public double rate(Skill skill, long xp)
		{
			return XpRates.defaultRate(skill, Intensity.BALANCED);
		}

		@Override
		public String methodName(Skill skill, long xp)
		{
			return "Test";
		}

		@Override
		public double counterHours(String counter, long from, long to)
		{
			// 3 minutes per kill for any boss, unknown for everything else
			return Counters.isKc(counter) ? Math.max(0, to - from) * 3 / 60.0 : -1;
		}

		@Override
		public boolean isPersonal(Skill skill)
		{
			return false;
		}
	};

	private static Map<String, Long> allAtLevel(int level)
	{
		Map<String, Long> xp = new HashMap<>();
		for (Skill s : Skills.ALL)
		{
			xp.put(s.name(), Skills.xpForLevel(level));
		}
		return xp;
	}

	private static Goal skillGoal(Skill skill, int level, Map<String, Long> xp)
	{
		Goal g = new Goal();
		g.setType(GoalType.SKILL);
		g.setSkill(skill.name());
		g.setTargetXp(Skills.xpForLevel(level));
		g.setName(level + " " + skill.getName());
		g.setStartXp(new HashMap<>(xp));
		g.setStartTotalLevel(Skills.totalLevel(xp));
		return g;
	}

	@Test
	public void skillGoalProgressAndCompletion()
	{
		Map<String, Long> xp = allAtLevel(90);
		Goal g = skillGoal(Skill.SLAYER, 99, xp);
		assertFalse(GoalPlanner.isComplete(g, xp));
		double pct = GoalPlanner.percent(g, xp);
		assertEquals(5_346_332 / 13_034_431.0, pct, 0.0001);

		xp.put(Skill.SLAYER.name(), Skills.xpForLevel(99));
		assertTrue(GoalPlanner.isComplete(g, xp));
	}

	@Test
	public void maxCapeRequiresEverySkill()
	{
		Map<String, Long> xp = allAtLevel(99);
		Goal g = new Goal();
		g.setType(GoalType.MAX_CAPE);
		g.setTargetLevel(99);
		assertTrue(GoalPlanner.isComplete(g, xp));

		xp.put(Skill.SAILING.name(), Skills.xpForLevel(98));
		assertFalse(GoalPlanner.isComplete(g, xp));
		assertEquals(1, GoalPlanner.resolveTargets(g, xp, RATES).entrySet().stream()
			.filter(e -> Skills.xp(xp, e.getKey()) < e.getValue()).count());
	}

	@Test
	public void xpOnlyHelpsSkillsTheGoalStillNeeds()
	{
		Goal max = new Goal();
		max.setType(GoalType.MAX_CAPE);
		assertTrue(GoalPlanner.helps(max, Skill.WOODCUTTING, Skills.xpForLevel(95)));
		assertFalse(GoalPlanner.helps(max, Skill.STRENGTH, Skills.xpForLevel(99)));

		Goal slayer = skillGoal(Skill.SLAYER, 99, allAtLevel(90));
		assertTrue(GoalPlanner.helps(slayer, Skill.SLAYER, Skills.xpForLevel(90)));
		assertFalse(GoalPlanner.helps(slayer, Skill.MINING, Skills.xpForLevel(90)));

		Goal total = new Goal();
		total.setType(GoalType.TOTAL_LEVEL);
		assertTrue(GoalPlanner.helps(total, Skill.MINING, Skills.xpForLevel(90)));
		assertFalse(GoalPlanner.helps(total, Skill.PRAYER, Skills.xpForLevel(99)));
	}

	@Test
	public void lastWeekOnlyKeepsXpThatHelped()
	{
		Map<String, Long> xp = allAtLevel(99);
		xp.put(Skill.FISHING.name(), Skills.xpForLevel(90));
		xp.put(Skill.WOODCUTTING.name(), Skills.xpForLevel(95));
		Goal g = new Goal();
		g.setType(GoalType.MAX_CAPE);
		LocalDate monday = LocalDate.of(2026, 9, 28);
		GoalPlanner.rollWeek(g, xp, RATES, 20, monday);
		assertTrue(g.getWeekTargets().containsKey(Skill.FISHING.name()));

		// Strength is already 99 so its XP doesn't move the goal on; Woodcutting's does
		xp.put(Skill.FISHING.name(), xp.get(Skill.FISHING.name()) + 263_000);
		xp.put(Skill.STRENGTH.name(), xp.get(Skill.STRENGTH.name()) + 23_000);
		xp.put(Skill.WOODCUTTING.name(), xp.get(Skill.WOODCUTTING.name()) + 1_200);
		GoalPlanner.rollWeek(g, xp, RATES, 20, monday.plusWeeks(1));

		Map<String, long[]> results = g.getLastWeekResults();
		assertEquals(263_000, results.get(Skill.FISHING.name())[1]);
		assertFalse(results.containsKey(Skill.STRENGTH.name()));
		assertEquals(1_200, results.get(Skill.WOODCUTTING.name())[1]);
	}

	@Test
	public void totalLevelPicksCheapestLevels()
	{
		Map<String, Long> xp = allAtLevel(80);
		Goal g = new Goal();
		g.setType(GoalType.TOTAL_LEVEL);
		g.setTargetLevel(Skills.totalLevel(xp) + 5);
		Map<Skill, Long> targets = GoalPlanner.resolveTargets(g, xp, RATES);
		int levels = 0;
		for (Map.Entry<Skill, Long> e : targets.entrySet())
		{
			levels += Skills.level(e.getValue()) - Skills.level(Skills.xp(xp, e.getKey()));
		}
		assertEquals(5, levels);
		// Construction has the best balanced rate, so its levels are the cheapest
		assertTrue(targets.containsKey(Skill.CONSTRUCTION));
	}

	@Test
	public void weeklyPlanSplitsRemainingXpAcrossWeeks()
	{
		Map<String, Long> xp = allAtLevel(90);
		Goal g = skillGoal(Skill.MINING, 99, xp);
		LocalDate monday = LocalDate.of(2026, 9, 28);
		g.setTargetDate(monday.plusWeeks(10).minusDays(1).toString());

		// Created on a Monday: first week is a full week
		assertNull(GoalPlanner.rollWeek(g, xp, RATES, 20, monday));
		long remaining = Skills.xpForLevel(99) - Skills.xpForLevel(90);
		long weekTarget = g.getWeekTargets().get(Skill.MINING.name());
		assertEquals(remaining / 10.0, weekTarget, remaining * 0.01);

		// Same week: no roll
		assertNull(GoalPlanner.rollWeek(g, xp, RATES, 20, monday.plusDays(3)));

		// Player only did 40% of the target, then the week ends
		long achieved = (long) (weekTarget * 0.4);
		xp.put(Skill.MINING.name(), xp.get(Skill.MINING.name()) + achieved);
		long[] ended = GoalPlanner.rollWeek(g, xp, RATES, 20, monday.plusWeeks(1));
		assertNotNull(ended);
		assertEquals(weekTarget, ended[0]);
		assertEquals(achieved, ended[1]);
		assertEquals(1, g.getWeeksPlanned());
		assertEquals(0, g.getWeeksMet());

		// Next week's target is rebalanced over the 9 remaining weeks, so it grows
		long newTarget = g.getWeekTargets().get(Skill.MINING.name());
		assertTrue(newTarget > weekTarget);
		assertEquals((remaining - achieved) / 9.0, newTarget, remaining * 0.01);
	}

	@Test
	public void goalCreatedMidWeekGetsPartialTarget()
	{
		Map<String, Long> xp = allAtLevel(90);
		Goal g = skillGoal(Skill.AGILITY, 99, xp);
		LocalDate thursday = LocalDate.of(2026, 10, 1);
		GoalPlanner.rollWeek(g, xp, RATES, 20, thursday);
		double remainingHours = (Skills.xpForLevel(99) - Skills.xpForLevel(90)) / RATES.rate(Skill.AGILITY, 0);
		long expected = Math.round((Skills.xpForLevel(99) - Skills.xpForLevel(90)) * (20 / remainingHours) * (4 / 7.0));
		assertEquals(expected, (long) g.getWeekTargets().get(Skill.AGILITY.name()), 2);
	}

	@Test
	public void onTrackStatusUsesAvailableHours()
	{
		Map<String, Long> xp = allAtLevel(90);
		Goal g = skillGoal(Skill.SLAYER, 99, xp);
		LocalDate today = LocalDate.of(2026, 9, 30);
		long now = System.currentTimeMillis();
		g.setCreatedAt(now);

		// ~117h at 60k/hr: a year away is easy at 20h/week, next week is not
		g.setTargetDate(today.plusYears(1).toString());
		GoalProgress p = GoalPlanner.compute(g, xp, RATES, 20, today, now);
		assertEquals(GoalProgress.Status.ON_TRACK, p.getStatus());

		g.setTargetDate(today.plusDays(7).toString());
		p = GoalPlanner.compute(g, xp, RATES, 20, today, now);
		assertEquals(GoalProgress.Status.BEHIND, p.getStatus());
		assertTrue(p.getRequiredHoursPerWeek() > 20);
	}

	@Test
	public void xpGainedOnAnotherDeviceCountsTowardsThisWeek()
	{
		Map<String, Long> state = allAtLevel(90);
		Goal g = skillGoal(Skill.WOODCUTTING, 99, state);
		LocalDate monday = LocalDate.of(2026, 9, 28);
		g.setTargetDate(monday.plusWeeks(10).minusDays(1).toString());
		GoalPlanner.rollWeek(g, state, RATES, 20, monday);
		long target = g.getWeekTargets().get(Skill.WOODCUTTING.name());

		// 300k gained on mobile; logging back in mid-week must not regenerate the week
		state.put(Skill.WOODCUTTING.name(), state.get(Skill.WOODCUTTING.name()) + 300_000);
		assertNull(GoalPlanner.rollWeek(g, state, RATES, 20, monday.plusDays(3)));
		GoalProgress p = GoalPlanner.compute(g, state, RATES, 20, monday.plusDays(3), System.currentTimeMillis());
		assertEquals(target, p.getWeekTarget());
		assertEquals(300_000, p.getWeekAchieved());
	}

	@Test
	public void purchaseGoalTracksCashStack()
	{
		Map<String, Long> state = allAtLevel(90);
		state.put(Counters.CASH, 300_000_000L);
		Goal g = new Goal();
		g.setType(GoalType.PURCHASE);
		g.setCounter(Counters.CASH);
		g.getItems().add(new GoalItem(20997, "Twisted bow"));
		g.setQuantity(1);
		g.setTargetCount(1_200_000_000L);
		g.setStartCount(300_000_000L);
		LocalDate monday = LocalDate.of(2026, 9, 28);
		g.setTargetDate(monday.plusWeeks(9).minusDays(1).toString());

		assertEquals(0.25, GoalPlanner.percent(g, state), 0.0001);
		GoalPlanner.rollWeek(g, state, RATES, 20, monday);
		assertEquals(100_000_000L, (long) g.getWeekTargets().get(GoalPlanner.COUNT_KEY));

		// Spending money lowers progress; saving enough makes it ready to buy, but only buying completes it
		state.put(Counters.CASH, 250_000_000L);
		GoalProgress p = GoalPlanner.compute(g, state, RATES, 20, monday.plusDays(1), System.currentTimeMillis());
		assertEquals(950_000_000L, p.getCountRemaining());
		state.put(Counters.CASH, 1_250_000_000L);
		assertFalse(GoalPlanner.isComplete(g, state));
		p = GoalPlanner.compute(g, state, RATES, 20, monday.plusDays(1), System.currentTimeMillis());
		assertEquals(GoalProgress.Status.READY, p.getStatus());
		assertEquals("1.25b gp", Counters.format(Counters.CASH, 1_250_000_000L));
	}

	@Test
	public void killCountGoalProgressAndWeeklyPlan()
	{
		Map<String, Long> state = allAtLevel(90);
		state.put(Counters.kc("Vorkath"), 100L);

		Goal g = new Goal();
		g.setType(GoalType.BOSS_KC);
		g.setName("500 Vorkath KC");
		g.setCounter(Counters.kc("Vorkath"));
		g.setTargetCount(500);
		g.setStartCount(100);
		LocalDate monday = LocalDate.of(2026, 9, 28);
		g.setTargetDate(monday.plusWeeks(4).minusDays(1).toString());

		assertFalse(GoalPlanner.isComplete(g, state));
		assertEquals(0.2, GoalPlanner.percent(g, state), 0.0001);

		GoalPlanner.rollWeek(g, state, RATES, 20, monday);
		assertEquals(100L, (long) g.getWeekTargets().get(GoalPlanner.COUNT_KEY));

		state.put(Counters.kc("Vorkath"), 160L);
		GoalProgress p = GoalPlanner.compute(g, state, RATES, 20, monday.plusDays(2), System.currentTimeMillis());
		assertEquals(60, p.getCountWeekAchieved());
		assertEquals(340, p.getCountRemaining());
		assertEquals(340 * 3 / 60.0, p.getHoursRemaining(), 0.001);

		// Week ends with 60 of 100 done; the next week is rebalanced over 3 weeks
		long[] ended = GoalPlanner.rollWeek(g, state, RATES, 20, monday.plusWeeks(1));
		assertEquals(100, ended[0]);
		assertEquals(60, ended[1]);
		assertEquals(114L, (long) g.getWeekTargets().get(GoalPlanner.COUNT_KEY));

		state.put(Counters.kc("Vorkath"), 500L);
		assertTrue(GoalPlanner.isComplete(g, state));
	}

	@Test
	public void relativeClueGoalMeasuresFromStart()
	{
		Map<String, Long> state = new HashMap<>();
		state.put(Counters.clues(Counters.ALL_TIERS), 250L);
		Goal g = new Goal();
		g.setType(GoalType.CLUES);
		g.setCounter(Counters.clues(Counters.ALL_TIERS));
		g.setRelative(true);
		g.setStartCount(250);
		g.setCreatedAt(System.currentTimeMillis());
		g.setTargetCount(350);
		state.put(Counters.clues(Counters.ALL_TIERS), 275L);
		assertEquals(0.25, GoalPlanner.percent(g, state), 0.0001);

		// No time estimate for clues in this rate source, so no hours
		GoalProgress p = GoalPlanner.compute(g, state, RATES, 20, LocalDate.now(), System.currentTimeMillis());
		assertEquals(-1, p.getHoursRemaining(), 0);
		assertEquals(GoalProgress.Status.TRACKING, p.getStatus());
	}

	@Test
	public void itemGoalCompletesWhenAllObtained()
	{
		Goal g = new Goal();
		g.setType(GoalType.ITEMS);
		GoalItem a = new GoalItem(1, "Twisted bow");
		GoalItem b = new GoalItem(2, "Scythe of vitur");
		g.getItems().add(a);
		g.getItems().add(b);
		Map<String, Long> state = new HashMap<>();
		assertFalse(GoalPlanner.isComplete(g, state));
		a.setObtainedAt(1);
		assertEquals(0.5, GoalPlanner.percent(g, state), 0);
		b.setObtainedAt(1);
		assertTrue(GoalPlanner.isComplete(g, state));
	}

	@Test
	public void bossDataMatchesVariants()
	{
		BossData data = new BossData(new Gson());
		assertEquals(3, data.minutesPerKill("Vorkath"), 0);
		assertEquals(35, data.minutesPerKill("Theatre of Blood: Hard Mode"), 0);
		// Unlisted variants fall back to their base activity
		assertEquals(30, data.minutesPerKill("Tombs of Amascut: Some New Mode"), 0);
		assertEquals(-1, data.minutesPerKill("Not a boss"), 0);
	}

	@Test
	public void trainingMethodRatesStepWithXp()
	{
		TrainingMethod m = new TrainingMethod("Test", new long[][]{{0, 10_000}, {100_000, 50_000}});
		assertEquals(10_000, m.rateAt(50_000), 0);
		assertEquals(50_000, m.rateAt(100_000), 0);
		// 50k XP at 10k/hr, then 100k XP at 50k/hr
		assertEquals(5 + 2, m.hours(50_000, 200_000), 0.0001);
	}

	@Test
	public void bundledTrainingMethodsLoad()
	{
		TrainingMethods methods = new TrainingMethods(new Gson());
		for (Skill s : Skills.ALL)
		{
			assertTrue(s.getName(), !methods.forSkill(s).isEmpty());
		}
		assertNotNull(methods.find(Skill.MINING, "Motherlode Mine"));
		TrainingMethod fastest = methods.defaultFor(Skill.MINING, Intensity.EFFICIENT, Skills.xpForLevel(90));
		TrainingMethod afk = methods.defaultFor(Skill.MINING, Intensity.RELAXED, Skills.xpForLevel(90));
		assertEquals("3-tick Granite", fastest.getName());
		assertEquals("Shooting Stars", afk.getName());
	}
}
