package com.runejourney.report;

import java.util.List;
import java.util.Locale;

/**
 * Inline SVG charts for exported HTML reports. Hovering a bar or point shows its value.
 */
public final class SvgCharts
{
	private static final int W = 900;
	private static final int H = 260;
	private static final int LEFT = 58;
	private static final int RIGHT = 12;
	private static final int TOP = 14;
	private static final int BOTTOM = 30;
	private static final String GRID = "#3a3226";
	private static final String AXIS_TEXT = "#a79d8b";
	private static final String COMPARE = "#8a8170";

	private SvgCharts()
	{
	}

	public static String timeSeries(List<Analytics.Bucket> series, List<Analytics.Bucket> compare, Unit unit, String color, boolean line)
	{
		StringBuilder s = new StringBuilder();
		s.append("<svg class=\"chart\" viewBox=\"0 0 ").append(W).append(' ').append(H)
			.append("\" preserveAspectRatio=\"none\" role=\"img\">");
		if (series.isEmpty())
		{
			return s.append("</svg>").toString();
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
		double step = ChartMath.niceStep(max, 4);
		double top = ChartMath.niceMax(max, 4);
		int plotW = W - LEFT - RIGHT;
		int plotH = H - TOP - BOTTOM;

		for (double v = 0; v <= top + step / 2; v += step)
		{
			double y = TOP + plotH - v / top * plotH;
			s.append("<line x1=\"").append(LEFT).append("\" x2=\"").append(W - RIGHT).append("\" y1=\"").append(f(y))
				.append("\" y2=\"").append(f(y)).append("\" stroke=\"").append(GRID).append("\"/>");
			s.append("<text x=\"").append(LEFT - 6).append("\" y=\"").append(f(y + 4)).append("\" text-anchor=\"end\" fill=\"")
				.append(AXIS_TEXT).append("\" font-size=\"11\">").append(esc(unit.axis(v))).append("</text>");
		}

		int n = series.size();
		double slot = plotW / (double) n;
		int every = ChartMath.labelEvery(n, 12);
		for (int i = 0; i < n; i++)
		{
			if (i % every == 0)
			{
				double x = LEFT + slot * i + slot / 2;
				s.append("<text x=\"").append(f(x)).append("\" y=\"").append(H - 10).append("\" text-anchor=\"middle\" fill=\"")
					.append(AXIS_TEXT).append("\" font-size=\"11\">").append(esc(series.get(i).getLabel())).append("</text>");
			}
		}

		if (compare != null && !compare.isEmpty())
		{
			s.append("<polyline fill=\"none\" stroke=\"").append(COMPARE).append("\" stroke-width=\"2\" stroke-dasharray=\"5 4\" points=\"");
			for (int i = 0; i < Math.min(n, compare.size()); i++)
			{
				double x = LEFT + slot * i + slot / 2;
				double y = TOP + plotH - compare.get(i).getValue() / top * plotH;
				s.append(f(x)).append(',').append(f(y)).append(' ');
			}
			s.append("\"/>");
		}

		if (line)
		{
			s.append("<polyline fill=\"none\" stroke=\"").append(color).append("\" stroke-width=\"2.5\" points=\"");
			for (int i = 0; i < n; i++)
			{
				double x = LEFT + slot * i + slot / 2;
				double y = TOP + plotH - series.get(i).getValue() / top * plotH;
				s.append(f(x)).append(',').append(f(y)).append(' ');
			}
			s.append("\"/>");
			for (int i = 0; i < n; i++)
			{
				Analytics.Bucket b = series.get(i);
				double x = LEFT + slot * i + slot / 2;
				double y = TOP + plotH - b.getValue() / top * plotH;
				s.append("<circle cx=\"").append(f(x)).append("\" cy=\"").append(f(y)).append("\" r=\"").append(n > 60 ? 2 : 3.5)
					.append("\" fill=\"").append(color).append("\"><title>").append(esc(tooltip(b, compare, i, unit)))
					.append("</title></circle>");
			}
		}
		else
		{
			double barW = Math.max(1, slot * 0.72);
			for (int i = 0; i < n; i++)
			{
				Analytics.Bucket b = series.get(i);
				double h = b.getValue() / top * plotH;
				double x = LEFT + slot * i + (slot - barW) / 2;
				s.append("<rect x=\"").append(f(x)).append("\" y=\"").append(f(TOP + plotH - h)).append("\" width=\"").append(f(barW))
					.append("\" height=\"").append(f(Math.max(0, h))).append("\" rx=\"2\" fill=\"").append(color).append("\"><title>")
					.append(esc(tooltip(b, compare, i, unit))).append("</title></rect>");
			}
		}
		return s.append("</svg>").toString();
	}

	private static String tooltip(Analytics.Bucket b, List<Analytics.Bucket> compare, int i, Unit unit)
	{
		String t = b.getLabel() + ": " + unit.format(b.getValue());
		if (compare != null && i < compare.size())
		{
			t += " (previous: " + unit.format(compare.get(i).getValue()) + ")";
		}
		return t;
	}

	/**
	 * Horizontal bars, largest first.
	 */
	public static String bars(List<Analytics.Entry> entries, Unit unit, String color, int maxRows)
	{
		int rows = Math.min(maxRows, entries.size());
		int rowH = 24;
		int labelW = 170;
		int valueW = 90;
		int height = Math.max(rowH, rows * rowH + 6);
		StringBuilder s = new StringBuilder();
		s.append("<svg class=\"bars\" viewBox=\"0 0 ").append(W).append(' ').append(height).append("\" role=\"img\">");
		double max = 0;
		for (int i = 0; i < rows; i++)
		{
			max = Math.max(max, entries.get(i).getValue());
		}
		double barSpace = W - labelW - valueW - 12;
		for (int i = 0; i < rows; i++)
		{
			Analytics.Entry e = entries.get(i);
			double y = i * rowH + 4;
			double w = max <= 0 ? 0 : Math.max(2, e.getValue() / max * barSpace);
			s.append("<text x=\"").append(labelW - 8).append("\" y=\"").append(f(y + 15)).append("\" text-anchor=\"end\" fill=\"#ece4d4\" font-size=\"13\">")
				.append(esc(e.getLabel())).append("</text>");
			s.append("<rect x=\"").append(labelW).append("\" y=\"").append(f(y + 3)).append("\" width=\"").append(f(w))
				.append("\" height=\"16\" rx=\"3\" fill=\"").append(color).append("\"><title>").append(esc(e.getLabel() + ": " + unit.format(e.getValue())))
				.append("</title></rect>");
			s.append("<text x=\"").append(f(labelW + w + 8)).append("\" y=\"").append(f(y + 15)).append("\" fill=\"").append(AXIS_TEXT)
				.append("\" font-size=\"12\">").append(esc(unit.format(e.getValue()))).append("</text>");
		}
		return s.append("</svg>").toString();
	}

	private static String f(double v)
	{
		return String.format(Locale.ENGLISH, "%.1f", v);
	}

	private static String esc(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
