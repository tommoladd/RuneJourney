package com.runejourney.ui;

import com.runejourney.RuneJourneyPlugin;
import com.runejourney.model.JourneyEvent;
import com.runejourney.planner.Skills;
import com.runejourney.service.JourneyService;
import com.runejourney.service.JourneyStore;
import com.runejourney.service.SessionView;
import com.runejourney.util.Format;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.FontManager;

/**
 * Renderers shared between tabs.
 */
@Slf4j
@Singleton
class Views
{
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private final SkillIconManager skillIconManager;
	private final JourneyService service;
	private final JourneyStore store;
	private final RuneJourneyPlugin plugin;
	private final Map<Skill, ImageIcon> icons = new EnumMap<>(Skill.class);
	@Setter
	private Runnable onChange = () ->
	{
	};

	@Inject
	Views(SkillIconManager skillIconManager, JourneyService service, JourneyStore store, RuneJourneyPlugin plugin)
	{
		this.skillIconManager = skillIconManager;
		this.service = service;
		this.store = store;
		this.plugin = plugin;
	}

	ImageIcon skillIcon(Skill skill)
	{
		if (skill == null)
		{
			return null;
		}
		return icons.computeIfAbsent(skill, s -> new ImageIcon(skillIconManager.getSkillImage(s, true)));
	}

	/**
	 * A Journey event row. When {@code date} is given the entry can be deleted via right click.
	 */
	JPanel event(JourneyEvent e, String date)
	{
		JPanel card = Ui.accentCard(e.getType().getColor());

		// The stripe colour shows the type; the time sits beside the title to keep rows short
		String time = Instant.ofEpochMilli(e.getTime()).atZone(ZoneId.systemDefault()).format(TIME);
		Skill skill = Skills.parse(e.getSkill());
		JLabel title = Ui.label(e.getTitle(), FontManager.getRunescapeFont(), Color.WHITE, Ui.TEXT_WIDTH - (skill != null ? 52 : 32));
		if (skill != null)
		{
			title.setIcon(skillIcon(skill));
			title.setIconTextGap(5);
		}
		title.setToolTipText(e.getType().getLabel());
		JLabel when = Ui.small(time, Ui.MUTED);
		when.setVerticalAlignment(SwingConstants.TOP);
		JPanel top = new JPanel(new BorderLayout(4, 0));
		top.setOpaque(false);
		top.add(title, BorderLayout.CENTER);
		top.add(when, BorderLayout.EAST);
		card.add(top);
		card.setToolTipText(e.getType().getLabel());

		if (e.getDetail() != null)
		{
			card.add(Ui.muted(e.getDetail()));
		}
		if (e.getScreenshot() != null)
		{
			String name = e.getScreenshot();
			card.add(Ui.link("View screenshot", () -> showScreenshot(name, e.getTitle())));
		}

		if (date != null)
		{
			JPopupMenu menu = new JPopupMenu();
			JMenuItem delete = new JMenuItem("Remove from Journey");
			delete.addActionListener(a ->
			{
				int ok = JOptionPane.showConfirmDialog(card, "Remove \"" + e.getTitle() + "\" from your Journey?",
					"RuneJourney", JOptionPane.OK_CANCEL_OPTION);
				if (ok == JOptionPane.OK_OPTION)
				{
					service.deleteEvent(date, e.getTime(), e.getTitle());
					onChange.run();
				}
			});
			menu.add(delete);
			card.setComponentPopupMenu(menu);
		}
		return card;
	}

	private void showScreenshot(String name, String title)
	{
		String key = service.getProfileKey();
		ExecutorService executor = plugin.getExecutor();
		if (key == null || executor == null || executor.isShutdown())
		{
			return;
		}
		executor.submit(() ->
		{
			BufferedImage image;
			try
			{
				image = store.readScreenshot(key, name);
			}
			catch (IOException ex)
			{
				log.warn("Unable to read screenshot {}", name, ex);
				image = null;
			}
			BufferedImage result = image;
			SwingUtilities.invokeLater(() ->
			{
				if (result == null)
				{
					JOptionPane.showMessageDialog(null, "That screenshot could not be found.", "RuneJourney", JOptionPane.WARNING_MESSAGE);
					return;
				}
				Image scaled = result;
				if (result.getWidth() > 900)
				{
					scaled = result.getScaledInstance(900, result.getHeight() * 900 / result.getWidth(), Image.SCALE_SMOOTH);
				}
				JOptionPane.showMessageDialog(null, new JLabel(new ImageIcon(scaled)), title, JOptionPane.PLAIN_MESSAGE);
			});
		});
	}

	JPanel session(SessionView s)
	{
		JPanel card = Ui.accentCard(Ui.GOLD);
		card.add(Ui.row(Ui.small("Session in progress", Ui.GOLD), Ui.small(Format.duration(s.getActiveMillis()), Color.WHITE)));
		card.add(Ui.title(s.getGoalName()));
		card.add(Ui.stat("XP gained", "+" + Format.number(s.getXpGained())));
		card.add(Ui.stat("XP/hr", Format.number((long) s.getXpPerHour())));
		if (s.getCurrentProgress() > 0)
		{
			card.add(Ui.stat("Goal progress", Format.percent(s.getStartProgress()) + " -> " + Format.percent(s.getCurrentProgress())));
		}
		if (s.getWeekTarget() > 0)
		{
			card.add(Ui.stat("This week", Format.compact(s.getWeekAchieved()) + " / " + Format.compact(s.getWeekTarget()) + " XP"));
			card.add(Ui.progress(s.getWeekAchieved() / (double) s.getWeekTarget(), Ui.GOLD));
		}
		JPanel actions = new JPanel(new BorderLayout());
		actions.setOpaque(false);
		actions.setBorder(new EmptyBorder(4, 0, 0, 0));
		actions.add(Ui.button("End session", () ->
		{
			service.endSession();
			onChange.run();
		}), BorderLayout.CENTER);
		card.add(actions);
		return card;
	}
}
