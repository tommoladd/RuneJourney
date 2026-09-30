package com.runejourney.wrapped;

import com.runejourney.RuneJourneyConfig;
import com.runejourney.service.JourneyStore;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import com.runejourney.planner.Skills;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.http.api.item.ItemPrice;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Plays a Wrapped: which slide is showing, timing, and whether it's drawn over the game or in
 * its own window. Input arrives from the Swing thread and drawing happens on the client thread, so
 * state is guarded by this object's lock.
 */
@Slf4j
@Singleton
public class WrappedPlayer
{
	private final Client client;
	private final OverlayManager overlayManager;
	private final MouseManager mouseManager;
	private final KeyManager keyManager;
	private final WrappedOverlay overlay;
	private final RuneJourneyConfig config;
	private final JourneyStore store;
	private final ItemManager itemManager;
	private final SkillIconManager skillIconManager;
	/**
	 * Resolved icons by key. Missing icons are cached as EMPTY so lookups aren't repeated every frame.
	 */
	private final Map<String, BufferedImage> iconCache = new ConcurrentHashMap<>();
	private static final BufferedImage EMPTY = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

	private volatile ExecutorService executor;
	private volatile Supplier<String> profileKey = () -> null;

	private WrappedWeek week;
	private int index;
	private long slideStart;
	private long openedAt;
	private boolean inGame;
	private WrappedWindow window;
	private volatile BufferedImage screenshot;
	private String screenshotName;
	private Point hover;
	private WrappedRenderer.Layout layout;

	@Inject
	WrappedPlayer(Client client, OverlayManager overlayManager, MouseManager mouseManager,
		KeyManager keyManager, WrappedOverlay overlay, RuneJourneyConfig config, JourneyStore store, ItemManager itemManager,
		SkillIconManager skillIconManager)
	{
		this.itemManager = itemManager;
		this.skillIconManager = skillIconManager;
		this.client = client;
		this.overlayManager = overlayManager;
		this.mouseManager = mouseManager;
		this.keyManager = keyManager;
		this.overlay = overlay;
		this.config = config;
		this.store = store;
		overlay.setPlayer(this);
	}

	public void setExecutor(ExecutorService executor, Supplier<String> profileKey)
	{
		this.executor = executor;
		this.profileKey = profileKey;
	}

	/**
	 * Starts playing. Call on the Swing thread.
	 */
	public void play(WrappedWeek w)
	{
		close();
		synchronized (this)
		{
			week = w;
			index = 0;
			openedAt = System.currentTimeMillis();
			slideStart = openedAt;
			inGame = config.wrappedInGame() && client.getGameState() == GameState.LOGGED_IN;
		}
		loadScreenshot();
		// Warm up the icons so they're ready when their slide appears
		for (WrappedWeek.Slide s : w.getSlides())
		{
			icon(s.getIcon());
			s.getLineIcons().forEach(this::icon);
		}

		if (inGame)
		{
			overlayManager.add(overlay);
			mouseManager.registerMouseListener(0, overlay);
			keyManager.registerKeyListener(overlay);
		}
		else
		{
			window = new WrappedWindow(this);
			window.setVisible(true);
		}
	}

	/**
	 * True when playing over the game screen (rather than in a window).
	 */
	public boolean isPlayingInGame()
	{
		return isInGame();
	}

	synchronized boolean isInGame()
	{
		return week != null && inGame;
	}

	/**
	 * The current slide state, advancing automatically when a slide's time is up.
	 */
	synchronized Frame frame()
	{
		if (week == null)
		{
			return null;
		}
		long now = System.currentTimeMillis();
		long elapsed = now - slideStart;
		WrappedWeek.Slide slide = week.getSlides().get(index);
		if (elapsed > WrappedRenderer.durationOf(slide) && index < week.getSlides().size() - 1)
		{
			index++;
			slideStart = now;
			elapsed = 0;
			loadScreenshot();
		}
		BufferedImage img = screenshot;
		String wanted = week.getSlides().get(index).getScreenshot();
		return new Frame(week, index, elapsed, now - openedAt, wanted != null && wanted.equals(screenshotName) ? img : null, hover);
	}

	static final class Frame
	{
		final WrappedWeek week;
		final int index;
		final long slideMs;
		final long totalMs;
		final BufferedImage screenshot;
		final Point hover;

