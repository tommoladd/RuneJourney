package com.runejourney.ui;

import com.runejourney.model.Goal;
import com.runejourney.model.GoalItem;
import com.runejourney.model.GoalType;
import com.runejourney.planner.Counters;
import com.runejourney.planner.GoalPlanner;
import com.runejourney.planner.Skills;
import com.runejourney.util.Format;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.text.ParseException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import lombok.Value;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * Create or edit a goal of any type.
 */
final class NewGoalDialog
{
	/**
	 * What the dialog needs to know about the account.
	 */
	@Value
	static class Context
	{
		int defaultHours;
		List<String> bosses;
		Map<String, Long> counters;
		ItemIndex itemIndex;
		ItemManager itemManager;
		/**
		 * Whether the bank has been opened, so its coins are included in the cash stack.
		 */
		boolean bankCashKnown;
		/**
		 * Collection log pages the player has opened: name to [obtained, total].
		 */
		Map<String, int[]> clogPages;
	}

	@Value
	static class Result
	{
		Goal goal;
		/**
		 * The player's current CA points if they entered them, to calibrate tracking.
		 */
		Long combatAchievementPoints;
	}

	private final Context ctx;
	private final Goal existing;

	private final JComboBox<GoalType> type = new JComboBox<>(GoalType.values());
	private final JComboBox<Skill> skill = new JComboBox<>(Skills.ALL.toArray(new Skill[0]));
	private final JComboBox<String> boss;
	private final JComboBox<String> clueTier = new JComboBox<>();
	private final JComboBox<String> clogPage = new JComboBox<>();
	private final JTextField target = new JTextField("99", 10);
	private final JCheckBox relative = new JCheckBox("more from now");
	private final JTextField caPoints = new JTextField(10);
	private final JButton chooseItems = new JButton("Choose items...");
	private final JLabel itemsSummary = new JLabel();
	private final JTextField name = new JTextField(16);
	private final JTextField date = new JTextField(10);
	private final JTextField hours = new JTextField(10);
	private final JTextField notes = new JTextField(16);
	private final JTextField quantity = new JTextField("1", 10);
	private final JTextField price = new JTextField(10);
	private List<GoalItem> items = new ArrayList<>();

	private final JLabel skillLabel = new JLabel("Skill");
	private final JLabel bossLabel = new JLabel("Boss / activity");
	private final JLabel tierLabel = new JLabel("Clue tier");
	private final JLabel pageLabel = new JLabel("Log page");
	private final JLabel targetLabel = new JLabel("Target");
	private final JLabel relativeLabel = new JLabel("");
	private final JLabel currentHint = new JLabel();
	private final JLabel caLabel = new JLabel("Your CA points now");
	private final JLabel caHint = new JLabel("<html><small>The game doesn't share this, so enter it once<br>(Combat Achievements overview).</small></html>");
	private final JLabel itemsLabel = new JLabel("Items");
	private final JLabel nameLabel = new JLabel("Name");
	private final JLabel notesLabel = new JLabel("Notes");
	private final JLabel hoursLabel = new JLabel("Hours per week");
	private final JLabel quantityLabel = new JLabel("Quantity");
	private final JLabel priceLabel = new JLabel("Price each (gp)");
	private final JLabel priceHint = new JLabel("<html><small>From the GE; kept up to date unless you change it</small></html>");
	private final JLabel hoursHint;
	private final JPanel form = new JPanel(new GridBagLayout());
	private final Map<JComponent, JComponent> rowLabels = new HashMap<>();
	private Runnable refit = () ->
	{
	};
	private int row;

