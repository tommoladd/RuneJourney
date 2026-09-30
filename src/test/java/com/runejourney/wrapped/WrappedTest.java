package com.runejourney.wrapped;

import com.runejourney.model.DayRecord;
import com.runejourney.model.EventType;
import com.runejourney.model.JourneyEvent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.sound.midi.Sequence;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class WrappedTest
{
	private static final LocalDate MONDAY = LocalDate.of(2026, 9, 21);

	private static List<DayRecord> week(boolean bossing)
	{
		List<DayRecord> days = new ArrayList<>();
		for (int i = 0; i < 5; i++)
		{
			DayRecord d = new DayRecord(MONDAY.plusDays(i).toString());
			d.setPlayMillis(2 * 3_600_000L);
			d.setXpGained(300_000);
			d.getSkillXp().put("SLAYER", 300_000L);
			if (bossing)
			{
				d.getBossKills().put("Vardorvis", 30 + i);
				d.getBossKills().put("Vorkath", 5);
				d.setDeaths(2);
			}
			days.add(d);
		}
		JourneyEvent goal = new JourneyEvent(0, EventType.GOAL_COMPLETED, "99 Slayer achieved!", "Your journey took 40 days", null, null, true, 0);
		days.get(4).getEvents().add(goal);
		return days;
	}

	private static List<String> text(WrappedWeek w)
	{
		return w.getSlides().stream()
			.map(s -> s.getEyebrow() + "|" + s.getHeadline() + "|" + s.getUnit() + "|" + s.getSubtitle())
			.collect(Collectors.toList());
	}

	@Test
	public void celebratesWhatHappened()
	{
		WrappedWeek w = WrappedBuilder.build(WrappedBuilder.Input.builder()
			.weekStart(MONDAY).player("ItBeTommo").days(week(true)).playStreak(5).build());
		String all = String.join("\n", text(w));

		assertEquals("ItBeTommo", w.getSlides().get(0).getHeadline());
		assertTrue(all.contains("Your favourite was Vardorvis (160 kills)"));
		assertTrue(all.contains("You completed your goal|99 Slayer"));
		assertTrue(all.contains("|times|"));
		// The boss slide counts every kill
		assertTrue(w.getSlides().stream().anyMatch(s -> s.getValue() == 185));
		// Summary last
		assertFalse(w.getSlides().get(w.getSlides().size() - 1).getSummary().isEmpty());
	}

	@Test
	public void onlyShowsStatsThatHappened()
	{
		WrappedWeek w = WrappedBuilder.build(WrappedBuilder.Input.builder()
			.weekStart(MONDAY).player("ItBeTommo").days(week(false)).build());
		String all = String.join("\n", text(w));
		assertFalse(all.contains("bosses"));
		assertFalse(all.contains("You died"));
	}

	@Test
	public void differentWeeksLookDifferent()
	{
		WrappedWeek a = WrappedBuilder.build(WrappedBuilder.Input.builder()
			.weekStart(MONDAY).player("P").days(week(true)).build());
		WrappedWeek b = WrappedBuilder.build(WrappedBuilder.Input.builder()
			.weekStart(MONDAY.plusWeeks(1)).player("P").days(week(true)).build());
		assertNotEquals(a.getTheme(), b.getTheme());
		// Same week, same Wrapped
		WrappedWeek again = WrappedBuilder.build(WrappedBuilder.Input.builder()
			.weekStart(MONDAY).player("P").days(week(true)).build());
		assertEquals(text(a), text(again));
	}

	@Test
	public void everyThemeComposes() throws Exception
	{
		for (int theme = 0; theme < WrappedBuilder.THEMES; theme++)
		{
			Sequence seq = WrappedMusic.compose(theme, theme * 7L, 60);
			assertTrue(seq.getTracks()[0].size() > 100);
			assertTrue(seq.getMicrosecondLength() > 10_000_000L);
		}
	}
}
