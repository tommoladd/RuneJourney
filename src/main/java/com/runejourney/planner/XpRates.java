package com.runejourney.planner;

import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.Skill;

/**
 * Generic mid-to-high level XP/hr assumptions used until RuneJourney has observed the player's own rates.
 * These are deliberately rough; they only need to be in the right ballpark for weekly planning.
 */
public final class XpRates
{
	/**
	 * Observed rates are only trusted after this much active training time.
	 */
	public static final long MIN_OBSERVED_MILLIS = 30 * 60 * 1000L;

	private static final Map<Skill, int[]> RATES = new EnumMap<>(Skill.class);

	static
	{
		// efficient, balanced, relaxed (thousands of XP per hour)
		rate(Skill.ATTACK, 110, 80, 50);
		rate(Skill.STRENGTH, 120, 85, 55);
		rate(Skill.DEFENCE, 110, 80, 50);
		rate(Skill.HITPOINTS, 45, 32, 20);
		rate(Skill.RANGED, 200, 120, 70);
		rate(Skill.PRAYER, 500, 250, 90);
		rate(Skill.MAGIC, 250, 120, 70);
		rate(Skill.COOKING, 450, 250, 150);
		rate(Skill.WOODCUTTING, 150, 90, 60);
		rate(Skill.FLETCHING, 1000, 250, 150);
		rate(Skill.FISHING, 110, 70, 45);
		rate(Skill.FIREMAKING, 300, 200, 150);
		rate(Skill.CRAFTING, 350, 200, 120);
		rate(Skill.SMITHING, 300, 180, 100);
		rate(Skill.MINING, 110, 60, 35);
		rate(Skill.HERBLORE, 400, 250, 150);
		rate(Skill.AGILITY, 90, 65, 50);
		rate(Skill.THIEVING, 250, 150, 90);
		rate(Skill.SLAYER, 90, 60, 40);
		rate(Skill.FARMING, 150, 100, 60);
		rate(Skill.RUNECRAFT, 90, 60, 40);
		rate(Skill.HUNTER, 180, 120, 70);
		rate(Skill.CONSTRUCTION, 900, 450, 250);
		rate(Skill.SAILING, 80, 60, 40);
	}

	private XpRates()
	{
	}

	private static void rate(Skill skill, int efficient, int balanced, int relaxed)
	{
		RATES.put(skill, new int[]{efficient * 1000, balanced * 1000, relaxed * 1000});
	}

	public static double defaultRate(Skill skill, Intensity intensity)
	{
		int[] r = RATES.get(skill);
		if (r == null)
		{
			return 50_000;
		}
		return r[intensity.ordinal()];
	}
}
