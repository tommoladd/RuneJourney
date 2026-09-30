package com.runejourney.service;

import com.google.gson.Gson;
import com.runejourney.RuneJourneyConfig;
import com.runejourney.model.Goal;
import com.runejourney.model.GoalItem;
import com.runejourney.model.GoalType;
import com.runejourney.planner.BossData;
import com.runejourney.planner.Counters;
import com.runejourney.planner.GoalProgress;
import com.runejourney.planner.Skills;
import com.runejourney.planner.TrainingMethods;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class GrandExchangePurchaseTest
{
	private static final int TWISTED_BOW = 20997;

	private JourneyService service;

	@Before
	public void setUp()
	{
		RuneJourneyConfig config = new RuneJourneyConfig()
		{
			@Override
			public boolean chatAnnouncements()
			{
				return false;
			}

			@Override
			public boolean screenshotGoals()
			{
				return false;
			}
		};
		Gson gson = new Gson();
		service = new JourneyService(null, null, config, gson, null, new TrainingMethods(gson), new BossData(gson));
		service.install("test", new JourneyStore.Loaded(null, new TreeMap<>()));
		Map<String, Long> xp = new HashMap<>();
		for (Skill s : Skills.ALL)
		{
			xp.put(s.name(), Skills.xpForLevel(80));
		}
		service.setBaseline(xp, 1);
	}

	private Goal purchaseGoal(int quantity, long price)
	{
		Goal g = new Goal();
		g.setType(GoalType.PURCHASE);
		g.setName("Buy Twisted bow");
		g.setCounter(Counters.CASH);
		g.getItems().add(new GoalItem(TWISTED_BOW, "Twisted bow"));
		g.setQuantity(quantity);
		g.setTargetCount(price * quantity);
		assertNull(service.createGoal(g));
		return g;
	}

	private GoalProgress progress(String id)
	{
		return service.goalProgress().stream().filter(p -> p.getGoal().getId().equals(id)).findFirst().get();
	}

	@Test
	public void affordingIsReadyButBuyingCompletes()
	{
		Goal g = purchaseGoal(1, 1_200_000_000L);
		service.onCash(true, 1_300_000_000L);
		assertEquals(GoalProgress.Status.READY, progress(g.getId()).getStatus());
		assertFalse(progress(g.getId()).isComplete());

		// Offer placed, then filled
		service.onGrandExchangeOffer(0, TWISTED_BOW, "Twisted bow", true, false, 0, 0);
		assertFalse(progress(g.getId()).isComplete());
		service.onGrandExchangeOffer(0, TWISTED_BOW, "Twisted bow", true, false, 1, 1_190_000_000L);
		assertTrue(progress(g.getId()).isComplete());
		assertTrue(service.goal(g.getId()).getStory().contains("Paid|1.19b gp"));
	}

	@Test
	public void loginReplayIsNotCountedTwice()
	{
		Goal g = purchaseGoal(5, 1_000_000L);
		service.onGrandExchangeOffer(2, TWISTED_BOW, "Twisted bow", true, false, 3, 3_000_000L);
		assertEquals(3, service.goal(g.getId()).getPurchasedQuantity());

		// The game replays the same offer on the next login
		service.resetSessionState();
		service.onGrandExchangeOffer(2, TWISTED_BOW, "Twisted bow", true, false, 3, 3_000_000L);
		assertEquals(3, service.goal(g.getId()).getPurchasedQuantity());

		// The rest fills later; collecting empties the slot
		service.onGrandExchangeOffer(2, TWISTED_BOW, "Twisted bow", true, false, 5, 5_000_000L);
		assertTrue(progress(g.getId()).isComplete());
		service.onGrandExchangeOffer(2, 0, null, false, true, 0, 0);
	}

	@Test
	public void sellingDoesNotCount()
	{
		Goal g = purchaseGoal(1, 1_000_000L);
		service.onGrandExchangeOffer(1, TWISTED_BOW, "Twisted bow", false, false, 1, 0);
		assertFalse(progress(g.getId()).isComplete());
	}

	@Test
	public void geBuysTickItemGoals()
	{
		Goal g = new Goal();
		g.setType(GoalType.ITEMS);
		g.setName("Megarares");
		g.getItems().add(new GoalItem(TWISTED_BOW, "Twisted bow"));
		g.getItems().add(new GoalItem(22325, "Scythe of vitur"));
		assertNull(service.createGoal(g));
		service.onGrandExchangeOffer(0, TWISTED_BOW, "Twisted bow", true, false, 1, 1);
		Goal after = service.goal(g.getId());
		assertTrue(after.getItems().get(0).isObtained());
		assertEquals("Grand Exchange", after.getItems().get(0).getSource());
		assertFalse(after.getItems().get(1).isObtained());
	}
}
