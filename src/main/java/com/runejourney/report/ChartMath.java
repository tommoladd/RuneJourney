package com.runejourney.report;

/**
 * Axis helpers shared by the Swing and SVG charts.
 */
public final class ChartMath
{
	private ChartMath()
	{
	}

	/**
	 * A "nice" step for about {@code ticks} gridlines up to {@code max}: 1, 2 or 5 times a power of ten.
	 */
	public static double niceStep(double max, int ticks)
	{
		if (max <= 0)
		{
			return 1;
		}
		double raw = max / ticks;
		double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
		double residual = raw / magnitude;
		double nice = residual > 5 ? 10 : residual > 2 ? 5 : residual > 1 ? 2 : 1;
		return nice * magnitude;
	}

	public static double niceMax(double max, int ticks)
	{
		double step = niceStep(max, ticks);
		return Math.max(step, Math.ceil(max / step) * step);
	}

	/**
	 * Show every n-th x label so that at most {@code maxLabels} are drawn.
	 */
	public static int labelEvery(int count, int maxLabels)
	{
		return Math.max(1, (int) Math.ceil(count / (double) Math.max(1, maxLabels)));
	}
}
