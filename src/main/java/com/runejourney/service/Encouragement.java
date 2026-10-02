package com.runejourney.service;

import com.runejourney.RuneJourneyConfig;
import com.runejourney.planner.Skills;
import com.runejourney.util.Format;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.runelite.api.Experience;
import net.runelite.api.Skill;

/**
 * Friendly chat messages for steady progress in a skill, varied so they don't repeat.
 */
final class Encouragement
{
	/**
	 * {amount} is the XP gap, {skill} the skill's name.
	 */
	private static final String[] GENERAL = {
		"That's another {amount} {skill} XP down. Keep it up!",
		"Another {amount} {skill} XP in the bag.",
		"{amount} more {skill} XP. Nice and steady.",
		"You've just put in another {amount} {skill} XP. Great work!",
		"{skill} is coming along nicely: another {amount} XP.",
		"Every bit counts: {amount} more {skill} XP.",
		"Another {amount} {skill} XP. The grind is paying off.",
		"{amount} {skill} XP closer. You've got this.",
		"Look at that, another {amount} {skill} XP done.",
		"Small steps, big progress: {amount} more {skill} XP.",
		"Another {amount} {skill} XP. Your future self says thanks.",
		"{amount} more {skill} XP. Keep that momentum going!",
	};

	private static final Map<Skill, String[]> SKILL_LINES = new EnumMap<>(Skill.class);

	static
	{
		SKILL_LINES.put(Skill.AGILITY, new String[]{
			"Another {amount} Agility XP. Those laps are adding up!",
			"{amount} more Agility XP. Mind the next obstacle!"});
		SKILL_LINES.put(Skill.RUNECRAFT, new String[]{
			"Another {amount} Runecraft XP. The Abyss thanks you for your patience.",
			"{amount} more Runecraft XP. One essence at a time."});
		SKILL_LINES.put(Skill.MINING, new String[]{
			"Another {amount} Mining XP. Rock solid progress.",
			"{amount} more Mining XP. You're on a roll... of ore."});
		SKILL_LINES.put(Skill.WOODCUTTING, new String[]{
			"Another {amount} Woodcutting XP. Timber!"});
		SKILL_LINES.put(Skill.FISHING, new String[]{
			"Another {amount} Fishing XP. That's a good haul.",
			"{amount} more Fishing XP. Patience of a true angler."});
		SKILL_LINES.put(Skill.SLAYER, new String[]{
			"Another {amount} Slayer XP. Your Slayer master would be proud."});
		SKILL_LINES.put(Skill.HUNTER, new String[]{
			"Another {amount} Hunter XP. Happy hunting!"});
		SKILL_LINES.put(Skill.FARMING, new String[]{
			"Another {amount} Farming XP. Your patches are thriving."});
		SKILL_LINES.put(Skill.THIEVING, new String[]{
			"Another {amount} Thieving XP. Light fingers, heavy progress."});
		SKILL_LINES.put(Skill.PRAYER, new String[]{
			"Another {amount} Prayer XP. The gods are pleased."});
		SKILL_LINES.put(Skill.SAILING, new String[]{
			"Another {amount} Sailing XP. Fair winds!"});
	}

	private final Random random;
	private String last;

	Encouragement(Random random)
	{
		this.random = random;
	}

	/**
	 * The XP gap set for a skill, or 0 for never.
	 */
	static long interval(RuneJourneyConfig config, Skill skill)
	{
		if (skill == null)
		{
			return 0;
		}
		switch (skill)
		{
			case ATTACK:
				return config.encourageAttack();
			case STRENGTH:
				return config.encourageStrength();
			case DEFENCE:
				return config.encourageDefence();
			case RANGED:
				return config.encourageRanged();
			case PRAYER:
				return config.encouragePrayer();
			case MAGIC:
				return config.encourageMagic();
			case RUNECRAFT:
				return config.encourageRunecraft();
			case CONSTRUCTION:
				return config.encourageConstruction();
			case HITPOINTS:
				return config.encourageHitpoints();
			case AGILITY:
				return config.encourageAgility();
			case HERBLORE:
				return config.encourageHerblore();
			case THIEVING:
				return config.encourageThieving();
			case CRAFTING:
				return config.encourageCrafting();
			case FLETCHING:
				return config.encourageFletching();
			case SLAYER:
				return config.encourageSlayer();
			case HUNTER:
				return config.encourageHunter();
			case MINING:
				return config.encourageMining();
			case SMITHING:
				return config.encourageSmithing();
			case FISHING:
				return config.encourageFishing();
			case COOKING:
				return config.encourageCooking();
			case FIREMAKING:
				return config.encourageFiremaking();
			case WOODCUTTING:
				return config.encourageWoodcutting();
			case FARMING:
				return config.encourageFarming();
			case SAILING:
				return config.encourageSailing();
			default:
				return 0;
		}
	}

	/**
	 * Whether gaining XP from {@code oldXp} to {@code newXp} passed another gap.
	 */
	static boolean crossed(long interval, long oldXp, long newXp)
	{
		return interval > 0 && newXp / interval > oldXp / interval;
	}

	/**
	 * A message for passing another {@code interval} XP, never the same line twice in a row.
	 */
	/**
	 * The line used for the last message, before the follow-up.
	 */
	String lastLine()
	{
		return last;
	}

	String message(Skill skill, long interval, long newXp)
	{
		List<String> lines = new ArrayList<>();
		for (String line : GENERAL)
		{
			lines.add(line);
		}
		String[] own = SKILL_LINES.get(skill);
		if (own != null)
		{
			// Skill-specific lines turn up a bit more often than any single general one
			for (String line : own)
			{
				lines.add(line);
				lines.add(line);
			}
		}
		String previous = last;
		lines.removeIf(l -> l.equals(previous));
		String line = lines.get(random.nextInt(lines.size()));
		last = line;
		String text = line.replace("{amount}", Format.compact(interval)).replace("{skill}", skill.getName());

		String followUp = followUp(newXp);
		return followUp == null ? text : text + " " + followUp;
	}

	/**
	 * Sometimes adds how far there is to go, so the message is useful as well as kind.
	 */
	private String followUp(long xp)
	{
		if (random.nextInt(3) != 0)
		{
			return null;
		}
		int level = Skills.level(xp);
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return random.nextBoolean()
				? Format.compact(xp) + " XP and counting."
				: Format.compact(Experience.MAX_SKILL_XP - xp) + " XP to go for 200m, if you dare.";
		}
		if (level >= 90 && random.nextBoolean())
		{
			return Format.compact(Experience.getXpForLevel(Experience.MAX_REAL_LEVEL) - xp) + " XP left until 99.";
		}
		return Format.compact(Experience.getXpForLevel(level + 1) - xp) + " XP to level " + (level + 1) + ".";
	}
}
