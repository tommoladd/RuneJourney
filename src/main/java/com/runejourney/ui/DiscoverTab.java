package com.runejourney.ui;

import com.runejourney.service.JourneyService;
import com.runejourney.service.Suggestion;
import java.awt.BorderLayout;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * "What should I do right now?" suggestions driven by goals, weekly plans and nearby milestones.
 */
class DiscoverTab extends RefreshableTab
{
	@Getter
	@RequiredArgsConstructor
	enum TimeAvailable
	{
		MIN_15("I've got 15 minutes", 15),
		MIN_30("I've got 30 minutes", 30),
		HOUR_1("I've got an hour", 60),
		HOUR_2("I've got a couple of hours", 120);

		private final String label;
		private final int minutes;

		@Override
		public String toString()
		{
			return label;
		}
	}

	private final JourneyService service;
	private final Views views;
	private final JComboBox<TimeAvailable> timeBox = new JComboBox<>(TimeAvailable.values());
	private final JPanel body = Ui.stack(4);
	private int lastVersion = -1;
	private long lastRefresh;

	@Inject
	DiscoverTab(JourneyService service, Views views)
	{
		this.service = service;
		this.views = views;
		setLayout(new BorderLayout(0, 6));
		setOpaque(false);
		timeBox.setSelectedItem(TimeAvailable.MIN_30);
		Ui.styled(timeBox);
		timeBox.addActionListener(e -> refresh(true));
		add(timeBox, BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
	}

	@Override
	void refresh(boolean force)
	{
		// Suggestions don't need to follow every XP drop
		long now = System.currentTimeMillis();
		int version = service.getVersion();
		if (!force && (version == lastVersion || now - lastRefresh < 15_000))
		{
			return;
		}
		lastVersion = version;
		lastRefresh = now;

		Map<String, List<Suggestion>> sections = service.discover(((TimeAvailable) timeBox.getSelectedItem()).getMinutes());
		body.removeAll();
		for (Map.Entry<String, List<Suggestion>> section : sections.entrySet())
		{
			body.add(Ui.header(section.getKey()));
			if (section.getValue().isEmpty())
			{
				body.add(Ui.empty(emptyText(section.getKey())));
				continue;
			}
			for (Suggestion s : section.getValue())
			{
				body.add(card(s));
			}
		}
		rebuild();
	}

	private static String emptyText(String section)
	{
		if (section.startsWith("Bossing"))
		{
			return "Kill some bosses or complete clues and RuneJourney will suggest trips that fit your time, "
				+ "favouring kill count milestones you are close to.";
		}
		if (section.startsWith("Work on"))
		{
			return "Create a goal with a weekly plan and RuneJourney will suggest what to work on with the time you have.";
		}
		if (section.startsWith("Your rates"))
		{
			return "Train a skill for 30 minutes or more and RuneJourney will start using your real XP rates.";
		}
		return "Nothing close right now. Keep going!";
	}

	private JPanel card(Suggestion s)
	{
		JPanel card = Ui.card();
		JLabel title = Ui.title(s.getTitle());
		if (s.getSkill() != null)
		{
			title.setIcon(views.skillIcon(s.getSkill()));
		}
		card.add(title);
		card.add(Ui.text(s.getDetail()));
		if (s.getProgress() >= 0)
		{
			card.add(Ui.progress(s.getProgress(), Ui.GOLD));
		}
		return card;
	}
}
