package com.runejourney.cloud;

import java.io.IOException;
import java.util.*;

public interface CloudFiles
{
	CloudCredentials readCredentials() throws IOException;

	void writeCredentials(CloudCredentials credentials) throws IOException;

	void appendSyncLog(String text) throws IOException;

	SyncState readSyncState(String profileKey) throws IOException;

	void writeSyncState(String profileKey, SyncState state) throws IOException;

	Map<String, String> readOutbox(String profileKey) throws IOException;

	void writeOutbox(String profileKey, Map<String, String> docs) throws IOException;

	void backup(String profileKey) throws IOException;

	List<String> syncedProfiles() throws IOException;

	String SESSION = "session";
	String SYNC = "sync";
	String SAVE = "save";

	boolean tryLock(String profileKey, String holder);

	boolean isLocked(String profileKey);

	void unlock(String profileKey, String holder);

	boolean changedOnDisk(String profileKey) throws IOException;
}
