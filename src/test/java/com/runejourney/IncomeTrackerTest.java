package com.runejourney;

import java.util.EnumSet;
import net.runelite.api.Skill;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class IncomeTrackerTest
{
	@Test
	public void barbarianFishingIsSkilling()
	{
		// Each leaping fish gives Fishing, Agility and Strength XP on the same tick
		assertFalse(IncomeTracker.isCombat(EnumSet.of(Skill.FISHING, Skill.AGILITY, Skill.STRENGTH)));
	}

	@Test
	public void meleeIsCombat()
	{
		assertTrue(IncomeTracker.isCombat(EnumSet.of(Skill.STRENGTH, Skill.HITPOINTS)));
		assertTrue(IncomeTracker.isCombat(EnumSet.of(Skill.STRENGTH)));
	}

	@Test
	public void combatAlongsideFishingIsStillCombat()
	{
		// Hitpoints XP only comes from fighting, whatever else dropped that tick
		assertTrue(IncomeTracker.isCombat(EnumSet.of(Skill.FISHING, Skill.STRENGTH, Skill.HITPOINTS)));
		assertTrue(IncomeTracker.isCombat(EnumSet.of(Skill.FISHING, Skill.RANGED)));
	}

	@Test
	public void plainSkillingIsNotCombat()
	{
		assertFalse(IncomeTracker.isCombat(EnumSet.of(Skill.FISHING)));
		assertFalse(IncomeTracker.isCombat(EnumSet.of(Skill.COOKING)));
		assertFalse(IncomeTracker.isCombat(EnumSet.of(Skill.MAGIC)));
	}
}
