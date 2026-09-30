package com.runejourney;

import com.runejourney.model.EventType;
import com.runejourney.model.Goal;
import com.runejourney.model.GoalType;
import com.runejourney.model.JourneyEvent;
import com.runejourney.planner.Counters;
import com.runejourney.planner.GoalProgress;
import com.runejourney.planner.Skills;
import com.runejourney.service.RangeSummary;
import com.runejourney.service.ReportService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import net.runelite.api.Skill;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ReportServiceTest
{
	@Test
	public void rendersHtmlAndText() throws Exception
	{
		RangeSummary s = new RangeSummary();
		s.setFrom(LocalDate.of(2026, 9, 21));
		s.setTo(LocalDate.of(2026, 9, 27));
		s.setDaysPlayed(5);
		s.setPlayMillis(18 * 3_600_000L + 42 * 60_000L);
		s.setXpGained(4_284_192);
		s.setLevelsGained(3);
		s.setLootValue(72_400_000);
		s.setClueLootValue(3_100_000);
		s.setBossKills(143);
		s.setCluesCompleted(7);
		s.setCombatTasks(2);
		s.setCombatTaskPoints(7);
		s.getClues().put("Elite", 5);
		s.getClues().put("Hard", 2);
		s.getSkillXp().put(Skill.SLAYER.name(), 1_300_000L);
		s.getSkillXp().put(Skill.MINING.name(), 420_000L);
		s.getLevelRanges().put(Skill.SLAYER.name(), new int[]{94, 95});
		s.getBossKillsByName().put("Theatre of Blood", 12);
		s.getBossKillsByName().put("Vorkath", 131);
		s.getCollectionLogItems().add("Avernic defender hilt");
		s.getQuests().add("Dragon Slayer II");
		s.getCombatTaskNames().add("Just Like That (Hard combat task · 3 points)");

		JourneyEvent drop = new JourneyEvent(System.currentTimeMillis(), EventType.DROP, "Avernic defender hilt",
			"40m gp · Theatre of Blood · KC 284", null, null, true, 40_000_000);
		s.getDrops().add(drop);
		s.getHighlights().add(drop);
		s.getHighlights().add(new JourneyEvent(System.currentTimeMillis(), EventType.LEVEL, "Level 95 Slayer <script>",
			"12,296,804 XP", Skill.SLAYER.name(), null, true, 95));

		for (Skill skill : Skills.ALL)
		{
			s.getStartSnapshot().put(skill.name(), Skills.xpForLevel(90));
			s.getEndSnapshot().put(skill.name(), Skills.xpForLevel(90) + (skill == Skill.SLAYER ? 1_300_000 : 0));
		}
		s.getStartSnapshot().put(Counters.QUEST_POINTS, 280L);
		s.getEndSnapshot().put(Counters.QUEST_POINTS, 284L);

		Goal goal = new Goal();
		goal.setType(GoalType.MAX_CAPE);
		goal.setName("Max by Christmas!");
		GoalProgress p = new GoalProgress();
		p.setGoal(goal);
		p.setPercent(0.861);
		p.setHoursRemaining(529);
		p.setStatus(GoalProgress.Status.BEHIND);
		p.setTargetDate(LocalDate.of(2026, 12, 25));

		ReportService.Report r = new ReportService.Report();
		r.setTitle("Weekly report");
		r.setPlayer("Tommo Ladd");
		r.setFrom(s.getFrom());
		r.setTo(s.getTo());
		r.setGenerated(LocalDateTime.now());
		r.setSummary(s);
		r.setGoals(Collections.singletonList(p));
		r.getMinutesPerKill().put("Vorkath", 3.0);
		r.getMinutesPerKill().put("Theatre of Blood", 25.0);

		for (int i = 0; i < 7; i++)
		{
			com.runejourney.model.DayRecord d = new com.runejourney.model.DayRecord(s.getFrom().plusDays(i).toString());
			d.setXpGained(200_000 + i * 50_000);
			d.getSkillXp().put(Skill.SLAYER.name(), d.getXpGained());
			d.setPlayMillis((2 + i % 3) * 3_600_000L);
			d.setLootValue(5_000_000);
			d.setSkillingIncome(400_000);
			d.getSkillingIncomeBySkill().put(Skill.THIEVING.name(), 400_000L);
			d.getBossKills().put("Vorkath", 15 + i);
			r.getDays().add(d);
			com.runejourney.model.DayRecord prevDay = new com.runejourney.model.DayRecord(s.getFrom().minusDays(7 - i).toString());
			prevDay.setXpGained(150_000);
			prevDay.setPlayMillis(2 * 3_600_000L);
			r.getPreviousDays().add(prevDay);
		}
		r.setFocus(new ReportService.Focus(com.runejourney.report.Metric.XP, null, null,
			com.runejourney.report.Granularity.AUTO, false, true));

		ReportService reports = new ReportService(null, null);
		String html = reports.toHtml(r, false);
		String text = reports.toText(r);

		assertTrue(html.contains("Tommo Ladd"));
		assertTrue(html.contains("Avernic defender hilt"));
		assertTrue(html.contains("Account progress"));
		assertTrue(html.contains("<svg class=\"chart\""));
		assertTrue(html.contains("Focus: XP gained"));
		assertTrue(html.contains("Thieving"));
		// Player-controlled text is escaped
		assertTrue(html.contains("Level 95 Slayer &lt;script&gt;"));
		assertTrue(text.contains("Weekly report"));
		assertTrue(text.contains("Vorkath ×131"));

		File out = new File("build/sample-report.html");
		Files.write(out.toPath(), html.getBytes(StandardCharsets.UTF_8));
		Files.write(new File("build/sample-report.txt").toPath(), text.getBytes(StandardCharsets.UTF_8));
	}
}