	private NewGoalDialog(Context ctx, Goal existing)
	{
		this.ctx = ctx;
		this.existing = existing;
		hoursHint = new JLabel("<html><small>Blank = your default (" + ctx.getDefaultHours() + "h)</small></html>");

		skill.setRenderer(new SkillRenderer());
		boss = new JComboBox<>(ctx.getBosses().toArray(new String[0]));
		boss.setEditable(true);
		clueTier.addItem(Counters.ALL_TIERS);
		Counters.CLUE_TIERS.forEach(clueTier::addItem);

		addRow(new JLabel("Goal type"), type);
		addRow(skillLabel, skill);
		addRow(bossLabel, boss);
		addRow(tierLabel, clueTier);
		addRow(pageLabel, clogPage);
		addRow(itemsLabel, chooseItems);
		addRow(new JLabel(""), itemsSummary);
		addRow(quantityLabel, quantity);
		addRow(priceLabel, price);
		addRow(new JLabel(""), priceHint);
		addRow(caLabel, caPoints);
		addRow(new JLabel(""), caHint);
		addRow(targetLabel, target);
		addRow(relativeLabel, relative);
		addRow(new JLabel(""), currentHint);
		addRow(nameLabel, name);
		addRow(new JLabel("Target date"), date);
		addRow(new JLabel(""), new JLabel("<html><small>Optional, YYYY-MM-DD, e.g. " + LocalDate.now().plusMonths(3) + "</small></html>"));
		addRow(hoursLabel, hours);
		addRow(new JLabel(""), hoursHint);
		addRow(notesLabel, notes);

		Long ca = ctx.getCounters().get(Counters.CA_POINTS);
		if (ca != null && ca > 0)
		{
			caPoints.setText(String.valueOf(ca));
		}
		chooseItems.addActionListener(e -> pickItems());
		quantity.addActionListener(e -> updateHint());
		boss.addActionListener(e -> updateHint());
		clueTier.addActionListener(e -> updateHint());
		ctx.getClogPages().keySet().forEach(clogPage::addItem);
		clogPage.addActionListener(e -> updateHint());
		relative.addActionListener(e -> updateHint());

		if (existing != null)
		{
			type.setSelectedItem(existing.getType());
			type.setEnabled(false);
		}
		type.addActionListener(e -> updateFields(true));
		updateFields(existing == null);
		if (existing != null)
		{
			fill(existing);
		}
		updateItemsSummary();
		updateHint();
	}

	private void addRow(JComponent label, JComponent field)
	{
		GridBagConstraints c = new GridBagConstraints();
		c.gridy = row++;
		c.insets = new Insets(3, 3, 3, 3);
		c.anchor = GridBagConstraints.WEST;
		c.gridx = 0;
		form.add(label, c);
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		form.add(field, c);
		rowLabels.put(field, label);
	}

	private void pickItems()
	{
		List<GoalItem> picked = ItemSearchDialog.show(form, ctx.getItemIndex(), ctx.getItemManager(), items);
		if (picked != null)
		{
			boolean purchase = type.getSelectedItem() == GoalType.PURCHASE;
			items = purchase && picked.size() > 1 ? new ArrayList<>(picked.subList(picked.size() - 1, picked.size())) : picked;
			if (purchase && !items.isEmpty())
			{
				long p = ctx.getItemIndex().price(items.get(0).getId());
				price.setText(p > 0 ? Format.number(p) : "");
			}
			updateItemsSummary();
			updateHint();
			refit.run();
		}
	}

	private void updateItemsSummary()
	{
		if (items.isEmpty())
		{
			itemsSummary.setText("<html><small>No items chosen yet</small></html>");
			return;
		}
		String names = items.stream().limit(4).map(GoalItem::getName).collect(Collectors.joining(", "));
		if (items.size() > 4)
		{
			names += " +" + (items.size() - 4) + " more";
		}
		itemsSummary.setText("<html><small>" + Ui.escape(names) + "</small></html>");
	}

	private String counterKey()
	{
		GoalType t = (GoalType) type.getSelectedItem();
		switch (t)
		{
			case BOSS_KC:
				Object b = boss.getSelectedItem();
				return b == null || b.toString().trim().isEmpty() ? null : Counters.kc(b.toString().trim());
			case CLUES:
				return Counters.clues((String) clueTier.getSelectedItem());
			case COMBAT_ACHIEVEMENTS:
				return Counters.CA_POINTS;
			case COMBAT_TASKS:
				return Counters.CA_TASKS;
			case QUEST_POINTS:
				return Counters.QUEST_POINTS;
			case COLLECTION_LOG:
				return Counters.COLLECTION_LOG;
			case MONEY:
			case PURCHASE:
				return Counters.CASH;
			case NET_WORTH:
				return Counters.WEALTH;
			case CLOG_CATEGORY:
			{
				Object page = clogPage.getSelectedItem();
				return page == null ? null : Counters.clogPage(page.toString());
			}
			default:
				return null;
		}
	}

