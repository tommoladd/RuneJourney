package com.runejourney.model;

import java.util.*;
import lombok.Data;

@Data
public class DaySlice
{
	private DayRecord day;
	private Map<String, NoteEdit> notes = new HashMap<>();
	private Set<String> deleted = new HashSet<>();
	private Set<String> screenshotsDeleted = new HashSet<>();
	private long snapshotClock;
}
