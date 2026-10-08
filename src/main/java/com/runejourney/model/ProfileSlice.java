package com.runejourney.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.Data;

/**
 * One PC's copy of the account's long-lived state, with when it last changed each part of it. See
 * {@link com.runejourney.sync.ProfileJoin} for how copies are combined.
 */
@Data
public class ProfileSlice
{
	private ProfileData profile;
	/**
	 * Logical clock of this PC's last change to each part, e.g. "holdings.bank" or "goal.(id).spec".
	 */
	private Map<String, Long> clocks = new HashMap<>();
	/**
	 * Goals this PC deleted.
	 */
	private Set<String> deletedGoals = new HashSet<>();
}