	private void updateHint()
	{
		String key = counterKey();
		GoalType t = (GoalType) type.getSelectedItem();
		if (t == GoalType.CLOG_CATEGORY && key == null)
		{
			currentHint.setText("<html><small>Open pages in your collection log in-game<br>and they'll appear here</small></html>");
			return;
		}
		if (key == null || t == GoalType.COMBAT_ACHIEVEMENTS)
		{
			currentHint.setText("");
			return;
		}
		Long have = ctx.getCounters().get(key);
		if (t == GoalType.CLOG_CATEGORY)
		{
			int[] page = ctx.getClogPages().get(Counters.suffix(key));
			currentHint.setText(page == null ? "" : "<html><small>" + page[0] + " / " + page[1] + " items obtained</small></html>");
			return;
		}
		if (Counters.WEALTH.equals(key))
		{
			currentHint.setText("<html><small>" + (have == null
				? "Open your bank once so RuneJourney can value it"
				: "Your net worth is " + Counters.format(key, have)) + "</small></html>");
			return;
		}
		if (Counters.CASH.equals(key))
		{
			String cash = "You have " + Counters.format(key, have == null ? 0 : have) + " in coins and platinum tokens";
			if (!ctx.isBankCashKnown())
			{
				cash += "<br>(open your bank once so your banked coins count)";
			}
			currentHint.setText("<html><small>" + cash + "</small></html>");
			return;
		}
		currentHint.setText("<html><small>You have " + (have == null ? "none recorded yet" : Format.number(have))
			+ (t == GoalType.BOSS_KC && have == null ? " (updates on your next kill)" : "") + "</small></html>");
	}

	private void fill(Goal g)
	{
		// Leave auto-generated names blank so they follow target changes
		name.setText(g.getName().equals(defaultName(g)) ? "" : g.getName());
		date.setText(g.getTargetDate() == null ? "" : g.getTargetDate());
		hours.setText(g.getHoursPerWeek() > 0 ? String.valueOf(g.getHoursPerWeek()) : "");
		notes.setText(g.getNotes() == null ? "" : g.getNotes());
		switch (g.getType())
		{
			case SKILL:
			{
				Skill s = Skills.parse(g.getSkill());
				if (s != null)
				{
					skill.setSelectedItem(s);
				}
				int level = Experience.getLevelForXp((int) Math.min(g.getTargetXp(), Experience.MAX_SKILL_XP));
				target.setText(Skills.xpForLevel(level) == g.getTargetXp() ? String.valueOf(level) : Format.compact(g.getTargetXp()));
				break;
			}
			case TOTAL_LEVEL:
			case BASE_LEVEL:
				target.setText(String.valueOf(g.getTargetLevel()));
				break;
			case ITEMS:
				items = new ArrayList<>(g.getItems());
				break;
			case PURCHASE:
				items = new ArrayList<>(g.getItems());
				quantity.setText(String.valueOf(Math.max(1, g.getQuantity())));
				price.setText(Format.number(g.getTargetCount() / Math.max(1, g.getQuantity())));
				break;
			default:
				if (g.getType().isCounter())
				{
					if (Counters.isKc(g.getCounter()))
					{
						boss.setSelectedItem(Counters.suffix(g.getCounter()));
					}
					else if (Counters.isClues(g.getCounter()))
					{
						clueTier.setSelectedItem(Counters.suffix(g.getCounter()));
					}
					else if (Counters.isClogPage(g.getCounter()))
					{
						clogPage.setSelectedItem(Counters.suffix(g.getCounter()));
						clogPage.setEnabled(false);
					}
					boss.setEnabled(false);
					clueTier.setEnabled(false);
					relative.setSelected(g.isRelative());
					target.setText(String.valueOf(g.isRelative() ? g.getTargetCount() - g.getStartCount() : g.getTargetCount()));
				}
		}
	}

