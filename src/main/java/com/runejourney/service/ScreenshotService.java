package com.runejourney.service;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.AllArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.DrawManager;

/**
 * Captures the game view for important Journey moments. Captures are delayed a couple of ticks so
 * level-up and drop dialogs are visible, then written off the client thread.
 */
@Slf4j
@Singleton
public class ScreenshotService
{
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final int DELAY_TICKS = 2;

	private final DrawManager drawManager;
	private final JourneyStore store;

	@Setter
	private volatile ExecutorService executor;

	/**
	 * Told about each screenshot once it's saved, on the executor.
	 */
	public interface Listener
	{
		void saved(String profileKey, String name, String title);
	}

	@Setter
	private volatile Listener listener;

	private final List<Pending> pending = new ArrayList<>();

	@AllArgsConstructor
	private static class Pending
	{
		String profileKey;
		String name;
		String title;
		int dueTick;
	}

	@Inject
	ScreenshotService(DrawManager drawManager, JourneyStore store)
	{
		this.drawManager = drawManager;
		this.store = store;
	}

	/**
	 * Schedules a capture and returns the file name it will be saved as.
	 */
	public synchronized String request(String profileKey, String title, int currentTick)
	{
		String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
		if (slug.length() > 40)
		{
			slug = slug.substring(0, 40);
		}
		String name = LocalDateTime.now().format(FILE_TIME) + "_" + slug + "_" + (pending.size() + 1) + ".png";
		pending.add(new Pending(profileKey, name, title, currentTick + DELAY_TICKS));
		return name;
	}

	public synchronized void onTick(int tick)
	{
		for (Iterator<Pending> it = pending.iterator(); it.hasNext(); )
		{
			Pending p = it.next();
			if (tick >= p.dueTick)
			{
				it.remove();
				capture(p);
			}
		}
	}

	public synchronized void clear()
	{
		pending.clear();
	}

	private void capture(Pending p)
	{
		drawManager.requestNextFrameListener(image ->
		{
			// The frame buffer is reused by the client, so copy it before handing it to another thread
			BufferedImage copy = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = copy.createGraphics();
			g.drawImage(image, 0, 0, null);
			g.dispose();
			ExecutorService exec = executor;
			if (exec == null || exec.isShutdown())
			{
				return;
			}
			exec.submit(() ->
			{
				try
				{
					store.writeScreenshot(p.profileKey, p.name, copy);
				}
				catch (IOException e)
				{
					log.warn("Unable to save RuneJourney screenshot {}", p.name, e);
					return;
				}
				Listener l = listener;
				if (l != null)
				{
					l.saved(p.profileKey, p.name, p.title);
				}
			});
		});
	}
}
