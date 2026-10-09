package com.runejourney.ui;

import com.runejourney.cloud.SyncManager;
import com.runejourney.service.JourneyService;
import com.runejourney.util.Format;
import java.awt.*;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.*;
import net.runelite.client.ui.components.materialtabs.*;

public class RuneJourneyPanel extends PluginPanel
{
	private final JourneyService service;
	private final Views views;
	private final TodayTab today;
	private final GoalsTab goals;
	private final JourneyTab journey;
	private final DiscoverTab discover;

	private final JLabel subtitle = new JLabel();
	private final JPanel loggedOut;
	private final JPanel display = new JPanel(new BorderLayout());
	private final JPanel content = new JPanel(new BorderLayout());
	private final JPanel offers = Ui.stack(4);
	private final CloudSection cloud;
	private final SyncManager sync;
	private List<JourneyService.MethodOffer> shownOffers = Collections.emptyList();
	private RefreshableTab selected;

	@Inject
	RuneJourneyPanel(JourneyService service, Views views, TodayTab today, GoalsTab goals, JourneyTab journey, DiscoverTab discover,
		CloudSection cloud, SyncManager sync)
	{
		this.service = service;
		this.views = views;
		this.today = today;
		this.goals = goals;
		this.journey = journey;
		this.discover = discover;
		this.cloud = cloud;
		this.sync = sync;
		views.setOnChange(() -> refresh(true));
		sync.setOnChange(() -> SwingUtilities.invokeLater(() -> refresh(false)));

		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel header = new JPanel(new BorderLayout());
		header.setOpaque(false);
		header.setBorder(new EmptyBorder(0, 2, 6, 2));
		JLabel title = new JLabel("RuneJourney");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Ui.GOLD);
		header.add(title, BorderLayout.WEST);
		subtitle.setFont(FontManager.getRunescapeSmallFont());
		subtitle.setForeground(Ui.MUTED);
		header.add(subtitle, BorderLayout.EAST);
		header.add(cloud.link, BorderLayout.SOUTH);

		display.setOpaque(false);
		MaterialTabGroup tabs = new MaterialTabGroup(display);
		tabs.setLayout(new GridLayout(2, 2, 4, 4));
		tabs.setBorder(new EmptyBorder(0, 0, 6, 0));
		MaterialTab todayTab = tab(tabs, "Today", today);
		tab(tabs, "My Goals", goals);
		tab(tabs, "My Journey", journey);
		tab(tabs, "Advisor", discover);

		JPanel heading = new JPanel(new BorderLayout());
		heading.setOpaque(false);
		heading.add(header, BorderLayout.NORTH);
		heading.add(cloud.card, BorderLayout.SOUTH);
		JPanel below = new JPanel(new BorderLayout());
		below.setOpaque(false);
		below.add(cloud.prompts, BorderLayout.NORTH);
		offers.setBorder(new EmptyBorder(0, 0, 6, 0));
		offers.setVisible(false);
		below.add(offers, BorderLayout.SOUTH);

		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(heading, BorderLayout.NORTH);
		top.add(tabs, BorderLayout.CENTER);
		top.add(below, BorderLayout.SOUTH);

		loggedOut = Ui.empty("Log in to start recording your journey. Your levels, drops, boss kills and "
			+ "milestones will be recorded automatically while you play.");

		content.setOpaque(false);
		content.add(top, BorderLayout.NORTH);
		content.add(display, BorderLayout.CENTER);
		add(content, BorderLayout.NORTH);

		tabs.select(todayTab);
	}

	private MaterialTab tab(MaterialTabGroup group, String name, RefreshableTab panel)
	{
		MaterialTab tab = new MaterialTab(name, group, panel);
		tab.setHorizontalAlignment(SwingConstants.CENTER);
		tab.setFont(FontManager.getRunescapeFont());
		tab.setOnSelectEvent(() ->
		{
			selected = panel;
			panel.refresh(true);
			return true;
		});
		group.addTab(tab);
		return tab;
	}

	public void refresh(boolean force)
	{
		boolean ready = service.isReady();
		String name = service.playerName();
		subtitle.setText(name != null ? name : "");

		cloud.refresh();
		showOffers(ready ? service.methodOffers() : Collections.emptyList());
		if (!ready)
		{
			if (loggedOut.getParent() == null)
			{
				content.remove(display);
				content.add(loggedOut, BorderLayout.CENTER);
				content.revalidate();
				content.repaint();
			}
			return;
		}
		if (loggedOut.getParent() != null)
		{
			content.remove(loggedOut);
			content.add(display, BorderLayout.CENTER);
			content.revalidate();
			force = true;
		}

		if (selected != null && (isShowing() || force))
		{
			selected.refresh(force);
		}
	}

	private void showOffers(List<JourneyService.MethodOffer> list)
	{
		if (list.equals(shownOffers))
		{
			return;
		}
		shownOffers = list;
		offers.removeAll();
		for (JourneyService.MethodOffer o : list)
		{
			offers.add(offerCard(o));
		}
		offers.setVisible(!list.isEmpty());
		offers.revalidate();
		offers.repaint();
	}

	private JPanel offerCard(JourneyService.MethodOffer o)
	{
		JPanel card = Ui.accentCard(Ui.GOLD);
		JLabel title = Ui.title("New " + o.getSkill().getName() + " method");
		title.setIcon(views.skillIcon(o.getSkill()));
		title.setIconTextGap(5);
		card.add(title);
		card.add(Ui.text(Format.compact(o.getXpPerHour()) + " XP/hr over " + Format.duration(o.getMillis())
			+ " of training. Save it to plan your goals at this rate."));
		JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
		buttons.setOpaque(false);
		buttons.add(Ui.button("Save...", () -> save(o)));
		buttons.add(Ui.button("Dismiss", () ->
		{
			service.dismissDetectedMethod(o.getSkill());
			refresh(true);
		}));
		card.add(buttons);
		return card;
	}

	private void save(JourneyService.MethodOffer o)
	{
		JComboBox<String> name = new JComboBox<>(o.getSavedNames().toArray(new String[0]));
		name.setEditable(true);
		name.setSelectedItem(o.getSuggestedName() != null ? o.getSuggestedName() : "");
		String prompt = "Name this " + o.getSkill().getName() + " method (" + Format.compact(o.getXpPerHour()) + " XP/hr)."
			+ (o.getSavedNames().isEmpty() ? "" : "\nPick one of your saved methods to replace it with this rate.");
		int ok = JOptionPane.showConfirmDialog(this, new Object[]{prompt, name}, "RuneJourney",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (ok != JOptionPane.OK_OPTION)
		{
			return;
		}
		Object typed = name.getEditor().getItem();
		String error = service.saveDetectedMethod(o.getSkill(), typed == null ? null : typed.toString());
		if (error != null)
		{
			JOptionPane.showMessageDialog(this, error, "RuneJourney", JOptionPane.WARNING_MESSAGE);
		}
		refresh(true);
	}

	@Override
	public void onActivate()
	{
		refresh(true);
		sync.onPanelOpened();
	}
}
