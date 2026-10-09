package com.runejourney.sync;

import com.runejourney.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class EventIds
{
	private EventIds()
	{
	}

	public static String random()
	{
		return UUID.randomUUID().toString();
	}

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
