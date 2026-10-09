package com.runejourney.service;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.DrawManager;

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

	private final List<Pending> pending = new ArrayList<>();

	@AllArgsConstructor
	private static class Pending
	{
		String profileKey;
		String name;
		int dueTick;
	}

	@Inject
	ScreenshotService(DrawManager drawManager, JourneyStore store)
	{
		this.drawManager = drawManager;
		this.store = store;
	}

	public synchronized String request(String profileKey, String title, int currentTick)
	{
		String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
		if (slug.length() > 40)
		{
			slug = slug.substring(0, 40);
		}
		String name = LocalDateTime.now().format(FILE_TIME) + "_" + slug + "_" + (pending.size() + 1) + ".png";
		pending.add(new Pending(profileKey, name, currentTick + DELAY_TICKS));
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
				}
			});
		});
	}
}
