package com.runejourney.ui;

import com.runejourney.model.GoalItem;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.*;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

final class ItemSearchDialog
{
	private final ItemIndex index;
	private final ItemManager itemManager;
	private final Map<Integer, ImageIcon> icons = new HashMap<>();

	private final JDialog dialog;
	private final JTextField search = new JTextField();
	private final JLabel status = new JLabel(" ");
	private final DefaultListModel<ItemIndex.Entry> results = new DefaultListModel<>();
	private final DefaultListModel<ItemIndex.Entry> chosen = new DefaultListModel<>();
	private final JList<ItemIndex.Entry> resultList = new JList<>(results);
	private final JList<ItemIndex.Entry> chosenList = new JList<>(chosen);
	private boolean confirmed;

	private ItemSearchDialog(Component parent, ItemIndex index, ItemManager itemManager, List<GoalItem> initial)
	{
		this.index = index;
		this.itemManager = itemManager;
		Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
		dialog = new JDialog(owner, "Choose items", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);

		for (GoalItem item : initial)
		{
			chosen.addElement(new ItemIndex.Entry(item.getId(), item.getName()));
		}

		ItemRenderer renderer = new ItemRenderer();
		resultList.setCellRenderer(renderer);
		chosenList.setCellRenderer(renderer);
		resultList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		resultList.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getClickCount() == 2)
				{
					addSelected();
				}
			}
		});
		chosenList.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getClickCount() == 2)
				{
					removeSelected();
				}
			}
		});

		Timer debounce = new Timer(250, e -> runSearch());
		debounce.setRepeats(false);
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				debounce.restart();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				debounce.restart();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				debounce.restart();
			}
		});
		search.addActionListener(e -> runSearch());

		JPanel top = new JPanel(new BorderLayout(0, 4));
		top.add(new JLabel("Search items (e.g. \"twisted bow\", \"pet\", \"ancestral\")"), BorderLayout.NORTH);
		top.add(search, BorderLayout.CENTER);
		top.add(status, BorderLayout.SOUTH);

		JPanel left = new JPanel(new BorderLayout(0, 4));
		left.add(new JLabel("Results (double-click to add)"), BorderLayout.NORTH);
		left.add(new JScrollPane(resultList), BorderLayout.CENTER);
		JButton add = new JButton("Add >");
		add.addActionListener(e -> addSelected());
		left.add(add, BorderLayout.SOUTH);

		JPanel right = new JPanel(new BorderLayout(0, 4));
		right.add(new JLabel("Your goal items"), BorderLayout.NORTH);
		right.add(new JScrollPane(chosenList), BorderLayout.CENTER);
		JButton remove = new JButton("< Remove");
		remove.addActionListener(e -> removeSelected());
		right.add(remove, BorderLayout.SOUTH);

		JPanel lists = new JPanel(new GridLayout(1, 2, 8, 0));
		lists.add(left);
		lists.add(right);

		JPanel buttons = new JPanel(new GridLayout(1, 2, 8, 0));
		JButton ok = new JButton("OK");
		ok.addActionListener(e ->
		{
			confirmed = true;
			dialog.dispose();
		});
		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> dialog.dispose());
		buttons.add(ok);
		buttons.add(cancel);

		JPanel content = new JPanel(new BorderLayout(0, 8));
		content.setBorder(new EmptyBorder(10, 10, 10, 10));
		content.add(top, BorderLayout.NORTH);
		content.add(lists, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		dialog.setContentPane(content);
		dialog.setSize(new Dimension(560, 440));
		dialog.setLocationRelativeTo(owner);

		if (!index.isReady())
		{
			status.setText("Loading item names...");
			search.setEnabled(false);
			index.load(() ->
			{
				status.setText(" ");
				search.setEnabled(true);
				search.requestFocusInWindow();
				runSearch();
			});
		}
	}

	static List<GoalItem> show(Component parent, ItemIndex index, ItemManager itemManager, List<GoalItem> initial)
	{
		ItemSearchDialog d = new ItemSearchDialog(parent, index, itemManager, initial);
		d.dialog.setVisible(true);
		if (!d.confirmed)
		{
			return null;
		}
		List<GoalItem> items = new ArrayList<>();
		for (int i = 0; i < d.chosen.size(); i++)
		{
			ItemIndex.Entry e = d.chosen.get(i);
			items.add(new GoalItem(e.getId(), e.getName()));
		}
		return items;
	}

	private void runSearch()
	{
		results.clear();
		List<ItemIndex.Entry> found = index.search(search.getText(), 100);
		for (ItemIndex.Entry e : found)
		{
			results.addElement(e);
		}
		if (index.isReady())
		{
			status.setText(search.getText().trim().length() < 2 ? "Type at least 2 letters" : found.size() + " found");
		}
	}

	private void addSelected()
	{
		for (ItemIndex.Entry e : resultList.getSelectedValuesList())
		{
			boolean present = false;
			for (int i = 0; i < chosen.size(); i++)
			{
				present |= chosen.get(i).getName().equalsIgnoreCase(e.getName());
			}
			if (!present)
			{
				chosen.addElement(e);
			}
		}
	}

	private void removeSelected()
	{
		for (ItemIndex.Entry e : chosenList.getSelectedValuesList())
		{
			chosen.removeElement(e);
		}
	}

	private ImageIcon icon(int id, JList<?> list)
	{
		if (id <= 0)
		{
			return null;
		}
		return icons.computeIfAbsent(id, k ->
		{
			AsyncBufferedImage img = itemManager.getImage(k);
			ImageIcon icon = new ImageIcon(img);
			img.onLoaded(() ->
			{
				icon.setImage(img);
				resultList.repaint();
				chosenList.repaint();
			});
			return icon;
		});
	}

	private class ItemRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
		{
			JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			ItemIndex.Entry e = (ItemIndex.Entry) value;
			label.setText(e.getName());
			label.setIcon(icon(e.getId(), list));
			label.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			return label;
		}
	}
}
