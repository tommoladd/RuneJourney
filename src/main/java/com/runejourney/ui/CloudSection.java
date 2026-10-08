package com.runejourney.ui;

import com.runejourney.RuneJourneyConfig;
import com.runejourney.cloud.CloudStatus;
import com.runejourney.cloud.SyncManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Named;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;

/**
 * Cloud sync in the side panel: a status line under the title that opens the cloud card (connect,
 * status, screenshots, disconnect), and questions for the player shown above the tabs.
 */
class CloudSection
{
	private static final String WARNING = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers";

	private final SyncManager sync;
	private final ConfigManager configManager;
	private final boolean developerMode;

	/**
	 * The status line under the panel's title.
	 */
	final JLabel link;
	/**
	 * Questions and warnings, above the tabs.
	 */
	final JPanel prompts = Ui.stack(4);
	/**
	 * The cloud card, shown when the status line is clicked.
	 */
	final JPanel card = Ui.stack(4);

	private CloudStatus shown;
	private long shownAt;
	private boolean open;
	private final JPasswordField keyField = new JPasswordField();
	private final JTextField serverField = new JTextField();

	@Inject
	CloudSection(SyncManager sync, ConfigManager configManager, RuneJourneyConfig config, @Named("developerMode") boolean developerMode)
	{
		this.sync = sync;
		this.configManager = configManager;
		this.developerMode = developerMode;
		this.link = Ui.link("", this::toggle);
		link.setBorder(new EmptyBorder(2, 0, 0, 0));
		prompts.setBorder(new EmptyBorder(0, 0, 6, 0));
		card.setBorder(new EmptyBorder(0, 0, 6, 0));
		card.setVisible(false);
		Ui.styled(keyField);
		Ui.styled(serverField);
		serverField.setText(config.cloudServer());
	}

	private void toggle()
	{
		open = !open;
		shown = null;
		refresh();
	}

	/**
	 * Rebuilds what's changed. Must be called on the Swing thread.
	 */
	void refresh()
	{
		CloudStatus s = sync.status();
		// "Synced 2 min ago" moves on by itself
		if (s.equals(shown) && (s.getLastSync() == 0 || System.currentTimeMillis() - shownAt < 30_000))
		{
			return;
		}
		shown = s;
		shownAt = System.currentTimeMillis();

		link.setText(summary(s));
		link.setToolTipText(open ? "Hide cloud sync" : "Cloud sync: back up your journey and use it on any PC");

		prompts.removeAll();
		addPrompts(s);
		prompts.setVisible(prompts.getComponentCount() > 0);
		prompts.revalidate();
		prompts.repaint();

		card.removeAll();
		if (open)
		{
			card.add(cloudCard(s));
		}
		card.setVisible(card.getComponentCount() > 0);
		card.revalidate();
		card.repaint();
	}

	private static String summary(CloudStatus s)
	{
		switch (s.getConnection())
		{
			case OFF:
				return "Cloud sync: off";
			case NOT_CONNECTED:
				return "Cloud sync: connect";
			case CONNECTING:
				return "Cloud sync: connecting...";
			case KEY_REJECTED:
				return "Cloud sync: paste a new key";
			default:
				break;
		}
		if (s.isUpdateNeeded())
		{
			return "Cloud sync: update needed";
		}
		if (s.isSyncing())
		{
			return "Cloud sync: syncing...";
		}
		if (s.getProblem() != null)
		{
			return "Cloud sync: will retry";
		}
		if (s.isLinked())
		{
			return s.getLastSync() > 0 ? "Cloud sync: synced " + ago(s.getLastSync()) : "Cloud sync: on";
		}
		if (Boolean.FALSE.equals(s.getConsent()))
		{
			return "Cloud sync: not on this PC";
		}
		return "Cloud sync: connected";
	}

	// ------------------------------------------------------------------
	// Questions above the tabs
	// ------------------------------------------------------------------

