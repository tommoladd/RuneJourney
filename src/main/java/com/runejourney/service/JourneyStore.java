package com.runejourney.service;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.runejourney.cloud.CloudCredentials;
import com.runejourney.cloud.CloudFiles;
import com.runejourney.cloud.MediaIndex;
import com.runejourney.cloud.SyncState;
import com.runejourney.model.DayRecord;
import com.runejourney.model.ProfileData;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Setter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Local-first persistence. Layout inside the plugin data directory:
 * <pre>
 *   &lt;profileKey&gt;/profile.json
 *   &lt;profileKey&gt;/days/yyyy-MM-dd.json
 *   &lt;profileKey&gt;/screenshots/*.png             (and *.jpg copies downloaded from the cloud)
 *   &lt;profileKey&gt;/sync/state.json, outbox.json, media.json, lock, thumbs/*.jpg
 *   &lt;profileKey&gt;/backup-&lt;time&gt;/                (the journey before it was replaced by the cloud's)
 *   cloud/credentials.json
 * </pre>
 * All methods perform blocking IO and must not be called on the client thread.
 */
@Slf4j
@Singleton
public class JourneyStore implements CloudFiles
{
	private static final String PROFILE_FILE = "profile.json";
	private static final String DAYS_DIR = "days";
	private static final String SCREENSHOT_DIR = "screenshots";
	private static final String CLOUD_DIR = "cloud";
	private static final String SYNC_LOG = "sync.log";
	private static final long MAX_SYNC_LOG_BYTES = 512 * 1024;
	private static final String CREDENTIALS_FILE = "credentials.json";
	private static final String SYNC_DIR = "sync";
	private static final String STATE_FILE = "state.json";
	private static final String OUTBOX_FILE = "outbox.json";
	private static final String MEDIA_FILE = "media.json";
	private static final String LOCK_FILE = "lock";
	private static final String THUMBS_DIR = "thumbs";
	private static final Type OUTBOX_TYPE = new TypeToken<Map<String, String>>()
	{
	}.getType();

	private final Gson gson;
	/**
	 * Profile key to the stamps of its files as this RuneLite last loaded or saved them.
	 */
	private final Map<String, Map<String, String>> seen = new ConcurrentHashMap<>();
	/**
	 * Accounts this window holds the lock for, and what holds each: playing it, a sync, a save.
	 */
	private final Map<String, FileLock> locks = new HashMap<>();
	private final Map<String, List<String>> holders = new HashMap<>();

	@Setter
	private Filepath root;

	@Inject
	JourneyStore(Gson gson)
	{
		this.gson = gson;
	}

	@Value
	public static class Loaded
	{
		ProfileData profile;
		TreeMap<String, DayRecord> days;
	}

	/**
	 * Fails rather than leaving out a file that couldn't be read, so a blank profile is never saved
	 * over one that's still there. A file that isn't valid JSON is moved aside and left out.
	 */
	public Loaded load(String profileKey) throws IOException
	{
		Filepath dir = profileDir(profileKey);
		// Before reading, so a write that lands during the load shows up as a change
		Map<String, String> stamps = scan(dir);
		ProfileData profile = null;
		Filepath profileFile = dir.joinSegment(PROFILE_FILE);
		if (profileFile.exists())
		{
			profile = read(profileFile, ProfileData.class);
		}

		TreeMap<String, DayRecord> days = new TreeMap<>();
		for (Filepath f : dayFiles(dir))
		{
			DayRecord day = read(f, DayRecord.class);
			if (day != null && day.getDate() != null)
			{
				days.put(day.getDate(), day);
			}
		}
		seen.put(profileKey, stamps);
		return new Loaded(profile, days);
	}

