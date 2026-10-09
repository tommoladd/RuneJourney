package com.runejourney.report;

public final class ChartMath
{
	private ChartMath()
	{
	}

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

	public static int labelEvery(int count, int maxLabels)
	{
		return Math.max(1, (int) Math.ceil(count / (double) Math.max(1, maxLabels)));
	}
}
