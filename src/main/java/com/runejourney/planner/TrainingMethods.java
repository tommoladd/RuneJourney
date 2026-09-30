package com.runejourney.planner;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;

/**
 * Known training methods per skill, loaded from the bundled training_methods.json.
 */
@Slf4j
@Singleton
public class TrainingMethods
{
	private final Map<Skill, List<TrainingMethod>> methods = new EnumMap<>(Skill.class);

	@Inject
	public TrainingMethods(Gson gson)
	{
		try (InputStream in = TrainingMethods.class.getResourceAsStream("/com/runejourney/training_methods.json");
			Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
		{
			Map<String, List<TrainingMethod>> loaded = gson.fromJson(reader, new TypeToken<Map<String, List<TrainingMethod>>>()
			{
			}.getType());
			loaded.forEach((name, list) ->
			{
				Skill skill = Skills.parse(name);
				if (skill != null && list != null && !list.isEmpty())
				{
					methods.put(skill, Collections.unmodifiableList(list));
				}
			});
		}
		catch (Exception e)
		{
			log.warn("Unable to load RuneJourney training methods", e);
		}

		// Hitpoints is trained alongside combat; use a single flat estimate
		for (Skill s : Skills.ALL)
		{
			if (!methods.containsKey(s))
			{
				long rate = (long) XpRates.defaultRate(s, Intensity.BALANCED);
				methods.put(s, Collections.singletonList(new TrainingMethod(
					s == Skill.HITPOINTS ? "Alongside combat" : "Typical training", new long[][]{{0, rate}})));
			}
		}
	}

	public List<TrainingMethod> forSkill(Skill skill)
	{
		return methods.getOrDefault(skill, Collections.emptyList());
	}

	public TrainingMethod find(Skill skill, String name)
	{
		for (TrainingMethod m : forSkill(skill))
		{
			if (m.getName().equals(name))
			{
				return m;
			}
		}
		return null;
	}

	/**
	 * The method matching the player's preferred intensity: fastest, slowest, or the middle option,
	 * judged by the rate at their current XP.
	 */
	public TrainingMethod defaultFor(Skill skill, Intensity intensity, long xp)
	{
		List<TrainingMethod> sorted = new ArrayList<>(forSkill(skill));
		if (sorted.isEmpty())
		{
			return null;
		}
		sorted.sort(Comparator.comparingDouble((TrainingMethod m) -> m.rateAt(xp)).reversed());
		switch (intensity)
		{
			case EFFICIENT:
				return sorted.get(0);
			case RELAXED:
				return sorted.get(sorted.size() - 1);
			default:
				return sorted.get(sorted.size() / 2);
		}
	}
}
