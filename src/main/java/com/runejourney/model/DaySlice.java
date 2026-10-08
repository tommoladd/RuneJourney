package com.runejourney.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.Data;

/**
 * One PC's part of a day: what it recorded itself, and its edits to anything else. Each PC uploads
 * only its own part; a day is all the parts combined, see {@link com.runejourney.sync.DaySlices}.
 */
@Data
public class DaySlice
{
	/**
	 * Counts, XP ranges and the Journey events this PC recorded. Notes are kept in {@link #notes}.
	 */
	private DayRecord day;
	/**
	 * Event ID to the note this PC last wrote on it.
	 */
	private Map<String, NoteEdit> notes = new HashMap<>();
	/**
	 * Events this PC deleted. A delete is kept for good and beats any edit.
	 */
	private Set<String> deleted = new HashSet<>();
	/**
	 * Events whose screenshot this PC deleted.
	 */
	private Set<String> screenshotsDeleted = new HashSet<>();
	/**
	 * When this PC last wrote the day's end-of-day snapshot, by logical clock.
	 */
	private long snapshotClock;
}
