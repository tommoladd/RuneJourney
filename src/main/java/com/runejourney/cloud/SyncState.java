package com.runejourney.cloud;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * Cloud sync progress for one account on this PC, saved in (account)/sync/state.json.
 */
@Data
public class SyncState
{
	/**
	 * Whose cloud the account is saved to. Connecting a different Discord account starts over.
	 */
	private String userUuid;
	/**
	 * True to save the account to the cloud from this PC, false for "Not on this PC", null until asked.
	 */
	private Boolean consent;
	/**
	 * The account's cloud profile.
	 */
	private String profileId;
	/**
	 * The device ID this PC's sync parts were made under. A different one now means the RuneLite
	 * folder was copied, so this PC has become a new device.
	 */
	private String deviceId;
	/**
	 * Syncing has started: this PC's parts of the journey exist.
	 */
	private boolean linked;
	/**
	 * Waiting for the player to choose between this PC's journey and the cloud's.
	 */
	private boolean choosing;
	/**
	 * This PC's own copy is being fetched back from the cloud (its journey was lost, or replaced by
	 * the cloud's). Nothing is uploaded until that has finished, so a blank copy can never replace it.
	 */
	private boolean restoring;
	/**
	 * Changes up to here have been fetched.
	 */
	private long cursor;
	/**
	 * How many commits this PC has made to the profile.
	 */
	private int seq;
	/**
	 * This PC's logical clock, see {@link com.runejourney.sync.Hlc}.
	 */
	private long clock;
	/**
	 * Document key to the SHA-256 of the last version of it this PC committed, so unchanged documents
	 * aren't uploaded again.
	 */
	private Map<String, String> sent = new HashMap<>();
	/**
	 * A commit sent whose reply hasn't arrived. It's sent again with the same change ID, which the
	 * server answers with the original result if it was applied.
	 */
	private PendingCommit pending;
	private long lastSync;
	/**
	 * The account's public page settings, as the server last said.
	 */
	private Api.PublicSettings publicSettings;
	/**
	 * The SHA-256 of what was last published to the public page, so unchanged pages aren't sent again.
	 */
	private String publishedHash;
	private long lastPublished;
	/**
	 * Why the public page couldn't be published, worded for the player, or null.
	 */
	private String publicProblem;
	/**
	 * The account's entry on the hiscores, from the last lookup for its public page.
	 */
	private Hiscores.Entry hiscores;
	/**
	 * When the hiscores were last tried, successfully or not.
	 */
	private long hiscoresTriedAt;

	@Data
	public static class PendingCommit
	{
		private String changeId;
		private int seq;
		private List<String> uploadIds = new ArrayList<>();
		/**
		 * Document key (or media ID for screenshots) to what was uploaded.
		 */
		private Map<String, String> docs = new HashMap<>();
		private List<String> media = new ArrayList<>();
		private long sentAt;
	}
}
