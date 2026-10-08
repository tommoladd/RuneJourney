package com.runejourney.service;

import com.google.gson.Gson;
import com.runejourney.RuneJourneyConfig;
import com.runejourney.planner.BossData;
import com.runejourney.planner.TrainingMethods;

/**
 * Builds services for tests in other packages.
 */
public final class TestServices
{
	private TestServices()
	{
	}

	public static JourneyService journey(RuneJourneyConfig config, Gson gson)
	{
		return new JourneyService(null, null, config, gson, null, new TrainingMethods(gson), new BossData(gson));
	}

	/**
	 * A copy of everything the service would save, sync parts included.
	 */
	public static JourneyStore.Loaded saved(JourneyService service)
	{
		return service.saved();
	}
}