		Frame(WrappedWeek week, int index, long slideMs, long totalMs, BufferedImage screenshot, Point hover)
		{
			this.week = week;
			this.index = index;
			this.slideMs = slideMs;
			this.totalMs = totalMs;
			this.screenshot = screenshot;
			this.hover = hover;
		}
	}

	/**
	 * Game art for an icon key (see {@link WrappedIcons}), or null.
	 */
	BufferedImage icon(String key)
	{
		if (key == null)
		{
			return null;
		}
		BufferedImage img = iconCache.computeIfAbsent(key, this::load);
		return img == EMPTY ? null : img;
	}

	private BufferedImage load(String key)
	{
		try
		{
			if (key.startsWith("skill:"))
			{
				Skill skill = Skills.parse(key.substring("skill:".length()));
				return skill == null ? EMPTY : skillIconManager.getSkillImage(skill, false);
			}
			if (key.startsWith("item:"))
			{
				String[] parts = key.split(":");
				int id = Integer.parseInt(parts[1]);
				int qty = parts.length > 2 ? Integer.parseInt(parts[2]) : 1;
				return itemManager.getImage(id, qty, qty > 1);
			}
			if (key.startsWith("name:"))
			{
				String name = key.substring("name:".length());
				for (ItemPrice price : itemManager.search(name))
				{
					if (price.getName().equalsIgnoreCase(name))
					{
						return itemManager.getImage(price.getId());
					}
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("Unable to load Wrapped icon {}", key, e);
		}
		return EMPTY;
	}

	synchronized void setLayout(WrappedRenderer.Layout layout)
	{
		this.layout = layout;
	}

	synchronized void setHover(Point p)
	{
		hover = p;
	}

	/**
	 * A click on the slides: buttons act, anywhere else moves on.
	 */
	void click(Point p)
	{
		WrappedRenderer.Layout l;
		synchronized (this)
		{
			l = layout;
		}
		if (l != null && l.getBack() != null && l.getBack().contains(p))
		{
			back();
		}
		else if (l != null && l.getClose() != null && l.getClose().contains(p) && l.getClose() != l.getNext())
		{
			close();
		}
		else
		{
			next();
		}
	}

	void next()
	{
		boolean finished;
		synchronized (this)
		{
			if (week == null)
			{
				return;
			}
			finished = index >= week.getSlides().size() - 1;
			if (!finished)
			{
				index++;
				slideStart = System.currentTimeMillis();
			}
		}
		if (finished)
		{
			close();
		}
		else
		{
			loadScreenshot();
		}
	}

	void back()
	{
		synchronized (this)
		{
			if (week == null || index == 0)
			{
				return;
			}
			index--;
			slideStart = System.currentTimeMillis();
		}
		loadScreenshot();
	}

	/**
	 * Stops playing. Safe to call from any thread.
	 */
	public void close()
	{
		boolean wasInGame;
		WrappedWindow w;
		synchronized (this)
		{
			if (week == null && window == null)
			{
				return;
			}
			wasInGame = inGame;
			w = window;
			week = null;
			window = null;
			screenshot = null;
			screenshotName = null;
			layout = null;
			hover = null;
		}
		if (wasInGame)
		{
			overlayManager.remove(overlay);
			mouseManager.unregisterMouseListener(overlay);
			keyManager.unregisterKeyListener(overlay);
		}
		if (w != null)
		{
			SwingUtilities.invokeLater(w::dispose);
		}
	}

	private void loadScreenshot()
	{
		String name;
		synchronized (this)
		{
			if (week == null)
			{
				return;
			}
			name = week.getSlides().get(index).getScreenshot();
			if (name == null || name.equals(screenshotName))
			{
				return;
			}
			screenshotName = name;
			screenshot = null;
		}
		String key = profileKey.get();
		ExecutorService exec = executor;
		if (key == null || exec == null || exec.isShutdown())
		{
			return;
		}
		exec.submit(() ->
		{
			try
			{
				BufferedImage img = store.readScreenshot(key, name);
				synchronized (this)
				{
					if (name.equals(screenshotName))
					{
						screenshot = img;
					}
				}
			}
			catch (IOException e)
			{
				log.debug("Unable to load Wrapped screenshot {}", name, e);
			}
		});
	}
}
