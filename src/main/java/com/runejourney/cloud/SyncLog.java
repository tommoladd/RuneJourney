package com.runejourney.cloud;

import com.runejourney.service.JourneyStore;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * What cloud sync did, for working out what went wrong: plugin-data/runejourney/cloud/sync.log,
 * with the one before it as sync.log.1. Each request's method, where it went (never a key or a
 * signed address), how it went and how long it took, and what each sync did. Written from the
 * sync's own threads, never the client's.
 */
@Slf4j
@Singleton
public class SyncLog
{
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final CloudFiles files;

	@Inject
	SyncLog(JourneyStore files)
	{
		this((CloudFiles) files);
	}

	SyncLog(CloudFiles files)
	{
		this.files = files;
	}

	/**
	 * Adds a line. Never fails: a log that can't be written is skipped.
	 */
	public synchronized void write(String format, Object... args)
	{
		String line = LocalDateTime.now().format(TIME) + "  " + (args.length == 0 ? format : String.format(format, args));
		try
		{
			files.appendSyncLog(line.replace('\n', ' ').replace('\r', ' ') + System.lineSeparator());
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Unable to write the RuneJourney sync log", e);
		}
	}
}
