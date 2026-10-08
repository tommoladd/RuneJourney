package com.runejourney.sync;

import com.runejourney.model.DayRecord;
import com.runejourney.model.JourneyEvent;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Journey event IDs. Most events get a random one. Events that each device would record on its own
 * from the same account state, such as a level-up found at login on one PC and seen live on another,
 * get a fixed one made from what happened, so their copies collapse into one.
 */
public final class EventIds
{
	private EventIds()
	{
	}

	public static String random()
	{
		return UUID.randomUUID().toString();
	}

	/**
	 * A fixed ID from its parts, e.g. {@code fixed("level", "ATTACK", 80)} is "level|ATTACK|80".
	 */
	public static String fixed(Object... parts)
	{
		StringBuilder id = new StringBuilder();
		for (Object part : parts)
		{
			if (id.length() > 0)
			{
				id.append('|');
			}
			id.append(part);
		}
		return id.toString();
	}

	/**
	 * Gives IDs to a day's events saved before events had them. The ID comes from the event itself and
	 * how many identical events came before it that day, so the same history gets the same IDs on
	 * every device.
	 *
	 * @return whether any event was given an ID
	 */
	public static boolean assignMissing(DayRecord day)
	{
		boolean changed = false;
		Map<String, Integer> seen = new HashMap<>();
		for (JourneyEvent e : day.getEvents())
		{
			String content = day.getDate() + "\n" + e.getTime() + "\n" + e.getType() + "\n" + e.getTitle() + "\n" + e.getDetail();
			int n = seen.merge(content, 1, Integer::sum) - 1;
			if (e.getId() == null)
			{
				e.setId(UUID.nameUUIDFromBytes((content + "\n" + n).getBytes(StandardCharsets.UTF_8)).toString());
				changed = true;
			}
		}
		return changed;
	}
}
