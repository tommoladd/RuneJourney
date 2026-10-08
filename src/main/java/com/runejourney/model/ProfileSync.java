package com.runejourney.model;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;

/**
 * A synced profile's copies, saved in profile.json so they're written together with it.
 */
@Data
public class ProfileSync
{
	/**
	 * This PC's copy, as of when it was last worked out.
	 */
	private ProfileSlice own;
	/**
	 * Other PCs' copies, by device ID.
	 */
	private Map<String, ProfileSlice> remotes = new HashMap<>();
}
