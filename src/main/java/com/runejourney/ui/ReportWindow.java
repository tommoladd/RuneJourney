package com.runejourney.ui;

import com.runejourney.RuneJourneyPlugin;
import com.runejourney.model.*;
import com.runejourney.planner.*;
import com.runejourney.report.*;
import com.runejourney.service.*;
import com.runejourney.util.Format;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import lombok.*;
import net.runelite.api.Skill;
import net.runelite.client.ui.*;
import net.runelite.client.util.Filepath;

class ReportWindow extends JFrame
{
	private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);
	private static final Map<Metric, Color> COLORS = new EnumMap<>(Metric.class);

	static
	{
		COLORS.put(Metric.XP, Ui.GOLD);
		COLORS.put(Metric.XP_PER_HOUR, Ui.GOLD);
		COLORS.put(Metric.PLAYTIME, new Color(0x4FC3F7));
		COLORS.put(Metric.INCOME, new Color(0x66BB6A));
		COLORS.put(Metric.LOOT, new Color(0x66BB6A));
		COLORS.put(Metric.SKILLING_INCOME, new Color(0x9CCC65));
		COLORS.put(Metric.GP_PER_HOUR, new Color(0x66BB6A));
		COLORS.put(Metric.KILLS, new Color(0xEF5350));
		COLORS.put(Metric.KILLS_PER_HOUR, new Color(0xEF5350));
		COLORS.put(Metric.CLUES, new Color(0xFFE082));
		COLORS.put(Metric.CLUE_LOOT, new Color(0xFFE082));
		COLORS.put(Metric.COLLECTION_LOG, new Color(0xFFD54F));
		COLORS.put(Metric.COMBAT_TASKS, new Color(0xBA68C8));
		COLORS.put(Metric.CA_POINTS, new Color(0xBA68C8));
		COLORS.put(Metric.LEVELS, new Color(0x8BC34A));
		COLORS.put(Metric.DEATHS, new Color(0x9E9E9E));
		COLORS.put(Metric.NET_WORTH, new Color(0x26A69A));
	}

	@Getter
	@RequiredArgsConstructor
	enum Period
	{
		LAST_7("Last 7 days"),
		LAST_30("Last 30 days"),
		LAST_90("Last 90 days"),
		THIS_WEEK("This week"),
		LAST_WEEK("Last week"),
		THIS_MONTH("This month"),
		LAST_MONTH("Last month"),
		THIS_YEAR("This year"),
		ALL_TIME("All time"),
		CUSTOM("Custom range");

		private final String label;

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum ChartStyle
	{
		BARS("Bars"), LINE("Line"), CUMULATIVE("Running total");

		private final String label;

		ChartStyle(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@Value
	static class FilterOption
	{
		String key;
		String label;

		@Override
		public String toString()
		{
			return label;
		}
	}

	private static final FilterOption ALL = new FilterOption(null, "All");

	private final JourneyService service;
	private final ReportService reports;
	private final RuneJourneyPlugin plugin;
	private final Views views;

	private final JComboBox<Period> periodBox = new JComboBox<>(Period.values());
	private final JTextField fromField = new JTextField(10);
	private final JTextField toField = new JTextField(10);
	private final JPanel customRow = new JPanel(new GridLayout(0, 1, 0, 4));
	private final JComboBox<Metric> metricBox = new JComboBox<>(Metric.values());
	private final JComboBox<FilterOption> filterBox = new JComboBox<>();
	private final JComboBox<Granularity> groupBox = new JComboBox<>(Granularity.values());
	private final JComboBox<ChartStyle> styleBox = new JComboBox<>(ChartStyle.values());
	private final JCheckBox compareBox = new JCheckBox("Compare with previous period", true);
	private final JLabel rangeLabel = new JLabel();
	private final JTabbedPane tabs = new JTabbedPane();

	private final JLabel exploreTitle = new JLabel();
	private final JPanel exploreTiles = new JPanel(new GridLayout(1, 4, 8, 0));
	private final TimeSeriesChart mainChart = new TimeSeriesChart(false);
	private final JLabel leftTitle = new JLabel();
	private final BarChart leftBars = new BarChart(15);
	private final JLabel rightTitle = new JLabel();
	private final BarChart rightBars = new BarChart(15);
	private final DefaultTableModel tableModel = new DefaultTableModel()
	{
		@Override
		public boolean isCellEditable(int row, int column)
		{
			return false;
		}
	};
	private final DefaultListModel<String> highlightModel = new DefaultListModel<>();

	private final JPanel overviewTiles = new JPanel(new GridLayout(2, 4, 8, 8));
	private final JPanel overviewCharts = new JPanel(new GridLayout(2, 3, 8, 8));
	private final BarChart xpBySkill = new BarChart(12);
	private final BarChart killsByBoss = new BarChart(12);
	private final BarChart incomeBySource = new BarChart(12);

	private static final String[] LOOT_COLUMNS = {"Source", "Kills / trips", "Total", "Per kill / trip", "Share",
		"GP / hour", "Vs previous", "Best item"};
	private final JPanel lootTiles = new JPanel(new GridLayout(1, 4, 8, 0));
	private final BarChart lootTotals = new BarChart(15);
	private final BarChart lootPerKill = new BarChart(15);
	private final DefaultTableModel lootModel = new DefaultTableModel(LOOT_COLUMNS, 0)
	{
		@Override
		public boolean isCellEditable(int row, int column)
		{
			return false;
		}

		@Override
		public Class<?> getColumnClass(int column)
		{
			return column == 0 || column == 7 ? String.class : LootCell.class;
		}
	};

	private String lootSource;
	private final JTextField lootSearch = new JTextField();
	private final JLabel lootTableTitle = new JLabel();
	private List<LootReport.Row> lootRows = Collections.emptyList();
	private final Map<String, LootReport.Match> lootMatches = new java.util.HashMap<>();
	private final JTable lootTable = new JTable(lootModel);
	private final JLabel lootDetailTitle = new JLabel();
	private final JComboBox<String> lootScopeBox = new JComboBox<>(new String[]{"All time", "This period"});
	private final JButton lootChartButton = Ui.button("Chart over time", () -> openExplore(Metric.LOOT, lootSource));
	private final JPanel lootDetailTiles = new JPanel(new GridLayout(1, 4, 8, 0));
	private final DefaultTableModel lootItemsModel = new DefaultTableModel(new String[]{"Item", "Quantity", "Total value",
		"Per kill / trip", "Share of source"}, 0)
	{
		@Override
		public boolean isCellEditable(int row, int column)
		{
			return false;
		}

		@Override
		public Class<?> getColumnClass(int column)
		{
			return column == 0 ? String.class : LootCell.class;
		}
	};

	private final JPanel recordsPanel = new JPanel();

	private LocalDate from;
	private LocalDate to;
	private List<DayRecord> days = Collections.emptyList();
	private List<DayRecord> previousDays = Collections.emptyList();
	private boolean updating;

	@Inject
	ReportWindow(JourneyService service, ReportService reports, RuneJourneyPlugin plugin, Views views)
	{
		this.service = service;
		this.reports = reports;
		this.plugin = plugin;
		this.views = views;

		setTitle("RuneJourney Reports" + (service.playerName() != null ? " · " + service.playerName() : ""));
		setIconImage(Icons.navIcon());
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		setMinimumSize(new Dimension(900, 620));
		setSize(new Dimension(1200, 820));

		JPanel root = new JPanel(new BorderLayout());
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.add(controls(), BorderLayout.WEST);

		tabs.addTab("Overview", scroll(overview()));
		tabs.addTab("Explore", scroll(explore()));
		tabs.addTab("Loot", scroll(loot()));
		tabs.addTab("Records", scroll(recordsPanel));
		root.add(tabs, BorderLayout.CENTER);
		setContentPane(root);

		periodBox.setSelectedItem(Period.LAST_30);
		groupBox.setSelectedItem(Granularity.AUTO);
		fromField.setText(LocalDate.now().minusDays(29).toString());
		toField.setText(LocalDate.now().toString());
		customRow.setVisible(false);

		periodBox.addActionListener(e ->
		{
			customRow.setVisible(periodBox.getSelectedItem() == Period.CUSTOM);
			if (periodBox.getSelectedItem() != Period.CUSTOM)
			{
				reload();
			}
		});
		metricBox.addActionListener(e ->
		{
			if (!updating)
			{
				updateFilters();
				render();
			}
		});
		filterBox.addActionListener(e ->
		{
			if (!updating)
			{
				render();
			}
		});
		groupBox.addActionListener(e -> render());
		styleBox.addActionListener(e -> render());
		compareBox.addActionListener(e -> render());

		reload();
		setLocationRelativeTo(null);
	}

	private JScrollPane scroll(JComponent content)
	{
		JScrollPane sp = new JScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		sp.setBorder(BorderFactory.createEmptyBorder());
		sp.getVerticalScrollBar().setUnitIncrement(24);
		return sp;
	}

	private JPanel controls()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(new EmptyBorder(14, 12, 14, 12));
		p.setPreferredSize(new Dimension(240, 10));

		JLabel title = new JLabel("RuneJourney Reports");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Ui.GOLD);
		add(p, title);
		rangeLabel.setFont(FontManager.getRunescapeSmallFont());
		rangeLabel.setForeground(Ui.MUTED);
		add(p, rangeLabel);
		p.add(Box.createVerticalStrut(12));

		add(p, label("Period"));
		add(p, periodBox);
		customRow.setOpaque(false);
		customRow.add(label("From (YYYY-MM-DD)"));
		customRow.add(fromField);
		customRow.add(label("To (YYYY-MM-DD)"));
		customRow.add(toField);
		customRow.add(Ui.button("Apply range", this::reload));
		add(p, customRow);
		p.add(Box.createVerticalStrut(10));

		add(p, label("Metric"));
		add(p, metricBox);
		add(p, label("Show"));
		add(p, filterBox);
		add(p, label("Group by"));
		add(p, groupBox);
		add(p, label("Chart"));
		add(p, styleBox);
		compareBox.setOpaque(false);
		compareBox.setFont(FontManager.getRunescapeSmallFont());
		add(p, compareBox);

		p.add(Box.createVerticalGlue());
		add(p, label("Export"));
		add(p, Ui.button("Save report (web page)", this::exportHtml));
		add(p, Ui.button("Save data (CSV)", this::exportCsv));
		add(p, Ui.button("Copy summary", this::copySummary));
		p.add(Box.createVerticalStrut(8));
		add(p, Ui.button("Refresh", this::reload));
		return p;
	}

	private static void add(JPanel p, JComponent c)
	{
		c.setAlignmentX(LEFT_ALIGNMENT);
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
		p.add(c);
		p.add(Box.createVerticalStrut(4));
	}

	private static JLabel label(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(Ui.MUTED);
		return l;
	}

	private static JPanel card(String title, JComponent content)
	{
		JPanel card = new JPanel(new BorderLayout(0, 6));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new EmptyBorder(10, 12, 10, 12));
		if (title != null)
		{
			JLabel t = new JLabel(title);
			t.setFont(FontManager.getRunescapeBoldFont());
			t.setForeground(Ui.GOLD);
			card.add(t, BorderLayout.NORTH);
		}
		card.add(content, BorderLayout.CENTER);
		return card;
	}

	private static JPanel cardWithTitle(JLabel title, JComponent content)
	{
		JPanel card = new JPanel(new BorderLayout(0, 6));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new EmptyBorder(10, 12, 10, 12));
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Ui.GOLD);
		card.add(title, BorderLayout.NORTH);
		card.add(content, BorderLayout.CENTER);
		return card;
	}

	private JPanel explore()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.setBorder(new EmptyBorder(14, 14, 14, 14));

		exploreTitle.setFont(FontManager.getRunescapeBoldFont());
		exploreTitle.setForeground(Color.WHITE);
		addRow(p, exploreTitle, 0);

		exploreTiles.setOpaque(false);
		addRow(p, exploreTiles, 70);
		addRow(p, card(null, mainChart), 330);

		JPanel breakdowns = new JPanel(new GridLayout(1, 2, 10, 0));
		breakdowns.setOpaque(false);
		breakdowns.add(cardWithTitle(leftTitle, leftBars));
		breakdowns.add(cardWithTitle(rightTitle, rightBars));
		addRow(p, breakdowns, 0);

		JTable table = new JTable(tableModel);
		table.setFillsViewportHeight(true);
		table.setRowHeight(20);
		JScrollPane tableScroll = new JScrollPane(table);
		tableScroll.setPreferredSize(new Dimension(300, 260));

		JList<String> highlights = new JList<>(highlightModel);
		highlights.setFont(FontManager.getRunescapeSmallFont());
		JScrollPane hlScroll = new JScrollPane(highlights);
		hlScroll.setPreferredSize(new Dimension(300, 260));

		JPanel bottom = new JPanel(new GridLayout(1, 2, 10, 0));
		bottom.setOpaque(false);
		bottom.add(card("Data", tableScroll));
		bottom.add(card("Related moments", hlScroll));
		addRow(p, bottom, 320);

		leftBars.setIcons(this::iconFor);
		rightBars.setIcons(this::iconFor);
		leftBars.setLabels(this::labelFor);
		leftBars.setOnClick(e ->
		{
			for (int i = 0; i < filterBox.getItemCount(); i++)
			{
				FilterOption o = filterBox.getItemAt(i);
				if (e.getKey().equals(o.getKey()))
				{
					filterBox.setSelectedIndex(i);
					return;
				}
			}
		});
		mainChart.setOnClick(null);
		return p;
	}

	private static void addRow(JPanel p, JComponent c, int height)
	{
		c.setAlignmentX(LEFT_ALIGNMENT);
		if (height > 0)
		{
			c.setPreferredSize(new Dimension(600, height));
			c.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		}
		else
		{
			c.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		}
		p.add(c);
		p.add(Box.createVerticalStrut(10));
	}

	private JPanel overview()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.setBorder(new EmptyBorder(14, 14, 14, 14));

		overviewTiles.setOpaque(false);
		addRow(p, overviewTiles, 150);
		overviewCharts.setOpaque(false);
		addRow(p, overviewCharts, 330);

		JPanel bars = new JPanel(new GridLayout(1, 3, 10, 0));
		bars.setOpaque(false);
		xpBySkill.setIcons(this::iconFor);
		xpBySkill.setLabels(this::labelFor);
		xpBySkill.setEmptyText("No XP gained");
		xpBySkill.setOnClick(e -> openExplore(Metric.XP, e.getKey()));
		killsByBoss.setEmptyText("No boss kills");
		killsByBoss.setOnClick(e -> openExplore(Metric.KILLS, e.getKey()));
		incomeBySource.setIcons(this::iconFor);
		incomeBySource.setLabels(this::labelFor);
		incomeBySource.setEmptyText("No income recorded");
		incomeBySource.setOnClick(e ->
		{
			if (Skills.parse(e.getKey()) != null)
			{
				openExplore(Metric.SKILLING_INCOME, e.getKey());
			}
			else
			{
				openExplore("clues".equals(e.getKey()) ? Metric.CLUE_LOOT : Metric.LOOT, null);
			}
		});
		bars.add(card("XP by skill", xpBySkill));
		bars.add(card("Kills by boss", killsByBoss));
		bars.add(card("Income by source", incomeBySource));
		addRow(p, bars, 0);
		return p;
	}

	private JPanel loot()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.setBorder(new EmptyBorder(14, 14, 14, 14));

		JLabel title = new JLabel("Loot by boss and activity");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		addRow(p, title, 0);
		addRow(p, label("GE value of loot, before supplies. GP / hour uses your own kill times. Click a source to chart it."), 0);

		lootTiles.setOpaque(false);
		addRow(p, lootTiles, 70);

		lootTotals.setEmptyText("No loot in this period");
		lootTotals.setOnClick(e -> selectLootSource(e.getKey()));
		lootPerKill.setEmptyText("No loot in this period");
		lootPerKill.setOnClick(e -> selectLootSource(e.getKey()));
		JPanel bars = new JPanel(new GridLayout(1, 2, 10, 0));
		bars.setOpaque(false);
		bars.add(card("Total loot", lootTotals));
		bars.add(card("Average per kill or trip", lootPerKill));
		addRow(p, bars, 0);

		JTable table = lootTable;
		numberTable(table);
		table.getSelectionModel().setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		table.getSelectionModel().addListSelectionListener(e ->
		{
			int row = table.getSelectedRow();
			if (!e.getValueIsAdjusting() && row >= 0)
			{
				lootSource = (String) lootModel.getValueAt(table.convertRowIndexToModel(row), 0);
				renderLootDetail();
			}
		});
		table.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				int row = table.rowAtPoint(e.getPoint());
				if (e.getClickCount() == 2 && row >= 0)
				{
					openExplore(Metric.LOOT, (String) lootModel.getValueAt(table.convertRowIndexToModel(row), 0));
				}
			}
		});
		JScrollPane tableScroll = new JScrollPane(table);
		tableScroll.setPreferredSize(new Dimension(300, 200));

		JPanel search = new JPanel(new BorderLayout(6, 0));
		search.setOpaque(false);
		search.add(label("Search sources or items"), BorderLayout.WEST);
		lootSearch.setToolTipText("e.g. \"tombs\" for a source, or \"rune chainbody\" for every source that gave one");
		lootSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				fillLootTable();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				fillLootTable();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
				fillLootTable();
			}
		});
		search.add(lootSearch, BorderLayout.CENTER);
		search.add(Ui.button("Clear", () -> lootSearch.setText("")), BorderLayout.EAST);
		JPanel list = new JPanel(new BorderLayout(0, 8));
		list.setOpaque(false);
		list.add(search, BorderLayout.NORTH);
		list.add(tableScroll, BorderLayout.CENTER);
		addRow(p, cardWithTitle(lootTableTitle, list), 290);

		JPanel detail = new JPanel(new BorderLayout(0, 8));
		detail.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		detail.setBorder(new EmptyBorder(10, 12, 10, 12));
		JPanel head = new JPanel(new BorderLayout(8, 0));
		head.setOpaque(false);
		lootDetailTitle.setFont(FontManager.getRunescapeBoldFont());
		lootDetailTitle.setForeground(Ui.GOLD);
		head.add(lootDetailTitle, BorderLayout.CENTER);
		JPanel headButtons = new JPanel(new GridLayout(1, 2, 6, 0));
		headButtons.setOpaque(false);
		lootScopeBox.addActionListener(e -> renderLootDetail());
		headButtons.add(lootScopeBox);
		headButtons.add(lootChartButton);
		head.add(headButtons, BorderLayout.EAST);
		detail.add(head, BorderLayout.NORTH);

		lootDetailTiles.setOpaque(false);
		lootDetailTiles.setPreferredSize(new Dimension(600, 70));
		JTable items = new JTable(lootItemsModel);
		numberTable(items);
		JScrollPane itemsScroll = new JScrollPane(items);
		itemsScroll.setPreferredSize(new Dimension(300, 300));
		JPanel body = new JPanel(new BorderLayout(0, 8));
		body.setOpaque(false);
		body.add(lootDetailTiles, BorderLayout.NORTH);
		body.add(itemsScroll, BorderLayout.CENTER);
		detail.add(body, BorderLayout.CENTER);
		addRow(p, detail, 440);
		renderLootDetail();
		return p;
	}

	private static void numberTable(JTable table)
	{
		table.setAutoCreateRowSorter(true);
		table.setFillsViewportHeight(true);
		table.setRowHeight(20);
		table.setDefaultRenderer(LootCell.class, new javax.swing.table.DefaultTableCellRenderer()
		{
			@Override
			protected void setValue(Object value)
			{
				setHorizontalAlignment(RIGHT);
				setText(value == null ? "" : value.toString());
			}
		});
	}

	private void selectLootSource(String source)
	{
		for (int i = 0; i < lootTable.getRowCount(); i++)
		{
			if (source.equals(lootModel.getValueAt(lootTable.convertRowIndexToModel(i), 0)))
			{
				lootTable.setRowSelectionInterval(i, i);
				lootTable.scrollRectToVisible(lootTable.getCellRect(i, 0, true));
				return;
			}
		}
	}

	private void renderLootDetail()
	{
		lootItemsModel.setRowCount(0);
		lootDetailTiles.removeAll();
		lootChartButton.setEnabled(lootSource != null);
		if (lootSource == null)
		{
			lootDetailTitle.setText("Click a source above to see everything it has given you");
			lootDetailTiles.revalidate();
			lootDetailTiles.repaint();
			return;
		}

		boolean allTime = "All time".equals(lootScopeBox.getSelectedItem());
		List<DayRecord> scope = allTime ? service.daysBetween(service.firstDay(), LocalDate.now()) : days;
		LootReport.SourceDetail d = LootReport.detail(scope, lootSource);
		LootReport.Match match = lootMatches.get(lootSource);
		String itemFilter = match != null && !match.isBySource() ? lootSearch.getText().trim() : "";
		lootDetailTitle.setText((itemFilter.isEmpty() ? "Everything from " : "Items matching \"" + itemFilter + "\" from ")
			+ lootSource + (allTime ? " (all time)" : " (this period)"));

		lootDetailTiles.add(tile("Total", Format.compact(d.getTotal()) + " gp", null, null));
		lootDetailTiles.add(tile("Kills / trips", Format.number(d.getTimes()), null, null));
		lootDetailTiles.add(tile("Average each",
			d.getTimes() > 0 ? Format.compact(Math.round((double) d.getTotal() / d.getTimes())) + " gp" : "-", null, null));
		String seen = d.getFirstDay() == null ? "-"
			: d.getFirstDay().equals(d.getLastDay()) ? Format.date(LocalDate.parse(d.getFirstDay()))
			: Format.date(LocalDate.parse(d.getFirstDay())) + " - " + Format.date(LocalDate.parse(d.getLastDay()));
		lootDetailTiles.add(tile("Seen", seen, null, null));

		for (LootReport.ItemRow r : d.getItems())
		{
			if (!LootReport.itemMatches(r.getName(), itemFilter))
			{
				continue;
			}
			lootItemsModel.addRow(new Object[]{
				r.getName(),
				LootCell.count(r.getQuantity()),
				LootCell.gp(r.getValue()),
				LootCell.gp(r.getPerTime()),
				LootCell.percent(r.getShare()),
			});
		}
		lootDetailTiles.revalidate();
		lootDetailTiles.repaint();
	}

	private void renderLoot()
	{
		List<LootReport.Row> rows = LootReport.build(days, previousDays,
			source -> service.isPersonalKillTime(source) ? service.minutesPerKill(source) : Double.NaN);

		long total = rows.stream().mapToLong(LootReport.Row::getTotal).sum();
		long previous = rows.stream().mapToLong(LootReport.Row::getPreviousTotal).sum();
		lootTiles.removeAll();
		double change = previous > 0 ? (double) (total - previous) / previous : Double.NaN;
		lootTiles.add(tile("Total loot", Format.compact(total) + " gp",
			Double.isNaN(change) ? " " : signedPercent(change) + " vs previous",
			Double.isNaN(change) ? Ui.MUTED : change >= 0 ? Ui.GOOD : Ui.BAD));
		LootReport.Row top = rows.isEmpty() ? null : rows.get(0);
		lootTiles.add(tile("Most lucrative", top == null ? "-" : top.getSource(),
			top == null ? " " : Format.compact(top.getTotal()) + " gp", Ui.MUTED));
		LootReport.Row bestEach = rows.stream().filter(r -> r.getTimes() > 1)
			.max((a, b) -> Double.compare(a.getPerTime(), b.getPerTime())).orElse(null);
		lootTiles.add(tile("Best per kill / trip", bestEach == null ? "-" : bestEach.getSource(),
			bestEach == null ? " " : Format.compact(Math.round(bestEach.getPerTime())) + " gp each", Ui.MUTED));
		LootReport.Row bestRate = rows.stream().filter(r -> !Double.isNaN(r.getGpPerHour()))
			.max((a, b) -> Double.compare(a.getGpPerHour(), b.getGpPerHour())).orElse(null);
		lootTiles.add(tile("Best GP / hour", bestRate == null ? "-" : bestRate.getSource(),
			bestRate == null ? "Needs a few timed kills" : Format.compact(Math.round(bestRate.getGpPerHour())) + " gp/hr", Ui.MUTED));

		lootTotals.setData(rows.stream()
			.map(r -> new Analytics.Entry(r.getSource(), r.getSource(), r.getTotal()))
			.collect(Collectors.toList()), Unit.GP, color(Metric.LOOT));
		lootPerKill.setData(rows.stream()
			.filter(r -> r.getTimes() > 1)
			.sorted((a, b) -> Double.compare(b.getPerTime(), a.getPerTime()))
			.map(r -> new Analytics.Entry(r.getSource(), r.getSource(), r.getPerTime()))
			.collect(Collectors.toList()), Unit.GP, color(Metric.LOOT));

		lootRows = rows;
		fillLootTable();
	}

	private void fillLootTable()
	{
		String search = lootSearch.getText();
		lootMatches.clear();
		lootModel.setRowCount(0);
		for (LootReport.Row r : lootRows)
		{
			LootReport.Match match = LootReport.match(r, search);
			if (match == null)
			{
				continue;
			}
			lootMatches.put(r.getSource(), match);
			lootModel.addRow(new Object[]{
				r.getSource(),
				LootCell.count(r.getTimes()),
				LootCell.gp(r.getTotal()),
				LootCell.gp(r.getPerTime()),
				LootCell.percent(r.getShare()),
				LootCell.gp(r.getGpPerHour()),
				r.getPreviousTotal() > 0 ? LootCell.change((double) (r.getTotal() - r.getPreviousTotal()) / r.getPreviousTotal()) : LootCell.gp(Double.NaN),
				match.isBySource() ? bestItemText(r) : matchText(r, match),
			});
		}

		int shown = lootModel.getRowCount();
		boolean searching = !search.trim().isEmpty();
		lootTableTitle.setText(!searching ? "All sources (click for every item, double-click to chart)"
			: shown == 0 ? "No sources or items match \"" + search.trim() + "\" in this period"
			: shown + " of " + lootRows.size() + (lootRows.size() == 1 ? " source matches" : " sources match") + " \"" + search.trim() + "\"");

		if (lootSource != null)
		{
			selectLootSource(lootSource);
		}
		renderLootDetail();
	}

	private static String bestItemText(LootReport.Row r)
	{
		return r.getBestItem() == null ? "" : r.getBestItem() + " (" + Format.compact(r.getBestItemValue()) + ")";
	}

	private static String matchText(LootReport.Row r, LootReport.Match match)
	{
		StringBuilder sb = new StringBuilder("Match: ");
		int shown = Math.min(2, match.getItems().size());
		for (int i = 0; i < shown; i++)
		{
			String name = match.getItems().get(i);
			ItemTotal t = r.getItems().get(name);
			sb.append(i > 0 ? ", " : "").append(name).append(" x").append(Format.number(t.getQuantity()))
				.append(" (").append(Format.compact(t.getValue())).append(')');
		}
		if (match.getItems().size() > shown)
		{
			sb.append(" +").append(match.getItems().size() - shown).append(" more");
		}
		return sb.toString();
	}

	private static final class LootCell extends Number implements Comparable<LootCell>
	{
		private final double value;
		private final String text;

		private LootCell(double value, String text)
		{
			this.value = value;
			this.text = text;
		}

		static LootCell count(long n)
		{
			return new LootCell(n, Format.number(n));
		}

		static LootCell gp(double v)
		{
			return Double.isNaN(v) ? new LootCell(Double.NEGATIVE_INFINITY, "-") : new LootCell(v, Format.compact(Math.round(v)));
		}

		static LootCell percent(double fraction)
		{
			return new LootCell(fraction, String.format(Locale.ENGLISH, "%.1f%%", fraction * 100));
		}

		static LootCell change(double fraction)
		{
			return new LootCell(fraction, signedPercent(fraction));
		}

		@Override
		public int compareTo(LootCell o)
		{
			return Double.compare(value, o.value);
		}

		@Override
		public int intValue()
		{
			return (int) value;
		}

		@Override
		public long longValue()
		{
			return (long) value;
		}

		@Override
		public float floatValue()
		{
			return (float) value;
		}

		@Override
		public double doubleValue()
		{
			return value;
		}

		@Override
		public String toString()
		{
			return text;
		}
	}

	private void reload()
	{
		LocalDate today = LocalDate.now();
		switch ((Period) periodBox.getSelectedItem())
		{
			case LAST_7:
				from = today.minusDays(6);
				to = today;
				break;
			case LAST_90:
				from = today.minusDays(89);
				to = today;
				break;
			case THIS_WEEK:
				from = GoalPlanner.weekStart(today);
				to = today;
				break;
			case LAST_WEEK:
				from = GoalPlanner.weekStart(today).minusWeeks(1);
				to = from.plusDays(6);
				break;
			case THIS_MONTH:
				from = today.withDayOfMonth(1);
				to = today;
				break;
			case LAST_MONTH:
				from = today.minusMonths(1).withDayOfMonth(1);
				to = from.with(TemporalAdjusters.lastDayOfMonth());
				break;
			case THIS_YEAR:
				from = today.withDayOfYear(1);
				to = today;
				break;
			case ALL_TIME:
				from = service.firstDay();
				to = today;
				break;
			case CUSTOM:
			{
				LocalDate a = GoalPlanner.parseDate(fromField.getText().trim());
				LocalDate b = GoalPlanner.parseDate(toField.getText().trim());
				if (a == null || b == null || b.isBefore(a))
				{
					JOptionPane.showMessageDialog(this, "Enter two dates as YYYY-MM-DD, with the end on or after the start.",
						"RuneJourney", JOptionPane.WARNING_MESSAGE);
					return;
				}
				from = a;
				to = b;
				break;
			}
			default:
				from = today.minusDays(29);
				to = today;
		}

		LocalDate[] prev = Analytics.previousPeriod(from, to);
		List<DayRecord> all = service.daysBetween(prev[0], to);
		days = new ArrayList<>();
		previousDays = new ArrayList<>();
		for (DayRecord d : all)
		{
			(LocalDate.parse(d.getDate()).isBefore(from) ? previousDays : days).add(d);
		}
		rangeLabel.setText(Format.date(from) + " - " + Format.date(to));
		updateFilters();
		render();
	}

	private void updateFilters()
	{
		updating = true;
		try
		{
			Metric m = (Metric) metricBox.getSelectedItem();
			FilterOption previous = (FilterOption) filterBox.getSelectedItem();
			filterBox.removeAllItems();
			filterBox.addItem(ALL);
			TreeSet<String> keys = new TreeSet<>();
			for (DayRecord d : days)
			{
				keys.addAll(m.parts(d).keySet());
			}
			switch (m.getFilter())
			{
				case SKILL:
					for (Skill s : Skills.ALL)
					{
						if (keys.contains(s.name()))
						{
							filterBox.addItem(new FilterOption(s.name(), s.getName()));
						}
					}
					break;
				case CLUE_TIER:
					for (String tier : Counters.CLUE_TIERS)
					{
						if (keys.contains(tier))
						{
							filterBox.addItem(new FilterOption(tier, tier));
						}
					}
					break;
				case BOSS:
				case SOURCE:
					keys.forEach(k -> filterBox.addItem(new FilterOption(k, k)));
					break;
				default:
					break;
			}
			filterBox.setEnabled(m.getFilter() != Metric.Filter.NONE);
			filterBox.setSelectedItem(ALL);
			if (previous != null)
			{
				for (int i = 0; i < filterBox.getItemCount(); i++)
				{
					if (previous.equals(filterBox.getItemAt(i)))
					{
						filterBox.setSelectedIndex(i);
					}
				}
			}
		}
		finally
		{
			updating = false;
		}
	}

	private void openExplore(Metric metric, String filterKey)
	{
		updating = true;
		metricBox.setSelectedItem(metric);
		updating = false;
		updateFilters();
		if (filterKey != null)
		{
			for (int i = 0; i < filterBox.getItemCount(); i++)
			{
				if (filterKey.equals(filterBox.getItemAt(i).getKey()))
				{
					updating = true;
					filterBox.setSelectedIndex(i);
					updating = false;
				}
			}
		}
		render();
		tabs.setSelectedIndex(1);
	}

	private String selectedFilter()
	{
		FilterOption o = (FilterOption) filterBox.getSelectedItem();
		return o == null ? null : o.getKey();
	}

	private Color color(Metric m)
	{
		return COLORS.getOrDefault(m, Ui.GOLD);
	}

	private javax.swing.Icon iconFor(String key)
	{
		Skill s = Skills.parse(key);
		return s == null ? null : views.skillIcon(s);
	}

	private String labelFor(String key)
	{
		Skill s = Skills.parse(key);
		return s == null ? key : s.getName();
	}

	private void render()
	{
		if (from == null)
		{
			return;
		}
		renderExplore();
		renderOverview();
		renderLoot();
		renderRecords();
		revalidate();
		repaint();
	}

	private void renderExplore()
	{
		Metric m = (Metric) metricBox.getSelectedItem();
		String filter = selectedFilter();
		Granularity g = ((Granularity) groupBox.getSelectedItem()).resolve(from, to);
		ChartStyle style = (ChartStyle) styleBox.getSelectedItem();
		Unit unit = m.getUnit();

		String subject = m.getLabel() + (filter != null ? " · " + labelFor(filter) : "");
		exploreTitle.setText(subject + "   (" + Format.date(from) + " - " + Format.date(to) + ", by " + g.noun() + ")");

		List<Analytics.Bucket> series = Analytics.series(days, from, to, m, filter, g);
		List<Analytics.Bucket> previous = compareBox.isSelected() ? Analytics.previousSeries(previousDays, from, to, m, filter, g) : null;
		if (style == ChartStyle.CUMULATIVE)
		{
			series = Analytics.cumulative(series, m);
			previous = previous == null ? null : Analytics.cumulative(previous, m);
		}
		mainChart.setData(series, previous, unit, color(m), style == ChartStyle.BARS ? TimeSeriesChart.Style.BARS : TimeSeriesChart.Style.LINE);

		Analytics.Stats stats = Analytics.stats(days, from, to, m, filter, g, previousDays);
		exploreTiles.removeAll();
		if (m.isBalance())
		{
			exploreTiles.add(tile("Latest", unit.format(stats.getTotal()), null, null));
			double delta = stats.getPerActiveDay();
			exploreTiles.add(tile("Change in period", (delta >= 0 ? "+" : "-") + unit.format(Math.abs(delta)), null, null,
				delta >= 0 ? Ui.GOOD : Ui.BAD));
		}
		else
		{
			exploreTiles.add(tile(m.isPerHour() ? "Overall rate" : "Total", unit.format(stats.getTotal()), null, null));
			exploreTiles.add(tile(m.isPerHour() ? "Hours played" : "Per day played",
				m.isPerHour() ? Format.hours(days.stream().mapToDouble(Metric::hours).sum()) : unit.format(stats.getPerActiveDay()),
				stats.getActiveDays() + " days played", Ui.MUTED));
		}
		Analytics.Bucket best = stats.getBest();
		exploreTiles.add(tile("Best " + g.noun(), best == null ? "-" : unit.format(best.getValue()), best == null ? null : best.getLabel(), Ui.MUTED));
		double change = stats.change();
		exploreTiles.add(tile("Vs previous period", Double.isNaN(change) ? "-" : signedPercent(change),
			Double.isNaN(stats.getPreviousTotal()) ? "No earlier data" : "was " + unit.format(stats.getPreviousTotal()),
			Ui.MUTED, Double.isNaN(change) ? Color.WHITE : change >= 0 ? Ui.GOOD : Ui.BAD));

		boolean parts = m.getFilter() != Metric.Filter.NONE && filter == null && !m.isPerHour();
		if (parts)
		{
			leftTitle.setText("By " + m.getFilter().name().toLowerCase(Locale.ENGLISH).replace("clue_tier", "tier") + " (click to focus)");
			leftBars.setData(Analytics.breakdown(days, from, to, m), unit, color(m));
		}
		else
		{
			leftTitle.setText("Busiest " + g.noun() + "s");
			List<Analytics.Entry> top = series.stream()
				.filter(b -> b.getValue() > 0)
				.sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
				.limit(10)
				.map(b -> new Analytics.Entry(b.getLabel(), b.getLabel(), b.getValue()))
				.collect(Collectors.toList());
			if (style == ChartStyle.CUMULATIVE)
			{
				top = Analytics.series(days, from, to, m, filter, g).stream()
					.filter(b -> b.getValue() > 0)
					.sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
					.limit(10)
					.map(b -> new Analytics.Entry(b.getLabel(), b.getLabel(), b.getValue()))
					.collect(Collectors.toList());
			}
			leftBars.setData(top, unit, color(m));
		}
		rightTitle.setText(m.isPerHour() ? "Rate by day of week" : "Average by day of week");
		if (m.isBalance())
		{
			rightBars.setEmptyText("Not used for balances like net worth");
			rightBars.setData(Collections.emptyList(), unit, color(m));
		}
		else
		{
			rightBars.setData(Analytics.byWeekday(days, from, to, m, filter), unit, color(m));
		}

		List<Analytics.Bucket> raw = Analytics.series(days, from, to, m, filter, g);
		List<Analytics.Bucket> rawPrev = previous != null ? Analytics.previousSeries(previousDays, from, to, m, filter, g) : null;
		List<String> columns = new ArrayList<>();
		columns.add(g == Granularity.DAY ? "Day" : g == Granularity.WEEK ? "Week" : "Month");
		columns.add(m.getLabel());
		if (!m.isPerHour())
		{
			columns.add("Running total");
		}
		columns.add("Hours played");
		if (rawPrev != null)
		{
			columns.add("Previous period");
		}
		tableModel.setDataVector(new Object[0][0], columns.toArray());
		double[] running = new double[raw.size()];
		for (int i = 0; i < raw.size(); i++)
		{
			running[i] = raw.get(i).getValue() + (i > 0 ? running[i - 1] : 0);
		}
		for (int i = raw.size() - 1; i >= 0; i--)
		{
			Analytics.Bucket b = raw.get(i);
			List<Object> row = new ArrayList<>();
			row.add(b.getLabel());
			row.add(unit.format(b.getValue()));
			if (!m.isPerHour())
			{
				row.add(unit.format(running[i]));
			}
			row.add(Format.hours(b.getHours()));
			if (rawPrev != null)
			{
				row.add(i < rawPrev.size() ? unit.format(rawPrev.get(i).getValue()) : "");
			}
			tableModel.addRow(row.toArray());
		}

		highlightModel.clear();
		List<JourneyEvent> related = new ArrayList<>();
		for (DayRecord d : days)
		{
			for (JourneyEvent e : d.getEvents())
			{
				if (m.getRelatedEvents().contains(e.getType()) && matchesFilter(e, filter))
				{
					related.add(e);
				}
			}
		}
		related.sort((a, b) -> Long.compare(b.getTime(), a.getTime()));
		for (JourneyEvent e : related.subList(0, Math.min(200, related.size())))
		{
			String when = Instant.ofEpochMilli(e.getTime()).atZone(ZoneId.systemDefault()).format(EVENT_TIME);
			highlightModel.addElement(when + "  ·  " + e.getTitle() + (e.getDetail() != null ? "  ·  " + e.getDetail() : ""));
		}
		if (highlightModel.isEmpty())
		{
			highlightModel.addElement("Nothing notable recorded for this metric in this period.");
		}
	}

	private static boolean matchesFilter(JourneyEvent e, String filter)
	{
		if (filter == null)
		{
			return true;
		}
		if (e.getSkill() != null)
		{
			return filter.equals(e.getSkill());
		}
		String text = e.getTitle() + " " + (e.getDetail() == null ? "" : e.getDetail());
		return text.toLowerCase(Locale.ENGLISH).contains(filter.toLowerCase(Locale.ENGLISH));
	}

	private void renderOverview()
	{
		Metric[] tiles = {Metric.PLAYTIME, Metric.XP, Metric.INCOME, Metric.KILLS, Metric.LEVELS, Metric.CLUES,
			Metric.COLLECTION_LOG, Metric.CA_POINTS};
		overviewTiles.removeAll();
		for (Metric m : tiles)
		{
			Analytics.Stats s = Analytics.stats(days, from, to, m, null, Granularity.DAY, previousDays);
			double change = s.change();
			JPanel tile = tile(m.getLabel(), m.getUnit().format(s.getTotal()),
				Double.isNaN(change) ? " " : signedPercent(change) + " vs previous",
				Double.isNaN(change) ? Ui.MUTED : change >= 0 ? Ui.GOOD : Ui.BAD);
			Ui.clickable(tile, () -> openExplore(m, null));
			overviewTiles.add(tile);
		}

		Metric[] charts = {Metric.XP, Metric.PLAYTIME, Metric.INCOME, Metric.KILLS, Metric.CLUES, Metric.NET_WORTH};
		overviewCharts.removeAll();
		Granularity g = Granularity.AUTO.resolve(from, to);
		for (Metric m : charts)
		{
			TimeSeriesChart chart = new TimeSeriesChart(true);
			chart.setData(Analytics.series(days, from, to, m, null, g), null, m.getUnit(), color(m),
				m.isPerHour() || m.isBalance() ? TimeSeriesChart.Style.LINE : TimeSeriesChart.Style.BARS);
			chart.setOnClick(i -> openExplore(m, null));
			Analytics.Stats s = Analytics.stats(days, from, to, m, null, g, null);
			JLabel title = new JLabel(m.getLabel() + "  ·  " + m.getUnit().format(s.getTotal()));
			overviewCharts.add(cardWithTitle(title, chart));
		}

		xpBySkill.setData(Analytics.breakdown(days, from, to, Metric.XP), Unit.COUNT, color(Metric.XP));
		killsByBoss.setData(Analytics.breakdown(days, from, to, Metric.KILLS), Unit.COUNT, color(Metric.KILLS));

		double loot = 0;
		double clueLoot = 0;
		for (DayRecord d : days)
		{
			loot += d.getLootValue() - d.getClueLootValue();
			clueLoot += d.getClueLootValue();
		}
		List<Analytics.Entry> income = new ArrayList<>();
		if (loot > 0)
		{
			income.add(new Analytics.Entry("loot", "Drops & loot", loot));
		}
		if (clueLoot > 0)
		{
			income.add(new Analytics.Entry("clues", "Clue caskets", clueLoot));
		}
		for (Analytics.Entry e : Analytics.breakdown(days, from, to, Metric.SKILLING_INCOME))
		{
			if (e.getValue() > 0)
			{
				income.add(e);
			}
		}
		income.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
		incomeBySource.setData(income, Unit.GP, color(Metric.INCOME));
	}

	private void renderRecords()
	{
		recordsPanel.removeAll();
		recordsPanel.setLayout(new BoxLayout(recordsPanel, BoxLayout.Y_AXIS));
		recordsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		recordsPanel.setBorder(new EmptyBorder(14, 14, 14, 14));

		int[] streaks = service.playStreaks();
		JPanel top = new JPanel(new GridLayout(1, 2, 8, 0));
		top.setOpaque(false);
		top.add(tile("Current play streak", streaks[0] + (streaks[0] == 1 ? " day" : " days"),
			streaks[0] > 0 && streaks[0] >= streaks[1] ? "Your best ever!" : "Days in a row with any play", Ui.MUTED));
		top.add(tile("Best play streak", streaks[1] + (streaks[1] == 1 ? " day" : " days"), null, null));
		addRow(recordsPanel, top, 90);

		List<JourneyService.PersonalRecord> records = service.records();
		JPanel grid = new JPanel(new GridLayout(0, 3, 8, 8));
		grid.setOpaque(false);
		for (JourneyService.PersonalRecord r : records)
		{
			grid.add(tile(r.getTitle(), r.getValue(), r.getDate() == null ? " " : Format.date(r.getDate()), Ui.MUTED));
		}
		if (records.isEmpty())
		{
			grid.add(tile("No records yet", "-", "Play a little and your personal bests will appear here", Ui.MUTED));
		}
		addRow(recordsPanel, grid, 0);
		JLabel note = label("Records cover everything RuneJourney has recorded, whatever period you choose. Beating one adds it to your Journey.");
		addRow(recordsPanel, note, 0);
	}

	private static String signedPercent(double change)
	{
		return (change >= 0 ? "+" : "") + String.format(Locale.ENGLISH, "%.0f%%", change * 100);
	}

	private static JPanel tile(String label, String value, String sub, Color subColor)
	{
		return tile(label, value, sub, subColor, Color.WHITE);
	}

	private static JPanel tile(String label, String value, String sub, Color subColor, Color valueColor)
	{
		JPanel t = new JPanel(new GridLayout(0, 1, 0, 2));
		t.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		t.setBorder(new EmptyBorder(8, 12, 8, 12));
		JLabel l = new JLabel(label);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(Ui.MUTED);
		JLabel v = new JLabel(value);
		Font big = FontManager.getRunescapeBoldFont().deriveFont(Font.PLAIN, 22f);
		v.setFont(big);
		v.setForeground(valueColor);
		t.add(l);
		t.add(v);
		if (sub != null)
		{
			JLabel s = new JLabel(sub);
			s.setFont(FontManager.getRunescapeSmallFont());
			s.setForeground(subColor);
			t.add(s);
		}
		return t;
	}

	private String title()
	{
		Period p = (Period) periodBox.getSelectedItem();
		return p == Period.CUSTOM ? "Custom report" : p.getLabel();
	}

	private void exportHtml()
	{
		Metric m = (Metric) metricBox.getSelectedItem();
		ReportService.Focus focus = new ReportService.Focus(m, selectedFilter(), selectedFilter() == null ? null : labelFor(selectedFilter()),
			(Granularity) groupBox.getSelectedItem(), styleBox.getSelectedItem() == ChartStyle.LINE, compareBox.isSelected());
		ReportService.Report report = reports.build(title(), from, to);
		report.setFocus(focus);
		save("Save RuneJourney report", "Web page", "html", ".html", () -> reports.toHtml(report, true));
	}

	private void exportCsv()
	{
		List<DayRecord> data = new ArrayList<>(days);
		LocalDate a = from;
		LocalDate b = to;
		save("Save RuneJourney data", "CSV spreadsheet", "csv", ".csv", () -> CsvExport.export(data, a, b));
	}

	private void save(String dialogTitle, String filterName, String extension, String suffix, java.util.function.Supplier<String> content)
	{
		String name = "RuneJourney " + (service.playerName() != null ? service.playerName() + " " : "") + from
			+ (from.equals(to) ? "" : " to " + to) + suffix;
		List<Filepath> chosen = new Filepath.Chooser()
			.setIsSave()
			.setAcceptsFiles()
			.setDialogTitle(dialogTitle)
			.addExtensionFilter(filterName, extension)
			.setDefaultExtension(extension)
			.setFileName(name.replaceAll("[\\\\/:*?\"<>|]", "_"))
			.showDialog(this);
		if (chosen == null || chosen.isEmpty())
		{
			return;
		}
		Filepath file = chosen.get(0);
		ExecutorService executor = plugin.getExecutor();
		if (executor == null || executor.isShutdown())
		{
			return;
		}
		executor.submit(() ->
		{
			String message;
			int type = JOptionPane.INFORMATION_MESSAGE;
			try
			{
				file.write(content.get());
				message = "Saved " + file.getFileName() + ".";
			}
			catch (Exception ex)
			{
				message = "Couldn't save the file: " + ex.getMessage();
				type = JOptionPane.WARNING_MESSAGE;
			}
			String msg = message;
			int t = type;
			SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, msg, "RuneJourney", t));
		});
	}

	private void copySummary()
	{
		String text = reports.toText(reports.build(title(), from, to));
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
		JOptionPane.showMessageDialog(this, "Summary copied to the clipboard.", "RuneJourney", JOptionPane.INFORMATION_MESSAGE);
	}
}
