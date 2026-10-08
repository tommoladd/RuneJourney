package com.runejourney.model;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;

/**
 * A synced day's parts, saved in the day's file so they're written together with it. The day
 * itself is always every part combined, plus anything this PC has recorded since it last worked out
 * its own part.
 */
@Data
public class DaySync
{
	/**
	 * This PC's part, as of when it was last worked out.
	 */
	private DaySlice own;
	/**
	 * Other PCs' parts, by device ID.
	 */
	private Map<String, DaySlice> remotes = new HashMap<>();
}
