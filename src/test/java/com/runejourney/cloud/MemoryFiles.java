package com.runejourney.cloud;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A PC's cloud sync files, kept in memory, as one RuneLite window sees them. Everything is copied
 * through JSON on the way in and out, as it would be on disk. Windows made with {@link #window}
 * share the same files and lock files, each with its own holds.
 */
class MemoryFiles implements CloudFiles
{
	private static class Disk
	{
		String credentials;
		final Map<String, String> states = new HashMap<>();
		final Map<String, String> outboxes = new HashMap<>();
		/**
		 * Account to the window holding its lock file.
		 */
		final Map<String, MemoryFiles> lockFiles = new HashMap<>();
		int backups;
		final StringBuilder syncLog = new StringBuilder();
	}

	private final Gson gson = new Gson();
	private final Disk disk;
	private final Map<String, List<String>> holds = new HashMap<>();
	final StringBuilder syncLog;

	MemoryFiles()
	{
		this(new Disk());
	}

	private MemoryFiles(Disk disk)
	{
		this.disk = disk;
		this.syncLog = disk.syncLog;
	}

	/**
	 * Another RuneLite window on the same PC.
	 */
	MemoryFiles window()
	{
		return new MemoryFiles(disk);
	}

	/**
	 * A copy of everything, as when a RuneLite folder is copied to another PC.
	 */
	MemoryFiles copy()
	{
		Disk d = new Disk();
		d.credentials = disk.credentials;
		d.states.putAll(disk.states);
		d.outboxes.putAll(disk.outboxes);
		return new MemoryFiles(d);
	}

	int backups()
	{
		return disk.backups;
	}

	SyncState state(String key)
	{
		return readSyncState(key);
	}

	@Override
	public CloudCredentials readCredentials()
	{
		return disk.credentials == null ? null : gson.fromJson(disk.credentials, CloudCredentials.class);
	}

	@Override
	public void writeCredentials(CloudCredentials c)
	{
		disk.credentials = gson.toJson(c);
	}
	@Override
	public void appendSyncLog(String text)
	{
		syncLog.append(text);
	}

	@Override
	public SyncState readSyncState(String key)
	{
		String json = disk.states.get(key);
		return json == null ? null : gson.fromJson(json, SyncState.class);
	}

	@Override
	public void writeSyncState(String key, SyncState state)
	{
		disk.states.put(key, gson.toJson(state));
	}

	@Override
	public Map<String, String> readOutbox(String key)
	{
		String json = disk.outboxes.get(key);
		return json == null ? new HashMap<>() : gson.fromJson(json, new TypeToken<Map<String, String>>()
		{
		}.getType());
	}

	@Override
	public void writeOutbox(String key, Map<String, String> docs)
	{
		disk.outboxes.put(key, gson.toJson(docs));
	}

	@Override
	public void backup(String key)
	{
		disk.backups++;
	}

	@Override
	public List<String> syncedProfiles()
	{
		return new ArrayList<>(disk.states.keySet());
	}

	@Override
	public boolean tryLock(String key, String holder)
	{
		MemoryFiles owner = disk.lockFiles.get(key);
		if (owner != null && owner != this)
		{
			return false;
		}
		disk.lockFiles.put(key, this);
		holds.computeIfAbsent(key, k -> new ArrayList<>()).add(holder);
		return true;
	}

	@Override
	public boolean isLocked(String key)
	{
		return disk.lockFiles.get(key) == this;
	}

	@Override
	public void unlock(String key, String holder)
	{
		List<String> held = holds.get(key);
		if (held != null && held.remove(holder) && held.isEmpty())
		{
			holds.remove(key);
			disk.lockFiles.remove(key);
		}
	}

	@Override
	public boolean changedOnDisk(String key)
	{
		return false;
	}
}