	private void updateFields(boolean resetTarget)
	{
		GoalType t = (GoalType) type.getSelectedItem();
		boolean counter = t.isCounter();
		boolean hasTarget = t == GoalType.SKILL || t == GoalType.TOTAL_LEVEL || t == GoalType.BASE_LEVEL || counter;
		boolean custom = t == GoalType.CUSTOM;

		show(t == GoalType.SKILL, skillLabel, skill);
		show(t == GoalType.BOSS_KC, bossLabel, boss);
		show(t == GoalType.CLUES, tierLabel, clueTier);
		boolean money = t == GoalType.MONEY || t == GoalType.PURCHASE || t == GoalType.NET_WORTH;
		boolean clog = t == GoalType.CLOG_CATEGORY;
		show(clog, pageLabel, clogPage);
		show(t == GoalType.ITEMS || t == GoalType.PURCHASE, itemsLabel, chooseItems, itemsSummary);
		show(t == GoalType.PURCHASE, quantityLabel, quantity, priceLabel, price, priceHint);
		show(t == GoalType.COMBAT_ACHIEVEMENTS, caLabel, caPoints, caHint);
		show(hasTarget && t != GoalType.PURCHASE && !clog, targetLabel, target);
		show(counter && !money && !clog, relativeLabel, relative);
		show(counter, currentHint);
		show(custom, notesLabel, notes);
		show(t.isSkilling() || counter, hoursLabel, hours, hoursHint);
		nameLabel.setText(custom ? "Name" : "Name (optional)");

		if (resetTarget)
		{
			switch (t)
			{
				case SKILL:
					target.setText("99");
					break;
				case TOTAL_LEVEL:
					target.setText("2000");
					break;
				case BASE_LEVEL:
					target.setText("90");
					break;
				case BOSS_KC:
					target.setText("100");
					relative.setSelected(false);
					break;
				case CLUES:
					target.setText("100");
					relative.setSelected(true);
					break;
				case COMBAT_ACHIEVEMENTS:
					target.setText("1000");
					relative.setSelected(false);
					break;
				case COMBAT_TASKS:
					target.setText("20");
					relative.setSelected(true);
					break;
				case QUEST_POINTS:
				case COLLECTION_LOG:
					target.setText("");
					relative.setSelected(false);
					break;
				case MONEY:
					target.setText("100m");
					relative.setSelected(false);
					break;
				case NET_WORTH:
					target.setText("1b");
					relative.setSelected(false);
					break;
				case CLOG_CATEGORY:
					relative.setSelected(false);
					break;
				case PURCHASE:
					relative.setSelected(false);
					break;
				default:
					break;
			}
		}
		switch (t)
		{
			case SKILL:
				targetLabel.setText("Level or XP");
				break;
			case TOTAL_LEVEL:
				targetLabel.setText("Total level");
				break;
			case BASE_LEVEL:
				targetLabel.setText("Base level");
				break;
			case BOSS_KC:
				targetLabel.setText("Kill count");
				break;
			case CLUES:
				targetLabel.setText("Clues");
				break;
			case COMBAT_ACHIEVEMENTS:
				targetLabel.setText("Target points");
				break;
			case COMBAT_TASKS:
				targetLabel.setText("Tasks");
				break;
			case QUEST_POINTS:
				targetLabel.setText("Quest points");
				break;
			case COLLECTION_LOG:
				targetLabel.setText("Log slots");
				break;
			case MONEY:
			case NET_WORTH:
				targetLabel.setText("Amount (e.g. 500m)");
				break;
			default:
				break;
		}
		updateHint();
		refit.run();
	}

	/**
	 * Shows or hides fields together with their row labels, so hidden rows leave no gaps.
	 */
	private void show(boolean visible, JComponent... components)
	{
		for (JComponent c : components)
		{
			c.setVisible(visible);
			JComponent label = rowLabels.get(c);
			if (label != null)
			{
				label.setVisible(visible);
			}
		}
	}

	static Result create(Component parent, Context ctx)
	{
		return show(parent, new NewGoalDialog(ctx, null), "New goal");
	}

	static Result edit(Component parent, Context ctx, Goal goal)
	{
		return show(parent, new NewGoalDialog(ctx, goal), "Edit goal");
	}

	private static Result show(Component parent, NewGoalDialog d, String title)
	{
		return FormDialog.show(parent, title, d.form, d::build, r -> d.refit = r);
	}

