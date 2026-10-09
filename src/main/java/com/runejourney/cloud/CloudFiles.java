package com.runejourney.cloud;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The files cloud sync keeps on this PC. Every method does blocking IO and must not be called on
 * the client thread.
 */
public interface CloudFiles
{
	/**
	 * @return null if this PC has never connected
	 */
	CloudCredentials readCredentials() throws IOException;

	void writeCredentials(CloudCredentials credentials) throws IOException;

	/**
	 * Adds to the sync log (cloud/sync.log), starting a new one when it gets large.
	 */
	void appendSyncLog(String text) throws IOException;

	/**
	 * @return null if the account has never been synced on this PC
	 */
	SyncState readSyncState(String profileKey) throws IOException;

	void writeSyncState(String profileKey, SyncState state) throws IOException;

	/**
	 * Documents waiting to upload, by document key.
	 */
	Map<String, String> readOutbox(String profileKey) throws IOException;

	void writeOutbox(String profileKey, Map<String, String> docs) throws IOException;

	/**
	 * Copies the account's journey to a backup folder beside it.
	 */
	void backup(String profileKey) throws IOException;

	/**
	 * Accounts with cloud sync state on this PC.
	 */
	List<String> syncedProfiles() throws IOException;

	/**
	 * Holds the account's lock for the account being played in this window.
	 */
	String SESSION = "session";
	/**
	 * Holds the account's lock while cloud sync works on it.
	 */
	String SYNC = "sync";
	/**
	 * Holds the account's lock while saving it at the login screen.
	 */
	String SAVE = "save";

	/**
	 * Takes the account's lock, so only one RuneLite window saves and syncs it at a time. Within a
	 * window it can be held several times (by playing, syncing, saving); each hold is given up with
	 * {@link #unlock} and the lock is released once none are left.
	 *
	 * @return whether this window holds it now
	 */
	boolean tryLock(String profileKey, String holder);

	/**
	 * Whether this window holds the account's lock, for anything.
	 */
	boolean isLocked(String profileKey);

	void unlock(String profileKey, String holder);

	/**
	 * Whether another window changed the account's files since this one loaded or saved them.
	 */
	boolean changedOnDisk(String profileKey) throws IOException;
}
