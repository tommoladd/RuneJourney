package com.runejourney.ui;

import com.runejourney.model.*;
import com.runejourney.planner.*;
import com.runejourney.service.*;
import com.runejourney.util.Format;
import com.runejourney.wrapped.WrappedPlayer;
import java.awt.*;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import lombok.*;
import net.runelite.api.Skill;
import net.runelite.client.ui.*;

class TodayTab extends RefreshableTab
{
	@Getter
	@RequiredArgsConstructor
	enum Range
	{
		TODAY("Today"),
		YESTERDAY("Yesterday"),
		THIS_WEEK("This week"),
		LAST_WEEK("Last week"),
		THIS_MONTH("This month"),
		LAST_30_DAYS("Last 30 days"),
		THIS_YEAR("This year"),
		ALL_TIME("Since RuneJourney was installed"),
		CUSTOM("Custom range...");

		private final String label;

		@Override
		public String toString()
		{
			return label;
		}
	}

	private final JourneyService service;
	private final Views views;
	private final ReportWindowManager reportWindows;
	private final WrappedPlayer wrappedPlayer;
	private final JButton wrappedButton = Ui.button("RuneJourney Wrapped", this::openWrapped);
	private final Color buttonBackground = wrappedButton.getBackground();
	private final Color buttonForeground = wrappedButton.getForeground();
	private final JComboBox<Range> rangeBox = new JComboBox<>(Range.values());
	private final JPanel body = Ui.stack(6);
	private static final java.time.format.DateTimeFormatter MENU_DATE =
		java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH);
	private static final int BREAKDOWN_ROWS = 8;
	private static final int HIGHLIGHT_ROWS = 3;
	private static final int SKILL_ROWS = 5;
	private static final int BOSS_ROWS = 3;
	private static final String HIGHLIGHTS = "highlights";
	private static final String SKILLS = "skills";
	private static final String MONEY = "money";
	private static final String ACTIVITY = "activity";
	private static final String GOLD_HEX = String.format("#%06X", Ui.GOLD.getRGB() & 0xFFFFFF);
	private final Set<String> expanded = new HashSet<>();
	private LocalDate customFrom = LocalDate.now().minusDays(6);
	private LocalDate customTo = LocalDate.now();
	private Range lastRange = Range.TODAY;

	@Inject
	TodayTab(JourneyService service, Views views, ReportWindowManager reportWindows, WrappedPlayer wrappedPlayer)
	{
		this.reportWindows = reportWindows;
		this.wrappedPlayer = wrappedPlayer;
		this.service = service;
		this.views = views;
		setLayout(new BorderLayout(0, 6));
		setOpaque(false);
		rangeBox.addActionListener(e ->
		{
			if (rangeBox.getSelectedItem() == Range.CUSTOM && !chooseCustomRange())
			{
				rangeBox.setSelectedItem(lastRange);
				return;
			}
			lastRange = (Range) rangeBox.getSelectedItem();
			refresh(true);
		});

		JButton charts = Ui.button("Charts", reportWindows::open);
		charts.setToolTipText("Open the reports window: charts, breakdowns and exports for any metric and period");
		Ui.styled(rangeBox);

		JPanel controls = new JPanel(new BorderLayout(4, 0));
		controls.setOpaque(false);
		controls.add(rangeBox, BorderLayout.CENTER);
		controls.add(charts, BorderLayout.EAST);

		wrappedButton.setToolTipText("Relive your week with RuneJourney Wrapped");
		JPanel top = new JPanel(new GridLayout(0, 1, 0, 6));
		top.setOpaque(false);
		top.add(wrappedButton);
		top.add(controls);
		add(top, BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
	}

	private boolean chooseCustomRange()
	{
		JTextField fromField = new JTextField(customFrom.toString(), 10);
		JTextField toField = new JTextField(customTo.toString(), 10);
		JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
		form.add(new JLabel("From (YYYY-MM-DD)"));
		form.add(fromField);
		form.add(new JLabel("To (YYYY-MM-DD)"));
		form.add(toField);
		while (true)
		{
			int ok = JOptionPane.showConfirmDialog(this, form, "Custom range", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
			if (ok != JOptionPane.OK_OPTION)
			{
				return false;
			}
			LocalDate a = GoalPlanner.parseDate(fromField.getText().trim());
			LocalDate b = GoalPlanner.parseDate(toField.getText().trim());
			if (a == null || b == null || b.isBefore(a))
			{
				JOptionPane.showMessageDialog(this, "Enter two dates as YYYY-MM-DD, with the end on or after the start.",
					"RuneJourney", JOptionPane.WARNING_MESSAGE);
				continue;
			}
			customFrom = a;
			customTo = b;
			return true;
		}
	}

	private void updateWrappedButton()
	{
		boolean isNew = service.isWrappedNew();
		boolean any = !service.wrappedWeeks().isEmpty();
		wrappedButton.setText(isNew ? "Your Week Wrapped is ready!" : "RuneJourney Wrapped");
		wrappedButton.setBackground(isNew ? Ui.GOLD : buttonBackground);
		wrappedButton.setForeground(isNew ? Color.BLACK : buttonForeground);
		wrappedButton.setEnabled(any);
		wrappedButton.setToolTipText(any ? "Relive your week with RuneJourney Wrapped"
			: "Your first Wrapped arrives the Monday after your first week with RuneJourney");
	}

	private void openWrapped()
	{
		List<LocalDate> weeks = service.wrappedWeeks();
		if (weeks.isEmpty())
		{
			return;
		}
		LocalDate latest = service.latestWrappedWeek();
		if (weeks.size() == 1 || (latest != null && service.isWrappedNew()))
		{
			playWrapped(weeks.get(0));
			return;
		}
		JPopupMenu menu = new JPopupMenu();
		for (LocalDate week : weeks.subList(0, Math.min(12, weeks.size())))
		{
			String label = (week.equals(latest) ? "Last week" : "Week of " + Format.date(week))
				+ " (" + week.format(MENU_DATE) + " - " + week.plusDays(6).format(MENU_DATE) + ")";
			JMenuItem item = new JMenuItem(label);
			item.addActionListener(e -> playWrapped(week));
			menu.add(item);
		}
		menu.show(wrappedButton, 0, wrappedButton.getHeight());
	}

	private void playWrapped(LocalDate week)
	{
		service.markWrappedSeen(week);
		wrappedPlayer.play(service.buildWrapped(week));
		updateWrappedButton();
	}

	private LocalDate[] selectedRange()
	{
		Range range = (Range) rangeBox.getSelectedItem();
		LocalDate today = LocalDate.now();
		LocalDate from;
		LocalDate to = today;
		switch (range)
		{
			case CUSTOM:
				from = customFrom;
				to = customTo;
				break;
			case YESTERDAY:
				from = to = today.minusDays(1);
				break;
			case THIS_WEEK:
				from = GoalPlanner.weekStart(today);
				break;
			case LAST_WEEK:
				from = GoalPlanner.weekStart(today).minusWeeks(1);
				to = from.plusDays(6);
				break;
			case THIS_MONTH:
				from = today.with(TemporalAdjusters.firstDayOfMonth());
				break;
			case LAST_30_DAYS:
				from = today.minusDays(29);
				break;
			case THIS_YEAR:
				from = today.with(TemporalAdjusters.firstDayOfYear());
				break;
			case ALL_TIME:
				from = service.firstDay();
				break;
			default:
				from = today;
		}
		return new LocalDate[]{from, to};
	}

	@Override
	void refresh(boolean force)
	{
		updateWrappedButton();
		Range range = (Range) rangeBox.getSelectedItem();
		LocalDate[] selected = selectedRange();
		LocalDate from = selected[0];
		LocalDate to = selected[1];

		RangeSummary r = service.summarize(from, to);
		body.removeAll();

		SessionView session = service.sessionView();
		if (session != null && (range == Range.TODAY || range == Range.THIS_WEEK))
		{
			body.add(views.session(session));
		}

		boolean multiDay = !from.equals(to);
		if (multiDay)
		{
			body.add(Ui.muted(Format.date(from) + " - " + Format.date(to)));
		}
		body.add(tiles(r, multiDay));
		int[] streak = service.playStreaks();
		if (streak[0] > 1)
		{
			body.add(Ui.small(streak[0] + "-day play streak" + (streak[1] > streak[0] ? " (best " + streak[1] + ")" : ", your best!"), Ui.GOLD));
		}
		if (r.getAwayXp() > 0)
		{
			body.add(Ui.muted("Not counted: +" + Format.compact(r.getAwayXp()) + " XP gained while away, some time between "
				+ r.getAwayFrom().format(MENU_DATE) + " and " + r.getAwayTo().format(MENU_DATE) + "."));
		}

		if (r.getPlayMillis() == 0 && r.getHighlights().isEmpty() && r.getXpGained() == 0)
		{
			body.add(Ui.empty(range == Range.TODAY
				? "Nothing recorded yet today. Go make some memories!"
				: "Nothing was recorded in this period."));
			rebuild();
			return;
		}

		highlights(r, multiDay);
		skills(r, multiDay);
		money(r);
		activity(r);
		rebuild();
	}

	private JPanel tiles(RangeSummary r, boolean multiDay)
	{
		JPanel grid = new JPanel(new GridLayout(0, 2, 4, 4));
		grid.setOpaque(false);
		grid.add(Ui.tile(Format.duration(r.getPlayMillis()),
			multiDay ? "Played, " + r.getDaysPlayed() + (r.getDaysPlayed() == 1 ? " day" : " days") : "Played", Color.WHITE));
		JPanel xp = Ui.tile(Format.compact(r.getXpGained()), "XP gained", Color.WHITE);
		xp.setToolTipText(Format.number(r.getXpGained()) + " XP");
		grid.add(xp);
		long profit = r.getLootValue() + r.getSkillingIncome() - r.getSuppliesCost();
		grid.add(Ui.tile(gp(profit), "Profit", profit > 0 ? Ui.GOOD : profit < 0 ? Ui.BAD : Color.WHITE));
		Long worth = r.getEndSnapshot().get(Counters.WEALTH);
		Long worthBefore = r.getStartSnapshot().get(Counters.WEALTH);
		if (worth != null)
		{
			long change = worthBefore == null ? 0 : worth - worthBefore;
			grid.add(Ui.tile(Format.compact(worth) + " gp",
				worthBefore == null ? "Net worth" : "Net worth " + (change >= 0 ? "+" : "") + Format.compact(change), Color.WHITE));
		}
		else
		{
			grid.add(Ui.tile(Format.number(r.getLevelsGained()), "Levels gained", Color.WHITE));
		}
		return grid;
	}

	private void highlights(RangeSummary r, boolean multiDay)
	{
		List<JourneyEvent> events = new ArrayList<>();
		if (multiDay && r.getBestMoment() != null)
		{
			events.add(r.getBestMoment());
		}
		for (JourneyEvent e : r.getHighlights())
		{
			if (!(multiDay && e.equals(r.getBestMoment())))
			{
				events.add(e);
			}
		}
		if (events.isEmpty())
		{
			return;
		}
		int hidden = events.size() - HIGHLIGHT_ROWS;
		JLabel more = hidden > 0 ? toggle(HIGHLIGHTS, "Show " + hidden + " more") : null;
		if (more != null)
		{
			more.setVerticalAlignment(SwingConstants.BOTTOM);
		}
		body.add(Ui.row(Ui.header("Highlights"), more));
		events.stream()
			.limit(expanded.contains(HIGHLIGHTS) ? events.size() : HIGHLIGHT_ROWS)
			.forEach(e -> body.add(views.event(e, null)));
	}

	private void skills(RangeSummary r, boolean multiDay)
	{
		List<Map.Entry<String, Long>> rows = r.getSkillXp().entrySet().stream()
			.filter(e -> e.getValue() > 0 && Skills.parse(e.getKey()) != null)
			.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
			.collect(Collectors.toList());
		if (rows.isEmpty())
		{
			return;
		}
		List<Map.Entry<String, Long>> top = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++)
		{
			if (i < SKILL_ROWS || r.getLevelRanges().containsKey(rows.get(i).getKey()))
			{
				top.add(rows.get(i));
			}
		}
		int hidden = rows.size() - top.size();
		JPanel card = section("Skills", SKILLS, hidden > 0 ? "Show " + hidden + " more" : null);
		for (Map.Entry<String, Long> e : expanded.contains(SKILLS) ? rows : top)
		{
			Skill s = Skills.parse(e.getKey());
			JPanel row = Ui.stat(s.getName(), "+" + Format.compact(e.getValue()));
			JLabel name = (JLabel) row.getComponent(0);
			name.setIcon(views.skillIcon(s));
			int[] levels = r.getLevelRanges().get(e.getKey());
			if (levels != null)
			{
				name.setText("<html>" + s.getName() + " <font color='" + GOLD_HEX + "'>lvl " + levels[1] + "</font></html>");
				withTooltip(row, "Level " + levels[0] + " to " + levels[1] + ", " + Format.number(e.getValue()) + " XP");
			}
			else
			{
				withTooltip(row, Format.number(e.getValue()) + " XP");
			}
			card.add(row);
		}
		if (multiDay && r.getBiggestDay() != null && r.getBiggestDayXp() > 0)
		{
			JLabel best = Ui.muted("Biggest day: " + Format.compact(r.getBiggestDayXp()) + " XP on "
				+ Format.date(LocalDate.parse(r.getBiggestDay())));
			best.setBorder(new EmptyBorder(4, 0, 0, 0));
			card.add(best);
		}
		body.add(card);
	}

	private void money(RangeSummary r)
	{
		if (r.getLootValue() == 0 && r.getSkillingIncome() == 0 && r.getSuppliesCost() == 0)
		{
			return;
		}
		JPanel card = section("Money", MONEY, "Show details");
		if (r.getLootValue() != 0)
		{
			card.add(Ui.stat("Loot", Format.compact(r.getLootValue()) + " gp"));
		}
		if (r.getSkillingIncome() != 0)
		{
			card.add(Ui.stat("Skilling", gp(r.getSkillingIncome()), r.getSkillingIncome() < 0 ? Ui.BAD : Color.WHITE));
		}
		if (r.getSuppliesCost() > 0)
		{
			card.add(Ui.stat("Supplies used", gp(-r.getSuppliesCost()), Ui.BAD));
		}
		if (!expanded.contains(MONEY))
		{
			body.add(card);
			return;
		}

		List<Map.Entry<String, LootSource>> sources = r.getLootBySource().entrySet().stream()
			.filter(e -> e.getValue().getValue() > 0)
			.sorted(Comparator.comparingLong((Map.Entry<String, LootSource> e) -> e.getValue().getValue()).reversed())
			.collect(Collectors.toList());
		if (!sources.isEmpty())
		{
			card.add(subheading("Loot by source"));
			sources.stream().limit(BREAKDOWN_ROWS).forEach(e -> card.add(withTooltip(
				Ui.stat(e.getKey(), Format.compact(e.getValue().getValue()) + " gp", ColorScheme.LIGHT_GRAY_COLOR),
				sourceTooltip(e.getValue()))));
			otherRow(card, sources.stream().skip(BREAKDOWN_ROWS).mapToLong(e -> e.getValue().getValue()).sum(),
				sources.size() - BREAKDOWN_ROWS);
		}

		List<Map.Entry<String, Long>> skilling = r.getSkillingIncomeBySkill().entrySet().stream()
			.filter(e -> e.getValue() != 0 && Skills.parse(e.getKey()) != null)
			.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
			.collect(Collectors.toList());
		if (!skilling.isEmpty())
		{
			card.add(subheading("Skilling by skill"));
			for (Map.Entry<String, Long> e : skilling)
			{
				Skill s = Skills.parse(e.getKey());
				JPanel row = Ui.stat(s.getName(), gp(e.getValue()), e.getValue() < 0 ? Ui.BAD : ColorScheme.LIGHT_GRAY_COLOR);
				((JLabel) row.getComponent(0)).setIcon(views.skillIcon(s));
				card.add(row);
			}
		}

		List<Map.Entry<String, ItemTotal>> supplies = r.getSuppliesUsed().entrySet().stream()
			.sorted(Comparator.comparingLong((Map.Entry<String, ItemTotal> e) -> e.getValue().getValue()).reversed())
			.collect(Collectors.toList());
		if (!supplies.isEmpty())
		{
			card.add(subheading("Supplies used"));
			supplies.stream().limit(BREAKDOWN_ROWS).forEach(e -> card.add(Ui.stat(
				e.getKey() + " x" + Format.number(e.getValue().getQuantity()),
				gp(-e.getValue().getValue()), ColorScheme.LIGHT_GRAY_COLOR)));
			otherRow(card, -supplies.stream().skip(BREAKDOWN_ROWS).mapToLong(e -> e.getValue().getValue()).sum(),
				supplies.size() - BREAKDOWN_ROWS);
		}
		body.add(card);
	}

	private void activity(RangeSummary r)
	{
		List<JPanel> rows = new ArrayList<>();
		if (r.getCluesCompleted() > 0)
		{
			rows.add(Ui.stat("Clue scrolls", Format.number(r.getCluesCompleted())
				+ (r.getClueLootValue() > 0 ? ", " + Format.compact(r.getClueLootValue()) + " gp" : "")));
		}
		count(rows, "Slayer tasks", r.getSlayerTasks());
		count(rows, "Collection log", r.getCollectionLogSlots());
		count(rows, "Quests", r.getQuestsCompleted());
		count(rows, "Personal bests", r.getPersonalBests());
		count(rows, "Pets", r.getPets());
		if (r.getCombatTasks() > 0)
		{
			rows.add(Ui.stat("Combat tasks", Format.number(r.getCombatTasks())
				+ (r.getCombatTaskPoints() > 0 ? ", " + Format.number(r.getCombatTaskPoints()) + " pts" : "")));
		}
		if (r.getDeaths() > 0)
		{
			rows.add(Ui.stat("Deaths", Format.number(r.getDeaths()), Ui.BAD));
		}

		List<Map.Entry<String, Integer>> bosses = r.getBossKillsByName().entrySet().stream()
			.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
			.collect(Collectors.toList());
		if (rows.isEmpty() && bosses.isEmpty())
		{
			return;
		}
		int hidden = Math.max(0, bosses.size() - BOSS_ROWS);
		JPanel card = section("Activity", ACTIVITY, hidden > 0 ? "Show " + hidden + " more" : null);
		bosses.stream()
			.limit(expanded.contains(ACTIVITY) ? bosses.size() : BOSS_ROWS)
			.forEach(e -> card.add(Ui.stat(e.getKey(), Format.number(e.getValue()) + " KC")));
		rows.forEach(card::add);
		body.add(card);
	}

	private JPanel section(String title, String key, String moreText)
	{
		JPanel card = Ui.card();
		JLabel heading = Ui.small(title, Ui.GOLD);
		heading.setFont(FontManager.getRunescapeBoldFont());
		JPanel head = Ui.row(heading, moreText == null && !expanded.contains(key) ? null : toggle(key, moreText));
		head.setBorder(new EmptyBorder(0, 0, 3, 0));
		card.add(head);
		return card;
	}

	private JLabel toggle(String key, String moreText)
	{
		return Ui.link(expanded.contains(key) ? "Show less" : moreText, () ->
		{
			if (!expanded.remove(key))
			{
				expanded.add(key);
			}
			refresh(true);
		});
	}

	private static JLabel subheading(String text)
	{
		JLabel l = Ui.small(text, Ui.MUTED);
		l.setBorder(new EmptyBorder(6, 0, 1, 0));
		return l;
	}

	private static String gp(long value)
	{
		return (value > 0 ? "+" : "") + Format.compact(value) + " gp";
	}

	private static void otherRow(JPanel card, long value, int count)
	{
		if (count > 0)
		{
			card.add(Ui.stat("Other (" + count + ")", gp(value), ColorScheme.LIGHT_GRAY_COLOR));
		}
	}

	private static JPanel withTooltip(JPanel row, String tooltip)
	{
		row.setToolTipText(tooltip);
		for (java.awt.Component c : row.getComponents())
		{
			((javax.swing.JComponent) c).setToolTipText(tooltip);
		}
		return row;
	}

	private static String sourceTooltip(LootSource s)
	{
		StringBuilder sb = new StringBuilder("<html>").append(Format.number(s.getTimes()))
			.append(s.getTimes() == 1 ? " time" : " times");
		s.getItems().entrySet().stream()
			.filter(e -> e.getValue().getValue() > 0)
			.sorted(Comparator.comparingLong((Map.Entry<String, ItemTotal> e) -> e.getValue().getValue()).reversed())
			.limit(5)
			.forEach(e -> sb.append("<br>").append(Ui.escape(e.getKey())).append(" x").append(Format.number(e.getValue().getQuantity()))
				.append(": ").append(Format.compact(e.getValue().getValue())).append(" gp"));
		return sb.append("</html>").toString();
	}

	private static void count(List<JPanel> rows, String name, int value)
	{
		if (value > 0)
		{
			rows.add(Ui.stat(name, Format.number(value)));
		}
	}
}
