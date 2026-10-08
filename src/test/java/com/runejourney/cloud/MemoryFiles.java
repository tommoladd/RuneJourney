package com.runejourney.cloud;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.runejourney.service.JourneyStore;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
		final Map<String, String> indexes = new HashMap<>();
		/**
		 * Account to the window holding its lock file.
		 */
		final Map<String, MemoryFiles> lockFiles = new HashMap<>();
		final Map<String, BufferedImage> screenshots = new TreeMap<>();
		final Map<String, byte[]> cloudCopies = new HashMap<>();
		final Map<String, byte[]> thumbs = new HashMap<>();
		int backups;
		final StringBuilder syncLog = new StringBuilder();
	}

	private final Gson gson = new Gson();
	private final Disk disk;
	private final Map<String, List<String>> holds = new HashMap<>();
	final Map<String, BufferedImage> screenshots;
	final Map<String, byte[]> cloudCopies;
	final Map<String, byte[]> thumbs;
	final StringBuilder syncLog;

	MemoryFiles()
	{
		this(new Disk());
	}

	private MemoryFiles(Disk disk)
	{
		this.disk = disk;
		this.screenshots = disk.screenshots;
		this.cloudCopies = disk.cloudCopies;
		this.thumbs = disk.thumbs;
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
		d.indexes.putAll(disk.indexes);
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
	public String syncLogLocation()
	{
		return "memory";
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
	public MediaIndex readMediaIndex(String key)
	{
		String json = disk.indexes.get(key);
		return json == null ? new MediaIndex() : gson.fromJson(json, MediaIndex.class);
	}

	@Override
	public void writeMediaIndex(String key, MediaIndex index)
	{
		disk.indexes.put(key, gson.toJson(index));
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

	@Override
	public List<JourneyStore.ScreenshotFile> listScreenshots(String key)
	{
		List<JourneyStore.ScreenshotFile> out = new ArrayList<>();
		screenshots.keySet().forEach(name -> out.add(new JourneyStore.ScreenshotFile(name, 1_000_000, 1_000, false)));
		cloudCopies.forEach((name, bytes) ->
		{
			if (!screenshots.containsKey(name))
			{
				out.add(new JourneyStore.ScreenshotFile(name, bytes.length, 1_000, true));
			}
		});
		return out;
	}

	@Override
	public BufferedImage readScreenshot(String key, String name)
	{
		return screenshots.get(name);
	}

	@Override
	public void writeCloudCopy(String key, String name, byte[] jpeg)
	{
		cloudCopies.put(name, jpeg);
	}

	@Override
	public void deleteCloudCopy(String key, String name)
	{
		cloudCopies.remove(name);
	}

	@Override
	public byte[] readThumb(String key, String mediaId)
	{
		return thumbs.get(mediaId);
	}

	@Override
	public void writeThumb(String key, String mediaId, byte[] jpeg)
	{
		thumbs.put(mediaId, jpeg);
	}

	@Override
	public void deleteThumb(String key, String mediaId)
	{
		thumbs.remove(mediaId);
	}
}
