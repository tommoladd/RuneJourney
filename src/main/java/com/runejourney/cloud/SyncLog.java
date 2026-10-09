package com.runejourney.cloud;

import com.runejourney.service.JourneyStore;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.inject.*;
import lombok.extern.slf4j.Slf4j;

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
