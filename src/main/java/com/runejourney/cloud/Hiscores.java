package com.runejourney.cloud;

import com.runejourney.service.PublicSnapshot;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import lombok.Data;
import net.runelite.client.hiscore.*;

public final class Hiscores
{
	private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} '’():&.,!+/-]{1,60}");

	private Hiscores()
	{
	}

	public interface Lookup
	{
		CompletableFuture<HiscoreResult> lookup(String name, HiscoreEndpoint endpoint);
	}

	@Data
	public static class Entry
	{
		private String name;
		private long fetchedAt;
		private Map<String, Integer> bosses = new LinkedHashMap<>();
		private Map<String, Integer> clues = new LinkedHashMap<>();
		private Map<String, Integer> activities = new LinkedHashMap<>();
		private int collections;
	}

	static HiscoreEndpoint endpoint(String world)
	{
		switch (world == null ? "main" : world)
		{
			case "seasonal":
				return HiscoreEndpoint.SEASONAL;
			case "deadman":
				return HiscoreEndpoint.DEADMAN;
			case "fresh-start":
				return HiscoreEndpoint.FRESH_START_WORLD;
			default:
				return HiscoreEndpoint.NORMAL;
		}
	}

	static Entry from(String name, HiscoreResult result, long now)
	{
		Entry e = new Entry();
		e.setName(name);
		e.setFetchedAt(now);
		if (result == null || result.getSkills() == null)
		{
			return e;
		}
		for (Map.Entry<HiscoreSkill, Skill> s : result.getSkills().entrySet())
		{
			HiscoreSkill skill = s.getKey();
			int score = s.getValue() == null ? -1 : s.getValue().getLevel();
			if (score <= 0 || skill.getType() == null)
			{
				continue;
			}
			String clue = clueTier(skill);
			if (clue != null)
			{
				e.getClues().put(clue, score);
			}
			else if (skill == HiscoreSkill.COLLECTIONS_LOGGED)
			{
				e.setCollections(score);
			}
			else if (skill == HiscoreSkill.CLUE_SCROLL_ALL || !NAME.matcher(skill.getName()).matches())
			{
				continue;
			}
			else if (skill.getType() == HiscoreSkillType.BOSS)
			{
				e.getBosses().put(skill.getName(), score);
			}
			else if (skill.getType() == HiscoreSkillType.ACTIVITY)
			{
				e.getActivities().put(skill.getName(), score);
			}
		}
		return e;
	}

	static void merge(PublicSnapshot page, Entry hiscores)
	{
		if (hiscores == null)
		{
			return;
		}
		PublicSnapshot.Kills kills = page.getKills();
		if (kills != null)
		{
			kills.setBosses(sorted(combine(kills.getBosses(), hiscores.getBosses())));
			kills.setClues(combine(kills.getClues(), hiscores.getClues()));
			if (!hiscores.getActivities().isEmpty())
			{
				kills.setActivities(new LinkedHashMap<>(hiscores.getActivities()));
			}
		}
		PublicSnapshot.Collection collection = page.getCollection();
		if (collection != null && hiscores.getCollections() > 0
			&& (collection.getCollectionLog() == null || collection.getCollectionLog() < hiscores.getCollections()))
		{
			collection.setCollectionLog(hiscores.getCollections());
		}
	}

	private static Map<String, Integer> combine(Map<String, Integer> recorded, Map<String, Integer> ranked)
	{
		Map<String, Integer> out = new LinkedHashMap<>(recorded);
		Map<String, String> byKey = new HashMap<>();
		recorded.keySet().forEach(name -> byKey.put(key(name), name));
		ranked.forEach((name, count) ->
		{
			String existing = byKey.get(key(name));
			int best = count;
			if (existing != null)
			{
				Integer seen = out.remove(existing);
				best = Math.max(count, seen == null ? 0 : seen);
			}
			out.put(name, best);
		});
		return out;
	}

	private static Map<String, Integer> sorted(Map<String, Integer> counts)
	{
		Map<String, Integer> out = new LinkedHashMap<>();
		counts.entrySet().stream()
			.sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
			.forEach(e -> out.put(e.getKey(), e.getValue()));
		return out;
	}

	static String key(String name)
	{
		String k = name.toLowerCase(Locale.ROOT);
		if (k.startsWith("the "))
		{
			k = k.substring(4);
		}
		return k.replaceAll("[^a-z0-9]", "");
	}

	private static String clueTier(HiscoreSkill skill)
	{
		switch (skill)
		{
			case CLUE_SCROLL_BEGINNER:
				return "Beginner";
			case CLUE_SCROLL_EASY:
				return "Easy";
			case CLUE_SCROLL_MEDIUM:
				return "Medium";
			case CLUE_SCROLL_HARD:
				return "Hard";
			case CLUE_SCROLL_ELITE:
				return "Elite";
			case CLUE_SCROLL_MASTER:
				return "Master";
			default:
				return null;
		}
	}
}
