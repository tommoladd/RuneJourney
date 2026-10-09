package com.runejourney.ui;

import com.runejourney.model.*;
import com.runejourney.planner.*;
import com.runejourney.service.JourneyService;
import com.runejourney.util.Format;
import java.awt.*;
import java.text.ParseException;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.*;
import lombok.*;
import net.runelite.api.*;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.QuantityFormatter;

final class MemoryDialog
{
	@Getter
	@RequiredArgsConstructor
	enum Kind
	{
		LOOT("Loot drop", EventType.DROP),
		KILL("Boss kill", EventType.BOSS_KC),
		PET("Pet", EventType.PET),
		COLLECTION_LOG("Collection log slot", EventType.COLLECTION_LOG),
		LEVEL("Level up", EventType.LEVEL),
		QUEST("Quest completed", EventType.QUEST),
		CLUE("Clue scroll", EventType.CLUE),
		OTHER("Other", EventType.NOTE);

		private final String label;
		private final EventType type;

		@Override
		public String toString()
		{
			return label;
		}
	}

	@Value
	static class Result
	{
		LocalDate date;
		List<JourneyEvent> events;
		List<JourneyService.LootItem> items;
	}

	private final ItemIndex itemIndex;
	private final ItemManager itemManager;

	private final JComboBox<Kind> kind = new JComboBox<>(Kind.values());
	private final JButton chooseItems = new JButton("Choose item...");
	private final JLabel itemsSummary = new JLabel();
	private final JTextField quantity = new JTextField("1", 8);
	private final JTextField value = new JTextField(10);
	private final JComboBox<String> boss;
	private final JTextField killCount = new JTextField(8);
	private final JTextField killTime = new JTextField(8);
	private final JComboBox<Skill> skill = new JComboBox<>(Skills.ALL.toArray(new Skill[0]));
	private final JTextField level = new JTextField("99", 8);
	private final JComboBox<String> quest;
	private final JComboBox<String> clueTier = new JComboBox<>(Counters.CLUE_TIERS.toArray(new String[0]));
	private final JTextField title = new JTextField(18);
	private final JTextField note = new JTextField(18);
	private final JTextField date = new JTextField(LocalDate.now().toString(), 10);
	private final JTextField time = new JTextField(LocalTime.now().withSecond(0).withNano(0).toString(), 6);
	private final JCheckBox highlight = new JCheckBox("Show in highlights", true);

	private final JLabel itemsLabel = new JLabel("Item");
	private final JLabel quantityLabel = new JLabel("Quantity");
	private final JLabel valueLabel = new JLabel("Value (gp)");
	private final JLabel valueHint = new JLabel("<html><small>Filled from the GE price; change it if you like</small></html>");
	private final JLabel bossLabel = new JLabel("Boss / source");
	private final JLabel killCountLabel = new JLabel("Kill count");
	private final JLabel killTimeLabel = new JLabel("Kill time");
	private final JLabel skillLabel = new JLabel("Skill");
	private final JLabel levelLabel = new JLabel("Level");
	private final JLabel questLabel = new JLabel("Quest");
	private final JLabel tierLabel = new JLabel("Tier");
	private final JLabel titleLabel = new JLabel("What happened?");
	private final JPanel form = new JPanel(new GridBagLayout());
	private final Map<JComponent, JComponent> rowLabels = new HashMap<>();
	private Runnable refit = () ->
	{
	};
	private int row;
	private List<GoalItem> items = new ArrayList<>();

