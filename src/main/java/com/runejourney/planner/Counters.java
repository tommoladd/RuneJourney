package com.runejourney.planner;

import com.runejourney.util.Format;
import java.util.*;

public final class Counters
{
	public static final String KC_PREFIX = "kc:";
	public static final String CLUES_PREFIX = "clues:";
	public static final String ALL_TIERS = "All";
	public static final String CA_POINTS = "ca:points";
	public static final String CA_TASKS = "ca:tasks";
	public static final String QUEST_POINTS = "qp";
	public static final String COLLECTION_LOG = "clog";
	public static final String CASH = "cash";
	public static final String WEALTH = "wealth";
	public static final String CLOG_PAGE_PREFIX = "clogpage:";

	public static final List<String> CLUE_TIERS = Collections.unmodifiableList(
		Arrays.asList("Beginner", "Easy", "Medium", "Hard", "Elite", "Master"));

	private Counters()
	{
	}

	public static String kc(String boss)
	{
		return KC_PREFIX + boss;
	}

	public static String clues(String tier)
	{
		return CLUES_PREFIX + tier;
	}

	public static String clogPage(String page)
	{
		return CLOG_PAGE_PREFIX + page;
	}

	public static boolean isClogPage(String key)
	{
		return key != null && key.startsWith(CLOG_PAGE_PREFIX);
	}

	public static boolean isMoney(String key)
	{
		return CASH.equals(key) || WEALTH.equals(key);
	}

	public static boolean isKc(String key)
	{
		return key != null && key.startsWith(KC_PREFIX);
	}

	public static boolean isClues(String key)
	{
		return key != null && key.startsWith(CLUES_PREFIX);
	}

	public static String suffix(String key)
	{
		int i = key.indexOf(':');
		return i < 0 ? key : key.substring(i + 1);
	}

	public static String label(String key)
	{
		if (isKc(key))
		{
			return suffix(key) + " KC";
		}
		if (isClues(key))
		{
			String tier = suffix(key);
			return ALL_TIERS.equals(tier) ? "Clue scrolls" : tier + " clues";
		}
		if (isClogPage(key))
		{
			return suffix(key) + " log";
		}
		switch (key)
		{
			case CA_POINTS:
				return "CA points";
			case CA_TASKS:
				return "Combat tasks";
			case QUEST_POINTS:
				return "Quest points";
			case COLLECTION_LOG:
				return "Collection log";
			case CASH:
				return "Cash stack";
			case WEALTH:
				return "Net worth";
			default:
				return key;
		}
	}

	public static String format(String key, long value)
	{
		if (isMoney(key))
		{
			return Format.compact(value) + " gp";
		}
		return Format.number(value);
	}

	public static String unit(String key)
	{
		if (isKc(key))
		{
			return "kills";
		}
		if (isClues(key))
		{
			return "clues";
		}
		if (isClogPage(key))
		{
			return "items";
		}
		switch (key)
		{
			case CA_POINTS:
			case QUEST_POINTS:
				return "points";
			case CA_TASKS:
				return "tasks";
			case COLLECTION_LOG:
				return "slots";
			case CASH:
			case WEALTH:
				return "gp";
			default:
				return "";
		}
	}
}
