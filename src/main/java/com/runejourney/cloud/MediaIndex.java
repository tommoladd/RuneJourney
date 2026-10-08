package com.runejourney.cloud;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;

/**
 * What's known about an account's screenshots in the cloud, saved in (account)/sync/media.json.
 */
@Data
public class MediaIndex
{
	/**
	 * Media ID to its screenshot.
	 */
	private Map<String, Entry> entries = new HashMap<>();
	/**
	 * The player was asked whether to back up the screenshots taken before connecting.
	 */
	private boolean backlogAsked;
	/**
	 * The cloud profile the states were last checked against. A different one (the account's cloud
	 * data was deleted, and it was saved again) has none of them until they go up again.
	 */
	private String profileId;

	public enum State
	{
		/**
		 * Waiting to upload.
		 */
		QUEUED,
		/**
		 * Waiting for room: the cloud screenshot storage is full.
		 */
		WAITING,
		UPLOADED,
		/**
		 * Kept on this PC only: removed from the cloud, or never backed up.
		 */
		LOCAL
	}

	@Data
	public static class Entry
	{
		/**
		 * The file name it was saved under on the PC that took it.
		 */
		private String name;
		private String title;
		private long time;
		private State state;
		/**
		 * It's in the cloud now (uploaded from any PC).
		 */
		private boolean inCloud;
		/**
		 * Bytes it takes in the cloud, screenshot and thumbnail.
		 */
		private long cloudBytes;
	}
}
