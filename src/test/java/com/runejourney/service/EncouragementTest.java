package com.runejourney.service;

import com.runejourney.RuneJourneyConfig;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class EncouragementTest
{
	private final RuneJourneyConfig defaults = new RuneJourneyConfig()
	{
	};

	@Test
	public void slowerSkillsCheerMoreOften()
	{
		assertEquals(250_000, Encouragement.interval(defaults, Skill.AGILITY));
		assertEquals(250_000, Encouragement.interval(defaults, Skill.RUNECRAFT));
		assertEquals(500_000, Encouragement.interval(defaults, Skill.ATTACK));
		assertEquals(1_000_000, Encouragement.interval(defaults, Skill.CRAFTING));
		assertEquals(1_000_000, Encouragement.interval(defaults, Skill.SMITHING));
		assertEquals(0, Encouragement.interval(defaults, null));
	}

	@Test
	public void firesOncePerGap()
	{
		assertFalse(Encouragement.crossed(250_000, 1_100_000, 1_249_999));
		assertTrue(Encouragement.crossed(250_000, 1_249_990, 1_250_010));
		assertFalse(Encouragement.crossed(250_000, 1_250_010, 1_260_000));
		// 0 turns it off for that skill
		assertFalse(Encouragement.crossed(0, 0, 50_000_000));
	}

	@Test
	public void messagesVaryAndNeverRepeatBackToBack()
	{
		Encouragement e = new Encouragement(new Random(7));
		Set<String> seen = new HashSet<>();
		String previous = null;
		for (int i = 0; i < 200; i++)
		{
			String m = e.message(Skill.AGILITY, 250_000, 5_000_000);
			assertTrue(m, m.contains("250k") && m.contains("Agility"));
			String line = e.lastLine();
			assertNotEquals(previous, line);
			previous = line;
			seen.add(line);
		}
		assertTrue("expected plenty of variety, got " + seen.size(), seen.size() >= 10);
	}

	@Test
	public void followUpsAreAccurate()
	{
		Encouragement e = new Encouragement(new Random(1));
		boolean sawLevel = false;
		for (int i = 0; i < 100; i++)
		{
			// 5,346,332 XP is exactly level 90, so the next level is 91
			String m = e.message(Skill.MINING, 250_000, 5_346_332);
			if (m.contains("XP to level"))
			{
				assertTrue(m, m.contains("to level 91"));
				sawLevel = true;
			}
		}
		assertTrue(sawLevel);
	}
}
