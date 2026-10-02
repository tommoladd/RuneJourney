package com.runejourney.ui;

import com.runejourney.model.ItemTotal;
import com.runejourney.model.JourneyEvent;
import com.runejourney.model.LootSource;
import com.runejourney.planner.GoalPlanner;
import com.runejourney.planner.Skills;
import com.runejourney.service.JourneyService;
import com.runejourney.service.RangeSummary;
import com.runejourney.service.SessionView;
import com.runejourney.util.Format;
import com.runejourney.wrapped.WrappedPlayer;
import java.awt.Color;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;

/**
 * "What have I actually accomplished today?" Also doubles as the weekly/monthly recap and
 * "since..." comparison view via the range selector.
 */
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
	private final JPanel body = Ui.stack(4);
	private static final java.time.format.DateTimeFormatter MENU_DATE =
		java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH);
	/**
	 * Rows shown in the loot and supplies cards before the rest are summed as "Other".
	 */
	private static final int BREAKDOWN_ROWS = 8;
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

		JPanel stats = Ui.card();
		stats.add(Ui.stat("Played", Format.duration(r.getPlayMillis())));
		stats.add(Ui.stat("XP gained", Format.number(r.getXpGained())));
		stats.add(Ui.stat("Levels gained", Format.number(r.getLevelsGained())));
		stats.add(Ui.stat("Loot", Format.compact(r.getLootValue()) + " gp"));
		long income = r.getLootValue() + r.getSkillingIncome();
		if (r.getSkillingIncome() != 0)
		{
			stats.add(Ui.stat("Skilling income", Format.compact(r.getSkillingIncome()) + " gp"));
			stats.add(Ui.stat("Total income", Format.compact(income) + " gp", Ui.GOOD));
		}
		if (r.getSuppliesCost() > 0)
		{
			long profit = income - r.getSuppliesCost();
			stats.add(Ui.stat("Supplies used", "-" + Format.compact(r.getSuppliesCost()) + " gp", Ui.BAD));
			stats.add(Ui.stat("Profit", (profit > 0 ? "+" : "") + Format.compact(profit) + " gp", profit >= 0 ? Ui.GOOD : Ui.BAD));
		}
		stats.add(Ui.stat("Boss kills", Format.number(r.getBossKills())));
		optional(stats, "Slayer tasks", r.getSlayerTasks());
		optional(stats, "Clues completed", r.getCluesCompleted());
		if (r.getClueLootValue() > 0)
		{
			stats.add(Ui.stat("Clue loot", Format.compact(r.getClueLootValue()) + " gp"));
		}
		optional(stats, "Combat tasks", r.getCombatTasks());
		optional(stats, "CA points earned", r.getCombatTaskPoints());
		optional(stats, "Collection log", r.getCollectionLogSlots());
		optional(stats, "Quests", r.getQuestsCompleted());
		optional(stats, "Personal bests", r.getPersonalBests());
		optional(stats, "Pets", r.getPets());
		stats.add(Ui.stat("Deaths", Format.number(r.getDeaths()), r.getDeaths() > 0 ? Ui.BAD : java.awt.Color.WHITE));
		long[] worth = service.netWorthToday();
		if (worth != null)
		{
			String change = worth[1] == Long.MIN_VALUE ? "" : " (" + (worth[1] >= 0 ? "+" : "") + Format.compact(worth[1]) + ")";
			stats.add(Ui.stat("Net worth", Format.compact(worth[0]) + " gp" + change,
				worth[1] == Long.MIN_VALUE || worth[1] >= 0 ? java.awt.Color.WHITE : Ui.BAD));
		}
		int[] streak = service.playStreaks();
		if (streak[0] > 1)
		{
			stats.add(Ui.stat("Play streak", streak[0] + " days" + (streak[1] > streak[0] ? " (best " + streak[1] + ")" : " (best!)"), Ui.GOLD));
		}
		if (multiDay)
		{
			stats.add(Ui.stat("Days played", Format.number(r.getDaysPlayed())));
		}
		body.add(stats);

		if (r.getPlayMillis() == 0 && r.getHighlights().isEmpty())
		{
			body.add(Ui.empty(range == Range.TODAY
				? "Nothing recorded yet today. Go make some memories!"
				: "Nothing was recorded in this period."));
			rebuild();
			return;
		}

		if (multiDay && r.getBestMoment() != null)
		{
			body.add(Ui.header("Best moment"));
			body.add(views.event(r.getBestMoment(), null));
		}

		if (!r.getHighlights().isEmpty())
		{
			body.add(Ui.header("Highlights"));
			for (JourneyEvent e : r.getHighlights())
			{
				if (multiDay && e.equals(r.getBestMoment()))
				{
					continue;
				}
				body.add(views.event(e, null));
			}
		}

		if (!r.getLevelRanges().isEmpty())
		{
			body.add(Ui.header("Levels"));
			JPanel card = Ui.card();
			r.getLevelRanges().entrySet().stream()
				.sorted(Comparator.comparing(e -> -e.getValue()[1]))
				.forEach(e ->
				{
					Skill s = Skills.parse(e.getKey());
					if (s != null)
					{
						JLabel l = Ui.text(e.getValue()[0] + " -> " + e.getValue()[1] + " " + s.getName());
						l.setIcon(views.skillIcon(s));
						card.add(l);
					}
				});
			body.add(card);
		}

		if (!r.getBossKillsByName().isEmpty() || r.getSlayerTasks() > 0 || r.getCluesCompleted() > 0)
		{
			body.add(Ui.header("Activity"));
			JPanel card = Ui.card();
			List<Map.Entry<String, Integer>> bosses = r.getBossKillsByName().entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.limit(8)
				.collect(Collectors.toList());
			for (Map.Entry<String, Integer> e : bosses)
			{
				card.add(Ui.stat(e.getKey(), e.getValue() + " KC"));
			}
			if (r.getSlayerTasks() > 0)
			{
				card.add(Ui.stat("Slayer", r.getSlayerTasks() + (r.getSlayerTasks() == 1 ? " task" : " tasks")));
			}
			if (r.getCluesCompleted() > 0)
			{
				card.add(Ui.stat("Clue scrolls", String.valueOf(r.getCluesCompleted())));
			}
			body.add(card);
		}

		if (!r.getSkillXp().isEmpty())
		{
			body.add(Ui.header("XP by skill"));
			JPanel card = Ui.card();
			r.getSkillXp().entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
				.limit(10)
				.forEach(e ->
				{
					Skill s = Skills.parse(e.getKey());
					if (s != null)
					{
						JPanel row = Ui.stat(s.getName(), "+" + Format.compact(e.getValue()));
						((JLabel) row.getComponent(0)).setIcon(views.skillIcon(s));
						card.add(row);
					}
				});
			body.add(card);
		}

		if (!r.getSkillingIncomeBySkill().isEmpty())
		{
			body.add(Ui.header("Skilling income"));
			JPanel card = Ui.card();
			r.getSkillingIncomeBySkill().entrySet().stream()
				.filter(e -> e.getValue() != 0)
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
				.forEach(e ->
				{
					Skill s = Skills.parse(e.getKey());
					if (s != null)
					{
						JPanel row = Ui.stat(s.getName(), (e.getValue() > 0 ? "+" : "") + Format.compact(e.getValue()) + " gp",
							e.getValue() >= 0 ? java.awt.Color.WHITE : Ui.BAD);
						((JLabel) row.getComponent(0)).setIcon(views.skillIcon(s));
						card.add(row);
					}
				});
			body.add(card);
		}

		if (!r.getLootBySource().isEmpty())
		{
			body.add(Ui.header("Loot by source"));
			JPanel card = Ui.card();
			List<Map.Entry<String, LootSource>> sources = r.getLootBySource().entrySet().stream()
				.filter(e -> e.getValue().getValue() > 0)
				.sorted(Comparator.comparingLong((Map.Entry<String, LootSource> e) -> e.getValue().getValue()).reversed())
				.collect(Collectors.toList());
			sources.stream().limit(BREAKDOWN_ROWS).forEach(e ->
				withTooltip(card, Ui.stat(e.getKey(), Format.compact(e.getValue().getValue()) + " gp"), sourceTooltip(e.getValue())));
			otherRow(card, sources.stream().skip(BREAKDOWN_ROWS).mapToLong(e -> e.getValue().getValue()).sum(),
				sources.size() - BREAKDOWN_ROWS, " gp");
			body.add(card);
		}

		if (!r.getSuppliesUsed().isEmpty())
		{
			body.add(Ui.header("Supplies used"));
			JPanel card = Ui.card();
			List<Map.Entry<String, ItemTotal>> supplies = r.getSuppliesUsed().entrySet().stream()
				.sorted(Comparator.comparingLong((Map.Entry<String, ItemTotal> e) -> e.getValue().getValue()).reversed())
				.collect(Collectors.toList());
			supplies.stream().limit(BREAKDOWN_ROWS).forEach(e -> card.add(Ui.stat(
				e.getKey() + " x" + Format.number(e.getValue().getQuantity()),
				"-" + Format.compact(e.getValue().getValue()) + " gp", Ui.BAD)));
			otherRow(card, -supplies.stream().skip(BREAKDOWN_ROWS).mapToLong(e -> e.getValue().getValue()).sum(),
				supplies.size() - BREAKDOWN_ROWS, " gp");
			body.add(card);
		}

		if (multiDay && r.getBiggestDay() != null && r.getBiggestDayXp() > 0)
		{
			body.add(Ui.header("Biggest day"));
			JPanel card = Ui.card();
			card.add(Ui.stat(Format.date(LocalDate.parse(r.getBiggestDay())), Format.compact(r.getBiggestDayXp()) + " XP"));
			body.add(card);
		}

		rebuild();
	}

	/**
	 * Sums up whatever didn't fit in a breakdown card.
	 */
	private static void otherRow(JPanel card, long value, int count, String suffix)
	{
		if (count > 0)
		{
			card.add(Ui.stat("Other (" + count + ")", (value < 0 ? "-" : "") + Format.compact(Math.abs(value)) + suffix,
				value < 0 ? Ui.BAD : java.awt.Color.WHITE));
		}
	}

	private static void withTooltip(JPanel card, JPanel row, String tooltip)
	{
		row.setToolTipText(tooltip);
		for (java.awt.Component c : row.getComponents())
		{
			((javax.swing.JComponent) c).setToolTipText(tooltip);
		}
		card.add(row);
	}

	/**
	 * How often a source paid out and its most valuable items.
	 */
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

	private static void optional(JPanel card, String name, int value)
	{
		if (value > 0)
		{
			card.add(Ui.stat(name, Format.number(value)));
		}
	}
}