	private void addPrompts(CloudStatus s)
	{
		String account = s.getAccount() != null ? s.getAccount() : "This account";
		if (s.isReadOnly())
		{
			JPanel card = Ui.accentCard(Ui.WARN);
			card.add(Ui.title("Open in another window"));
			card.add(Ui.text(account + " is open in another RuneLite window, so this window isn't saving it. "
				+ "Log out there, then log in again here."));
			prompts.add(card);
			return;
		}
		if (s.getConnection() == CloudStatus.Connection.KEY_REJECTED)
		{
			JPanel card = Ui.accentCard(Ui.BAD);
			card.add(Ui.title("Cloud sync stopped"));
			card.add(Ui.text("This key no longer works. Your journey is still saved on this PC. Paste a new key to keep syncing."));
			card.add(buttons(Ui.button("Paste a new key", this::openCard)));
			prompts.add(card);
			return;
		}
		if (s.isUpdateNeeded() && s.getConnection() == CloudStatus.Connection.CONNECTED)
		{
			JPanel card = Ui.accentCard(Ui.WARN);
			card.add(Ui.title("Update RuneJourney"));
			card.add(Ui.text("A newer RuneJourney saved this account on another PC. Update RuneJourney to keep syncing."));
			prompts.add(card);
			return;
		}
		switch (s.getPrompt())
		{
			case CONSENT:
			{
				JPanel card = Ui.accentCard(Ui.GOLD);
				card.add(Ui.title("Save " + account + " to the cloud?"));
				card.add(Ui.text(s.isSavedElsewhere()
					? account + " is already saved in " + s.getUserName() + "'s cloud from another PC. Sync it on this PC too?"
					: "Back up " + account + "'s journey to " + s.getUserName() + "'s RuneJourney cloud, so you can use it on any PC."));
				card.add(buttons(Ui.button("Save", () -> sync.consent(true)), Ui.button("Not on this PC", () -> sync.consent(false))));
				prompts.add(card);
				break;
			}
			case CHOOSE:
			{
				JPanel card = Ui.accentCard(Ui.GOLD);
				card.add(Ui.title("Which journey?"));
				card.add(Ui.text(account + " already has a journey in the cloud, and this PC has its own. Use the cloud's "
					+ "(this PC's is backed up first), or combine both?"));
				card.add(Ui.muted("Combining can count XP gained while away twice, if both PCs found it."));
				card.add(buttons(Ui.button("Use the cloud's", () -> sync.choose(true)), Ui.button("Combine both", () -> sync.choose(false))));
				prompts.add(card);
				break;
			}
			case BACKLOG:
			{
				JPanel card = Ui.accentCard(Ui.GOLD);
				card.add(Ui.title("Back up your screenshots?"));
				card.add(Ui.text("You have " + s.getBacklogCount() + (s.getBacklogCount() == 1 ? " screenshot" : " screenshots")
					+ " from before you connected, about " + megabytes(s.getBacklogBytes()) + " in the cloud. Newest go first."));
				card.add(buttons(Ui.button("Back up", () -> sync.backlog(true)), Ui.button("Not now", () -> sync.backlog(false))));
				prompts.add(card);
				break;
			}
			default:
				break;
		}
		if (s.isMediaFull() && s.isScreenshots())
		{
			JPanel card = Ui.accentCard(Ui.WARN);
			card.add(Ui.title("Cloud screenshots full"));
			card.add(Ui.text(megabytes(s.getMediaUsed()) + " used. New screenshots are still saved on this PC, "
				+ "and back up once there's room."));
			card.add(buttons(Ui.button("Manage", this::openWebsite)));
			prompts.add(card);
		}
	}

	private void openCard()
	{
		open = true;
		shown = null;
		refresh();
	}

	// ------------------------------------------------------------------
	// The cloud card
	// ------------------------------------------------------------------

	private JPanel cloudCard(CloudStatus s)
	{
		JPanel card = Ui.section("Cloud sync");
		switch (s.getConnection())
		{
			case OFF:
				card.add(Ui.text("Back up your journey and goals, and keep them in step on every PC you play on. "
					+ "Everything is encrypted before it leaves this PC."));
				card.add(buttons(Ui.button("Turn on cloud sync", this::turnOn)));
				break;
			case CONNECTING:
				card.add(Ui.text("Checking your key..."));
				break;
			case NOT_CONNECTED:
			case KEY_REJECTED:
				connectForm(card);
				break;
			case CONNECTED:
				connected(card, s);
				break;
		}
		if (s.getProblem() != null && s.getConnection() != CloudStatus.Connection.OFF)
		{
			card.add(Ui.label(s.getProblem(), FontManager.getRunescapeSmallFont(), Ui.WARN));
		}
		if (developerMode && s.getConnection() != CloudStatus.Connection.OFF)
		{
			devServer(card);
		}
		return card;
	}

