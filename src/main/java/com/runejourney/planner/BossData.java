package com.runejourney.planner;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.inject.*;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class BossData
{
	@Value
	public static class Boss
	{
		String name;
		double minutes;
	}

	private final List<Boss> bosses = new ArrayList<>();

	@Inject
	public BossData(Gson gson)
	{
		try (InputStream in = BossData.class.getResourceAsStream("/com/runejourney/bosses.json");
			Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
		{
			List<Boss> loaded = gson.fromJson(reader, new TypeToken<List<Boss>>()
			{
			}.getType());
			bosses.addAll(loaded);
		}
		catch (Exception e)
		{
			log.warn("Unable to load RuneJourney boss data", e);
		}
	}

	public List<Boss> all()
	{
		return Collections.unmodifiableList(bosses);
	}

	/**
	 * Names compared loosely, because the game's kill count message, the hiscores and older versions of
	 * this list disagree on a leading "The" and on plurals ("The Leviathan" vs "Leviathan").
	 */
	public static String key(String name)
	{
		String k = name.trim().toLowerCase(Locale.ENGLISH);
		if (k.startsWith("the "))
		{
			k = k.substring("the ".length());
		}
		if (k.endsWith("s"))
		{
			k = k.substring(0, k.length() - 1);
		}
		return k;
	}

	/** This list's spelling of the boss, or the name unchanged if it isn't listed */
	public String canonical(String name)
	{
		if (name == null)
		{
			return null;
		}
		String k = key(name);
		for (Boss b : bosses)
		{
			if (key(b.getName()).equals(k))
			{
				return b.getName();
			}
		}
		return name;
	}

	public double minutesPerKill(String name)
	{
		if (name == null)
		{
			return -1;
		}
		String lower = canonical(name).toLowerCase(Locale.ENGLISH);
		Boss best = null;
		for (Boss b : bosses)
		{
			String key = b.getName().toLowerCase(Locale.ENGLISH);
			if (key.equals(lower))
			{
				return b.getMinutes();
			}
			if (lower.startsWith(key) && (best == null || key.length() > best.getName().length()))
			{
				best = b;
			}
		}
		return best == null ? -1 : best.getMinutes();
	}

	public static double minutesPerClue(String tier)
	{
		switch (tier)
		{
			case "Beginner":
				return 5;
			case "Easy":
				return 6;
			case "Medium":
				return 8;
			case "Hard":
				return 12;
			case "Elite":
				return 20;
			case "Master":
				return 30;
			default:
				return 12;
		}
	}
}
