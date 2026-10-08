package com.runejourney.ui;

import com.runejourney.RuneJourneyPlugin;
import com.runejourney.cloud.MediaIndex;
import com.runejourney.cloud.SyncManager;
import com.runejourney.model.JourneyEvent;
import com.runejourney.service.JourneyService;
import com.runejourney.service.JourneyStore;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.Filepath;

/**
 * Every screenshot RuneJourney has saved for the account: view, save a copy or delete them.
 * All file access happens on the plugin's executor, never the Swing or client thread.
 */
@Slf4j
class ScreenshotWindow extends JFrame
{
	private static final int THUMB_WIDTH = 220;
	private static final int THUMB_HEIGHT = 124;
	private static final int COLUMNS = 4;
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final DateTimeFormatter SHOWN_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

	private enum Sort
	{
		NEWEST("Newest first"), OLDEST("Oldest first"), LARGEST("Largest first");

		private final String label;

		Sort(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	private enum Show
	{
		ALL("All screenshots"), IN_JOURNEY("In my Journey"), NOT_IN_JOURNEY("Not in my Journey");

		private final String label;

		Show(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@Value
	private static class Shot
	{
		String name;
		long size;
		long time;
		String title;
		JourneyEvent event;
		/**
		 * Taken on another PC: only in the cloud, not yet opened here.
		 */
		boolean cloudOnly;
		/**
		 * Whether it's in the cloud, for the badge; null when cloud sync isn't on.
		 */
		MediaIndex.Entry cloud;
	}

	private final JourneyService service;
	private final JourneyStore store;
	private final RuneJourneyPlugin plugin;
	private final SyncManager sync;

	private final JLabel summary = new JLabel();
	private final JComboBox<Sort> sortBox = new JComboBox<>(Sort.values());
	private final JComboBox<Show> showBox = new JComboBox<>(Show.values());
	private final JButton deleteSelected = Ui.button("Delete selected", this::deleteSelected);
	private final JPanel grid = new JPanel(new GridLayout(0, COLUMNS, 10, 10));
	private final Map<String, ImageIcon> thumbnails = new HashMap<>();
	private final Set<String> selected = new LinkedHashSet<>();
	private List<Shot> shots = Collections.emptyList();
	private List<Shot> visible = Collections.emptyList();

	@Inject
	ScreenshotWindow(JourneyService service, JourneyStore store, RuneJourneyPlugin plugin, SyncManager sync)
	{
		this.service = service;
		this.store = store;
		this.plugin = plugin;
		this.sync = sync;

		setTitle("RuneJourney Screenshots" + (service.playerName() != null ? " · " + service.playerName() : ""));
		setIconImage(Icons.navIcon());
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		setMinimumSize(new Dimension(760, 520));
		setSize(new Dimension(1040, 760));

		JPanel root = new JPanel(new BorderLayout());
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.add(toolbar(), BorderLayout.NORTH);

		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		grid.setBorder(new EmptyBorder(14, 14, 14, 14));
		JPanel top = new JPanel(new BorderLayout());
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(grid, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(top, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(24);
		root.add(scroll, BorderLayout.CENTER);
		setContentPane(root);

		sortBox.addActionListener(e -> render());
		showBox.addActionListener(e -> render());
		reload();
		setLocationRelativeTo(null);
	}

	private JPanel toolbar()
	{
		JPanel bar = new JPanel(new BorderLayout(10, 0));
		bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		bar.setBorder(new EmptyBorder(10, 14, 10, 14));

		JPanel left = new JPanel(new GridLayout(0, 1, 0, 2));
		left.setOpaque(false);
		JLabel title = new JLabel("Screenshots");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Ui.GOLD);
		left.add(title);
		summary.setFont(FontManager.getRunescapeSmallFont());
		summary.setForeground(Ui.MUTED);
		left.add(summary);
		bar.add(left, BorderLayout.WEST);

		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		right.setOpaque(false);
		Ui.styled(sortBox);
		Ui.styled(showBox);
		right.add(showBox);
		right.add(sortBox);
		right.add(deleteSelected);
		right.add(Ui.button("Refresh", this::reload));
		bar.add(right, BorderLayout.EAST);
		return bar;
	}

	// ------------------------------------------------------------------
	// Loading
	// ------------------------------------------------------------------

	private void reload()
	{
		String key = service.getProfileKey();
		if (key == null)
		{
			summary.setText("Log in to see your screenshots.");
			return;
		}
		Map<String, JourneyEvent> events = service.screenshotEvents();
		boolean synced = sync.status().isLinked();
		Map<String, MediaIndex.Entry> cloud = sync.media();
		background(() ->
		{
			List<JourneyStore.ScreenshotFile> files = store.listScreenshots(key);
			List<Shot> loaded = new ArrayList<>();
			Set<String> here = new java.util.HashSet<>();
			for (JourneyStore.ScreenshotFile f : files)
			{
				here.add(f.getName());
				JourneyEvent e = events.get(f.getName());
				long time = e != null ? e.getTime() : timeFromName(f.getName(), f.getModified());
				MediaIndex.Entry entry = cloud.get(SyncManager.mediaId(f.getName()));
				loaded.add(new Shot(f.getName(), f.getSize(), time, e != null ? e.getTitle() : titleFromName(f.getName()), e, false,
					synced ? (entry != null ? entry : new MediaIndex.Entry()) : null));
			}
			// Taken on other PCs
			for (MediaIndex.Entry entry : cloud.values())
			{
				if (entry.isInCloud() && entry.getName() != null && JourneyStore.isScreenshotName(entry.getName()) && !here.contains(entry.getName()))
				{
					JourneyEvent e = events.get(entry.getName());
					long time = e != null ? e.getTime() : entry.getTime() > 0 ? entry.getTime() : timeFromName(entry.getName(), 0);
					String title = e != null ? e.getTitle() : entry.getTitle() != null ? entry.getTitle() : titleFromName(entry.getName());
					loaded.add(new Shot(entry.getName(), entry.getCloudBytes(), time, title, e, true, entry));
				}
			}
			SwingUtilities.invokeLater(() ->
			{
				shots = loaded;
				selected.retainAll(loaded.stream().map(Shot::getName).collect(java.util.stream.Collectors.toSet()));
				render();
			});
		}, "Couldn't read your screenshots");
	}

	private void render()
	{
		Show show = (Show) showBox.getSelectedItem();
		List<Shot> list = new ArrayList<>();
		for (Shot s : shots)
		{
			if (show == Show.ALL || (show == Show.IN_JOURNEY) == (s.getEvent() != null))
			{
				list.add(s);
			}
		}
		switch ((Sort) sortBox.getSelectedItem())
		{
			case OLDEST:
				list.sort((a, b) -> Long.compare(a.getTime(), b.getTime()));
				break;
			case LARGEST:
				list.sort((a, b) -> Long.compare(b.getSize(), a.getSize()));
				break;
			default:
				list.sort((a, b) -> Long.compare(b.getTime(), a.getTime()));
		}
		visible = list;

		long bytes = shots.stream().mapToLong(Shot::getSize).sum();
		long unlinked = shots.stream().filter(s -> s.getEvent() == null).count();
		summary.setText(shots.size() + (shots.size() == 1 ? " screenshot" : " screenshots") + " · " + megabytes(bytes)
			+ (unlinked > 0 ? " · " + unlinked + " not in your Journey" : ""));
		updateDeleteButton();

		grid.removeAll();
		for (int i = 0; i < list.size(); i++)
		{
			grid.add(card(list.get(i), i));
		}
		if (list.isEmpty())
		{
			JLabel empty = new JLabel(shots.isEmpty()
				? "No screenshots yet. RuneJourney takes them for big moments: turn them on in the plugin settings."
				: "Nothing matches this filter.");
			empty.setForeground(Ui.MUTED);
			grid.add(empty);
		}
		grid.revalidate();
		grid.repaint();
	}

	private JPanel card(Shot s, int index)
	{
		JPanel card = new JPanel(new BorderLayout(0, 6));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		Color stripe = s.getEvent() != null ? s.getEvent().getType().getColor() : Ui.MUTED;
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(3, 0, 0, 0, stripe), new EmptyBorder(8, 8, 8, 8)));

		JLabel image = new JLabel("Loading...", SwingConstants.CENTER);
		image.setForeground(Ui.MUTED);
		image.setPreferredSize(new Dimension(THUMB_WIDTH, THUMB_HEIGHT));
		image.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		ImageIcon thumb = thumbnails.get(s.getName());
		if (thumb != null)
		{
			image.setText(null);
			image.setIcon(thumb);
		}
		else
		{
			loadThumbnail(s, icon ->
			{
				image.setText(icon == null ? "Can't open" : null);
				image.setIcon(icon);
			});
		}
		card.add(image, BorderLayout.CENTER);

		JPanel text = new JPanel(new GridLayout(0, 1, 0, 1));
		text.setOpaque(false);
		JLabel title = new JLabel(s.getTitle());
		title.setForeground(Color.WHITE);
		title.setToolTipText(s.getTitle());
		text.add(title);
		JLabel meta = new JLabel(shownTime(s.getTime()) + " · " + megabytes(s.getSize()));
		meta.setFont(FontManager.getRunescapeSmallFont());
		meta.setForeground(Ui.MUTED);
		text.add(meta);
		JLabel badge = badge(s);
		if (badge != null)
		{
			text.add(badge);
			badge.setInheritsPopupMenu(true);
		}
		JCheckBox pick = new JCheckBox(s.getEvent() == null ? "Select (not in Journey)" : "Select", selected.contains(s.getName()));
		pick.setOpaque(false);
		pick.setFont(FontManager.getRunescapeSmallFont());
		pick.addActionListener(e ->
		{
			if (pick.isSelected())
			{
				selected.add(s.getName());
			}
			else
			{
				selected.remove(s.getName());
			}
			updateDeleteButton();
		});
		text.add(pick);
		card.add(text, BorderLayout.SOUTH);

		JPopupMenu menu = new JPopupMenu();
		JMenuItem view = new JMenuItem("View");
		view.addActionListener(e -> new Viewer(index).setVisible(true));
		menu.add(view);
		JMenuItem save = new JMenuItem("Save a copy...");
		save.addActionListener(e -> saveCopy(s));
		menu.add(save);
		if (s.getCloud() != null && s.getCloud().isInCloud())
		{
			JMenuItem remove = new JMenuItem(s.isCloudOnly() ? "Remove from cloud" : "Remove from cloud (keep on this PC)");
			remove.addActionListener(e -> removeFromCloud(s));
			menu.add(remove);
		}
		menu.addSeparator();
		JMenuItem delete = new JMenuItem("Delete");
		delete.addActionListener(e -> delete(Collections.singletonList(s.getName()), card));
		menu.add(delete);
		card.setComponentPopupMenu(menu);
		image.setInheritsPopupMenu(true);
		title.setInheritsPopupMenu(true);
		meta.setInheritsPopupMenu(true);
		text.setInheritsPopupMenu(true);

		image.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					new Viewer(index).setVisible(true);
				}
			}
		});
		return card;
	}

	private void updateDeleteButton()
	{
		deleteSelected.setText(selected.isEmpty() ? "Delete selected" : "Delete selected (" + selected.size() + ")");
		deleteSelected.setEnabled(!selected.isEmpty());
	}

	private void loadThumbnail(Shot s, Consumer<ImageIcon> done)
	{
		String key = service.getProfileKey();
		String name = s.getName();
		background(() ->
		{
			BufferedImage full;
			if (s.isCloudOnly())
			{
				byte[] thumb = store.readThumb(key, SyncManager.mediaId(name));
				full = thumb == null ? null : ImageIO.read(new java.io.ByteArrayInputStream(thumb));
			}
			else
			{
				full = store.readScreenshot(key, name);
			}
			ImageIcon icon = full == null ? null : new ImageIcon(scale(full, THUMB_WIDTH, THUMB_HEIGHT));
			SwingUtilities.invokeLater(() ->
			{
				if (icon != null)
				{
					thumbnails.put(name, icon);
				}
				done.accept(icon);
			});
		}, null);
	}

	/**
	 * Where the screenshot is kept, when cloud sync is on for the account.
	 */
	private static JLabel badge(Shot s)
	{
		MediaIndex.Entry cloud = s.getCloud();
		if (cloud == null)
		{
			return null;
		}
		String text;
		Color color = Ui.MUTED;
		if (s.isCloudOnly())
		{
			text = "In the cloud, from another PC";
			color = Ui.GOLD;
		}
		else if (cloud.getState() == MediaIndex.State.WAITING)
		{
			text = "Waiting: cloud full";
			color = Ui.WARN;
		}
		else if (cloud.getState() == MediaIndex.State.QUEUED)
		{
			text = "Waiting to back up";
		}
		else if (cloud.isInCloud())
		{
			text = "In the cloud";
			color = Ui.GOOD;
		}
		else
		{
			text = "Only on this PC";
		}
		JLabel badge = new JLabel(text);
		badge.setFont(FontManager.getRunescapeSmallFont());
		badge.setForeground(color);
		return badge;
	}

	/**
	 * Opens a screenshot: from this PC, or fetched from the cloud if it was taken on another.
	 */
	private void open(Shot s, Consumer<BufferedImage> done)
	{
		String key = service.getProfileKey();
		background(() ->
		{
			BufferedImage image = store.readScreenshot(key, s.getName());
			if (image == null && s.getCloud() != null && s.getCloud().isInCloud())
			{
				sync.openFromCloud(key, s.getName()).whenComplete((cloud, e) -> SwingUtilities.invokeLater(() -> done.accept(cloud)));
				return;
			}
			SwingUtilities.invokeLater(() -> done.accept(image));
		}, null);
	}

	private void removeFromCloud(Shot s)
	{
		String message = s.isCloudOnly()
			? "Remove this screenshot from the cloud? It was taken on another PC, which keeps its own copy."
			: "Remove this screenshot from the cloud? It stays on this PC, and won't be backed up again.";
		if (JOptionPane.showConfirmDialog(this, message, "RuneJourney", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE)
			!= JOptionPane.OK_OPTION)
		{
			return;
		}
		sync.removeFromCloud(service.getProfileKey(), s.getName()).whenComplete((v, e) -> SwingUtilities.invokeLater(() ->
		{
			if (e != null)
			{
				JOptionPane.showMessageDialog(this, "Couldn't remove it from the cloud. Try again in a moment.", "RuneJourney",
					JOptionPane.WARNING_MESSAGE);
			}
			reload();
		}));
	}

	// ------------------------------------------------------------------
	// Actions
	// ------------------------------------------------------------------

	private void deleteSelected()
	{
		delete(new ArrayList<>(selected), this);
	}

	private void delete(List<String> names, java.awt.Component parent)
	{
		if (names.isEmpty())
		{
			return;
		}
		String what = names.size() == 1 ? "this screenshot" : names.size() + " screenshots";
		Set<String> inCloud = new java.util.HashSet<>();
		for (Shot s : shots)
		{
			if (names.contains(s.getName()) && s.getCloud() != null && s.getCloud().isInCloud())
			{
				inCloud.add(s.getName());
			}
		}
		int ok = JOptionPane.showConfirmDialog(parent, "Delete " + what + "? This can't be undone.\n"
				+ (inCloud.isEmpty() ? "" : "It's removed from the cloud too, so your other PCs lose it as well.\n")
				+ "Their Journey entries stay, without the screenshot.",
			"RuneJourney", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (ok != JOptionPane.OK_OPTION)
		{
			return;
		}
		String key = service.getProfileKey();
		background(() ->
		{
			List<String> deleted = new ArrayList<>();
			for (String name : names)
			{
				if (store.deleteScreenshot(key, name) || inCloud.contains(name))
				{
					deleted.add(name);
				}
			}
			for (String name : inCloud)
			{
				sync.removeFromCloud(key, name);
			}
			SwingUtilities.invokeLater(() ->
			{
				for (String name : deleted)
				{
					service.forgetScreenshot(name);
					thumbnails.remove(name);
					selected.remove(name);
				}
				reload();
			});
		}, "Couldn't delete the screenshot");
	}

	private void saveCopy(Shot s)
	{
		List<Filepath> chosen = new Filepath.Chooser()
			.setIsSave()
			.setAcceptsFiles()
			.setDialogTitle("Save a copy of this screenshot")
			.addExtensionFilter("PNG image", "png")
			.setDefaultExtension("png")
			.setFileName(s.getName())
			.showDialog(this);
		if (chosen == null || chosen.isEmpty())
		{
			return;
		}
		Filepath target = chosen.get(0);
		open(s, image ->
		{
			if (image == null)
			{
				JOptionPane.showMessageDialog(this, "Couldn't save the copy: the screenshot is missing.", "RuneJourney",
					JOptionPane.WARNING_MESSAGE);
				return;
			}
			background(() ->
			{
				try (OutputStream out = target.openOutputStream())
				{
					ImageIO.write(image, "png", out);
				}
				SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Saved " + target.getFileName() + ".",
					"RuneJourney", JOptionPane.INFORMATION_MESSAGE));
			}, "Couldn't save the copy");
		});
	}

	private interface IoTask
	{
		void run() throws IOException;
	}

	private void background(IoTask task, String failure)
	{
		ExecutorService executor = plugin.getExecutor();
		if (executor == null || executor.isShutdown())
		{
			return;
		}
		executor.submit(() ->
		{
			try
			{
				task.run();
			}
			catch (IOException | RuntimeException ex)
			{
				log.warn("Screenshot gallery: {}", failure, ex);
				if (failure != null)
				{
					SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, failure + ": " + ex.getMessage(),
						"RuneJourney", JOptionPane.WARNING_MESSAGE));
				}
			}
		});
	}

	// ------------------------------------------------------------------
	// Full-size viewer
	// ------------------------------------------------------------------

	/**
	 * One screenshot at a time, with previous/next (also the arrow keys).
	 */
	private class Viewer extends JDialog
	{
		private final JLabel image = new JLabel("Loading...", SwingConstants.CENTER);
		private final JLabel caption = new JLabel();
		private int index;

		Viewer(int start)
		{
			super(ScreenshotWindow.this, "Screenshot", false);
			this.index = start;
			setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
			JPanel root = new JPanel(new BorderLayout(0, 8));
			root.setBackground(ColorScheme.DARK_GRAY_COLOR);
			root.setBorder(new EmptyBorder(10, 10, 10, 10));
			image.setForeground(Ui.MUTED);
			image.setPreferredSize(new Dimension(1000, 600));
			root.add(image, BorderLayout.CENTER);

			JPanel bottom = new JPanel(new BorderLayout(10, 0));
			bottom.setOpaque(false);
			caption.setForeground(Color.WHITE);
			bottom.add(caption, BorderLayout.CENTER);
			JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
			buttons.setOpaque(false);
			buttons.add(Ui.button("< Previous", () -> step(-1)));
			buttons.add(Ui.button("Next >", () -> step(1)));
			buttons.add(Ui.button("Save a copy...", () -> saveCopy(current())));
			buttons.add(Ui.button("Delete", () ->
			{
				Shot s = current();
				dispose();
				delete(Collections.singletonList(s.getName()), ScreenshotWindow.this);
			}));
			bottom.add(buttons, BorderLayout.EAST);
			root.add(bottom, BorderLayout.SOUTH);
			setContentPane(root);

			root.registerKeyboardAction(e -> step(-1), KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
			root.registerKeyboardAction(e -> step(1), KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
			root.registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

			pack();
			setLocationRelativeTo(ScreenshotWindow.this);
			display(index);
		}

		private Shot current()
		{
			return visible.get(index);
		}

		private void step(int delta)
		{
			if (!visible.isEmpty())
			{
				display(Math.floorMod(index + delta, visible.size()));
			}
		}

		private void display(int i)
		{
			if (visible.isEmpty())
			{
				dispose();
				return;
			}
			index = i;
			Shot s = current();
			setTitle(s.getTitle());
			String note = s.getEvent() != null && s.getEvent().getNote() != null ? " · " + s.getEvent().getNote() : "";
			caption.setText(Ui.wrap(s.getTitle() + " · " + shownTime(s.getTime()) + note + "   (" + (i + 1) + " of " + visible.size() + ")", 560));
			image.setIcon(null);
			image.setText(s.isCloudOnly() ? "Fetching from the cloud..." : "Loading...");
			open(s, full ->
			{
				if (index != i)
				{
					return;
				}
				Image fitted = full == null ? null : scale(full, 1000, 600);
				image.setText(fitted == null ? "This screenshot can't be opened." : null);
				image.setIcon(fitted == null ? null : new ImageIcon(fitted));
			});
		}
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/**
	 * Scales an image to fit within a box, keeping its shape.
	 */
	private static BufferedImage scale(BufferedImage src, int maxWidth, int maxHeight)
	{
		double ratio = Math.min(1, Math.min((double) maxWidth / src.getWidth(), (double) maxHeight / src.getHeight()));
		int w = Math.max(1, (int) Math.round(src.getWidth() * ratio));
		int h = Math.max(1, (int) Math.round(src.getHeight() * ratio));
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(src, 0, 0, w, h, null);
		g.dispose();
		return out;
	}

	private static String megabytes(long bytes)
	{
		return bytes < 1024 * 1024
			? Math.max(1, bytes / 1024) + " KB"
			: String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024d * 1024d));
	}

	private static String shownTime(long millis)
	{
		return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(SHOWN_TIME);
	}

	/**
	 * Screenshots are named "yyyy-MM-dd_HH-mm-ss_title-slug_n.png".
	 */
	static long timeFromName(String name, long fallback)
	{
		if (name.length() >= 19)
		{
			try
			{
				return LocalDateTime.parse(name.substring(0, 19), FILE_TIME).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
			}
			catch (DateTimeParseException ignored)
			{
				// Not one of ours; use the file's own time
			}
		}
		return fallback;
	}

	static String titleFromName(String name)
	{
		String base = name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
		String[] parts = base.split("_");
		String slug = parts.length >= 4 ? String.join("_", java.util.Arrays.copyOfRange(parts, 2, parts.length - 1)) : base;
		String words = slug.replace('-', ' ').trim();
		return words.isEmpty() ? "Screenshot" : Character.toUpperCase(words.charAt(0)) + words.substring(1);
	}
}
