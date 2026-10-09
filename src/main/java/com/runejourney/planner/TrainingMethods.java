package com.runejourney.planner;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.inject.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;

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