	private Result build()
	{
		GoalType t = (GoalType) type.getSelectedItem();
		Goal g = new Goal();
		if (existing != null)
		{
			g.setId(existing.getId());
		}
		g.setType(t);
		String customName = name.getText().trim();
		Long ca = null;

		switch (t)
		{
			case SKILL:
			{
				Skill s = (Skill) skill.getSelectedItem();
				long value = parseNumber(target.getText(), "Enter a level (e.g. 99) or an XP amount (e.g. 50m).");
				g.setSkill(s.name());
				if (value <= Experience.MAX_VIRT_LEVEL)
				{
					if (value < 2)
					{
						throw new IllegalArgumentException("Enter a level between 2 and " + Experience.MAX_VIRT_LEVEL + ".");
					}
					g.setTargetXp(Skills.xpForLevel((int) value));
				}
				else
				{
					if (value > Experience.MAX_SKILL_XP)
					{
						throw new IllegalArgumentException("The maximum XP in a skill is 200m.");
					}
					g.setTargetXp(value);
				}
				break;
			}
			case TOTAL_LEVEL:
			{
				long value = parseNumber(target.getText(), "Enter a total level.");
				if (value < 33 || value > Skills.MAX_TOTAL_LEVEL)
				{
					throw new IllegalArgumentException("Enter a total level up to " + Skills.MAX_TOTAL_LEVEL + ".");
				}
				g.setTargetLevel((int) value);
				break;
			}
			case BASE_LEVEL:
			{
				long value = parseNumber(target.getText(), "Enter a base level.");
				if (value < 2 || value > Experience.MAX_REAL_LEVEL)
				{
					throw new IllegalArgumentException("Enter a base level between 2 and 99.");
				}
				g.setTargetLevel((int) value);
				break;
			}
			case MAX_CAPE:
				g.setTargetLevel(Experience.MAX_REAL_LEVEL);
				break;
			case ITEMS:
				if (items.isEmpty())
				{
					throw new IllegalArgumentException("Choose at least one item.");
				}
				g.setItems(new ArrayList<>(items));
				break;
			case CUSTOM:
				if (customName.isEmpty())
				{
					throw new IllegalArgumentException("Give your goal a name.");
				}
				g.setNotes(notes.getText().trim());
				break;
			case CLOG_CATEGORY:
			{
				String key = counterKey();
				int[] page = key == null ? null : ctx.getClogPages().get(Counters.suffix(key));
				if (page == null)
				{
					throw new IllegalArgumentException("Open the page in your collection log in-game first, then choose it here.");
				}
				if (existing == null && page[0] >= page[1])
				{
					throw new IllegalArgumentException("You've already completed that page!");
				}
				g.setCounter(key);
				g.setTargetCount(page[1]);
				break;
			}
			case PURCHASE:
			{
				if (items.isEmpty())
				{
					throw new IllegalArgumentException("Choose the item you want to buy.");
				}
				long qty = parseNumber(quantity.getText(), "Enter how many you want to buy.");
				if (qty < 1)
				{
					throw new IllegalArgumentException("Enter a quantity of at least 1.");
				}
				long each = parseNumber(price.getText().replace(",", ""), "Enter the price in gp (e.g. 1.2b).");
				if (each < 1)
				{
					throw new IllegalArgumentException("That item has no GE price, so enter what you expect to pay.");
				}
				GoalItem item = items.get(0);
				g.setItems(new ArrayList<>(items.subList(0, 1)));
				g.setQuantity((int) Math.min(Integer.MAX_VALUE, qty));
				g.setCounter(Counters.CASH);
				g.setTargetCount(each * qty);
				// Prices are only known once the item index has loaded
				g.setFixedPrice(ctx.getItemIndex().isReady()
					? each != ctx.getItemIndex().price(item.getId())
					: existing != null && existing.isFixedPrice());
				break;
			}
			default:
			{
				String key = counterKey();
				if (key == null)
				{
					throw new IllegalArgumentException("Choose a boss or activity.");
				}
				long value = parseNumber(target.getText(), "Enter how many " + Counters.unit(key) + " you're aiming for.");
				if (value < 1)
				{
					throw new IllegalArgumentException("Enter a target of at least 1.");
				}
				if (t == GoalType.COMBAT_ACHIEVEMENTS && !caPoints.getText().trim().isEmpty())
				{
					ca = parseNumber(caPoints.getText(), "Enter your current CA points as a number, or leave it blank.");
				}
				g.setCounter(key);
				g.setTargetCount(value);
				g.setRelative(relative.isSelected());
				if (!relative.isSelected())
				{
					Long have = t == GoalType.COMBAT_ACHIEVEMENTS ? ca : ctx.getCounters().get(key);
					if (have != null && have >= value && existing == null)
					{
						throw new IllegalArgumentException("You already have " + Format.number(have) + ". Choose a higher target, "
							+ "or tick \"more from now\".");
					}
				}
			}
		}
		g.setName(customName.isEmpty() ? defaultName(g) : customName);

		String dateText = date.getText().trim();
		if (!dateText.isEmpty())
		{
			LocalDate d = GoalPlanner.parseDate(dateText);
			if (d == null)
			{
				throw new IllegalArgumentException("Enter the target date as YYYY-MM-DD.");
			}
			if (!d.isAfter(LocalDate.now()))
			{
				throw new IllegalArgumentException("The target date needs to be in the future.");
			}
			g.setTargetDate(d.toString());
		}

		String hoursText = hours.getText().trim();
		if (!hoursText.isEmpty() && (t.isSkilling() || t.isCounter()))
		{
			long h = parseNumber(hoursText, "Enter hours per week as a number, or leave it blank.");
			if (h < 1 || h > 168)
			{
				throw new IllegalArgumentException("Hours per week must be between 1 and 168.");
			}
			g.setHoursPerWeek((int) h);
		}
		return new Result(g, ca);
	}

