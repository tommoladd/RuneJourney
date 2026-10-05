package com.runejourney;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class SkillingRulesTest
{
	private static SkillingRules.Change gained(String name, int qty, long price)
	{
		return new SkillingRules.Change(name, qty, price);
	}

	private static SkillingRules.Change used(String name, int qty, long price)
	{
		return new SkillingRules.Change(name, -qty, price);
	}

	private static SkillingRules.Income income(List<Skill> skills, SkillingRules.Change... changes)
	{
		return SkillingRules.evaluate(skills, Arrays.asList(changes));
	}

	private static List<Skill> xp(Skill... skills)
	{
		return Arrays.asList(skills);
	}

	@Test
	public void gatheringCountsWhatWasProduced()
	{
		SkillingRules.Income i = income(xp(Skill.WOODCUTTING), gained("Yew logs", 1, 300));
		assertEquals(Skill.WOODCUTTING, i.getSkill());
		assertEquals(300, i.getValue());

		// Bait used while fishing isn't a loss
		i = income(xp(Skill.FISHING), gained("Raw shark", 1, 900), used("Fishing bait", 1, 5));
		assertEquals(900, i.getValue());
	}

	@Test
	public void barbarianFishingIsFishing()
	{
		SkillingRules.Income i = income(xp(Skill.FISHING, Skill.AGILITY, Skill.STRENGTH),
			gained("Leaping sturgeon", 1, 150), used("Feather", 1, 3));
		assertEquals(Skill.FISHING, i.getSkill());
		assertEquals(150, i.getValue());
	}

	@Test
	public void birdhousesAreHunter()
	{
		// Building the birdhouse is not Crafting income
		assertNull(income(xp(Skill.CRAFTING), gained("Yew bird house", 1, 4_000), used("Yew logs", 1, 300), used("Clockwork", 1, 900)));
		// Placing it uses it up, which isn't income either
		assertNull(income(xp(Skill.HUNTER), used("Yew bird house", 1, 4_000), used("Hammerstone seed", 10, 10)));
		// Collecting it is Hunter income
		SkillingRules.Income i = income(xp(Skill.HUNTER), gained("Bird nest", 3, 7_000), gained("Clue nest (elite)", 1, 0));
		assertEquals(Skill.HUNTER, i.getSkill());
		assertEquals(21_000, i.getValue());
	}

	@Test
	public void processingCountsValueAdded()
	{
		// High alchemy: coins in, item and nature rune out
		SkillingRules.Income i = income(xp(Skill.MAGIC), gained("Coins", 768, 1), used("Rune platebody", 1, 38_000),
			used("Nature rune", 1, 140));
		assertEquals(Skill.MAGIC, i.getSkill());
		assertEquals(768 - 38_000 - 140, i.getValue());

		i = income(xp(Skill.RUNECRAFT), gained("Blood rune", 50, 300), used("Pure essence", 50, 3));
		assertEquals(50 * 300 - 50 * 3, i.getValue());
	}

	@Test
	public void untradeableInputsStillCountAsUsed()
	{
		// Zeah runecrafting: dark essence fragments can't be sold, so they're worth nothing
		SkillingRules.Income i = income(xp(Skill.RUNECRAFT), gained("Blood rune", 104, 300),
			used("Dark essence fragments", 104, 0));
		assertEquals(Skill.RUNECRAFT, i.getSkill());
		assertEquals(104 * 300, i.getValue());

		i = income(xp(Skill.RUNECRAFT), gained("Soul rune", 52, 150), used("Dark essence fragments", 52, 0));
		assertEquals(52 * 150, i.getValue());
	}

	@Test
	public void makingSomethingWorthlessIsNotIncome()
	{
		// Venerating and chiselling Zeah essence
		assertNull(income(xp(Skill.RUNECRAFT), gained("Dark essence block", 26, 0), used("Dense essence block", 26, 0)));
		assertNull(income(xp(Skill.CRAFTING), gained("Dark essence fragments", 4, 0), used("Dark essence block", 1, 0)));
		// Making guardian essence on the same tick runes leave the inventory isn't a Crafting loss
		assertNull(income(xp(Skill.CRAFTING), gained("Guardian essence", 10, 0), used("Guardian fragments", 10, 0),
			used("Nature rune", 60, 100)));
	}

	@Test
	public void sortingSalvageEarnsTheLoot()
	{
		SkillingRules.Income i = income(xp(Skill.SAILING), used("Small salvage", 1, 0), gained("Bronze bar", 2, 100),
			gained("Coins", 34, 1));
		assertEquals(Skill.SAILING, i.getSkill());
		assertEquals(234, i.getValue());
		assertEquals(2, i.getProducts().size());

		// Anything else leaving the inventory while sorting isn't charged to Sailing
		i = income(xp(Skill.SAILING), used("Small salvage", 1, 0), gained("Bones", 1, 80), used("Shark", 1, 900));
		assertEquals(80, i.getValue());
	}

	@Test
	public void sailingWithoutSortingIsNotIncome()
	{
		// Hauling salvage in can't be sold
		assertNull(income(xp(Skill.SAILING), gained("Small salvage", 1, 0)));
		// Nor is anything else that happens on a Sailing XP drop
		assertNull(income(xp(Skill.SAILING), gained("Bronze bar", 1, 100), used("Shark", 1, 900)));
	}

	@Test
	public void spendingIsNotIncome()
	{
		// Teleporting uses runes but makes nothing
		assertNull(income(xp(Skill.MAGIC), used("Law rune", 1, 150)));
		// Burying bones and burning logs aren't money-making skills
		assertNull(income(xp(Skill.PRAYER), used("Dragon bones", 1, 2_500)));
		assertNull(income(xp(Skill.FIREMAKING), used("Yew logs", 1, 300)));
	}

	@Test
	public void unrelatedItemsDontCountAsGathering()
	{
		// Picking up a coin stack while mining isn't mining income
		assertNull(income(xp(Skill.MINING), gained("Coins", 5_000, 1)));
	}

	@Test
	public void farmingCountsHarvestsNotPlanting()
	{
		assertEquals(Skill.FARMING, income(xp(Skill.FARMING), gained("Grimy ranarr weed", 1, 7_000)).getSkill());
		assertNull(income(xp(Skill.FARMING), used("Ranarr seed", 1, 40_000)));
		assertNull(income(xp(Skill.FARMING), gained("Supercompost", 1, 50)));
	}

	@Test
	public void nothingWithoutXp()
	{
		assertNull(SkillingRules.evaluate(Collections.emptyList(), Collections.singletonList(gained("Yew logs", 1, 300))));
	}
}
