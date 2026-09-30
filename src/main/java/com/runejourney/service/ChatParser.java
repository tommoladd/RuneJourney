package com.runejourney.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;

/**
 * Recognises game messages that represent Journey-worthy events. Input must already have
 * formatting tags removed.
 */
public final class ChatParser
{
	private static final Pattern KILL_COUNT = Pattern.compile(
		"^Your (?:completed )?(.+?) (?:kill |chest |completion |success |rescue )?count is: ?([\\d,]+)\\.?$");
	private static final Pattern COLLECTION_LOG = Pattern.compile("^New item added to your collection log: (.+)$");
	private static final Pattern QUEST = Pattern.compile("^Congratulations, you've completed a quest: (.+?)\\.?$");
	private static final Pattern COMBAT_TASK = Pattern.compile(
		"you've completed an? (\\w+) combat task: (.+?)(?: \\(([\\d,]+) points?\\))?\\.?$", Pattern.CASE_INSENSITIVE);
	/**
	 * Some game messages carry a machine-readable prefix such as "CA_ID:1234|".
	 */
	private static final Pattern ID_PREFIX = Pattern.compile("^[A-Z_]+:\\d+\\|");
	/**
	 * Colour markers the game embeds in some messages, e.g. "@ach_comp@" before a combat task name.
	 */
	private static final Pattern MARKER = Pattern.compile("@[A-Za-z0-9_]+@");
	private static final Pattern DIARY_TIER = Pattern.compile(
		"^Congratulations! You have completed all of the (\\w+) tasks in the (.+?) area\\..*$");
	private static final Pattern PERSONAL_BEST = Pattern.compile(
		"(\\d+:\\d{2}(?::\\d{2})?(?:\\.\\d{2})?) \\(new personal best\\)", Pattern.CASE_INSENSITIVE);
	private static final Pattern SLAYER_TASK = Pattern.compile("^You've completed ([\\d,]+) tasks?.*$");
	private static final Pattern CLUE = Pattern.compile("^You have completed ([\\d,]+) (\\w+) Treasure Trails?\\.?$");

	private ChatParser()
	{
	}

	public enum Kind
	{
		KILL_COUNT,
		COLLECTION_LOG,
		QUEST,
		COMBAT_TASK,
		DIARY_TIER,
		PERSONAL_BEST,
		PET,
		DUPLICATE_PET,
		SLAYER_TASK,
		CLUE,
	}

	@Value
	public static class Result
	{
		Kind kind;
		/**
		 * Primary subject: boss, item, quest, task or area name, or PB time.
		 */
		String name;
		/**
		 * Secondary detail such as combat task tier or diary tier.
		 */
		String detail;
		int count;
	}

	public static Result parse(String message)
	{
		message = clean(ID_PREFIX.matcher(message.trim()).replaceFirst(""));
		Matcher m = KILL_COUNT.matcher(message);
		if (m.matches())
		{
			return new Result(Kind.KILL_COUNT, normalizeBoss(m.group(1)), null, parseInt(m.group(2)));
		}

		m = COLLECTION_LOG.matcher(message);
		if (m.matches())
		{
			return new Result(Kind.COLLECTION_LOG, m.group(1).trim(), null, 0);
		}

		m = QUEST.matcher(message);
		if (m.matches())
		{
			return new Result(Kind.QUEST, m.group(1).trim(), null, 0);
		}

		m = COMBAT_TASK.matcher(message);
		if (m.find())
		{
			int points = m.group(3) != null ? parseInt(m.group(3)) : defaultTaskPoints(m.group(1));
			return new Result(Kind.COMBAT_TASK, m.group(2).trim(), capitalize(m.group(1)), points);
		}

		m = DIARY_TIER.matcher(message);
		if (m.matches())
		{
			return new Result(Kind.DIARY_TIER, m.group(2).trim(), capitalize(m.group(1)), 0);
		}

		m = PERSONAL_BEST.matcher(message);
		if (m.find())
		{
			return new Result(Kind.PERSONAL_BEST, m.group(1), null, 0);
		}

		if (message.startsWith("You have a funny feeling like you're being followed")
			|| message.startsWith("You feel something weird sneaking into your backpack"))
		{
			return new Result(Kind.PET, null, null, 0);
		}

		if (message.startsWith("You have a funny feeling like you would have been followed"))
		{
			return new Result(Kind.DUPLICATE_PET, null, null, 0);
		}

		m = SLAYER_TASK.matcher(message);
		if (m.matches())
		{
			return new Result(Kind.SLAYER_TASK, null, null, parseInt(m.group(1)));
		}

		m = CLUE.matcher(message);
		if (m.matches())
		{
			return new Result(Kind.CLUE, capitalize(m.group(2)), null, parseInt(m.group(1)));
		}

		return null;
	}

	/**
	 * Removes the game's embedded colour markers from text.
	 */
	public static String clean(String text)
	{
		return text == null ? null : MARKER.matcher(text).replaceAll("").trim();
	}

	private static String normalizeBoss(String name)
	{
		// "Your subdued Wintertodt count is: 12"
		if (name.startsWith("subdued "))
		{
			name = name.substring("subdued ".length());
		}
		return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	/**
	 * Points per combat task tier, for messages that don't state them.
	 */
	private static int defaultTaskPoints(String tier)
	{
		switch (tier.toLowerCase())
		{
			case "easy":
				return 1;
			case "medium":
				return 2;
			case "hard":
				return 3;
			case "elite":
				return 4;
			case "master":
				return 5;
			case "grandmaster":
				return 6;
			default:
				return 0;
		}
	}

	private static int parseInt(String s)
	{
		try
		{
			return Integer.parseInt(s.replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private static String capitalize(String s)
	{
		if (s == null || s.isEmpty())
		{
			return s;
		}
		return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
	}
}
