package com.runejourney.ui;

import com.runejourney.model.DayRecord;
import com.runejourney.model.EventType;
import com.runejourney.model.JourneyEvent;
import com.runejourney.service.JourneyService;
import com.runejourney.util.Format;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;

/**
 * The chronological scrapbook of the account.
 */
class JourneyTab extends RefreshableTab
{
	private static final int PAGE_DAYS = 14;
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);

	@Getter
	@RequiredArgsConstructor
	enum Filter
	{
		ALL("Everything", null, false),
		HIGHLIGHTS("Highlights only", null, true),
		SKILLS("Skills", EventType.Category.SKILLS, false),
		PVM("PvM", EventType.Category.PVM, false),
		LOOT("Drops & collection log", EventType.Category.LOOT, false),
		ACCOUNT("Quests, diaries & memories", EventType.Category.ACCOUNT, false),
		GOALS("Goals & sessions", EventType.Category.GOALS, false);

		private final String label;
		private final EventType.Category category;
		private final boolean highlightsOnly;

		@Override
		public String toString()
		{
			return label;
		}
	}

	private final JourneyService service;
	private final Views views;
	private final ItemIndex itemIndex;
	private final ItemManager itemManager;
	private final ScreenshotWindowManager screenshotWindows;
	private final JComboBox<Filter> filterBox = new JComboBox<>(Filter.values());
	private final JPanel body = Ui.stack(4);
	private int days = PAGE_DAYS;
	private int lastEventVersion = -1;

	@Inject
	JourneyTab(JourneyService service, Views views, ItemIndex itemIndex, ItemManager itemManager,
		ScreenshotWindowManager screenshotWindows)
	{
		this.screenshotWindows = screenshotWindows;
		this.itemIndex = itemIndex;
		this.itemManager = itemManager;
		this.service = service;
		this.views = views;
		setLayout(new BorderLayout(0, 6));
		setOpaque(false);

		JButton note = Ui.button("+ Memory", this::addMemory);
		note.setToolTipText("Add your own entry: a drop, kill, pet, level, quest or anything else, on any date");
		Ui.styled(filterBox);

		JPanel controls = new JPanel(new BorderLayout(4, 0));
		controls.setOpaque(false);
		controls.add(filterBox, BorderLayout.CENTER);
		controls.add(note, BorderLayout.EAST);
		filterBox.addActionListener(e ->
		{
			days = PAGE_DAYS;
			refresh(true);
		});

		JButton gallery = Ui.button("Screenshots", screenshotWindows::open);
		gallery.setToolTipText("View, save or delete the screenshots RuneJourney has taken");
		JPanel top = new JPanel(new GridLayout(0, 1, 0, 4));
		top.setOpaque(false);
		top.add(controls);
		top.add(gallery);

		add(top, BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
	}

	private void addMemory()
	{
		if (!service.isReady())
		{
			return;
		}
		MemoryDialog.Result result = MemoryDialog.show(this, itemIndex, itemManager, service.knownBosses());
		if (result != null)
		{
			service.addMemories(result.getDate(), result.getEvents(), result.getItems());
			refresh(true);
		}
	}

	@Override
	void refresh(boolean force)
	{
		// The timeline can be long, so only rebuild it when events change
		int version = service.getEventVersion();
		if (!force && version == lastEventVersion)
		{
			return;
		}
		lastEventVersion = version;

		Filter filter = (Filter) filterBox.getSelectedItem();
		List<DayRecord> records = service.journeyDays(days + 1, filter.getCategory(), filter.isHighlightsOnly());
		body.removeAll();

		if (records.isEmpty())
		{
			body.add(Ui.empty("Your Journey is empty for now. Levels, drops, boss kills, quests and milestones "
				+ "will appear here automatically as you play."));
			rebuild();
			return;
		}

		for (int i = 0; i < Math.min(days, records.size()); i++)
		{
			DayRecord d = records.get(i);
			body.add(dayHeader(d));
			for (JourneyEvent e : d.getEvents())
			{
				body.add(views.event(e, d.getDate()));
			}
		}

		if (records.size() > days)
		{
			JButton more = new JButton("Show older days");
			more.addActionListener(e ->
			{
				days += PAGE_DAYS;
				refresh(true);
			});
			body.add(more);
		}
		rebuild();
	}

	private JPanel dayHeader(DayRecord d)
	{
		JPanel p = new JPanel(new GridLayout(0, 1));
		p.setOpaque(false);
		p.setBorder(new EmptyBorder(10, 2, 2, 0));
		LocalDate date = LocalDate.parse(d.getDate());
		String name = date.equals(LocalDate.now()) ? "Today" : date.equals(LocalDate.now().minusDays(1)) ? "Yesterday" : date.format(DAY);
		JLabel title = new JLabel(name);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Ui.GOLD);
		p.add(title);

		StringBuilder summary = new StringBuilder();
		if (d.getPlayMillis() > 0)
		{
			summary.append(Format.duration(d.getPlayMillis()));
		}
		if (d.getXpGained() > 0)
		{
			summary.append(summary.length() > 0 ? " · " : "").append(Format.compact(d.getXpGained())).append(" XP");
		}
		if (d.getLootValue() > 0)
		{
			summary.append(summary.length() > 0 ? " · " : "").append(Format.compact(d.getLootValue())).append(" loot");
		}
		if (summary.length() > 0)
		{
			p.add(Ui.muted(summary.toString()));
		}
		return p;
	}
}
