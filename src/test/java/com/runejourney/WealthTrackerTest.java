package com.runejourney;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class WealthTrackerTest
{
	private static final int ITEM = 4151;
	private static final int DOSE_1 = 143;
	private static final int DOSE_2 = 141;
	private static final int DOSE_3 = 139;
	private static final int DOSE_4 = 2434;

	@Test
	public void buyOfferHoldsUnspentCoinsAndBoughtItems()
	{
		// 10 at 1,000 each; 4 bought for 3,600 so far
		Map<Integer, Long> held = new HashMap<>();
		WealthTracker.addOffer(held, true, ITEM, 10, 4, 1_000, 3_600);
		assertEquals(4L, (long) held.get(ITEM));
		assertEquals(6_400L, (long) held.get(ItemID.COINS));
	}

	@Test
	public void sellOfferHoldsUnsoldItemsAndCoinsReceived()
	{
		Map<Integer, Long> held = new HashMap<>();
		WealthTracker.addOffer(held, false, ITEM, 10, 3, 1_000, 3_300);
		assertEquals(7L, (long) held.get(ITEM));
		assertEquals(3_300L, (long) held.get(ItemID.COINS));
	}

	@Test
	public void coinsPastOneStackBecomePlatinum()
	{
		Map<Integer, Long> held = new HashMap<>();
		WealthTracker.addOffer(held, true, ITEM, 2, 0, 2_000_000_000L, 0);
		WealthTracker.addOffer(held, true, ITEM, 1, 0, 1_500L, 0);
		Map<Integer, Integer> out = WealthTracker.toHoldings(held);
		assertEquals(4_000_001, (int) out.get(ItemID.PLATINUM));
		assertEquals(500, (int) out.get(ItemID.COINS));
		// Nothing bought yet
		assertTrue(!out.containsKey(ITEM));
	}

	@Test
	public void storedDosesAreFullPotionsPlusTheRemainder()
	{
		Map<Integer, Integer> held = new HashMap<>();
		WealthTracker.addDoses(held, 7, new int[]{-1, DOSE_1, DOSE_2, DOSE_3, DOSE_4});
		assertEquals(1, (int) held.get(DOSE_4));
		assertEquals(1, (int) held.get(DOSE_3));
		assertEquals(2, held.size());
	}

	@Test
	public void fewerDosesThanAFullPotion()
	{
		Map<Integer, Integer> held = new HashMap<>();
		WealthTracker.addDoses(held, 2, new int[]{-1, DOSE_1, DOSE_2, DOSE_3, DOSE_4});
		assertEquals(1, (int) held.get(DOSE_2));
		assertEquals(1, held.size());
	}

	@Test
	public void unfinishedPotionsAreCountedOneEach()
	{
		Map<Integer, Integer> held = new HashMap<>();
		WealthTracker.addDoses(held, 13, new int[]{-1, ITEM, -1, -1, -1});
		assertEquals(13, (int) held.get(ITEM));
	}
}
