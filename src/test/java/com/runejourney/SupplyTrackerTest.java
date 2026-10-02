package com.runejourney;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SupplyTrackerTest
{
	@Test
	public void potionDosesAreLeftovers()
	{
		assertTrue(SupplyTracker.isLeftover("Prayer potion(4)", "Prayer potion(3)"));
		assertTrue(SupplyTracker.isLeftover("Saradomin brew(1)", "Vial"));
		assertFalse(SupplyTracker.isLeftover("Prayer potion(4)", "Super restore(3)"));
	}

	@Test
	public void foodPortionsAreLeftovers()
	{
		assertTrue(SupplyTracker.isLeftover("Summer pie", "Half a summer pie"));
		assertTrue(SupplyTracker.isLeftover("Half a summer pie", "Pie dish"));
		assertTrue(SupplyTracker.isLeftover("Cake", "2/3 cake"));
		assertTrue(SupplyTracker.isLeftover("2/3 cake", "Slice of cake"));
		assertTrue(SupplyTracker.isLeftover("Anchovy pizza", "1/2 anchovy pizza"));
		assertTrue(SupplyTracker.isLeftover("Jug of wine", "Jug"));
	}

	@Test
	public void unrelatedItemsAreNotLeftovers()
	{
		// e.g. a log cut on the same tick a shark was eaten
		assertFalse(SupplyTracker.isLeftover("Shark", "Yew logs"));
		assertFalse(SupplyTracker.isLeftover("Shark", "Raw shark"));
	}

	@Test
	public void potionsAreGroupedAcrossDoses()
	{
		assertEquals("Prayer potion", SupplyTracker.supplyName("Prayer potion(3)"));
		assertEquals("Divine super combat potion", SupplyTracker.supplyName("Divine super combat potion(1)"));
		assertEquals("Shark", SupplyTracker.supplyName("Shark"));
	}
}
