package com.runejourney.planner;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * Typical minutes per kill for bosses, raids and minigames (including banking), and for clue scrolls.
 * Names match the kill count chat messages.
 */
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
	 * Typical minutes per kill, or -1 if unknown. Variants such as "Theatre of Blood: Hard Mode" fall
	 * back to their base activity when not listed.
	 */
	public double minutesPerKill(String name)
	{
		if (name == null)
		{
			return -1;
		}
		String lower = name.toLowerCase(Locale.ENGLISH);
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

	/**
	 * Typical minutes to complete one clue of the tier, including getting it.
	 */
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
