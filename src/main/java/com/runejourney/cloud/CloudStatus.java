package com.runejourney.cloud;

import lombok.Builder;
import lombok.Value;

/**
 * Everything the side panel shows about cloud sync, as of one moment.
 */
@Value
@Builder(toBuilder = true)
public class CloudStatus
{
	public enum Connection
	{
		/**
		 * Cloud sync is turned off in the settings.
		 */
		OFF,
		NOT_CONNECTED,
		CONNECTING,
		CONNECTED,
		/**
		 * The key was revoked on the website (or the account deleted).
		 */
		KEY_REJECTED
	}

	public enum Character
	{
		/**
		 * Not copied yet: the player hasn't stood still since the page started showing it.
		 */
		WAITING,
		/**
		 * Copied, and waiting to be sent.
		 */
		SENDING,
		SHOWN
	}

	public enum Prompt
	{
		NONE,
		/**
		 * "Save (name) to (Discord name)'s cloud?"
		 */
		CONSENT,
		/**
		 * This PC and the cloud both have a journey for the account.
		 */
		CHOOSE,
		/**
		 * Back up screenshots taken before connecting?
		 */
		BACKLOG
	}

	Connection connection;
	String userName;
	String deviceName;
	/**
	 * The last problem, worded for the player, or null.
	 */
	String problem;
	/**
	 * A newer RuneJourney saved data this version can't read.
	 */
	boolean updateNeeded;

	/**
	 * The account loaded in this window, or null.
	 */
	String account;
	Prompt prompt;
	/**
	 * For {@link Prompt#CONSENT}: the account is already saved in the cloud from another PC.
	 */
	boolean savedElsewhere;
	/**
	 * Whether the account is saved to the cloud from this PC: true, false ("Not on this PC"), or
	 * null if not asked yet.
	 */
	Boolean consent;
	boolean linked;
	/**
	 * Another RuneLite window has the account open, so this one isn't saving it.
	 */
	boolean readOnly;
	boolean syncing;
	long lastSync;
	/**
	 * Changes waiting to upload.
	 */
	int waiting;

	/**
	 * The account's public page: whether it's public, in the website's search, taken down after a
	 * report, its address once published, and why it couldn't be published.
	 */
	boolean publicEnabled;
	boolean publicSearchable;
	boolean publicBlocked;
	String publicUrl;
	String publicProblem;
	/**
	 * The character on the public page, or null if the page doesn't show it.
	 */
	Character publicCharacter;

	boolean screenshots;
	long mediaUsed;
	long mediaQuota;
	boolean mediaFull;
	int mediaWaiting;
	int backlogCount;
	long backlogBytes;
}
