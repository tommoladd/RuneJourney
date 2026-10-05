package com.runejourney.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Small Swing building blocks shared by the RuneJourney tabs.
 */
final class Ui
{
	static final Color GOLD = new Color(0xE0B040);
	static final Color MUTED = new Color(0x9A9A9A);
	static final Color GOOD = new Color(0x4CAF50);
	static final Color WARN = new Color(0xFFA726);
	static final Color BAD = new Color(0xEF5350);
	/**
	 * Usable width for wrapped text inside a card.
	 */
	static final int TEXT_WIDTH = PluginPanel.PANEL_WIDTH - 60;

	private Ui()
	{
	}

	static JPanel stack(int gap)
	{
		JPanel p = new JPanel(new DynamicGridLayout(0, 1, 0, gap));
		p.setOpaque(false);
		return p;
	}

	static JPanel card()
	{
		JPanel p = stack(3);
		p.setOpaque(true);
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(new EmptyBorder(8, 8, 8, 8));
		return p;
	}

	/**
	 * A card with a coloured stripe down its left edge.
	 */
	static JPanel accentCard(Color accent)
	{
		JPanel p = card();
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
			new EmptyBorder(5, 7, 5, 6)));
		return p;
	}

	/*
	 * Type scale, used everywhere in the sidebar:
	 *   header - section headings: bold, gold, sentence case
	 *   title  - the name of a thing (event, goal, suggestion): regular, white
	 *   text / muted / small - everything else: small font
	 * Nothing is upper-cased; the RuneScape fonts read badly in capitals.
	 */

	static JLabel header(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeBoldFont());
		l.setForeground(GOLD);
		l.setBorder(new EmptyBorder(10, 2, 2, 0));
		return l;
	}

	static JLabel title(String text)
	{
		return label(text, FontManager.getRunescapeFont(), Color.WHITE);
	}

	/**
	 * Applies the sidebar font to a form control such as a dropdown or check box.
	 */
	static <T extends JComponent> T styled(T component)
	{
		component.setFont(FontManager.getRunescapeSmallFont());
		return component;
	}

	static JLabel text(String text)
	{
		return label(text, FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR);
	}

	static JLabel muted(String text)
	{
		return label(text, FontManager.getRunescapeSmallFont(), MUTED);
	}

	static JLabel label(String text, Font font, Color color)
	{
		return label(text, font, color, TEXT_WIDTH);
	}

	static JLabel label(String text, Font font, Color color, int width)
	{
		JLabel l = new JLabel(wrap(text, width));
		l.setFont(font);
		l.setForeground(color);
		return l;
	}

	/**
	 * Wraps text in HTML so long lines break within the sidebar.
	 */
	static String wrap(String text)
	{
		return wrap(text, TEXT_WIDTH);
	}

	static String wrap(String text, int width)
	{
		if (text == null)
		{
			return "";
		}
		return "<html><div style='width:" + width + "px'>" + escape(text) + "</div></html>";
	}

	static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/**
	 * A "label ........ value" row.
	 */
	static JPanel stat(String name, String value)
	{
		return stat(name, value, Color.WHITE);
	}

	static JPanel stat(String name, String value, Color valueColor)
	{
		JPanel row = rowPanel();
		JLabel l = new JLabel(name);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JLabel v = new JLabel(value);
		v.setFont(FontManager.getRunescapeSmallFont());
		v.setForeground(valueColor);
		v.setHorizontalAlignment(SwingConstants.RIGHT);
		v.setToolTipText(value);
		row.add(l, BorderLayout.WEST);
		row.add(v, BorderLayout.CENTER);
		return row;
	}

	/**
	 * A left/right row that never asks for more than the sidebar's width, so a long value is
	 * shortened with "..." rather than widening (and clipping) the whole view.
	 */
	private static JPanel rowPanel()
	{
		JPanel row = new JPanel(new BorderLayout(6, 0))
		{
			@Override
			public java.awt.Dimension getPreferredSize()
			{
				java.awt.Dimension d = super.getPreferredSize();
				d.width = Math.min(d.width, TEXT_WIDTH);
				return d;
			}
		};
		row.setOpaque(false);
		return row;
	}

	/**
	 * A short single-line label. Unlike {@link #text} it doesn't reserve the full wrapping width,
	 * so it can sit beside other components.
	 */
	static JLabel small(String text, Color color)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		return l;
	}

	/**
	 * A row with a label on the left and another on the right, neither wrapping.
	 */
	static JPanel row(JComponent left, JComponent right)
	{
		JPanel row = rowPanel();
		row.add(left, BorderLayout.WEST);
		if (right != null)
		{
			if (right instanceof JLabel)
			{
				((JLabel) right).setHorizontalAlignment(SwingConstants.RIGHT);
			}
			row.add(right, BorderLayout.CENTER);
		}
		return row;
	}

	static JButton button(String text, Runnable action)
	{
		JButton b = new JButton(text);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setFocusPainted(false);
		b.addActionListener(e -> action.run());
		return b;
	}

	/**
	 * Makes a card respond to clicks with a hover highlight.
	 */
	static void clickable(JPanel card, Runnable action)
	{
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		Color normal = card.getBackground();
		MouseAdapter listener = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				action.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				card.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				card.setBackground(normal);
			}
		};
		card.addMouseListener(listener);
	}

	/**
	 * A headline number with a caption beneath, for the grid at the top of a page.
	 */
	static JPanel tile(String value, String caption, Color valueColor)
	{
		JPanel p = new JPanel(new BorderLayout(0, 2))
		{
			@Override
			public Dimension getPreferredSize()
			{
				// Two share a row, so never ask for more than half the width
				Dimension d = super.getPreferredSize();
				d.width = Math.min(d.width, TEXT_WIDTH / 2);
				return d;
			}
		};
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(new EmptyBorder(6, 8, 6, 6));
		JLabel v = new JLabel(value);
		v.setFont(FontManager.getRunescapeBoldFont());
		v.setForeground(valueColor);
		JLabel c = small(caption, MUTED);
		p.add(v, BorderLayout.NORTH);
		p.add(c, BorderLayout.SOUTH);
		p.setToolTipText(caption + ": " + value);
		return p;
	}

	/**
	 * A section card with a gold heading.
	 */
	static JPanel section(String title)
	{
		JPanel card = card();
		JLabel h = new JLabel(title);
		h.setFont(FontManager.getRunescapeBoldFont());
		h.setForeground(GOLD);
		h.setBorder(new EmptyBorder(0, 0, 3, 0));
		card.add(h);
		return card;
	}

	/**
	 * A clickable text link.
	 */
	static JLabel link(String text, Runnable action)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(GOLD);
		l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		l.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				action.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				l.setForeground(Color.WHITE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				l.setForeground(GOLD);
			}
		});
		return l;
	}

	static JPanel empty(String message)
	{
		JPanel p = card();
		p.add(muted(message));
		return p;
	}

	static JComponent progress(double fraction, Color color)
	{
		return new ProgressBar(fraction, color);
	}

	private static class ProgressBar extends JComponent
	{
		private final double fraction;
		private final Color color;

		ProgressBar(double fraction, Color color)
		{
			this.fraction = Math.max(0, Math.min(1, fraction));
			this.color = color;
			setPreferredSize(new Dimension(10, 6));
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g;
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(ColorScheme.DARK_GRAY_COLOR.darker());
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), 4, 4);
			g2.setColor(color);
			g2.fillRoundRect(0, 0, (int) Math.round(getWidth() * fraction), getHeight(), 4, 4);
		}
	}
}
