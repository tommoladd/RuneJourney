package com.runejourney.ui;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.*;

final class Ui
{
	static final Color GOLD = new Color(0xE0B040);
	static final Color MUTED = new Color(0x9A9A9A);
	static final Color GOOD = new Color(0x4CAF50);
	static final Color WARN = new Color(0xFFA726);
	static final Color BAD = new Color(0xEF5350);
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

	static JPanel accentCard(Color accent)
	{
		JPanel p = card();
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
			new EmptyBorder(5, 7, 5, 6)));
		return p;
	}

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

	static JLabel small(String text, Color color)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		return l;
	}

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

	static JPanel tile(String value, String caption, Color valueColor)
	{
		JPanel p = new JPanel(new BorderLayout(0, 2))
		{
			@Override
			public Dimension getPreferredSize()
			{
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
