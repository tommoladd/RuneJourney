package com.runejourney.report;

import com.runejourney.util.Format;
import java.util.Locale;

/**
 * How a metric's values are displayed.
 */
public enum Unit
{
	COUNT,
	GP,
	HOURS,
	RATE;

	public String format(double v)
	{
		switch (this)
		{
			case GP:
				return Format.compact(Math.round(v)) + " gp";
			case HOURS:
				return v <= 0 ? "0 min" : Format.hours(v);
			case RATE:
				return Format.compact(Math.round(v)) + "/hr";
			default:
				if (v != Math.floor(v) && Math.abs(v) < 100)
				{
					return String.format(Locale.ENGLISH, "%.1f", v);
				}
				return Format.compact(Math.round(v));
		}
	}

	/**
	 * Shorter form for chart axes.
	 */
	public String axis(double v)
	{
		switch (this)
		{
			case HOURS:
				return v == Math.floor(v) ? (long) v + "h" : String.format(Locale.ENGLISH, "%.1fh", v);
			case GP:
			case RATE:
			default:
				if (v != Math.floor(v) && Math.abs(v) < 10)
				{
					return String.format(Locale.ENGLISH, "%.1f", v);
				}
				return Format.compact(Math.round(v));
		}
	}
}