	private void turnOn()
	{
		int ok = JOptionPane.showConfirmDialog(card, WARNING + ".\n\nTurn on cloud sync?", "RuneJourney",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (ok == JOptionPane.OK_OPTION)
		{
			configManager.setConfiguration(RuneJourneyConfig.GROUP, "cloudSync", true);
		}
	}

	private void connectForm(JPanel card)
	{
		card.add(Ui.text("1. Sign in with Discord on the RuneJourney website and make a key for this PC. "
			+ "The key's name (e.g. Desktop) is how this PC shows on the website."));
		card.add(buttons(Ui.button("Get a key", this::openWebsite)));
		card.add(Ui.text("2. Paste the key here:"));
		card.add(keyField);
		card.add(buttons(Ui.button("Connect", () ->
		{
			String key = new String(keyField.getPassword()).trim();
			if (key.isEmpty())
			{
				JOptionPane.showMessageDialog(card, "Paste the key from the website first.", "RuneJourney", JOptionPane.WARNING_MESSAGE);
				return;
			}
			keyField.setText("");
			sync.connect(key);
		})));
	}

	private void connected(JPanel card, CloudStatus s)
	{
		card.add(Ui.stat("Connected as", s.getUserName() != null ? s.getUserName() : "you"));
		if (s.getDeviceName() != null)
		{
			card.add(Ui.stat("This PC", s.getDeviceName()));
		}

		if (s.getAccount() != null)
		{
			if (s.isLinked())
			{
				card.add(Ui.stat(s.getAccount(), s.isSyncing() ? "Syncing..."
					: s.getLastSync() > 0 ? "Synced " + ago(s.getLastSync()) : "Saved to the cloud", Ui.GOOD));
				if (s.getWaiting() > 0)
				{
					card.add(Ui.muted(s.getWaiting() + (s.getWaiting() == 1 ? " change" : " changes") + " waiting to upload."));
				}
				publicPage(card, s);
			}
			else if (Boolean.FALSE.equals(s.getConsent()))
			{
				card.add(Ui.row(Ui.small(s.getAccount() + " isn't saved from this PC.", Ui.MUTED), Ui.link("Save it", () -> sync.consent(true))));
			}
		}

		if (s.isScreenshots() && s.getMediaQuota() > 0)
		{
			card.add(Ui.stat("Screenshots", megabytes(s.getMediaUsed()) + " of " + megabytes(s.getMediaQuota()),
				s.isMediaFull() ? Ui.WARN : Color.WHITE));
			card.add(Ui.progress(s.getMediaUsed() / (double) s.getMediaQuota(), s.isMediaFull() ? Ui.WARN : Ui.GOLD));
			if (s.getMediaWaiting() > 0)
			{
				card.add(Ui.muted(s.getMediaWaiting() + (s.getMediaWaiting() == 1 ? " screenshot" : " screenshots")
					+ (s.isMediaFull() ? " waiting for room." : " waiting to upload.")));
			}
		}

		JPanel row = new JPanel(new GridLayout(1, 2, 4, 0));
		row.setOpaque(false);
		row.add(Ui.button("Sync now", sync::syncNow));
		row.add(Ui.button("Manage", this::openWebsite));
		card.add(row);
		card.add(buttons(Ui.button("Disconnect this PC", this::disconnect)));
		card.add(Ui.muted("Deleting cloud data and keys is done on the website, signed in with Discord."));
		// What sync did, request by request, for working out a problem
		card.add(Ui.link("Open the sync log", () -> LinkBrowser.open(sync.syncLogLocation())));
	}

	/**
	 * The account's public page: its address, and switches to make it public and list it in search.
	 * Which parts of the journey it shows is chosen on the website.
	 */
	private void publicPage(JPanel card, CloudStatus s)
	{
		JLabel heading = Ui.small("Public page", Ui.GOLD);
		heading.setBorder(new EmptyBorder(6, 0, 0, 0));
		card.add(heading);
		if (s.isPublicBlocked())
		{
			card.add(Ui.label("This account's public page was taken down after a report.", FontManager.getRunescapeSmallFont(), Ui.WARN));
			return;
		}
		if (!s.isPublicEnabled())
		{
			card.add(Ui.muted("Share " + s.getAccount() + "'s journey on the RuneJourney website: your character, levels, kills, timeline and goals. "
				+ "It shows your RuneScape name."));
			card.add(buttons(Ui.button("Make public", () -> makePublic(s))));
			return;
		}
		String url = s.getPublicUrl();
		if (url != null)
		{
			JLabel link = Ui.link(shortUrl(url), () -> LinkBrowser.browse(url));
			link.setToolTipText(url);
			card.add(link);
		}
		else
		{
			card.add(Ui.muted("Publishing after the next sync..."));
		}
		if (s.getPublicCharacter() == CloudStatus.Character.WAITING)
		{
			card.add(Ui.muted("Stand still in game for a moment to put your character on the page."));
		}
		else if (s.getPublicCharacter() == CloudStatus.Character.SENDING)
		{
			card.add(Ui.muted("Sending your character..."));
		}
		JCheckBox search = Ui.styled(new JCheckBox("Show in the website's search", s.isPublicSearchable()));
		search.setOpaque(false);
		search.addActionListener(e -> sync.setPublic(null, search.isSelected()));
		card.add(search);
		if (s.getPublicProblem() != null)
		{
			card.add(Ui.label(s.getPublicProblem(), FontManager.getRunescapeSmallFont(), Ui.WARN));
		}
		JPanel row = new JPanel(new GridLayout(1, 2, 4, 0));
		row.setOpaque(false);
		row.add(Ui.button("Choose what it shows", this::openWebsite));
		row.add(Ui.button("Make private", () ->
		{
			int ok = JOptionPane.showConfirmDialog(card, "Make " + s.getAccount() + "'s journey private?\nIts public page is deleted.",
				"RuneJourney", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (ok == JOptionPane.OK_OPTION)
			{
				sync.setPublic(false, null);
			}
		}));
		card.add(row);
	}

	private void makePublic(CloudStatus s)
	{
		int ok = JOptionPane.showConfirmDialog(card, "Make " + s.getAccount() + "'s journey public?\n\n"
				+ "Anyone can see its page on the RuneJourney website, with your RuneScape name, your character\n"
				+ "in 3D, levels, kills, timeline, goals and records. Screenshots, notes, memories and net worth\n"
				+ "stay hidden unless you show them on the website. You can make it private again at any time.",
			"RuneJourney", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (ok == JOptionPane.OK_OPTION)
		{
			sync.setPublic(true, null);
		}
	}

	/**
	 * The address without "https://", to fit the sidebar.
	 */
	private static String shortUrl(String url)
	{
		return url.replaceFirst("^https?://", "");
	}

	private void disconnect()
	{
		int ok = JOptionPane.showConfirmDialog(card, "Disconnect this PC from your RuneJourney cloud?\n"
				+ "Your journey stays on this PC, and what's in the cloud stays there.",
			"RuneJourney", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (ok == JOptionPane.OK_OPTION)
		{
			sync.disconnect();
		}
	}

	private void devServer(JPanel card)
	{
		card.add(Ui.muted("Developer mode: cloud server address (leave blank for " + SyncManager.SERVER + ")"));
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(false);
		row.add(serverField, BorderLayout.CENTER);
		row.add(Ui.button("Use", () -> configManager.setConfiguration(RuneJourneyConfig.GROUP, "cloudServer", serverField.getText().trim())),
			BorderLayout.EAST);
		card.add(row);
	}

	private void openWebsite()
	{
		String server = sync.server();
		if (!server.isEmpty())
		{
			LinkBrowser.browse((server.endsWith("/") ? server : server + "/") + "cloud");
		}
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private static JPanel buttons(JComponent... buttons)
	{
		JPanel row = new JPanel(new GridLayout(1, buttons.length, 4, 0));
		row.setOpaque(false);
		for (JComponent b : buttons)
		{
			row.add(b);
		}
		return row;
	}

	static String megabytes(long bytes)
	{
		return bytes < 1024 * 1024
			? Math.max(1, bytes / 1024) + " KB"
			: String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024d * 1024d));
	}

	static String ago(long millis)
	{
		long minutes = (System.currentTimeMillis() - millis) / 60_000;
		if (minutes < 1)
		{
			return "just now";
		}
		if (minutes < 60)
		{
			return minutes + " min ago";
		}
		long hours = minutes / 60;
		if (hours < 24)
		{
			return hours + (hours == 1 ? " hour ago" : " hours ago");
		}
		long days = hours / 24;
		return days + (days == 1 ? " day ago" : " days ago");
	}
}
