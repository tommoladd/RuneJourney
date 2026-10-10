package com.runejourney.ui;

import com.runejourney.RuneJourneyConfig;
import com.runejourney.model.*;
import com.runejourney.planner.*;
import com.runejourney.service.*;
import com.runejourney.util.Format;
import java.awt.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.function.LongFunction;
import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;

class GoalsTab extends RefreshableTab
{
	private final JourneyService service;
	private final RuneJourneyConfig config;
	private final Views views;
	private final ItemIndex itemIndex;
	private final ItemManager itemManager;
	private final JPanel body = Ui.stack(6);
	private final JButton newGoal;
	private final List<JComboBox<?>> combos = new ArrayList<>();

	private String openGoalId;
	private int lastVersion = -1;

	@Inject
	GoalsTab(JourneyService service, RuneJourneyConfig config, Views views, ItemIndex itemIndex, ItemManager itemManager)
	{
		this.service = service;
		this.config = config;
		this.views = views;
		this.itemIndex = itemIndex;
		this.itemManager = itemManager;
		setLayout(new BorderLayout(0, 6));
		setOpaque(false);

		newGoal = Ui.button("+ New goal", this::newGoal);
		add(newGoal, BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
	}

	private NewGoalDialog.Context dialogContext()
	{
		return new NewGoalDialog.Context(config.hoursPerWeek(), service.knownBosses(), service.counterValues(), itemIndex, itemManager,
			service.isBankCashKnown(), service.collectionLogPages());
	}

	private void newGoal()
	{
		NewGoalDialog.Result result = NewGoalDialog.create(this, dialogContext());
		if (result == null)
		{
			return;
		}
		if (result.getCombatAchievementPoints() != null)
		{
			service.setCombatAchievementPoints(result.getCombatAchievementPoints());
		}
		Goal goal = result.getGoal();
		String error = service.createGoal(goal);
		if (error != null)
		{
			JOptionPane.showMessageDialog(this, error, "RuneJourney", JOptionPane.WARNING_MESSAGE);
		}
		else
		{
			openGoalId = goal.getId();
		}
		refresh(true);
	}

	private void open(String id)
	{
		openGoalId = id;
		refresh(true);
	}

	@Override
	void refresh(boolean force)
	{
		int version = service.getVersion();
		if (!force && version == lastVersion)
		{
			return;
		}
		for (JComboBox<?> c : combos)
		{
			if (c.isPopupVisible())
			{
				return;
			}
		}
		lastVersion = version;
		combos.clear();
		body.removeAll();

		List<GoalProgress> goals = service.goalProgress();
		GoalProgress open = null;
		for (GoalProgress p : goals)
		{
			if (p.getGoal().getId().equals(openGoalId))
			{
				open = p;
			}
		}

		if (open != null)
		{
			newGoal.setVisible(false);
			detail(open);
		}
		else
		{
			openGoalId = null;
			newGoal.setVisible(true);
			list(goals);
		}
		rebuild();
	}

	private void list(List<GoalProgress> goals)
	{
		SessionView session = service.sessionView();
		if (session != null)
		{
			body.add(views.session(session));
		}

		boolean anyActive = false;
		for (GoalProgress p : goals)
		{
			if (!p.isComplete())
			{
				if (!anyActive)
				{
					body.add(Ui.header("Working towards"));
					anyActive = true;
				}
				body.add(summaryCard(p));
			}
		}
		if (!anyActive)
		{
			body.add(Ui.empty("What are you working towards? Create a goal such as 99 Slayer, 2,000 total or "
				+ "Max Cape and RuneJourney will build a weekly plan around your available time."));
		}

		boolean anyComplete = false;
		for (int i = goals.size() - 1; i >= 0; i--)
		{
			GoalProgress p = goals.get(i);
			if (p.isComplete())
			{
				if (!anyComplete)
				{
					body.add(Ui.header("Achieved"));
					anyComplete = true;
				}
				body.add(summaryCard(p));
			}
		}
	}

	private JPanel summaryCard(GoalProgress p)
	{
		Goal g = p.getGoal();
		JPanel card = Ui.card();
		card.add(goalTitle(g));

		if (p.isComplete())
		{
			card.add(Ui.small("Achieved " + Format.date(toDate(g.getCompletedAt())), Ui.GOOD));
		}
		else if (g.getType() == GoalType.CUSTOM)
		{
			if (g.getNotes() != null && !g.getNotes().isEmpty())
			{
				card.add(Ui.muted(g.getNotes()));
			}
			if (p.getTargetDate() != null)
			{
				card.add(Ui.small(targetText(p), Ui.MUTED));
			}
		}
		else
		{
			card.add(Ui.progress(p.getPercent(), Ui.GOLD));
			card.add(Ui.row(Ui.small(Format.percent(p.getPercent()), Color.WHITE), Ui.small(remainingText(p), Ui.MUTED)));
			JPanel status = statusLine(p);
			if (status != null)
			{
				card.add(status);
			}
			if (p.getWeekTarget() > 0)
			{
				card.add(Ui.row(Ui.small("This week", Ui.MUTED),
					Ui.small(Format.compact(p.getWeekAchieved()) + " / " + Format.compact(p.getWeekTarget()) + " XP",
						p.getWeekAchieved() >= p.getWeekTarget() ? Ui.GOOD : Color.WHITE)));
			}
			else if (p.getCountWeekTarget() > 0)
			{
				card.add(Ui.row(Ui.small("This week", Ui.MUTED),
					Ui.small(countText(g.getCounter(), p.getCountWeekAchieved(), p.getCountWeekTarget()),
						p.getCountWeekAchieved() >= p.getCountWeekTarget() ? Ui.GOOD : Color.WHITE)));
			}
		}
		Ui.clickable(card, () -> open(g.getId()));
		return card;
	}

	private static boolean showStatus(GoalProgress p)
	{
		GoalProgress.Status s = p.getStatus();
		return s == GoalProgress.Status.READY || s == GoalProgress.Status.COMPLETE || s == GoalProgress.Status.OVERDUE
			|| (p.getTargetDate() != null && s != GoalProgress.Status.NO_TARGET && s != GoalProgress.Status.TRACKING);
	}

	private JLabel goalTitle(Goal g)
	{
		boolean itemIcon = (g.getType() == GoalType.PURCHASE || g.getType() == GoalType.ITEMS) && !g.getItems().isEmpty();
		JLabel name = Ui.label(g.getName(), FontManager.getRunescapeFont(), Color.WHITE,
			Ui.TEXT_WIDTH - (itemIcon ? 44 : Skills.parse(g.getSkill()) != null ? 24 : 0));
		Skill skill = Skills.parse(g.getSkill());
		if (skill != null)
		{
			name.setIcon(views.skillIcon(skill));
			name.setIconTextGap(5);
		}
		else if ((g.getType() == GoalType.PURCHASE || g.getType() == GoalType.ITEMS) && !g.getItems().isEmpty()
			&& g.getItems().get(0).getId() > 0)
		{
			itemManager.getImage(g.getItems().get(0).getId()).addTo(name);
			name.setIconTextGap(5);
		}
		return name;
	}

	private JPanel statusLine(GoalProgress p)
	{
		if (!showStatus(p))
		{
			return p.getProjectedCompletion() == null ? null
				: Ui.row(Ui.small("Estimated finish", Ui.MUTED), Ui.small(Format.date(p.getProjectedCompletion()), Ui.MUTED));
		}
		String right = p.getTargetDate() != null ? targetText(p) : p.getProjectedCompletion() != null
			? "ETA " + Format.date(p.getProjectedCompletion()) : "";
		String left = p.getStatus() == GoalProgress.Status.NO_TARGET ? "No target date" : p.getStatus().getLabel();
		return Ui.row(Ui.small(left, statusColor(p.getStatus())), Ui.small(right, Ui.MUTED));
	}

	private static String targetText(GoalProgress p)
	{
		if (p.getDaysLeft() < 0)
		{
			return "Target " + Format.date(p.getTargetDate());
		}
		return p.getDaysLeft() + (p.getDaysLeft() == 1 ? " day left" : " days left");
	}

	private static String remainingText(GoalProgress p)
	{
		Goal g = p.getGoal();
		String time = p.getHoursRemaining() > 0 ? " · ~" + Format.hours(p.getHoursRemaining()) : "";
		if (g.getType() == GoalType.TOTAL_LEVEL)
		{
			return p.getLevelsRemaining() + " levels" + time;
		}
		if (g.getType() == GoalType.ITEMS)
		{
			return p.getItemsObtained() + " / " + p.getItemsTotal() + " items";
		}
		if (g.getType().isCounter())
		{
			if (g.isRelative())
			{
				return countText(g.getCounter(), p.getCountGained(), g.getTargetCount() - g.getStartCount()) + time;
			}
			return countText(g.getCounter(), p.getCountCurrent(), p.getCountTarget()) + time;
		}
		return Format.compact(p.getXpRemaining()) + " XP" + time;
	}

	private void detail(GoalProgress p)
	{
		Goal g = p.getGoal();
		JLabel back = Ui.link("< Back to goals", () -> open(null));
		back.setBorder(new EmptyBorder(0, 2, 0, 0));
		body.add(back);

		SessionView session = service.sessionView();
		boolean sessionHere = session != null && g.getId().equals(session.getGoalId());
		if (sessionHere)
		{
			body.add(views.session(session));
		}

		body.add(headerCard(p));
		body.add(actions(p, sessionHere));

		if (p.isComplete())
		{
			if (!g.getStory().isEmpty())
			{
				JPanel story = Ui.section("Your journey to " + g.getName());
				for (String line : g.getStory())
				{
					String[] parts = line.split("\\|", 2);
					story.add(parts.length == 2 ? Ui.stat(parts[0], parts[1]) : Ui.text(line));
				}
				body.add(story);
			}
			return;
		}
		if (g.getType() == GoalType.CUSTOM)
		{
			return;
		}
		if (g.getType() == GoalType.ITEMS)
		{
			body.add(itemChecklist(p));
			return;
		}
		if (g.getType().isCounter())
		{
			body.add(counterOverview(p));
			if (Counters.isClogPage(g.getCounter()))
			{
				body.add(clogMissing(Counters.suffix(g.getCounter())));
			}
			if (p.getCountWeekTarget() > 0)
			{
				body.add(counterWeek(p));
			}
			if (!g.getLastWeekResults().isEmpty())
			{
				body.add(lastWeek(g));
			}
			return;
		}

		body.add(overview(p));
		if (!p.getWeek().isEmpty())
		{
			body.add(thisWeek(p));
		}
		if (!g.getLastWeekResults().isEmpty())
		{
			body.add(lastWeek(g));
		}
		if (!p.getSkills().isEmpty())
		{
			body.add(trainingPlan(p));
		}
	}

	private JPanel headerCard(GoalProgress p)
	{
		Goal g = p.getGoal();
		Color color = statusColor(p.getStatus());
		JPanel card = Ui.accentCard(color);
		card.add(goalTitle(g));

		if (p.isComplete())
		{
			card.add(Ui.small("Achieved " + Format.date(toDate(g.getCompletedAt())), Ui.GOOD));
			return card;
		}
		if (g.getType() == GoalType.CUSTOM)
		{
			if (g.getNotes() != null && !g.getNotes().isEmpty())
			{
				card.add(Ui.text(g.getNotes()));
			}
			if (p.getTargetDate() != null)
			{
				card.add(Ui.stat("Target", Format.date(p.getTargetDate())));
			}
			card.add(Ui.muted("Mark this goal complete when you've achieved it."));
			return card;
		}

		card.add(Ui.progress(p.getPercent(), Ui.GOLD));
		card.add(Ui.row(Ui.small(Format.percent(p.getPercent()) + " complete", Color.WHITE), Ui.small(remainingText(p), Ui.MUTED)));
		if (showStatus(p))
		{
			card.add(Ui.small(p.getStatus().getLabel(), color));
		}
		String explanation = statusExplanation(p);
		if (!explanation.isEmpty())
		{
			card.add(Ui.text(explanation));
		}
		return card;
	}

	private static String statusExplanation(GoalProgress p)
	{
		String eta = p.getProjectedCompletion() == null ? null : Format.date(p.getProjectedCompletion());
		String basis = p.isProjectionFromPace() ? "At your current pace" : "At " + p.getAvailableHoursPerWeek() + "h/week";
		switch (p.getStatus())
		{
			case ON_TRACK:
				return eta == null ? "" : basis + " you'll finish around " + eta + ", before your target of "
					+ Format.date(p.getTargetDate()) + ".";
			case SLIGHTLY_BEHIND:
			case BEHIND:
				if (p.getRequiredHoursPerWeek() <= 0)
				{
					return "Reaching this by " + Format.date(p.getTargetDate()) + " needs about "
						+ perWeek(p) + "." + (eta != null ? " " + basis + " you'd finish around " + eta + "." : "");
				}
				return "Reaching this by " + Format.date(p.getTargetDate()) + " needs about "
					+ Format.hours(p.getRequiredHoursPerWeek()) + "/week; you planned " + p.getAvailableHoursPerWeek() + "h/week."
					+ (eta != null ? " " + basis + " you'd finish around " + eta + "." : "");
			case TRACKING:
				if (Counters.isMoney(p.getGoal().getCounter()))
				{
					return "RuneJourney will estimate a finish date once it has seen a few days of your saving"
						+ (p.getRequiredPerWeek() > 0 ? ". You need to save about " + perWeek(p) + "." : ".");
				}
				if (p.getGoal().getType() == GoalType.ITEMS)
				{
					return "Drops are random, so there's no estimate. Items tick off automatically when you get them"
						+ (p.getTargetDate() != null ? "; " + p.getDaysLeft() + " days left." : ".");
				}
				return "Keep playing and RuneJourney will project a finish date from your pace"
					+ (p.getRequiredPerWeek() > 0 ? "; you need about " + perWeek(p) + "." : ".");
			case OVERDUE:
				return "The target date has passed. Edit the goal to set a new one.";
			case READY:
				return "You have enough! Buy it on the Grand Exchange and this goal completes automatically.";
			default:
				return eta != null ? basis + " you'd finish around " + eta + "." : "";
		}
	}

	private JPanel actions(GoalProgress p, boolean sessionHere)
	{
		Goal g = p.getGoal();
		JPanel actions = new JPanel(new GridLayout(0, 2, 4, 4));
		actions.setOpaque(false);

		if (!p.isComplete())
		{
			actions.add(Ui.button("Edit", () -> edit(g.getId())));
			if (sessionHere)
			{
				actions.add(Ui.button("End session", () ->
				{
					service.endSession();
					refresh(true);
				}));
			}
			else if (g.getType() != GoalType.MONEY && g.getType() != GoalType.PURCHASE && g.getType() != GoalType.ITEMS)
			{
				JButton start = Ui.button("Start session", () ->
				{
					service.startSession(g.getId());
					refresh(true);
				});
				start.setToolTipText("Track a play session towards this goal");
				actions.add(start);
			}
			if (g.getType() == GoalType.PURCHASE)
			{
				JButton bought = Ui.button("Mark as bought", () ->
				{
					service.completeGoalManually(g.getId());
					refresh(true);
				});
				bought.setToolTipText("For purchases outside the Grand Exchange; GE purchases are picked up automatically");
				actions.add(bought);
			}
			if (g.getType() == GoalType.CUSTOM)
			{
				actions.add(Ui.button("Mark complete", () ->
				{
					service.completeGoalManually(g.getId());
					refresh(true);
				}));
			}
		}
		if (!p.isComplete())
		{
			boolean pinned = g.getId().equals(service.overlayGoalId());
			JButton pin = Ui.button(pinned ? "Unpin overlay" : "Show on overlay", () ->
			{
				service.setOverlayGoal(pinned ? null : g.getId());
				refresh(true);
			});
			pin.setToolTipText(config.showOverlay() ? "Show this goal on the in-game overlay"
				: "Show this goal on the in-game overlay (turn the overlay on in RuneJourney's settings)");
			actions.add(pin);
		}
		actions.add(Ui.button("Delete", () ->
		{
			String message = p.isComplete()
				? "Remove \"" + g.getName() + "\"? Its Journey entries are kept."
				: "Delete the goal \"" + g.getName() + "\"?";
			int ok = JOptionPane.showConfirmDialog(this, message, "RuneJourney", JOptionPane.OK_CANCEL_OPTION);
			if (ok == JOptionPane.OK_OPTION)
			{
				service.deleteGoal(g.getId());
				open(null);
			}
		}));
		return actions;
	}

	private static String countText(String key, long have, long target)
	{
		if (Counters.isMoney(key))
		{
			return Format.compact(have) + " / " + Counters.format(key, target);
		}
		return Format.number(have) + " / " + Format.number(target) + " " + Counters.unit(key);
	}

	private static String perWeek(GoalProgress p)
	{
		if (Counters.isMoney(p.getGoal().getCounter()))
		{
			return Counters.format(Counters.CASH, Math.round(p.getRequiredPerWeek())) + " a week";
		}
		double v = p.getRequiredPerWeek();
		String unit = p.getUnit() != null ? p.getUnit() : p.getGoal().getType() == GoalType.ITEMS ? "items" : "";
		return (v >= 10 ? String.valueOf(Math.round(v)) : String.format(Locale.ENGLISH, "%.1f", v)) + " " + unit + " a week";
	}

	private JPanel counterOverview(GoalProgress p)
	{
		Goal g = p.getGoal();
		JPanel card = Ui.section("Overview");
		if (!Counters.isMoney(g.getCounter()))
		{
			card.add(Ui.stat("Tracking", Counters.label(g.getCounter())));
		}
		if (g.getType() == GoalType.PURCHASE && !g.getItems().isEmpty())
		{
			GoalItem item = g.getItems().get(0);
			JLabel buying = Ui.small((g.getQuantity() > 1 ? Format.number(g.getQuantity()) + " x " : "") + item.getName(), Color.WHITE);
			if (item.getId() > 0)
			{
				itemManager.getImage(item.getId()).addTo(buying);
			}
			card.add(Ui.row(Ui.small("Buying", Ui.MUTED), buying));
			card.add(Ui.stat("Price", Counters.format(Counters.CASH, g.getTargetCount())
				+ (g.isFixedPrice() ? " (your price)" : " (GE)")));
			if (g.getQuantity() > 1 || g.getPurchasedQuantity() > 0)
			{
				card.add(Ui.stat("Bought so far", g.getPurchasedQuantity() + " / " + Math.max(1, g.getQuantity())));
			}
		}
		card.add(Ui.stat("Started", Format.date(toDate(g.getCreatedAt()))));
		String key = g.getCounter();
		if (Counters.isMoney(key))
		{
			card.add(Ui.stat(Counters.WEALTH.equals(key) ? "Net worth" : "Cash stack", Counters.format(key, p.getCountCurrent())));
			if (g.getType() == GoalType.MONEY)
			{
				card.add(Ui.stat("Target", Counters.format(key, p.getCountTarget())));
			}
			card.add(Ui.stat("Still needed", Counters.format(key, p.getCountRemaining())));
			card.add(Ui.stat("Saved since you started", (p.getCountGained() > 0 ? "+" : "") + Counters.format(key, p.getCountGained())));
		}
		else
		{
			card.add(Ui.stat("When you started", Counters.format(key, g.getStartCount())));
			card.add(Ui.stat("Now", Counters.format(key, p.getCountCurrent())));
			card.add(Ui.stat("Target", Counters.format(key, p.getCountTarget())));
			card.add(Ui.stat("Gained since", "+" + Counters.format(key, p.getCountGained())));
			card.add(Ui.stat("Remaining", Format.number(p.getCountRemaining()) + " " + p.getUnit()));
		}
		if (p.getHoursRemaining() > 0)
		{
			card.add(Ui.stat("Time remaining", "~" + Format.hours(p.getHoursRemaining())));
			if (Counters.isKc(g.getCounter()))
			{
				String boss = Counters.suffix(g.getCounter());
				double mins = service.minutesPerKill(boss);
				card.add(Ui.stat("Per kill", String.format(Locale.ENGLISH, mins < 10 ? "%.1fm" : "%.0fm", mins)
					+ (service.isPersonalKillTime(boss) ? " (yours)" : " (typical)")));
			}
		}
		if (p.getTargetDate() != null)
		{
			card.add(Ui.stat("Target date", Format.date(p.getTargetDate())));
			card.add(Ui.stat("Needed per week", perWeek(p)));
		}
		if (p.getProjectedCompletion() != null)
		{
			card.add(Ui.stat(p.isProjectionFromPace() ? "Finish at your pace" : "Finish at " + p.getAvailableHoursPerWeek() + "h/week", Format.date(p.getProjectedCompletion())));
		}
		if (Counters.CASH.equals(g.getCounter()))
		{
			card.add(spacer());
			card.add(Ui.muted(service.isBankCashKnown()
				? "Counts coins and platinum tokens in your inventory and bank. The bank updates whenever you open it."
				: "Open your bank once so RuneJourney can count the coins and platinum tokens in it."));
		}
		if (Counters.WEALTH.equals(g.getCounter()))
		{
			card.add(spacer());
			card.add(Ui.muted("Your bank, inventory and equipment at GE prices. The bank updates whenever you open it."));
		}
		if (Counters.CA_POINTS.equals(g.getCounter()))
		{
			card.add(spacer());
			card.add(Ui.muted("CA points go up automatically as you complete combat tasks. If the total drifts, "
				+ "edit the goal and enter your current points."));
		}
		return card;
	}

	private JPanel counterWeek(GoalProgress p)
	{
		Goal g = p.getGoal();
		JPanel card = Ui.section("This week's plan");
		boolean done = p.getCountWeekAchieved() >= p.getCountWeekTarget();
		String weekOf = g.getWeekStart() == null ? "" : "Week of " + Format.date(LocalDate.parse(g.getWeekStart()));
		card.add(Ui.muted(weekOf + (p.getWeekHours() > 0 ? " · ~" + Format.hours(p.getWeekHours()) + " planned" : "")));
		long toGo = p.getCountWeekTarget() - p.getCountWeekAchieved();
		String left = Counters.isMoney(g.getCounter()) ? Counters.format(g.getCounter(), toGo) : Format.number(toGo);
		card.add(Ui.row(Ui.small(Counters.isMoney(g.getCounter()) ? "Savings" : Counters.label(g.getCounter()), Color.WHITE),
			Ui.small(done ? "Done!" : left + " to go", done ? Ui.GOOD : Ui.MUTED)));
		card.add(Ui.progress(p.getCountWeekAchieved() / (double) Math.max(1, p.getCountWeekTarget()), done ? Ui.GOOD : Ui.GOLD));
		card.add(Ui.small(countText(g.getCounter(), p.getCountWeekAchieved(), p.getCountWeekTarget()), Ui.MUTED));
		card.add(spacer());
		card.add(Ui.muted("Each Monday the plan is rebuilt from whatever is left."));
		return card;
	}

	private JPanel itemChecklist(GoalProgress p)
	{
		Goal g = p.getGoal();
		JPanel card = Ui.section("Items (" + p.getItemsObtained() + " / " + p.getItemsTotal() + ")");
		for (int i = 0; i < g.getItems().size(); i++)
		{
			GoalItem item = g.getItems().get(i);
			JCheckBox box = new JCheckBox();
			box.setSelected(item.isObtained());
			box.setOpaque(false);
			box.setFocusPainted(false);
			box.setToolTipText(item.isObtained() ? "Mark as not obtained" : "Mark as obtained");
			box.addActionListener(e ->
			{
				service.setItemObtained(g.getId(), item, box.isSelected());
				refresh(true);
			});

			JPanel text = Ui.stack(0);
			JLabel name = Ui.small(item.getName(), item.isObtained() ? Ui.GOOD : Color.WHITE);
			text.add(name);
			if (item.isObtained())
			{
				String when = Format.date(toDate(item.getObtainedAt()));
				text.add(Ui.small(when + (item.getSource() != null ? " · " + item.getSource() : ""), Ui.MUTED));
			}

			JLabel icon = new JLabel();
			icon.setPreferredSize(new java.awt.Dimension(36, 32));
			if (item.getId() > 0)
			{
				itemManager.getImage(item.getId()).addTo(icon);
			}

			JPanel row = new JPanel(new BorderLayout(4, 0));
			row.setOpaque(false);
			JPanel left = new JPanel(new BorderLayout(2, 0));
			left.setOpaque(false);
			left.add(box, BorderLayout.WEST);
			left.add(icon, BorderLayout.EAST);
			row.add(left, BorderLayout.WEST);
			row.add(text, BorderLayout.CENTER);
			card.add(row);
		}
		card.add(spacer());
		card.add(Ui.muted("Items tick off automatically from drops, clue caskets and collection log messages. "
			+ "Tick anything you already had."));
		return card;
	}

	private JPanel clogMissing(String page)
	{
		List<ClogItem> items = service.collectionLogPage(page);
		long missing = items.stream().filter(i -> !i.isObtained()).count();
		JPanel card = Ui.section("Still missing (" + missing + ")");
		for (ClogItem item : items)
		{
			if (item.isObtained())
			{
				continue;
			}
			JLabel label = Ui.small(item.getName(), Color.WHITE);
			if (item.getId() > 0)
			{
				itemManager.getImage(item.getId()).addTo(label);
				label.setIconTextGap(6);
			}
			card.add(label);
		}
		card.add(spacer());
		card.add(Ui.muted("Updates when you get a new slot, or when you open this page in your collection log."));
		return card;
	}

	private void edit(String id)
	{
		Goal current = service.goal(id);
		if (current == null)
		{
			return;
		}
		NewGoalDialog.Result result = NewGoalDialog.edit(this, dialogContext(), current);
		if (result == null)
		{
			return;
		}
		if (result.getCombatAchievementPoints() != null)
		{
			service.setCombatAchievementPoints(result.getCombatAchievementPoints());
		}
		String error = service.updateGoal(result.getGoal());
		if (error != null)
		{
			JOptionPane.showMessageDialog(this, error, "RuneJourney", JOptionPane.WARNING_MESSAGE);
		}
		refresh(true);
	}

	private JPanel overview(GoalProgress p)
	{
		Goal g = p.getGoal();
		JPanel card = Ui.section("Overview");
		card.add(Ui.stat("Started", Format.date(toDate(g.getCreatedAt()))));
		if (g.getType() == GoalType.SKILL)
		{
			card.add(Ui.stat("Starting level", String.valueOf(Skills.level(g.getStartXp().getOrDefault(g.getSkill(), 0L)))));
		}
		card.add(Ui.stat("XP gained since", "+" + Format.number(p.getXpGained())));
		if (p.getLevelsGained() > 0)
		{
			card.add(Ui.stat("Levels gained since", "+" + p.getLevelsGained()));
		}
		card.add(Ui.stat("XP remaining", Format.number(p.getXpRemaining())));
		card.add(Ui.stat("Time remaining", "~" + Format.hours(p.getHoursRemaining())));
		if (p.getTargetDate() != null)
		{
			card.add(Ui.stat("Target date", Format.date(p.getTargetDate())));
			card.add(Ui.stat("Needed per week", Format.hours(p.getRequiredHoursPerWeek()),
				p.getRequiredHoursPerWeek() > p.getAvailableHoursPerWeek() ? Ui.WARN : Ui.GOOD));
		}
		card.add(Ui.stat("You planned", p.getAvailableHoursPerWeek() + "h/week"));
		if (p.getProjectedCompletion() != null)
		{
			card.add(Ui.stat(p.isProjectionFromPace() ? "Finish at your pace" : "Finish at " + p.getAvailableHoursPerWeek() + "h/week", Format.date(p.getProjectedCompletion())));
		}
		return card;
	}

	private JPanel thisWeek(GoalProgress p)
	{
		Goal g = p.getGoal();
		JPanel card = Ui.section("This week's plan");
		String weekOf = g.getWeekStart() == null ? "" : "Week of " + Format.date(LocalDate.parse(g.getWeekStart())) + " · ";
		card.add(Ui.muted(weekOf + Format.compact(p.getWeekAchieved()) + " / " + Format.compact(p.getWeekTarget())
			+ " XP · ~" + Format.hours(p.getWeekHours()) + " planned"));
		card.add(Ui.progress(p.getWeekAchieved() / (double) Math.max(1, p.getWeekTarget()),
			p.getWeekTarget() > 0 && p.getWeekAchieved() >= p.getWeekTarget() ? Ui.GOOD : Ui.GOLD));

		for (GoalProgress.WeekRow w : p.getWeek())
		{
			boolean done = w.getAchieved() >= w.getTarget();
			JPanel block = Ui.stack(2);
			block.setBorder(new EmptyBorder(8, 0, 0, 0));

			JLabel name = Ui.small(w.getSkill().getName(), Color.WHITE);
			name.setIcon(views.skillIcon(w.getSkill()));
			name.setIconTextGap(5);
			block.add(Ui.row(name, Ui.small(done ? "Done!" : "~" + Format.hours(w.getHoursLeft()) + " left", done ? Ui.GOOD : Ui.MUTED)));
			block.add(Ui.progress(w.getAchieved() / (double) Math.max(1, w.getTarget()), done ? Ui.GOOD : Ui.GOLD));
			block.add(Ui.row(Ui.small(Format.compact(w.getAchieved()) + " / " + Format.compact(w.getTarget()) + " XP", Ui.MUTED),
				Ui.small("Lvl " + level(w.getFromLevelExact()) + " -> " + level(w.getToLevelExact()), Ui.MUTED)));
			card.add(block);
		}
		card.add(spacer());
		card.add(Ui.muted("Train these however you like. Each Monday the plan is rebuilt from whatever is left."));
		return card;
	}

	private static String level(double exact)
	{
		return String.format(Locale.ENGLISH, "%.1f", Math.floor(exact * 10) / 10);
	}

	private JPanel lastWeek(Goal g)
	{
		JPanel card = Ui.section("Last week");
		long[] count = g.getLastWeekResults().get(GoalPlanner.COUNT_KEY);
		if (count != null)
		{
			card.add(weekResult(Ui.small(Counters.label(g.getCounter()), Color.WHITE), count,
				v -> Counters.format(g.getCounter(), v)));
		}

		List<Map.Entry<Skill, long[]>> planned = new ArrayList<>();
		List<String> also = new ArrayList<>();
		for (Map.Entry<String, long[]> e : g.getLastWeekResults().entrySet())
		{
			Skill s = Skills.parse(e.getKey());
			long[] v = e.getValue();
			if (s == null)
			{
				continue;
			}
			if (v[0] > 0)
			{
				planned.add(new AbstractMap.SimpleEntry<>(s, v));
			}
			else if (v[1] > 0 && GoalPlanner.helps(g, s, g.getWeekStartXp().getOrDefault(s.name(), Long.MAX_VALUE) - v[1]))
			{
				also.add(s.getName() + " +" + Format.compact(v[1]));
			}
		}
		planned.sort(Comparator.comparingDouble((Map.Entry<Skill, long[]> e) -> e.getValue()[1] / (double) e.getValue()[0]).reversed());
		for (Map.Entry<Skill, long[]> e : planned)
		{
			JLabel name = Ui.small(e.getKey().getName(), Color.WHITE);
			name.setIcon(views.skillIcon(e.getKey()));
			name.setIconTextGap(5);
			card.add(weekResult(name, e.getValue(), v -> Format.compact(v) + " XP"));
		}
		if (!also.isEmpty())
		{
			JLabel extra = Ui.muted("Also towards your goal: " + String.join(", ", also));
			extra.setBorder(new EmptyBorder(6, 0, 0, 0));
			card.add(extra);
		}
		card.add(spacer());
		card.add(Ui.muted("Weekly plans met: " + g.getWeeksMet() + " of " + g.getWeeksPlanned()
			+ (g.getPlanStreak() > 1 ? " · " + g.getPlanStreak() + " in a row" : "")
			+ (g.getBestPlanStreak() > 1 ? " (best " + g.getBestPlanStreak() + ")" : "")));
		return card;
	}

	private static JPanel weekResult(JLabel name, long[] v, LongFunction<String> format)
	{
		long target = v[0];
		long achieved = v[1];
		String verdict;
		Color color;
		if (achieved > target)
		{
			verdict = "+" + format.apply(achieved - target) + " over";
			color = Ui.GOOD;
		}
		else if (achieved == target)
		{
			verdict = "Done";
			color = Ui.GOOD;
		}
		else if (achieved == 0)
		{
			verdict = "Not done";
			color = Ui.WARN;
		}
		else
		{
			verdict = format.apply(target - achieved) + " short";
			color = Ui.WARN;
		}
		String numbers = format.apply(achieved) + " of " + format.apply(target);
		if (target > 0 && achieved >= target * 2)
		{
			numbers += ", " + (achieved / target) + "x the plan";
		}

		JPanel block = Ui.stack(1);
		block.setBorder(new EmptyBorder(4, 0, 0, 0));
		block.add(Ui.row(name, Ui.small(verdict, color)));
		JLabel detail = Ui.small(numbers, Ui.MUTED);
		detail.setBorder(new EmptyBorder(0, name.getIcon() == null ? 0 : name.getIcon().getIconWidth() + name.getIconTextGap(), 0, 0));
		block.add(detail);
		return block;
	}

	private JPanel trainingPlan(GoalProgress p)
	{
		JPanel card = Ui.section("What's left & how you'll train");
		for (GoalProgress.SkillRow row : p.getSkills())
		{
			Skill s = row.getSkill();
			JPanel block = Ui.stack(2);
			block.setBorder(new EmptyBorder(6, 0, 2, 0));

			JLabel name = Ui.small(s.getName() + "  " + row.getCurrentLevel() + " -> " + row.getTargetLevel(), Color.WHITE);
			name.setIcon(views.skillIcon(s));
			name.setIconTextGap(5);
			block.add(Ui.row(name, Ui.small("~" + Format.hours(row.getHours()), Color.WHITE)));
			block.add(Ui.small(Format.compact(row.getRemainingXp()) + " XP · avg " + Format.compact((long) row.getRate()) + " XP/hr", Ui.MUTED));

			List<String> choices = service.methodChoices(s);
			if (choices.size() > 1)
			{
				JComboBox<String> combo = new JComboBox<>(choices.toArray(new String[0]));
				combo.setFont(FontManager.getRunescapeSmallFont());
				combo.setSelectedItem(row.getMethodName());
				combo.setToolTipText("How you plan to train " + s.getName());
				combo.addActionListener(e ->
				{
					String chosen = (String) combo.getSelectedItem();
					if (chosen != null && !chosen.equals(row.getMethodName()))
					{
						service.setPreferredMethod(s, chosen);
						refresh(true);
					}
				});
				combos.add(combo);
				block.add(combo);
			}
			else
			{
				block.add(Ui.small(row.getMethodName(), Ui.MUTED));
			}
			card.add(block);
		}
		card.add(spacer());
		card.add(Ui.muted("Choose how you like to train each skill. Estimates follow each method's rates at your level. "
			+ "After " + service.rateSampleMinutes() + " minutes of training, RuneJourney offers to save your own rate as a method."));
		return card;
	}

	private static JPanel spacer()
	{
		JPanel p = new JPanel();
		p.setOpaque(false);
		p.setBorder(new EmptyBorder(2, 0, 0, 0));
		return p;
	}

	private static LocalDate toDate(long millis)
	{
		return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();
	}

	private static Color statusColor(GoalProgress.Status status)
	{
		switch (status)
		{
			case COMPLETE:
			case ON_TRACK:
			case READY:
				return Ui.GOOD;
			case SLIGHTLY_BEHIND:
				return Ui.WARN;
			case BEHIND:
			case OVERDUE:
				return Ui.BAD;
			default:
				return Ui.GOLD;
		}
	}
}
