package com.runejourney.util;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.runelite.api.Skill;

public final class Format
{
	private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

	private Format()
	{
	}

	public static String number(long n)
	{
		return NumberFormat.getIntegerInstance(Locale.ENGLISH).format(n);
	}

	public static String compact(long n)
	{
		long abs = Math.abs(n);
		String sign = n < 0 ? "-" : "";
		if (abs >= 1_000_000_000L)
		{
			return sign + trim(abs / 1_000_000_000d) + "b";
		}
		if (abs >= 1_000_000L)
		{
			return sign + trim(abs / 1_000_000d) + "m";
		}
		if (abs >= 10_000L)
		{
			return sign + Math.round(abs / 1_000d) + "k";
		}
		return sign + number(abs);
	}

	private static String trim(double v)
	{
		String s = String.format(Locale.ENGLISH, v >= 100 ? "%.0f" : v >= 10 ? "%.1f" : "%.2f", v);
		if (s.contains("."))
		{
			s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
		}
		return s;
	}

	public static String duration(long millis)
	{
		long minutes = millis / 60_000;
		long hours = minutes / 60;
		minutes %= 60;
		if (hours == 0)
		{
			return minutes + " min";
		}
		return hours + "h " + minutes + "m";
	}

	public static String clock(double minutes)
	{
		long seconds = Math.max(1, Math.round(minutes * 60));
		long h = seconds / 3600;
		long m = seconds / 60 % 60;
		long s = seconds % 60;
		return h > 0
			? String.format(Locale.ENGLISH, "%d:%02d:%02d", h, m, s)
			: String.format(Locale.ENGLISH, "%d:%02d", m, s);
	}

	public static String hours(double hours)
	{
		if (hours < 1)
		{
			return Math.max(1, Math.round(hours * 60)) + " min";
		}
		if (hours < 10)
		{
			return String.format(Locale.ENGLISH, "%.1fh", hours);
		}
		return Math.round(hours) + "h";
	}

	public static String date(LocalDate date)
	{
		return date == null ? "" : date.format(SHORT_DATE);
	}

	public static String skill(Skill skill)
	{
		return skill.getName();
	}

	public static String percent(double fraction)
	{
		return String.format(Locale.ENGLISH, "%.1f%%", fraction * 100);
	}
}
