package com.runejourney.service;

import com.google.gson.Gson;
import com.runejourney.RuneJourneyConfig;
import com.runejourney.model.ProfileData;
import com.runejourney.planner.BossData;
import com.runejourney.planner.Skills;
import com.runejourney.planner.TrainingMethods;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class SavedMethodsTest
{
	private static final int TICKS_PER_MINUTE = 100;

	private final Gson gson = new Gson();
	private final RuneJourneyConfig config = new RuneJourneyConfig()
	{
		@Override
		public boolean chatAnnouncements()
		{
			return false;
		}
	};
	private JourneyService service;
	private final Map<String, Long> xp = new HashMap<>();
	private int tick = 1;

	@Before
	public void setUp()
	{
		start(null);
	}

	private void start(ProfileData profile)
	{
		service = new JourneyService(null, null, config, gson, null, new TrainingMethods(gson), new BossData(gson));
		service.install("test", new JourneyStore.Loaded(profile, new TreeMap<>()));
		for (Skill s : Skills.ALL)
		{
			xp.put(s.name(), Skills.xpForLevel(80));
		}
		service.setBaseline(new HashMap<>(xp), tick);
	}

	/**
	 * An XP drop every 3 seconds at the given rate.
	 */
	private void train(Skill skill, long xpPerHour, int minutes)
	{
		long perDrop = xpPerHour / 1200;
		for (int i = 0; i < minutes * 20; i++)
		{
			tick += 5;
			long now = xp.merge(skill.name(), perDrop, Long::sum);
			service.onXp(skill, now, tick);
		}
	}

	private void rest(int minutes)
	{
		tick += minutes * TICKS_PER_MINUTE;
	}

	private JourneyService.MethodOffer onlyOffer()
	{
		List<JourneyService.MethodOffer> offers = service.methodOffers();
		assertEquals(1, offers.size());
		return offers.get(0);
	}

	@Test
	public void offersRateAfterSampleTimeAndPlansWithItOnceSaved()
	{
		long at = Skills.xpForLevel(80);
		double generic = service.rate(Skill.SMITHING, at);
		train(Skill.SMITHING, 120_000, 14);
		assertTrue(service.methodOffers().isEmpty());

		train(Skill.SMITHING, 120_000, 2);
		JourneyService.MethodOffer offer = onlyOffer();
		assertEquals(Skill.SMITHING, offer.getSkill());
		assertEquals(120_000, offer.getXpPerHour(), 1_000);
		assertFalse(service.isPersonal(Skill.SMITHING));
		assertEquals(generic, service.rate(Skill.SMITHING, at), 0.1);

		assertNull(service.saveDetectedMethod(Skill.SMITHING, "  Gold   bars "));
		assertTrue(service.methodOffers().isEmpty());
		assertTrue(service.isPersonal(Skill.SMITHING));
		assertEquals(120_000, service.rate(Skill.SMITHING, at), 1_000);
		assertEquals("Gold bars (yours)", service.methodName(Skill.SMITHING, at));
		assertTrue(service.methodChoices(Skill.SMITHING).contains("Gold bars (yours)"));
	}

	@Test
	public void offerKeepsUpdatingWhileTrainingContinues()
	{
		train(Skill.SMITHING, 120_000, 31);
		long early = onlyOffer().getMillis();
		train(Skill.SMITHING, 120_000, 20);
		assertEquals(early + 20 * 60_000L, onlyOffer().getMillis(), 3_000);
	}

	@Test
	public void matchingRateNeedsNoSaveAndBecomesTheMethodInUse()
	{
		train(Skill.SMITHING, 120_000, 31);
		service.saveDetectedMethod(Skill.SMITHING, "Gold bars");
		rest(10);
		train(Skill.SMITHING, 60_000, 31);
		service.saveDetectedMethod(Skill.SMITHING, "Platebodies");
		assertEquals(60_000, service.rate(Skill.SMITHING, 0), 1_000);

		// Within 15% of gold bars: no new offer, and plans follow gold bars again
		rest(10);
		train(Skill.SMITHING, 110_000, 31);
		assertTrue(service.methodOffers().isEmpty());
		assertEquals(120_000, service.rate(Skill.SMITHING, 0), 1_000);

		// Choosing one by name sticks, whatever was trained last
		service.setPreferredMethod(Skill.SMITHING, "Platebodies (yours)");
		rest(10);
		train(Skill.SMITHING, 120_000, 31);
		assertEquals(60_000, service.rate(Skill.SMITHING, 0), 1_000);
	}

	@Test
	public void savingUnderAnExistingNameReplacesIt()
	{
		train(Skill.FISHING, 50_000, 31);
		service.saveDetectedMethod(Skill.FISHING, "Barbarian");
		rest(10);
		train(Skill.FISHING, 80_000, 31);
		assertEquals(1, onlyOffer().getSavedNames().size());
		assertNull(service.saveDetectedMethod(Skill.FISHING, "barbarian"));
		assertEquals(1, service.methodChoices(Skill.FISHING).stream().filter(n -> n.endsWith("(yours)")).count());
		assertEquals(80_000, service.rate(Skill.FISHING, 0), 1_000);
	}

	@Test
	public void dismissedRateIsNotOfferedAgain()
	{
		train(Skill.MINING, 50_000, 31);
		service.dismissDetectedMethod(Skill.MINING);
		assertTrue(service.methodOffers().isEmpty());

		rest(10);
		train(Skill.MINING, 52_000, 31);
		assertTrue(service.methodOffers().isEmpty());

		rest(10);
		train(Skill.MINING, 90_000, 31);
		assertEquals(90_000, onlyOffer().getXpPerHour(), 1_000);
	}

	@Test
	public void shortBreaksCountButLongOnesStartAgain()
	{
		train(Skill.CRAFTING, 100_000, 10);
		rest(6);
		train(Skill.CRAFTING, 100_000, 10);
		assertTrue(service.methodOffers().isEmpty());

		// A 4 minute bank trip is part of the training time
		rest(6);
		train(Skill.CRAFTING, 100_000, 10);
		rest(4);
		train(Skill.CRAFTING, 100_000, 6);
		assertEquals(100_000 * 16 / 20.0, onlyOffer().getXpPerHour(), 1_000);
	}

	@Test
	public void sampleTimeFollowsConfig()
	{
		RuneJourneyConfig longer = new RuneJourneyConfig()
		{
			@Override
			public int rateSampleMinutes()
			{
				return 45;
			}
		};
		service = new JourneyService(null, null, longer, gson, null, new TrainingMethods(gson), new BossData(gson));
		service.install("test", new JourneyStore.Loaded(null, new TreeMap<>()));
		service.setBaseline(new HashMap<>(xp), tick);
		train(Skill.SMITHING, 120_000, 44);
		assertTrue(service.methodOffers().isEmpty());

		train(Skill.SMITHING, 120_000, 2);
		assertEquals(120_000, onlyOffer().getXpPerHour(), 1_000);
	}

	@Test
	public void hitpointsIsNeverOffered()
	{
		train(Skill.HITPOINTS, 40_000, 40);
		assertTrue(service.methodOffers().isEmpty());
	}

	@Test
	public void renameAndDeleteFollowTheChosenMethod()
	{
		train(Skill.AGILITY, 60_000, 31);
		service.saveDetectedMethod(Skill.AGILITY, "Rooftops");
		rest(10);
		train(Skill.AGILITY, 30_000, 31);
		service.saveDetectedMethod(Skill.AGILITY, "Gnome");
		service.setPreferredMethod(Skill.AGILITY, "Rooftops (yours)");

		assertNotNull(service.renameSavedMethod(Skill.AGILITY, "Rooftops", "gnome"));
		assertNotNull(service.renameSavedMethod(Skill.AGILITY, "Rooftops", "   "));
		assertNull(service.renameSavedMethod(Skill.AGILITY, "Rooftops", "Seers"));
		assertEquals("Seers (yours)", service.methodName(Skill.AGILITY, 0));

		service.deleteSavedMethod(Skill.AGILITY, "Seers");
		assertNotEquals("Seers (yours)", service.methodName(Skill.AGILITY, 0));
		// With no method chosen, the remaining saved one is used
		assertEquals("Gnome (yours)", service.methodName(Skill.AGILITY, 0));
	}

	@Test
	public void lifetimeAverageChoiceIsCleared()
	{
		ProfileData profile = new ProfileData();
		profile.getPreferredMethods().put("SMITHING", "My own XP rate");
		start(profile);
		assertFalse(profile.getPreferredMethods().containsKey("SMITHING"));
		assertFalse(service.isPersonal(Skill.SMITHING));
	}

	@Test
	public void noOfferWhenTurnedOff()
	{
		RuneJourneyConfig off = new RuneJourneyConfig()
		{
			@Override
			public boolean offerSavedRates()
			{
				return false;
			}
		};
		service = new JourneyService(null, null, off, gson, null, new TrainingMethods(gson), new BossData(gson));
		service.install("test", new JourneyStore.Loaded(null, new TreeMap<>()));
		service.setBaseline(new HashMap<>(xp), tick);
		train(Skill.SMITHING, 120_000, 31);
		assertTrue(service.methodOffers().isEmpty());
	}
}
