package com.runejourney.service;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.runejourney.model.DayRecord;
import com.runejourney.model.ProfileData;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.TreeMap;
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
 *   &lt;profileKey&gt;/screenshots/*.png
 * </pre>
 * All methods perform blocking IO and must not be called on the client thread.
 */
@Slf4j
@Singleton
public class JourneyStore
{
	private static final String PROFILE_FILE = "profile.json";
	private static final String DAYS_DIR = "days";
	private static final String SCREENSHOT_DIR = "screenshots";

	private final Gson gson;

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

	public Loaded load(String profileKey) throws IOException
	{
		Filepath dir = profileDir(profileKey);
		ProfileData profile = null;
		Filepath profileFile = dir.joinSegment(PROFILE_FILE);
		if (profileFile.exists())
		{
			profile = read(profileFile, ProfileData.class);
		}

		TreeMap<String, DayRecord> days = new TreeMap<>();
		Filepath daysDir = dir.joinSegment(DAYS_DIR);
		if (daysDir.isDirectory())
		{
			List<Filepath> files;
			try (Stream<Filepath> walk = daysDir.walk(1))
			{
				files = walk.filter(f -> f.getFileName().endsWith(".json")).collect(Collectors.toList());
			}
			for (Filepath f : files)
			{
				DayRecord day = read(f, DayRecord.class);
				if (day != null && day.getDate() != null)
				{
					days.put(day.getDate(), day);
				}
			}
		}
		return new Loaded(profile, days);
	}

	private <T> T read(Filepath file, Class<T> type)
	{
		try (Reader reader = file.openBufferedReader())
		{
			return gson.fromJson(reader, type);
		}
		catch (IOException | JsonParseException e)
		{
			log.warn("Unable to read RuneJourney data file {}", file, e);
			return null;
		}
	}

	public void writeProfile(String profileKey, String json) throws IOException
	{
		writeAtomic(profileDir(profileKey), PROFILE_FILE, json);
	}

	public void writeDay(String profileKey, String date, String json) throws IOException
	{
		writeAtomic(profileDir(profileKey).joinSegment(DAYS_DIR), date + ".json", json);
	}

	private void writeAtomic(Filepath dir, String name, String content) throws IOException
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

	public BufferedImage readScreenshot(String profileKey, String name) throws IOException
	{
		Filepath file = profileDir(profileKey).joinSegment(SCREENSHOT_DIR).joinSegment(name);
		if (!file.exists())
		{
			return null;
		}
		try (InputStream in = file.openInputStream())
		{
			return ImageIO.read(in);
		}
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