	private MemoryDialog(ItemIndex itemIndex, ItemManager itemManager, List<String> bosses)
	{
		this.itemIndex = itemIndex;
		this.itemManager = itemManager;
		boss = new JComboBox<>(bosses.toArray(new String[0]));
		boss.setEditable(true);
		boss.setSelectedItem("");
		skill.setRenderer(new SkillRenderer());
		quest = new JComboBox<>(Arrays.stream(Quest.values()).map(Quest::getName).sorted().toArray(String[]::new));
		quest.setEditable(true);

		addRow(new JLabel("Type"), kind);
		addRow(itemsLabel, chooseItems);
		addRow(new JLabel(""), itemsSummary);
		addRow(quantityLabel, quantity);
		addRow(valueLabel, value);
		addRow(new JLabel(""), valueHint);
		addRow(bossLabel, boss);
		addRow(killCountLabel, killCount);
		addRow(killTimeLabel, killTime);
		addRow(skillLabel, skill);
		addRow(levelLabel, level);
		addRow(questLabel, quest);
		addRow(tierLabel, clueTier);
		addRow(titleLabel, title);
		addRow(new JLabel("Note"), note);
		addRow(new JLabel("Date"), date);
		addRow(new JLabel("Time"), time);
		addRow(new JLabel(""), highlight);

		killTime.setToolTipText("Optional, e.g. 1:23 or 17:42.60");
		chooseItems.addActionListener(e -> pickItems());
		quantity.addActionListener(e -> updateValue());
		quantity.addFocusListener(new java.awt.event.FocusAdapter()
		{
			@Override
			public void focusLost(java.awt.event.FocusEvent e)
			{
				updateValue();
			}
		});
		kind.addActionListener(e -> updateFields());
		updateFields();
		updateItems();
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

	private void updateFields()
	{
		Kind k = (Kind) kind.getSelectedItem();
		boolean itemKind = k == Kind.LOOT || k == Kind.PET || k == Kind.COLLECTION_LOG;
		show(itemKind, itemsLabel, chooseItems, itemsSummary);
		show(k == Kind.LOOT, quantityLabel, quantity, valueHint);
		show(k == Kind.LOOT || k == Kind.CLUE, valueLabel, value);
		show(itemKind || k == Kind.KILL, bossLabel, boss);
		show(k == Kind.LOOT || k == Kind.KILL || k == Kind.PET, killCountLabel, killCount);
		show(k == Kind.KILL, killTimeLabel, killTime);
		show(k == Kind.LEVEL, skillLabel, skill, levelLabel, level);
		show(k == Kind.QUEST, questLabel, quest);
		show(k == Kind.CLUE, tierLabel, clueTier);
		show(k == Kind.OTHER, titleLabel, title);
		bossLabel.setText(k == Kind.KILL ? "Boss" : "Source (optional)");
		chooseItems.setText(k == Kind.LOOT ? "Choose item(s)..." : "Choose item...");
		valueLabel.setText(k == Kind.CLUE ? "Loot value (gp)" : "Value (gp)");
		refit.run();
	}

	private void pickItems()
	{
		List<GoalItem> picked = ItemSearchDialog.show(form, itemIndex, itemManager, items);
		if (picked != null)
		{
			Kind k = (Kind) kind.getSelectedItem();
			items = k == Kind.LOOT || picked.isEmpty() ? picked : picked.subList(picked.size() - 1, picked.size());
			updateItems();
			updateValue();
			refit.run();
		}
	}

	private void updateItems()
	{
		if (items.isEmpty())
		{
			itemsSummary.setText("<html><small>Nothing chosen yet</small></html>");
			return;
		}
		String names = items.stream().limit(4).map(GoalItem::getName).collect(Collectors.joining(", "));
		if (items.size() > 4)
		{
			names += " +" + (items.size() - 4) + " more";
		}
		itemsSummary.setText("<html><small>" + Ui.escape(names) + "</small></html>");
	}

	private long quantityValue()
	{
		try
		{
			return Math.max(1, QuantityFormatter.parseQuantity(quantity.getText().trim()));
		}
		catch (ParseException | NumberFormatException e)
		{
			return 1;
		}
	}

	private void updateValue()
	{
		long qty = items.size() == 1 ? quantityValue() : 1;
		long total = 0;
		for (GoalItem item : items)
		{
			total += itemIndex.price(item.getId()) * qty;
		}
		value.setText(total > 0 ? Format.number(total) : "");
	}

	static Result show(Component parent, ItemIndex itemIndex, ItemManager itemManager, List<String> bosses)
	{
		MemoryDialog d = new MemoryDialog(itemIndex, itemManager, bosses);
		return FormDialog.show(parent, "Add a memory", d.form, d::build, r -> d.refit = r);
	}

	private Result build()
	{
		Kind k = (Kind) kind.getSelectedItem();
		LocalDate day = GoalPlanner.parseDate(date.getText().trim());
		if (day == null || day.isAfter(LocalDate.now()))
		{
			throw new IllegalArgumentException("Enter the date as YYYY-MM-DD (today or earlier).");
		}
		LocalTime at;
		try
		{
			at = LocalTime.parse(time.getText().trim());
		}
		catch (DateTimeParseException e)
		{
			throw new IllegalArgumentException("Enter the time as HH:MM, e.g. 18:30.");
		}
		long when = LocalDateTime.of(day, at).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

		String source = text(boss.getSelectedItem());
		Long kc = optionalNumber(killCount.getText(), "Enter the kill count as a number, or leave it blank.");
		String extra = text(note.getText());
		List<JourneyEvent> events = new ArrayList<>();
		List<JourneyService.LootItem> obtained = new ArrayList<>();

		switch (k)
		{
			case LOOT:
			case PET:
			case COLLECTION_LOG:
			{
				if (items.isEmpty())
				{
					throw new IllegalArgumentException("Choose the item.");
				}
				long qty = k == Kind.LOOT && items.size() == 1 ? quantityValue() : 1;
				Long total = k == Kind.LOOT ? optionalNumber(value.getText(), "Enter the value in gp, or leave it blank.") : null;
				for (GoalItem item : items)
				{
					long itemValue = total == null ? itemIndex.price(item.getId()) * qty
						: items.size() == 1 ? total : itemIndex.price(item.getId());
					String name = k == Kind.PET ? "Pet: " + item.getName()
						: qty > 1 ? Format.number(qty) + " x " + item.getName() : item.getName();
					List<String> parts = new ArrayList<>();
					if (k == Kind.LOOT && itemValue > 0)
					{
						parts.add(Format.compact(itemValue) + " gp");
					}
					if (source != null)
					{
						parts.add(source + (kc != null ? " · KC " + Format.number(kc) : ""));
					}
					else if (kc != null)
					{
						parts.add("KC " + Format.number(kc));
					}
					events.add(event(k.getType(), name, join(parts, extra), when, null, itemValue));
					obtained.add(new JourneyService.LootItem(item.getId(), item.getName(), (int) qty, itemValue));
				}
				break;
			}
			case KILL:
			{
				if (source == null)
				{
					throw new IllegalArgumentException("Choose or type the boss.");
				}
				String name = kc != null ? Format.number(kc) + " " + source + " KC" : "Killed " + source;
				List<String> parts = new ArrayList<>();
				String t = text(killTime.getText());
				if (t != null)
				{
					parts.add("Kill time " + t);
				}
				events.add(event(k.getType(), name, join(parts, extra), when, null, kc != null ? kc : 0));
				break;
			}
			case LEVEL:
			{
				Skill s = (Skill) skill.getSelectedItem();
				Long lvl = optionalNumber(level.getText(), "Enter the level as a number.");
				if (lvl == null || lvl < 2 || lvl > Experience.MAX_VIRT_LEVEL)
				{
					throw new IllegalArgumentException("Enter a level between 2 and " + Experience.MAX_VIRT_LEVEL + ".");
				}
				events.add(event(k.getType(), "Level " + lvl + " " + s.getName(), extra, when, s.name(), lvl));
				break;
			}
			case QUEST:
			{
				String q = text(quest.getSelectedItem());
				if (q == null)
				{
					throw new IllegalArgumentException("Choose or type the quest.");
				}
				events.add(event(k.getType(), "Completed " + q, extra, when, null, 0));
				break;
			}
			case CLUE:
			{
				String tier = (String) clueTier.getSelectedItem();
				Long loot = optionalNumber(value.getText(), "Enter the loot value in gp, or leave it blank.");
				List<String> parts = new ArrayList<>();
				if (loot != null && loot > 0)
				{
					parts.add("Loot " + Format.compact(loot) + " gp");
				}
				events.add(event(k.getType(), tier + " clue", join(parts, extra), when, null, loot != null ? loot : 0));
				break;
			}
			default:
			{
				String t = text(title.getText());
				if (t == null)
				{
					throw new IllegalArgumentException("Describe what happened.");
				}
				events.add(event(k.getType(), t.length() > 200 ? t.substring(0, 200) : t, extra, when, null, 0));
			}
		}
		return new Result(day, events, obtained);
	}

	private JourneyEvent event(EventType type, String name, String detail, long when, String skillKey, long val)
	{
		return new JourneyEvent(when, type, name, detail, skillKey, null, highlight.isSelected(), val);
	}

	private static String join(List<String> parts, String note)
	{
		List<String> all = new ArrayList<>(parts);
		if (note != null)
		{
			all.add(note);
		}
		return all.isEmpty() ? null : String.join(" · ", all);
	}

	private static String text(Object o)
	{
		if (o == null)
		{
			return null;
		}
		String s = o.toString().trim();
		return s.isEmpty() ? null : s;
	}

	private static Long optionalNumber(String text, String error)
	{
		String t = text.trim().replace(",", "");
		if (t.isEmpty())
		{
			return null;
		}
		try
		{
			return QuantityFormatter.parseQuantity(t);
		}
		catch (ParseException | NumberFormatException e)
		{
			throw new IllegalArgumentException(error);
		}
	}
}