	/**
	 * The name a goal gets when the player doesn't choose one. For counter goals being edited this
	 * compares against the "N more" form.
	 */
	private static String defaultName(Goal g)
	{
		switch (g.getType())
		{
			case SKILL:
			{
				Skill s = Skills.parse(g.getSkill());
				if (s == null)
				{
					return "";
				}
				int level = Experience.getLevelForXp((int) Math.min(g.getTargetXp(), Experience.MAX_SKILL_XP));
				return Skills.xpForLevel(level) == g.getTargetXp()
					? level + " " + s.getName()
					: Format.compact(g.getTargetXp()) + " " + s.getName() + " XP";
			}
			case TOTAL_LEVEL:
				return Format.number(g.getTargetLevel()) + " Total level";
			case BASE_LEVEL:
				return "Base " + g.getTargetLevel();
			case MAX_CAPE:
				return "Max Cape";
			case ITEMS:
			{
				if (g.getItems().isEmpty())
				{
					return "Obtain items";
				}
				String first = g.getItems().get(0).getName();
				return g.getItems().size() == 1 ? first : first + " + " + (g.getItems().size() - 1) + " more";
			}
			case MONEY:
				return "Save " + Counters.format(Counters.CASH, g.getTargetCount());
			case NET_WORTH:
				return Counters.format(Counters.WEALTH, g.getTargetCount()) + " net worth";
			case CLOG_CATEGORY:
				return g.getCounter() == null ? "Collection log page" : "Complete the " + Counters.suffix(g.getCounter()) + " log";
			case PURCHASE:
				if (g.getItems().isEmpty())
				{
					return "Purchase";
				}
				return "Buy " + (g.getQuantity() > 1 ? Format.number(g.getQuantity()) + " x " : "") + g.getItems().get(0).getName();
			default:
				if (g.getType().isCounter() && g.getCounter() != null)
				{
					long n = g.isRelative() && g.getStartCount() > 0 ? g.getTargetCount() - g.getStartCount() : g.getTargetCount();
					String label = Counters.label(g.getCounter());
					if (Counters.isClues(g.getCounter()) && Counters.ALL_TIERS.equals(Counters.suffix(g.getCounter())))
					{
						label = "clue scrolls";
					}
					return (g.isRelative() ? "+" : "") + Format.number(n) + " " + label;
				}
				return "";
		}
	}

	private static long parseNumber(String text, String error)
	{
		try
		{
			return QuantityFormatter.parseQuantity(text.trim());
		}
		catch (ParseException | NumberFormatException e)
		{
			throw new IllegalArgumentException(error);
		}
	}
}
