package com.runejourney.wrapped;

import com.runejourney.model.DayRecord;
import com.runejourney.model.EventType;
import com.runejourney.model.JourneyEvent;
import com.runejourney.planner.Skills;
import com.runejourney.util.Format;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import net.runelite.api.Skill;

/**
 * Turns a week of records into Wrapped slides. Only stats worth celebrating get a slide, and the
 * phrasing, order and theme vary with the week so no two Wrapped feel the same. Pure, so it can be tested.
 */
public final class WrappedBuilder
{
	private static final DateTimeFormatter RANGE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
	public static final int THEMES = 5;

	@Value
	@Builder
	public static class Input
	{
		LocalDate weekStart;
		String player;
		/**
		 * The week's days (missing days are fine).
		 */
		List<DayRecord> days;
		/**
		 * Totals of earlier weeks, oldest first, for "biggest week yet" comparisons.
		 */
		@Singular("earlierWeek")
		List<long[]> earlierWeeks;
		/**
		 * Weekly plan results for this week: [goal name, achieved, target, isMoney].
		 */
		@Singular
		List<Object[]> planResults;
		int playStreak;
		/**
		 * Known item ids by lower-case name (e.g. from imported collection log pages).
		 */
		Map<String, Integer> itemIds;
	}

	private WrappedBuilder()
	{
	}

	/**
	 * [xp, kills, playMillis] for a week, used to compare weeks.
	 */
	public static long[] weekTotals(List<DayRecord> days)
	{
		long xp = 0;
		long kills = 0;
		long play = 0;
		for (DayRecord d : days)
		{
			xp += d.getXpGained();
			kills += d.getBossKills().values().stream().mapToLong(Integer::longValue).sum();
			play += d.getPlayMillis();
		}
		return new long[]{xp, kills, play};
	}

