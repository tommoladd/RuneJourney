package com.runejourney.service;

import com.google.gson.Gson;
import com.runejourney.RuneJourneyConfig;
import com.runejourney.model.EventType;
import com.runejourney.model.ProfileData;
import com.runejourney.planner.BossData;
import com.runejourney.planner.TrainingMethods;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class WealthTest
{
	private static final int TWISTED_BOW = 20997;

	private final RuneJourneyConfig config = new RuneJourneyConfig()
	{
		@Override
		public boolean chatAnnouncements()
		{
			return false;
		}
	};

	private JourneyService service(ProfileData profile)
	{
		Gson gson = new Gson();
		JourneyService service = new JourneyService(null, null, config, gson, null, new TrainingMethods(gson), new BossData(gson));
		service.install("test", new JourneyStore.Loaded(profile, new TreeMap<>()));
		return service;
	}

	private static long records(JourneyService service)
	{
		return service.journeyDays(10, null, false).stream()
			.flatMap(d -> d.getEvents().stream()).filter(e -> e.getType() == EventType.RECORD).count();
	}

	private static Map<String, Long> parts(long bank, long equipment)
	{
		Map<String, Long> parts = new HashMap<>();
		parts.put(JourneyService.BANK, bank);
		parts.put("equipment", equipment);
		return parts;
	}

	@Test
	public void equippingFromTheBankKeepsNetWorth()
	{
		JourneyService service = service(null);
		service.onWealth(parts(6_000_000_000L, 0));
		assertEquals(6_000_000_000L, service.netWorthToday()[0]);

		// A 1.5b bow moves from the bank to equipment; both containers are valued together
		service.onWealth(parts(4_500_000_000L, 1_500_000_000L));
		assertEquals(6_000_000_000L, service.netWorthToday()[0]);
		assertEquals(0, records(service));
	}

	@Test
	public void milestonesOnlyForRealGrowth()
	{
		JourneyService service = service(null);
		service.onWealth(parts(9_000_000_000L, 0));
		service.onWealth(parts(7_500_000_000L, 1_500_000_000L));
		assertEquals(0, records(service));

		service.onWealth(parts(8_600_000_000L, 1_500_000_000L));
		assertEquals(1, records(service)); // passed 10b
	}

	@Test
	public void holdingsAreKept()
	{
		JourneyService service = service(null);
		Map<Integer, Integer> bank = new HashMap<>();
		bank.put(TWISTED_BOW, 1);
		service.onHoldings(JourneyService.BANK, bank);

		Map<String, Map<Integer, Integer>> held = service.holdings();
		assertEquals(Integer.valueOf(1), held.get(JourneyService.BANK).get(TWISTED_BOW));

		// The copy is detached from the profile
		held.get(JourneyService.BANK).put(TWISTED_BOW, 5);
		assertEquals(Integer.valueOf(1), service.holdings().get(JourneyService.BANK).get(TWISTED_BOW));
	}

	@Test
	public void oldProfilesKeepTheirBankValue()
	{
		ProfileData old = new ProfileData();
		old.setBankValue(6_241_563_729L);
		old.setBankValueKnown(true);
		// Saved after logging out, when the game had emptied these
		old.setInventoryValue(0);
		old.setEquipmentValue(0);

		JourneyService service = service(old);
		assertEquals(6_241_563_729L, service.netWorthToday()[0]);
		assertEquals(0, old.getBankValue());

		service.onWealth("equipment", 4_700_000_000L);
		assertEquals(10_941_563_729L, service.netWorthToday()[0]);
		assertTrue(old.getWealthParts().containsKey("equipment"));
	}
}
