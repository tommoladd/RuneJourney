package com.runejourney.ui;

import com.runejourney.report.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Collections;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import net.runelite.client.ui.*;

class BarChart extends JComponent
{
	private static final int ROW = 22;
	private static final int LABEL_W = 140;

	private List<Analytics.Entry> entries = Collections.emptyList();
	private Unit unit = Unit.COUNT;
	private Color color = Ui.GOLD;
	private int maxRows;
	private int hover = -1;
	private Consumer<Analytics.Entry> onClick;
	private Function<String, Icon> icons = k -> null;
	private Function<String, String> labels = k -> k;
	private String emptyText = "Nothing to show";

	BarChart(int maxRows)
	{
		this.maxRows = maxRows;
		setOpaque(true);
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				int i = rowAt(e.getY());
				if (i != hover)
				{
					hover = i;
					repaint();
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hover = -1;
				repaint();
			}

			@Override
			public void mouseClicked(MouseEvent e)
			{
				int i = rowAt(e.getY());
				if (onClick != null && i >= 0)
				{
					onClick.accept(entries.get(i));
				}
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	void setData(List<Analytics.Entry> entries, Unit unit, Color color)
	{
		this.entries = entries;
		this.unit = unit;
		this.color = color;
		hover = -1;
		revalidate();
		repaint();
	}

	void setOnClick(Consumer<Analytics.Entry> onClick)
	{
		this.onClick = onClick;
		setCursor(onClick == null ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
	}

	void setIcons(Function<String, Icon> icons)
	{
		this.icons = icons;
	}

	void setLabels(Function<String, String> labels)
	{
		this.labels = labels;
	}

	void setEmptyText(String emptyText)
	{
		this.emptyText = emptyText;
	}

	private int rows()
	{
		return Math.min(maxRows, entries.size());
	}

	private int rowAt(int y)
	{
		int i = (y - 4) / ROW;
		return i >= 0 && i < rows() ? i : -1;
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(300, Math.max(ROW + 8, rows() * ROW + 8));
	}

	@Override
	protected void paintComponent(Graphics g0)
	{
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(getBackground());
		g.fillRect(0, 0, getWidth(), getHeight());
		g.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = g.getFontMetrics();

		int rows = rows();
		if (rows == 0)
		{
			g.setColor(Ui.MUTED);
			g.drawString(emptyText, 8, 4 + fm.getAscent() + 3);
			g.dispose();
			return;
		}

		double max = 0;
		for (int i = 0; i < rows; i++)
		{
			max = Math.max(max, entries.get(i).getValue());
		}
		int valueW = 80;
		int barSpace = Math.max(10, getWidth() - LABEL_W - valueW - 12);
		for (int i = 0; i < rows; i++)
		{
			Analytics.Entry e = entries.get(i);
			int y = 4 + i * ROW;
			if (i == hover)
			{
				g.setColor(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				g.fillRect(0, y, getWidth(), ROW);
			}
			int textY = y + (ROW + fm.getAscent()) / 2 - 2;
			int lx = 6;
			Icon icon = icons.apply(e.getKey());
			if (icon != null)
			{
				icon.paintIcon(this, g, lx, y + (ROW - icon.getIconHeight()) / 2);
				lx += icon.getIconWidth() + 5;
			}
			g.setColor(Color.WHITE);
			String label = labels.apply(e.getLabel());
			while (fm.stringWidth(label) > LABEL_W - lx - 6 && label.length() > 3)
			{
				label = label.substring(0, label.length() - 2);
			}
			g.drawString(label, lx, textY);

			int w = max <= 0 ? 0 : (int) Math.max(2, e.getValue() / max * barSpace);
			g.setColor(i == hover ? color.brighter() : color);
			g.fillRoundRect(LABEL_W, y + 5, w, ROW - 10, 4, 4);
			g.setColor(Ui.MUTED);
			g.drawString(unit.format(e.getValue()), LABEL_W + w + 6, textY);
		}
		g.dispose();
	}
}