	public static WrappedWeek build(Input in)
	{
		Random rnd = new Random(in.getWeekStart().toEpochDay() * 31 + 7);
		WrappedWeek w = new WrappedWeek();
		w.setWeekStart(in.getWeekStart());
		w.setWeekEnd(in.getWeekStart().plusDays(6));
		w.setPlayer(in.getPlayer());
		w.setTheme((int) Math.floorMod(in.getWeekStart().toEpochDay() / 7, THEMES));

		// ---- Gather the week
		long play = 0;
		long xp = 0;
		long income = 0;
		int deaths = 0;
		int clues = 0;
		int clogSlots = 0;
		int levels = 0;
		int daysPlayed = 0;
		DayRecord longestDay = null;
		Map<String, Long> skillXp = new HashMap<>();
		Map<String, Integer> bosses = new HashMap<>();
		Map<String, int[]> levelRanges = new HashMap<>();
		List<JourneyEvent> events = new ArrayList<>();
		for (DayRecord d : in.getDays())
		{
			play += d.getPlayMillis();
			xp += d.getXpGained();
			income += d.getLootValue() + d.getSkillingIncome();
			deaths += d.getDeaths();
			clues += d.getCluesCompleted();
			clogSlots += d.getCollectionLogSlots();
			levels += d.getLevelsGained();
			if (d.getPlayMillis() > 0)
			{
				daysPlayed++;
				if (longestDay == null || d.getPlayMillis() > longestDay.getPlayMillis())
				{
					longestDay = d;
				}
			}
			d.getSkillXp().forEach((k, v) -> skillXp.merge(k, v, Long::sum));
			d.getBossKills().forEach((k, v) -> bosses.merge(k, v, Integer::sum));
			for (JourneyEvent e : d.getEvents())
			{
				events.add(e);
				if (e.getType() == EventType.LEVEL && e.getSkill() != null && e.getValue() > 0)
				{
					int lvl = (int) e.getValue();
					levelRanges.merge(e.getSkill(), new int[]{lvl - 1, lvl}, (a, b) -> new int[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
				}
			}
		}
		int kills = bosses.values().stream().mapToInt(Integer::intValue).sum();

		List<WrappedWeek.Slide> middle = new ArrayList<>();

		// ---- Time played
		if (play > 0)
		{
			WrappedWeek.Slide s = slide(pick(rnd, "This week you played for", "You spent", "Time in Gielinor"),
				play, WrappedWeek.ValueFormat.DURATION, null);
			s.setIcon(WrappedIcons.TIME);
			String days = daysPlayed + (daysPlayed == 1 ? " day" : " days");
			s.setSubtitle(longestDay != null && daysPlayed > 1
				? "across " + days + " · your big one was " + dayName(longestDay) + " (" + Format.duration(longestDay.getPlayMillis()) + ")"
				: "across " + days);
			middle.add(s);
		}

		// ---- XP
		if (xp > 0)
		{
			WrappedWeek.Slide s = slide(pick(rnd, "You gained", "You racked up", "Your skills grew by"),
				xp, WrappedWeek.ValueFormat.COUNT, "XP");
			List<Map.Entry<String, Long>> top = skillXp.entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(3).collect(Collectors.toList());
			if (!top.isEmpty())
			{
				s.setSubtitle(pick(rnd, "Mostly ", "Led by ", "Your top skill: ") + skillName(top.get(0).getKey())
					+ " (+" + Format.compact(top.get(0).getValue()) + ")");
				s.setIcon(WrappedIcons.skill(top.get(0).getKey()));
				for (Map.Entry<String, Long> e : top)
				{
					line(s, skillName(e.getKey()) + "  +" + Format.compact(e.getValue()), WrappedIcons.skill(e.getKey()));
				}
			}
			middle.add(s);
		}

		// ---- Levels
		if (levels > 0)
		{
			WrappedWeek.Slide s = slide(pick(rnd, "You levelled up", "Level-up fireworks went off"),
				levels, WrappedWeek.ValueFormat.COUNT, levels == 1 ? "time" : "times");
			levelRanges.entrySet().stream()
				.sorted((a, b) -> b.getValue()[1] - a.getValue()[1])
				.limit(4)
				.forEach(e -> line(s, skillName(e.getKey()) + "  " + e.getValue()[0] + " -> " + e.getValue()[1], WrappedIcons.skill(e.getKey())));
			levelRanges.entrySet().stream().max((a, b) -> a.getValue()[1] - b.getValue()[1])
				.ifPresent(e -> s.setIcon(WrappedIcons.skill(e.getKey())));
			boolean has99 = levelRanges.values().stream().anyMatch(r -> r[1] >= 99);
			s.setSubtitle(has99 ? "Including a 99. Legendary." : pick(rnd, "Keep that jingle coming", "Every one of them earned"));
			middle.add(s);
		}

		// ---- Bosses
		if (kills > 0)
		{
			Map.Entry<String, Integer> fav = bosses.entrySet().stream().max(Map.Entry.comparingByValue()).get();
			WrappedWeek.Slide s = slide(pick(rnd, "You killed", "You slayed", "Bosses defeated"),
				kills, WrappedWeek.ValueFormat.COUNT, kills == 1 ? "boss" : "bosses");
			s.setSubtitle("Your favourite was " + fav.getKey() + " (" + Format.number(fav.getValue()) + (fav.getValue() == 1 ? " kill)" : " kills)"));
			s.setIcon(WrappedIcons.boss(fav.getKey()));
			bosses.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).skip(1).limit(3)
				.forEach(e -> line(s, e.getKey() + "  x" + e.getValue(), WrappedIcons.boss(e.getKey())));
			middle.add(s);
		}

		// ---- Income
		if (income > 0)
		{
			WrappedWeek.Slide s = slide(pick(rnd, "You made", "Your coffers grew by", "Loot and profit"),
				income, WrappedWeek.ValueFormat.GP, "gp");
			s.setIcon(WrappedIcons.COINS);
			JourneyEvent best = events.stream().filter(e -> e.getType() == EventType.DROP)
				.max(Comparator.comparingLong(JourneyEvent::getValue)).orElse(null);
			if (best != null && best.getValue() > 0)
			{
				s.setSubtitle("Best drop: " + best.getTitle() + " (" + Format.compact(best.getValue()) + ")");
			}
			middle.add(s);
		}

		// ---- Clues
		if (clues > 0)
		{
			WrappedWeek.Slide s = slide(pick(rnd, "You completed", "Treasure hunted"), clues, WrappedWeek.ValueFormat.COUNT,
				clues == 1 ? "clue scroll" : "clue scrolls");
			s.setSubtitle(pick(rnd, "Uri would be proud", "Another step closer to 3rd age", "The Wise Old Man approves"));
			s.setIcon(WrappedIcons.CLUE);
			middle.add(s);
		}

		// ---- Collection log
		if (clogSlots > 0)
		{
			WrappedWeek.Slide s = slide(pick(rnd, "You added", "Your collection log grew by"), clogSlots, WrappedWeek.ValueFormat.COUNT,
				clogSlots == 1 ? "new slot" : "new slots");
			s.setIcon(WrappedIcons.COLLECTION_LOG);
			events.stream().filter(e -> e.getType() == EventType.COLLECTION_LOG).limit(4)
				.forEach(e -> line(s, e.getTitle(), itemIcon(in, e.getTitle())));
			middle.add(s);
		}

		// ---- Deaths
		if (deaths > 0)
		{
			WrappedWeek.Slide s = slide(pick(rnd, "You died", "Death saw you"), deaths, WrappedWeek.ValueFormat.COUNT,
				deaths == 1 ? "time" : "times");
			s.setSubtitle(deaths >= 20 ? pick(rnd, "Death knows you by name now", "Your gravestone has a loyalty card")
				: deaths >= 5 ? pick(rnd, "Occupational hazard", "The price of greatness") : pick(rnd, "Barely a scratch", "Only a flesh wound"));
			s.setIcon(WrappedIcons.DEATH);
			middle.add(s);
		}

		// ---- Goals completed and weekly plans
		List<JourneyEvent> goals = events.stream().filter(e -> e.getType() == EventType.GOAL_COMPLETED).collect(Collectors.toList());
		for (JourneyEvent g : goals)
		{
			WrappedWeek.Slide s = new WrappedWeek.Slide();
			s.setEyebrow("You completed your goal");
			s.setHeadline(g.getTitle().replaceFirst(" achieved!$", "").replaceFirst("!$", ""));
			s.setSubtitle(g.getDetail() != null ? g.getDetail() : "What a journey");
			s.setIcon(WrappedIcons.GOAL);
			middle.add(s);
		}
		for (Object[] plan : in.getPlanResults())
		{
			long achieved = (long) plan[1];
			long target = (long) plan[2];
			if (target <= 0)
			{
				continue;
			}
			boolean money = (boolean) plan[3];
			WrappedWeek.Slide s = slide(achieved >= target ? "You smashed your weekly plan for" : "Your weekly plan for",
				Double.NaN, WrappedWeek.ValueFormat.NONE, null);
			s.setHeadline((String) plan[0]);
			String amount = money ? Format.compact(achieved) + " / " + Format.compact(target) + " gp"
				: Format.compact(achieved) + " / " + Format.compact(target);
			s.setIcon(WrappedIcons.PLAN);
			s.setSubtitle(amount + (achieved >= target ? " · " + Math.round(achieved * 100.0 / target) + "% of target" : " · next week's plan has been rebalanced"));
			middle.add(s);
		}

		// ---- Records beaten
		List<JourneyEvent> records = events.stream().filter(e -> e.getType() == EventType.RECORD).collect(Collectors.toList());
		if (!records.isEmpty())
		{
			WrappedWeek.Slide s = slide("You broke", records.size(), WrappedWeek.ValueFormat.COUNT,
				records.size() == 1 ? "personal record" : "personal records");
			s.setIcon(WrappedIcons.RECORD);
			records.stream().limit(4).forEach(e -> line(s, e.getTitle(), null));
			middle.add(s);
		}

		// ---- Streak
		if (in.getPlayStreak() >= 3)
		{
			WrappedWeek.Slide s = slide("You're on a streak:", in.getPlayStreak(), WrappedWeek.ValueFormat.COUNT, "days in a row");
			s.setSubtitle(pick(rnd, "Don't break the chain", "Gielinor missed you less than usual"));
			s.setIcon(WrappedIcons.STREAK);
			middle.add(s);
		}

		// ---- Biggest week yet?
		long[] totals = weekTotals(in.getDays());
		if (!in.getEarlierWeeks().isEmpty())
		{
			boolean xpBest = totals[0] > 0 && in.getEarlierWeeks().stream().allMatch(t -> totals[0] > t[0]);
			boolean killBest = totals[1] > 0 && in.getEarlierWeeks().stream().allMatch(t -> totals[1] > t[1]);
			if (xpBest || killBest)
			{
				WrappedWeek.Slide s = new WrappedWeek.Slide();
				s.setEyebrow("Plot twist");
				s.setHeadline(xpBest ? "Your biggest XP week yet" : "Your most bossing ever");
				s.setSubtitle("Out of " + (in.getEarlierWeeks().size() + 1) + " weeks with RuneJourney");
				s.setIcon(WrappedIcons.INTRO);
				middle.add(s);
			}
		}

		// Shuffle the middle a little so each week feels different, keeping XP and time near the start
		List<WrappedWeek.Slide> opening = new ArrayList<>(middle.subList(0, Math.min(2, middle.size())));
		List<WrappedWeek.Slide> rest = new ArrayList<>(middle.subList(opening.size(), middle.size()));
		Collections.shuffle(rest, rnd);
		if (rnd.nextBoolean())
		{
			Collections.reverse(opening);
		}

		// ---- Best moment, near the end
		JourneyEvent moment = bestMoment(events);

		WrappedWeek.Slide intro = new WrappedWeek.Slide();
		intro.setEyebrow("RuneJourney Wrapped");
		intro.setIcon(WrappedIcons.INTRO);
		intro.setHeadline(in.getPlayer() != null ? in.getPlayer() : "Your week");
		intro.setSubtitle(pick(rnd, "Here's how your week went", "Let's look back at your week", "Your week in Gielinor")
			+ " · " + in.getWeekStart().format(RANGE) + " - " + w.getWeekEnd().format(RANGE));
		w.getSlides().add(intro);
		w.getSlides().addAll(opening);
		w.getSlides().addAll(rest);
		if (moment != null)
		{
			WrappedWeek.Slide s = new WrappedWeek.Slide();
			s.setEyebrow("Your moment of the week");
			s.setHeadline(moment.getTitle());
			s.setSubtitle(moment.getDetail());
			s.setScreenshot(moment.getScreenshot());
			s.setIcon(moment.getSkill() != null ? WrappedIcons.skill(moment.getSkill())
				: moment.getType() == EventType.DROP || moment.getType() == EventType.COLLECTION_LOG || moment.getType() == EventType.PET
				? itemIcon(in, moment.getTitle()) : WrappedIcons.GOAL);
			w.getSlides().add(s);
		}

		WrappedWeek.Slide outro = new WrappedWeek.Slide();
		outro.setEyebrow("That's a wrap");
		outro.setHeadline(pick(rnd, "What a week", "See you next week", "Onwards, adventurer"));
		if (play > 0)
		{
			outro.getSummary().add(new String[]{"Played", Format.duration(play)});
		}
		if (xp > 0)
		{
			outro.getSummary().add(new String[]{"XP", Format.compact(xp)});
		}
		if (kills > 0)
		{
			outro.getSummary().add(new String[]{"Boss kills", Format.number(kills)});
		}
		if (income > 0)
		{
			outro.getSummary().add(new String[]{"Income", Format.compact(income)});
		}
		if (levels > 0)
		{
			outro.getSummary().add(new String[]{"Levels", String.valueOf(levels)});
		}
		if (clogSlots > 0)
		{
			outro.getSummary().add(new String[]{"Log slots", String.valueOf(clogSlots)});
		}
		if (clues > 0 && outro.getSummary().size() < 6)
		{
			outro.getSummary().add(new String[]{"Clues", String.valueOf(clues)});
		}
		if (deaths > 0 && outro.getSummary().size() < 6)
		{
			outro.getSummary().add(new String[]{"Deaths", String.valueOf(deaths)});
		}
		outro.setSubtitle("Your next Wrapped arrives on Monday");
		w.getSlides().add(outro);
		return w;
	}

	private static JourneyEvent bestMoment(List<JourneyEvent> events)
	{
		JourneyEvent best = null;
		int bestRank = Integer.MAX_VALUE;
		for (JourneyEvent e : events)
		{
			if (!e.isHighlight())
			{
				continue;
			}
			int rank;
			switch (e.getType())
			{
				case GOAL_COMPLETED:
					rank = 0;
					break;
				case PET:
					rank = 1;
					break;
				case DROP:
					rank = 2;
					break;
				case LEVEL:
					rank = e.getValue() >= 99 ? 1 : 5;
					break;
				case COLLECTION_LOG:
					rank = 3;
					break;
				case PERSONAL_BEST:
					rank = 4;
					break;
				default:
					rank = 6;
			}
			// Prefer moments with a screenshot, then higher value
			if (e.getScreenshot() != null)
			{
				rank -= 1;
			}
			if (rank < bestRank || (rank == bestRank && best != null && e.getValue() > best.getValue()))
			{
				best = e;
				bestRank = rank;
			}
		}
		return best;
	}

	private static void line(WrappedWeek.Slide s, String text, String icon)
	{
		s.getLines().add(text);
		s.getLineIcons().add(icon);
	}

	/**
	 * Icon for an item named in an event: its known id when available, otherwise looked up by name later.
	 */
	private static String itemIcon(Input in, String name)
	{
		String clean = name.replaceFirst("^[\\d,]+ x ", "").replaceFirst("^Pet: ", "");
		Integer id = in.getItemIds() == null ? null : in.getItemIds().get(clean.toLowerCase(Locale.ENGLISH));
		return id != null ? WrappedIcons.item(id) : WrappedIcons.byName(clean);
	}

	private static WrappedWeek.Slide slide(String eyebrow, double value, WrappedWeek.ValueFormat format, String unit)
	{
		WrappedWeek.Slide s = new WrappedWeek.Slide();
		s.setEyebrow(eyebrow);
		s.setValue(value);
		s.setFormat(format);
		s.setUnit(unit);
		return s;
	}

	private static String pick(Random rnd, String... options)
	{
		return options[rnd.nextInt(options.length)];
	}

	private static String skillName(String key)
	{
		Skill s = Skills.parse(key);
		return s == null ? key : s.getName();
	}

	private static String dayName(DayRecord d)
	{
		return LocalDate.parse(d.getDate()).getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
	}
}
