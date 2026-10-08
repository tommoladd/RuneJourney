package com.runejourney.sync;

import com.runejourney.planner.Skills;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class XpRangesTest
{
	private static List<long[]> ranges(long... bounds)
	{
		List<long[]> out = new java.util.ArrayList<>();
		for (int i = 0; i < bounds.length; i += 2)
		{
			out.add(new long[]{bounds[i], bounds[i + 1]});
		}
		return out;
	}

	private static void assertRanges(List<long[]> actual, long... bounds)
	{
		List<long[]> expected = ranges(bounds);
		assertEquals(expected.size(), actual.size());
		for (int i = 0; i < expected.size(); i++)
		{
			assertArrayEquals(expected.get(i), actual.get(i));
		}
	}

	@Test
	public void touchingAndOverlappingRangesJoin()
	{
		assertRanges(XpRanges.normalize(ranges(30, 40, 10, 20, 20, 25, 35, 50, 60, 60)), 10, 25, 30, 50);
	}

	@Test
	public void subtractingSplitsRanges()
	{
		assertRanges(XpRanges.subtract(ranges(0, 100), ranges(10, 20, 50, 60)), 0, 10, 20, 50, 60, 100);
		assertRanges(XpRanges.subtract(ranges(0, 100), ranges(0, 100)));
		assertRanges(XpRanges.subtract(ranges(0, 100), ranges(-50, 10, 90, 500)), 10, 90);
	}

	@Test
	public void unionOfMapsIsBySkill()
	{
		java.util.Map<String, List<long[]>> a = new java.util.HashMap<>();
		a.put("AGILITY", ranges(0, 10));
		java.util.Map<String, List<long[]>> b = new java.util.HashMap<>();
		b.put("AGILITY", ranges(10, 20));
		b.put("MINING", ranges(5, 6));
		java.util.Map<String, List<long[]>> u = XpRanges.union(a, b);
		assertRanges(u.get("AGILITY"), 0, 20);
		assertRanges(u.get("MINING"), 5, 6);
		assertTrue(XpRanges.same(u, XpRanges.union(b, a)));
	}

	@Test
	public void levelsAreCountedAcrossPieces()
	{
		long l80 = Skills.xpForLevel(80);
		long l82 = Skills.xpForLevel(82);
		assertEquals(2, XpRanges.levels(Arrays.asList(new long[]{l80, l82})));
		assertEquals(1, XpRanges.levels(Arrays.asList(new long[]{l80 - 1, l80 + 1}, new long[]{l80 + 5, l80 + 10})));
	}
}