	private <T> T read(Filepath file, Type type) throws IOException
	{
		// Read in full first: Gson reports a failed read as bad JSON
		String json;
		try (InputStream in = file.openInputStream())
		{
			json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		T value = parse(gson, json, type);
		if (value == null)
		{
			Filepath aside = file.getParent().joinSegment(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
			file.moveTo(aside);
			log.warn("RuneJourney data file {} was damaged and has been moved to {}", file, aside);
		}
		return value;
	}

	/**
	 * @return the parsed file, or null if it's damaged (not valid JSON, or empty)
	 */
	static <T> T parse(Gson gson, String json, Class<T> type)
	{
		return parse(gson, json, (Type) type);
	}

	static <T> T parse(Gson gson, String json, Type type)
	{
		try
		{
			return gson.fromJson(json, type);
		}
		catch (JsonParseException e)
		{
			return null;
		}
	}

	public void writeProfile(String profileKey, String json) throws IOException
	{
		Filepath f = writeAtomic(profileDir(profileKey), PROFILE_FILE, json);
		remember(profileKey, PROFILE_FILE, f);
	}

	public void writeDay(String profileKey, String date, String json) throws IOException
	{
		String name = date + ".json";
		Filepath f = writeAtomic(profileDir(profileKey).joinSegment(DAYS_DIR), name, json);
		remember(profileKey, DAYS_DIR + "/" + name, f);
	}

	public void deleteDay(String profileKey, String date) throws IOException
	{
		String name = date + ".json";
		profileDir(profileKey).joinSegment(DAYS_DIR).joinSegment(name).deleteIfExists();
		Map<String, String> known = seen.get(profileKey);
		if (known != null)
		{
			known.remove(DAYS_DIR + "/" + name);
		}
	}

	private Filepath writeAtomic(Filepath dir, String name, String content) throws IOException
	{
		dir.createDirectories();
		Filepath target = dir.joinSegment(name);
		Filepath tmp = dir.joinSegment(name + ".tmp");
		tmp.write(content);
		try
		{
			tmp.moveTo(target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException e)
		{
			tmp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
		}
		return target;
	}

	/**
	 * Whether a profile's files changed since it was last loaded, other than by this RuneLite's own
	 * saves. Another RuneLite window playing the same account changes them.
	 */
	@Override
	public boolean changedOnDisk(String profileKey) throws IOException
	{
		Map<String, String> known = seen.get(profileKey);
		return known == null || !known.equals(scan(profileDir(profileKey)));
	}

	private void remember(String profileKey, String name, Filepath file) throws IOException
	{
		Map<String, String> known = seen.get(profileKey);
		if (known != null)
		{
			known.put(name, stamp(file));
		}
	}

	/**
	 * The size and modified time of each data file, by path within the profile folder.
	 */
	private static Map<String, String> scan(Filepath dir) throws IOException
	{
		Map<String, String> stamps = new ConcurrentHashMap<>();
		Filepath profileFile = dir.joinSegment(PROFILE_FILE);
		if (profileFile.exists())
		{
			stamps.put(PROFILE_FILE, stamp(profileFile));
		}
		for (Filepath f : dayFiles(dir))
		{
			stamps.put(DAYS_DIR + "/" + f.getFileName(), stamp(f));
		}
		return stamps;
	}

	private static String stamp(Filepath file) throws IOException
	{
		return file.size() + "@" + file.getLastModifiedTime();
	}

	private static List<Filepath> dayFiles(Filepath dir) throws IOException
	{
		Filepath daysDir = dir.joinSegment(DAYS_DIR);
		if (!daysDir.isDirectory())
		{
			return new java.util.ArrayList<>();
		}
		try (Stream<Filepath> walk = daysDir.walk(1))
		{
			return walk.filter(f -> f.getFileName().endsWith(".json")).collect(Collectors.toList());
		}
	}

	public void writeScreenshot(String profileKey, String name, BufferedImage image) throws IOException
	{
		Filepath dir = profileDir(profileKey).joinSegment(SCREENSHOT_DIR);
		dir.createDirectories();
		try (OutputStream out = dir.joinSegment(name).openOutputStream())
		{
			ImageIO.write(image, "png", out);
		}
	}

	/**
	 * A screenshot by the name it was taken with, or the copy downloaded from the cloud if this PC
	 * doesn't have the original.
	 */
	@Override
	public BufferedImage readScreenshot(String profileKey, String name) throws IOException
	{
		if (!isScreenshotName(name))
		{
			return null;
		}
		Filepath dir = profileDir(profileKey).joinSegment(SCREENSHOT_DIR);
		Filepath file = dir.joinSegment(name);
		if (!file.exists())
		{
			file = dir.joinSegment(cloudCopyName(name));
			if (!file.exists())
			{
				return null;
			}
		}
		try (InputStream in = file.openInputStream())
		{
			return ImageIO.read(in);
		}
	}

	/**
	 * Cloud copies are JPEGs saved beside where the original would be.
	 */
	private static String cloudCopyName(String name)
	{
		return name.substring(0, name.length() - ".png".length()) + ".jpg";
	}

	@Value
	public static class ScreenshotFile
	{
		String name;
		long size;
		/**
		 * Epoch millis the file was last written.
		 */
		long modified;
		/**
		 * Only a copy downloaded from the cloud is on this PC.
		 */
		boolean cloudCopy;
	}

	/**
	 * Only names RuneJourney itself writes, so a name can never point outside the folder.
	 */
	private static final java.util.regex.Pattern SCREENSHOT_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9._-]+\\.png");

	public static boolean isScreenshotName(String name)
	{
		return name != null && SCREENSHOT_NAME.matcher(name).matches() && !name.startsWith(".");
	}

	/**
	 * Every screenshot saved for a profile, newest first. Copies downloaded from the cloud are listed
	 * by the name they were taken with.
	 */
	@Override
	public List<ScreenshotFile> listScreenshots(String profileKey) throws IOException
	{
		Filepath dir = profileDir(profileKey).joinSegment(SCREENSHOT_DIR);
		if (!dir.isDirectory())
		{
			return new ArrayList<>();
		}
		List<Filepath> files;
		try (Stream<Filepath> walk = dir.walk(1))
		{
			files = walk.collect(Collectors.toList());
		}
		Set<String> names = new HashSet<>();
		files.forEach(f -> names.add(f.getFileName()));
		List<ScreenshotFile> result = new ArrayList<>();
		for (Filepath f : files)
		{
			String name = f.getFileName();
			if (isScreenshotName(name))
			{
				result.add(new ScreenshotFile(name, f.size(), f.getLastModifiedTime().toMillis(), false));
			}
			else if (name.endsWith(".jpg"))
			{
				String original = name.substring(0, name.length() - ".jpg".length()) + ".png";
				if (isScreenshotName(original) && !names.contains(original))
				{
					result.add(new ScreenshotFile(original, f.size(), f.getLastModifiedTime().toMillis(), true));
				}
			}
		}
		result.sort((a, b) -> Long.compare(b.getModified(), a.getModified()));
		return result;
	}

	/**
	 * Deletes a screenshot, and any copy of it downloaded from the cloud.
	 */
	public boolean deleteScreenshot(String profileKey, String name) throws IOException
	{
		if (!isScreenshotName(name))
		{
			return false;
		}
		Filepath dir = profileDir(profileKey).joinSegment(SCREENSHOT_DIR);
		Filepath file = dir.joinSegment(name);
		Filepath copy = dir.joinSegment(cloudCopyName(name));
		boolean existed = file.exists() || copy.exists();
		file.deleteIfExists();
		copy.deleteIfExists();
		return existed;
	}

	@Override
	public void writeCloudCopy(String profileKey, String name, byte[] jpeg) throws IOException
	{
		if (!isScreenshotName(name))
		{
			throw new IOException("Not a screenshot name");
		}
		Filepath dir = profileDir(profileKey).joinSegment(SCREENSHOT_DIR);
		dir.createDirectories();
		dir.joinSegment(cloudCopyName(name)).write(jpeg);
	}

	@Override
	public void deleteCloudCopy(String profileKey, String name) throws IOException
	{
		if (isScreenshotName(name))
		{
			profileDir(profileKey).joinSegment(SCREENSHOT_DIR).joinSegment(cloudCopyName(name)).deleteIfExists();
		}
	}

	// ------------------------------------------------------------------
	// Cloud sync
	// ------------------------------------------------------------------

	private Filepath syncDir(String profileKey)
	{
		return profileDir(profileKey).joinSegment(SYNC_DIR);
	}

	@Override
	public CloudCredentials readCredentials() throws IOException
	{
		return readJson(root().joinSegment(CLOUD_DIR).joinSegment(CREDENTIALS_FILE), CloudCredentials.class);
	}

	@Override
	public void writeCredentials(CloudCredentials credentials) throws IOException
	{
		writeAtomic(root().joinSegment(CLOUD_DIR), CREDENTIALS_FILE, gson.toJson(credentials));
	}

	@Override
	public String syncLogLocation()
	{
		Filepath dir = root().joinSegment(CLOUD_DIR);
		Filepath log = dir.joinSegment(SYNC_LOG);
		return (log.exists() ? log : dir).toString();
	}

	@Override
	public void appendSyncLog(String text) throws IOException
	{
		Filepath dir = root().joinSegment(CLOUD_DIR);
		dir.createDirectories();
		Filepath log = dir.joinSegment(SYNC_LOG);
		if (log.exists() && log.size() > MAX_SYNC_LOG_BYTES)
		{
			log.moveTo(dir.joinSegment(SYNC_LOG + ".1"), StandardCopyOption.REPLACE_EXISTING);
		}
		log.write(text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	@Override
	public SyncState readSyncState(String profileKey) throws IOException
	{
		return readJson(syncDir(profileKey).joinSegment(STATE_FILE), SyncState.class);
	}

	@Override
	public void writeSyncState(String profileKey, SyncState state) throws IOException
	{
		writeAtomic(syncDir(profileKey), STATE_FILE, gson.toJson(state));
	}

	@Override
	public Map<String, String> readOutbox(String profileKey) throws IOException
	{
		Map<String, String> docs = readJson(syncDir(profileKey).joinSegment(OUTBOX_FILE), OUTBOX_TYPE);
		return docs != null ? docs : new TreeMap<>();
	}

	@Override
	public void writeOutbox(String profileKey, Map<String, String> docs) throws IOException
	{
		writeAtomic(syncDir(profileKey), OUTBOX_FILE, gson.toJson(docs));
	}

	@Override
	public MediaIndex readMediaIndex(String profileKey) throws IOException
	{
		MediaIndex index = readJson(syncDir(profileKey).joinSegment(MEDIA_FILE), MediaIndex.class);
		return index != null ? index : new MediaIndex();
	}

	@Override
	public void writeMediaIndex(String profileKey, MediaIndex index) throws IOException
	{
		writeAtomic(syncDir(profileKey), MEDIA_FILE, gson.toJson(index));
	}

	@Override
	public void backup(String profileKey) throws IOException
	{
		Filepath dir = profileDir(profileKey);
		Filepath backup = dir.joinSegment("backup-" + System.currentTimeMillis());
		backup.joinSegment(DAYS_DIR).createDirectories();
		Filepath profileFile = dir.joinSegment(PROFILE_FILE);
		if (profileFile.exists())
		{
			profileFile.copyTo(backup.joinSegment(PROFILE_FILE));
		}
		for (Filepath f : dayFiles(dir))
		{
			f.copyTo(backup.joinSegment(DAYS_DIR).joinSegment(f.getFileName()));
		}
		log.info("RuneJourney backed up this PC's journey to {}", backup);
	}

	@Override
	public List<String> syncedProfiles() throws IOException
	{
		List<String> keys = new ArrayList<>();
		Filepath root = root();
		if (!root.isDirectory())
		{
			return keys;
		}
		try (Stream<Filepath> walk = root.walk(1))
		{
			for (Filepath dir : walk.collect(Collectors.toList()))
			{
				if (!dir.equals(root) && dir.isDirectory() && !CLOUD_DIR.equals(dir.getFileName())
					&& dir.joinSegment(SYNC_DIR).joinSegment(STATE_FILE).exists())
				{
					keys.add(dir.getFileName());
				}
			}
		}
		return keys;
	}

	@Override
	public synchronized boolean tryLock(String profileKey, String holder)
	{
		if (!locks.containsKey(profileKey))
		{
			FileChannel channel = null;
			FileLock lock = null;
			try
			{
				Filepath dir = syncDir(profileKey);
				dir.createDirectories();
				channel = dir.joinSegment(LOCK_FILE).openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				lock = channel.tryLock();
			}
			catch (IOException | OverlappingFileLockException e)
			{
				log.debug("Unable to lock RuneJourney profile", e);
			}
			if (lock == null)
			{
				closeQuietly(channel);
				return false;
			}
			locks.put(profileKey, lock);
		}
		holders.computeIfAbsent(profileKey, k -> new ArrayList<>()).add(holder);
		return true;
	}

	@Override
	public synchronized boolean isLocked(String profileKey)
	{
		return locks.containsKey(profileKey);
	}

	/**
	 * Gives up one hold. The lock itself is released once nothing in this window holds it.
	 */
	@Override
	public synchronized void unlock(String profileKey, String holder)
	{
		List<String> held = holders.get(profileKey);
		if (held == null || !held.remove(holder) || !held.isEmpty())
		{
			return;
		}
		holders.remove(profileKey);
		release(profileKey);
	}

	public synchronized void unlockAll()
	{
		holders.clear();
		new ArrayList<>(locks.keySet()).forEach(this::release);
	}

	private void release(String profileKey)
	{
		FileLock lock = locks.remove(profileKey);
		if (lock != null)
		{
			try
			{
				lock.release();
			}
			catch (IOException e)
			{
				log.debug("Unable to release RuneJourney profile lock", e);
			}
			closeQuietly(lock.channel());
		}
	}

	private static void closeQuietly(FileChannel channel)
	{
		if (channel != null)
		{
			try
			{
				channel.close();
			}
			catch (IOException e)
			{
				log.debug("Unable to close lock file", e);
			}
		}
	}

	@Override
	public byte[] readThumb(String profileKey, String mediaId) throws IOException
	{
		Filepath f = thumbFile(profileKey, mediaId);
		if (f == null || !f.exists())
		{
			return null;
		}
		try (InputStream in = f.openInputStream())
		{
			return in.readAllBytes();
		}
	}

	@Override
	public void writeThumb(String profileKey, String mediaId, byte[] jpeg) throws IOException
	{
		Filepath f = thumbFile(profileKey, mediaId);
		if (f != null)
		{
			f.getParent().createDirectories();
			f.write(jpeg);
		}
	}

	@Override
	public void deleteThumb(String profileKey, String mediaId) throws IOException
	{
		Filepath f = thumbFile(profileKey, mediaId);
		if (f != null)
		{
			f.deleteIfExists();
		}
	}

	private Filepath thumbFile(String profileKey, String mediaId)
	{
		// Media IDs are made by RuneJourney: 32 hex digits
		if (mediaId == null || !mediaId.matches("[0-9a-f]{32}"))
		{
			return null;
		}
		return syncDir(profileKey).joinSegment(THUMBS_DIR).joinSegment(mediaId + ".jpg");
	}

	/**
	 * @return null if the file doesn't exist, or is damaged (it's moved aside)
	 */
	private <T> T readJson(Filepath file, Type type) throws IOException
	{
		if (!file.exists())
		{
			return null;
		}
		return read(file, type);
	}

	private Filepath root()
	{
		if (root == null)
		{
			throw new IllegalStateException("RuneJourney data directory not set");
		}
		return root;
	}

	private Filepath profileDir(String profileKey)
	{
		if (root == null)
		{
			throw new IllegalStateException("RuneJourney data directory not set");
		}
		return root.joinSegment(profileKey);
	}
}
