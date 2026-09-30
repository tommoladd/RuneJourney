package com.runejourney.ui;

import com.runejourney.report.Analytics;
import com.runejourney.report.ChartMath;
import com.runejourney.report.Unit;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.JComponent;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Bar or line chart of a metric over time, with an optional dashed overlay of the previous period.
 */
class TimeSeriesChart extends JComponent
{
	enum Style
	{
		BARS, LINE
	}

	private static final Color GRID = new Color(255, 255, 255, 22);
	private static final Color AXIS = new Color(0x9A9A9A);
	private static final Color COMPARE = new Color(0x8A8170);

	private List<Analytics.Bucket> series = Collections.emptyList();
	private List<Analytics.Bucket> compare;
	private Unit unit = Unit.COUNT;
	private Color color = Ui.GOLD;
	private Style style = Style.BARS;
	private final boolean compact;
	private int hover = -1;
	private IntConsumer onClick;

	TimeSeriesChart(boolean compact)
	{
		this.compact = compact;
		setPreferredSize(new Dimension(400, compact ? 90 : 300));
		setOpaque(true);
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				int i = indexAt(e.getX());
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
				int i = indexAt(e.getX());
				if (onClick != null && i >= 0)
				{
					onClick.accept(i);
				}
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	void setData(List<Analytics.Bucket> series, List<Analytics.Bucket> compare, Unit unit, Color color, Style style)
	{
		this.series = series;
		this.compare = compare;
		this.unit = unit;
		this.color = color;
		this.style = style;
		this.hover = -1;
		repaint();
	}

	void setOnClick(IntConsumer onClick)
	{
		this.onClick = onClick;
		setCursor(onClick == null ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
	}

	private int left()
	{
		return compact ? 4 : 58;
	}

	private int bottom()
	{
		return compact ? 4 : 24;
	}

	private int top()
	{
		return compact ? 6 : 12;
	}

	private int right()
	{
		return compact ? 4 : 12;
	}

	private int indexAt(int x)
	{
		if (series.isEmpty())
		{
			return -1;
		}
		double slot = (getWidth() - left() - right()) / (double) series.size();
		int i = (int) ((x - left()) / slot);
		return i >= 0 && i < series.size() ? i : -1;
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

		int w = getWidth();
		int h = getHeight();
		int plotW = w - left() - right();
		int plotH = h - top() - bottom();
		if (series.isEmpty() || plotW <= 0 || plotH <= 0)
		{
			g.dispose();
			return;
		}

		double max = 0;
		for (Analytics.Bucket b : series)
		{
			max = Math.max(max, b.getValue());
		}
		if (compare != null)
		{
			for (Analytics.Bucket b : compare)
			{
				max = Math.max(max, b.getValue());
			}
		}
		if (max <= 0 && !compact)
		{
			g.setColor(AXIS);
			String msg = "Nothing recorded in this period";
			g.drawString(msg, (w - fm.stringWidth(msg)) / 2, h / 2);
		}
		double step = ChartMath.niceStep(max, 4);
		double topValue = ChartMath.niceMax(max, 4);

		if (!compact)
		{
			for (double v = 0; v <= topValue + step / 2; v += step)
			{
				int y = (int) Math.round(top() + plotH - v / topValue * plotH);
				g.setColor(GRID);
				g.drawLine(left(), y, w - right(), y);
				g.setColor(AXIS);
				String label = unit.axis(v);
				g.drawString(label, left() - 6 - fm.stringWidth(label), y + fm.getAscent() / 2 - 1);
			}
		}

		int n = series.size();
		double slot = plotW / (double) n;
		if (!compact)
		{
			g.setColor(AXIS);
			int every = ChartMath.labelEvery(n, Math.max(2, plotW / 70));
			for (int i = 0; i < n; i += every)
			{
				String label = series.get(i).getLabel();
				int x = (int) (left() + slot * i + slot / 2 - fm.stringWidth(label) / 2.0);
				g.drawString(label, Math.max(0, x), h - 6);
			}
		}

		if (compare != null && !compare.isEmpty())
		{
			Stroke old = g.getStroke();
			g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{5f, 4f}, 0f));
			g.setColor(COMPARE);
			Path2D path = new Path2D.Double();
			for (int i = 0; i < Math.min(n, compare.size()); i++)
			{
				double x = left() + slot * i + slot / 2;
				double y = top() + plotH - compare.get(i).getValue() / topValue * plotH;
				if (i == 0)
				{
					path.moveTo(x, y);
				}
				else
				{
					path.lineTo(x, y);
				}
			}
			g.draw(path);
			g.setStroke(old);
		}

		if (style == Style.LINE)
		{
			Path2D path = new Path2D.Double();
			Path2D area = new Path2D.Double();
			area.moveTo(left() + slot / 2, top() + plotH);
			for (int i = 0; i < n; i++)
			{
				double x = left() + slot * i + slot / 2;
				double y = top() + plotH - series.get(i).getValue() / topValue * plotH;
				if (i == 0)
				{
					path.moveTo(x, y);
				}
				else
				{
					path.lineTo(x, y);
				}
				area.lineTo(x, y);
			}
			area.lineTo(left() + slot * (n - 1) + slot / 2, top() + plotH);
			area.closePath();
			g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 40));
			g.fill(area);
			g.setColor(color);
			g.setStroke(new BasicStroke(compact ? 1.5f : 2.2f));
			g.draw(path);
			if (hover >= 0)
			{
				double x = left() + slot * hover + slot / 2;
				double y = top() + plotH - series.get(hover).getValue() / topValue * plotH;
				g.setColor(Color.WHITE);
				g.fillOval((int) x - 4, (int) y - 4, 8, 8);
			}
		}
		else
		{
			double barW = Math.max(1, slot * 0.72);
			for (int i = 0; i < n; i++)
			{
				double v = series.get(i).getValue();
				int bh = (int) Math.round(v / topValue * plotH);
				int x = (int) Math.round(left() + slot * i + (slot - barW) / 2);
				g.setColor(i == hover ? color.brighter() : color);
				g.fillRoundRect(x, top() + plotH - bh, (int) Math.max(1, Math.round(barW)), bh, 3, 3);
			}
		}

		if (hover >= 0 && !compact)
		{
			drawTooltip(g, fm, slot, plotH);
		}
		g.dispose();
	}

	private void drawTooltip(Graphics2D g, FontMetrics fm, double slot, int plotH)
	{
		Analytics.Bucket b = series.get(hover);
		String line1 = b.getLabel() + (b.getEnd() != null && !b.getEnd().equals(b.getStart()) ? " - " + b.getEnd().getDayOfMonth() : "");
		String line2 = unit.format(b.getValue());
		String line3 = compare != null && hover < compare.size() ? "Previous: " + unit.format(compare.get(hover).getValue()) : null;
		int tw = Math.max(fm.stringWidth(line1), Math.max(fm.stringWidth(line2), line3 == null ? 0 : fm.stringWidth(line3))) + 16;
		int th = (line3 == null ? 2 : 3) * (fm.getHeight() + 1) + 10;
		int x = (int) (left() + slot * hover + slot / 2 + 10);
		if (x + tw > getWidth() - 4)
		{
			x = (int) (left() + slot * hover + slot / 2 - tw - 10);
		}
		int y = top() + 6;
		g.setColor(new Color(20, 20, 20, 230));
		g.fillRoundRect(x, y, tw, th, 8, 8);
		g.setColor(Ui.GOLD);
		g.drawRoundRect(x, y, tw, th, 8, 8);
		int ty = y + 6 + fm.getAscent();
		g.setColor(Ui.MUTED);
		g.drawString(line1, x + 8, ty);
		g.setColor(Color.WHITE);
		g.drawString(line2, x + 8, ty + fm.getHeight() + 1);
		if (line3 != null)
		{
			g.setColor(COMPARE);
			g.drawString(line3, x + 8, ty + 2 * (fm.getHeight() + 1));
		}
	}
}
