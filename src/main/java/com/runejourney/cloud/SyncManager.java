package com.runejourney.cloud;

import com.google.gson.Gson;
import com.runejourney.RuneJourneyConfig;
import com.runejourney.model.DaySlice;
import com.runejourney.model.ProfileSlice;
import com.runejourney.service.JourneyService;
import com.runejourney.service.JourneyStore;
import com.runejourney.service.PublicAchievements;
import com.runejourney.service.PublicCollectionLog;
import com.runejourney.service.PublicSnapshot;
import com.runejourney.sync.Envelope;
import com.runejourney.sync.Hlc;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.Setter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.hiscore.HiscoreClient;
import net.runelite.client.hiscore.HiscoreResult;

/**
 * Keeps each account's journey in step with the RuneJourney cloud.
 * <ul>
 *   <li>Connecting: the player pastes a key made on the website.</li>
 *   <li>Each account is saved to the cloud only once the player agrees, once per PC.</li>
 *   <li>Each sync pulls other PCs' changes, then pushes this PC's own parts of whatever changed.
 *       Logging in waits (briefly) for the pull, so XP another PC already recorded isn't counted again.</li>
 *   <li>Uploads wait in a persistent outbox until committed, and a commit whose reply was lost is
 *       sent again with the same change ID, so nothing is lost or counted twice.</li>
 * </ul>
 * All state is confined to the plugin's executor; network replies hop back onto it.
 */
@Slf4j
@Singleton
public class SyncManager
{
	/**
	 * The RuneJourney website. Only a developer (RuneLite in developer mode) can point the plugin at
	 * another server, from the side panel.
	 */
	public static final String SERVER = "https://runejourney.org";
	static final String JOURNEY = "journey";
	static final String MEDIA = "media";
	static final String THUMB = "thumb";
	/**
	 * The public page section that shows the character model.
	 */
	static final String CHARACTER = "character";
	/**
	 * Screenshot backup. Off for now: the cloud keeps journeys only, and the website turns
	 * screenshot uploads down. The code stays for when it comes back.
	 */
	static final boolean SCREENSHOTS = false;
	private static final String MEDIA_PREFIX = "media:";
	private static final long HOLD_MILLIS = 8_000;
	private static final long PLAYING_EVERY = 2 * 60_000L;
	private static final long IDLE_EVERY = 5 * 60_000L;
	private static final long MIN_BACKOFF = 60_000L;
	private static final long MAX_BACKOFF = 15 * 60_000L;
	private static final int MAX_FILES = 50;
	private static final int MEDIA_PER_COMMIT = 5;
	/**
	 * Screenshot commits per sync, so a big backlog doesn't hold up the next login's pull.
	 */
	private static final int MEDIA_COMMITS_PER_SYNC = 3;
	/**
	 * While the account is played, its public page is published at most this often.
	 */
	private static final long PUBLISH_EVERY = 10 * 60_000L;
	/**
	 * Under the website's 256 KB request limit.
	 */
	private static final int MAX_PAGE_BYTES = 240_000;
	/**
	 * How often a public account is looked up on the hiscores.
	 */
	private static final long HISCORES_EVERY = 3 * 60 * 60_000L;
	/**
	 * After a failed lookup, how long until the next try.
	 */
	private static final long HISCORES_RETRY = 30 * 60_000L;
	/**
	 * How often "Sync now" can look the account up again.
	 */
	private static final long HISCORES_ASKED_EVERY = 5 * 60_000L;
	/**
	 * The public page section with quests and the collection log.
	 */
	static final String COLLECTION = "collection";
	/**
	 * The server's limit for one screenshot, less room for encryption.
	 */
	private static final int MAX_MEDIA_BYTES = 5 * 1024 * 1024 - 1024;
	private static final int MAX_DOWNLOAD = 6 * 1024 * 1024;
	private static final int MAX_DOCUMENT = 16 * 1024 * 1024;
	/**
	 * Roughly how much smaller the JPEG copy is than the PNG, for estimating a backup's size.
	 */
	private static final double JPEG_RATIO = 0.17;

	private final JourneyService service;
	private final CloudApi api;
	private final CloudFiles files;
	private final RuneJourneyConfig config;
	private final Gson gson;
	private final boolean developerMode;
	private final Hiscores.Lookup hiscores;
	private final SyncLog syncLog;
	private boolean screenshotsOn = SCREENSHOTS;

	/**
	 * Called when the status changes, from any thread.
	 */
	@Setter
	private Runnable onChange;
	/**
	 * Saves the loaded account's files.
	 */
	@Setter
	private Runnable saver;

	private Executor executor;
	private ScheduledFuture<?> timer;

	// Confined to the executor
	private CloudCredentials creds = new CloudCredentials();
	private byte[] dataKey;
	private final Map<String, SyncState> states = new HashMap<>();
	private final Map<String, Hlc> clocks = new HashMap<>();
	private final Map<String, MediaIndex> indexes = new HashMap<>();
	private final Map<String, Map<String, String>> outboxes = new HashMap<>();
	/**
	 * Account to [screenshots not yet offered for backup, their estimated cloud bytes, when counted].
	 */
	private final Map<String, long[]> backlogs = new HashMap<>();
	private final Set<String> resolved = new HashSet<>();
	private final Map<String, Boolean> savedElsewhere = new HashMap<>();
	/**
	 * Account to the character model captured for its public page, until it's sent.
	 */
	private final Map<String, Captured> characters = new HashMap<>();
	/**
	 * Looks the website turned down this session, so they aren't captured again.
	 */
	private final Set<String> refusedLooks = new HashSet<>();
	/**
	 * Collection logs the website turned down this session, so they aren't sent again.
	 */
	private final Set<String> refusedLogs = new HashSet<>();
	/**
	 * Quests and combat tasks the website turned down this session, so they aren't sent again.
	 */
	private final Set<String> refusedAchievements = new HashSet<>();
	private volatile String active;
	private boolean loggedIn;
	private CompletableFuture<Void> running = CompletableFuture.completedFuture(null);
	private boolean syncing;
	private long lastCycle;
	private long backoff;
	private long backoffUntil;
	/**
	 * "Sync now" was pressed: the next sync publishes the public page whenever it last went.
	 */
	private boolean publishNow;
	private boolean connecting;
	private boolean updateNeeded;
	private String problem;
	private Api.Media usage;
	private boolean mediaFull;

	private volatile CloudStatus status = CloudStatus.builder().connection(CloudStatus.Connection.OFF).prompt(CloudStatus.Prompt.NONE).build();
	private volatile Map<String, MediaIndex.Entry> mediaView = Collections.emptyMap();
	private volatile boolean ready;
	private volatile String holdKey;
	private volatile long holdUntil;
	/**
	 * Read on the client thread: the loaded account while its public page shows its character, and
	 * the looks that needn't be captured (on the page, waiting to be sent, or turned down).
	 */
	private volatile String characterFor;
	private volatile Set<String> characterLooks = Collections.emptySet();

	@Inject
	SyncManager(JourneyService service, CloudApi api, JourneyStore files, RuneJourneyConfig config, Gson gson,
		@Named("developerMode") boolean developerMode, HiscoreClient hiscoreClient, SyncLog syncLog)
	{
		this(service, api, (CloudFiles) files, config, gson, developerMode, hiscoreClient::lookupAsync, syncLog);
	}

	/**
	 * For tests: no one is on the hiscores.
	 */
	SyncManager(JourneyService service, CloudApi api, CloudFiles files, RuneJourneyConfig config, Gson gson, boolean developerMode)
	{
		this(service, api, files, config, gson, developerMode, (name, endpoint) -> CompletableFuture.completedFuture(null));
	}

	SyncManager(JourneyService service, CloudApi api, CloudFiles files, RuneJourneyConfig config, Gson gson, boolean developerMode,
		Hiscores.Lookup hiscores)
	{
		this(service, api, files, config, gson, developerMode, hiscores, new SyncLog(files));
	}

	SyncManager(JourneyService service, CloudApi api, CloudFiles files, RuneJourneyConfig config, Gson gson, boolean developerMode,
		Hiscores.Lookup hiscores, SyncLog syncLog)
	{
		this.service = service;
		this.api = api;
		this.files = files;
		this.config = config;
		this.gson = gson;
		this.developerMode = developerMode;
		this.hiscores = hiscores;
		this.syncLog = syncLog;
	}

	/**
	 * Screenshots are backed up: while {@link #SCREENSHOTS} is on, and the player wants them to be.
	 */
	private boolean screenshots()
	{
		return screenshotsOn && config.cloudScreenshots();
	}

	/**
	 * For tests of screenshot backup, while it's off.
	 */
	void enableScreenshots()
	{
		screenshotsOn = true;
	}

	/**
	 * Where the sync log is, to open it from the side panel.
	 */
	public String syncLogLocation()
	{
		return files.syncLogLocation();
	}

	/**
	 * Notes what sync did in the sync log (and RuneLite's debug log), with {} for each argument.
	 */
	private void note(String format, Object... args)
	{
		log.debug("RuneJourney cloud: " + format, args);
		StringBuilder out = new StringBuilder();
		int at = 0;
		int arg = 0;
		for (int i = format.indexOf("{}"); i >= 0 && arg < args.length; i = format.indexOf("{}", at))
		{
			out.append(format, at, i).append(args[arg++]);
			at = i + 2;
		}
		out.append(format.substring(at));
		// Anything left over, such as an exception
		for (; arg < args.length; arg++)
		{
			out.append(": ").append(args[arg]);
		}
		syncLog.write("%s", out);
	}

	// ------------------------------------------------------------------
	// Lifecycle (any thread)
	// ------------------------------------------------------------------

	public void start(ScheduledExecutorService executor)
	{
		this.executor = executor;
		submit(this::loadCredentials);
		timer = executor.scheduleWithFixedDelay(() -> runSafely(this::tick), 30, 30, TimeUnit.SECONDS);
	}

	/**
	 * For tests: runs everything on the given executor, with no timer.
	 */
	void start(Executor executor)
	{
		this.executor = executor;
		submit(this::loadCredentials);
	}

	public void stop()
	{
		if (timer != null)
		{
			timer.cancel(false);
			timer = null;
		}
		api.cancelAll();
		executor = null;
		holdKey = null;
		characterFor = null;
	}

	public CloudStatus status()
	{
		return status;
	}

	/**
	 * Media ID to what's known about the loaded account's screenshots in the cloud.
	 */
	public Map<String, MediaIndex.Entry> media()
	{
		return mediaView;
	}

	/**
	 * Whether logging in should wait before counting XP, because other PCs' records are still being
	 * fetched.
	 */
	public boolean isHolding(String key)
	{
		return key != null && key.equals(holdKey) && System.currentTimeMillis() < holdUntil;
	}

	/**
	 * An account logged in, after its files were loaded (or checked) in this window.
	 */
	public void onLogin(String key)
	{
		if (ready && config.cloudSync())
		{
			holdUntil = System.currentTimeMillis() + HOLD_MILLIS;
			holdKey = key;
		}
		submit(() -> loginSync(key));
	}

	/**
	 * The account logged out: uploads what's left.
	 *
	 * @return completes when done (successfully or not)
	 */
	public CompletableFuture<Void> onLogout(String key)
	{
		CompletableFuture<Void> done = new CompletableFuture<>();
		if (!submit(() ->
		{
			loggedIn = false;
			release(key);
			queue(() -> usable() && isLinked(key) ? syncAccount(key) : CompletableFuture.completedFuture(null))
				.whenComplete((v, e) -> done.complete(null));
		}))
		{
			done.complete(null);
		}
		return done;
	}

	/**
	 * RuneLite is closing: a last, best-effort upload.
	 */
	public Future<?> onExit()
	{
		String key = active;
		if (key == null || executor == null)
		{
			return CompletableFuture.completedFuture(null);
		}
		return onLogout(key).orTimeout(5, TimeUnit.SECONDS).exceptionally(e -> null);
	}

	public void onConfigChanged()
	{
		submit(() ->
		{
			if (usable() && active != null && loggedIn)
			{
				loginSync(active);
			}
			publish();
		});
	}

	/**
	 * Whether to capture the character, on the client thread: the account's public page shows it,
	 * and in a different look.
	 */
	public boolean wantsCharacter(String key, String look)
	{
		return key != null && key.equals(characterFor) && !characterLooks.contains(look);
	}

	/**
	 * The character was captured for the account's public page: it's sent with the next sync.
	 */
	public void setCharacter(String key, String look, CharacterModel model)
	{
		Set<String> looks = new HashSet<>(characterLooks);
		looks.add(look);
		characterLooks = looks;
		submit(() ->
		{
			characters.put(key, new Captured(look, model));
			note("character captured: {} faces", model.faceCount());
			publish();
			if (usable() && isLinked(key) && resolved.contains(key))
			{
				// Sent now, rather than with the next sync
				queue(() -> withLock(key, key.equals(service.getProfileKey()), () -> publishCharacter(key, state(key))));
			}
		});
	}

	/**
	 * A screenshot was saved: queues it to back up.
	 */
	public void onScreenshot(String key, String name, String title)
	{
		submit(() ->
		{
			SyncState st = states.get(key);
			if (!usable() || !screenshots() || st == null || !st.isLinked())
			{
				return;
			}
			MediaIndex index = index(key);
			MediaIndex.Entry e = index.getEntries().computeIfAbsent(mediaId(name), k -> new MediaIndex.Entry());
			e.setName(name);
			e.setTitle(title);
			e.setTime(System.currentTimeMillis());
			if (e.getState() == null)
			{
				e.setState(mediaFull ? MediaIndex.State.WAITING : MediaIndex.State.QUEUED);
			}
			saveIndex(key);
			publish();
		});
	}

	// ------------------------------------------------------------------
	// Actions from the side panel (any thread)
	// ------------------------------------------------------------------

	/**
	 * Connects this PC with a key made on the website. The PC is named after the key.
	 */
	public void connect(String apiKey)
	{
		submit(() -> connectNow(apiKey.trim()));
	}

	public void disconnect()
	{
		submit(() ->
		{
			if (!creds.isConnected())
			{
				return;
			}
			api.disconnect(session()).whenCompleteAsync((v, e) ->
			{
				// Forget the key here even if the server couldn't be reached
				creds.setApiKey(null);
				creds.setDataKey(null);
				creds.setUserUuid(null);
				creds.setUserName(null);
				creds.setRegistered(false);
				creds.setKeyRejected(false);
				dataKey = null;
				resolved.clear();
				problem = null;
				saveCredentials();
				publish();
			}, executor);
		});
	}

	/**
	 * The player's answer to "Save this account to the cloud?".
	 */
	public void consent(boolean save)
	{
		submit(() ->
		{
			String key = active;
			if (key == null)
			{
				return;
			}
			queue(() -> withLock(key, true, () ->
			{
				SyncState st = state(key);
				st.setConsent(save);
				st.setUserUuid(creds.getUserUuid());
				saveState(key);
				publish();
				return save && usable() ? link(key, st) : CompletableFuture.completedFuture(null);
			}));
		});
	}

	/**
	 * The player's choice when this PC and the cloud both have a journey for the account.
	 *
	 * @param useCloud replace this PC's journey (backed up first) with the cloud's, rather than
	 *                 combining both
	 */
	public void choose(boolean useCloud)
	{
		submit(() ->
		{
			String key = active;
			if (key == null)
			{
				return;
			}
			queue(() -> withLock(key, true, () ->
			{
				SyncState st = state(key);
				if (!st.isChoosing())
				{
					return CompletableFuture.completedFuture(null);
				}
				if (!useCloud)
				{
					service.link(key, service.getGeneration(), me(), clock(key), false);
					return startSyncing(key, st, false);
				}
				// Unsaved changes are written first (the executor runs tasks in order), then the backup is taken
				save();
				return CompletableFuture.runAsync(() ->
				{
				}, executor).thenComposeAsync(v ->
				{
					try
					{
						files.backup(key);
					}
					catch (IOException e)
					{
						log.warn("Unable to back up RuneJourney before using the cloud journey", e);
						throw new StopSync("Couldn't back up this PC's journey, so it hasn't been replaced.");
					}
					service.replaceWithCloud(key, service.getGeneration(), me());
					return startSyncing(key, st, true);
				}, executor);
			}));
		});
	}

	private CompletableFuture<Void> startSyncing(String key, SyncState st, boolean fromScratch)
	{
		st.setChoosing(false);
		st.setLinked(true);
		st.setCursor(0);
		st.setRestoring(fromScratch);
		writeOutbox(key, new HashMap<>());
		service.exportAll(key, service.getGeneration());
		saveState(key);
		save();
		publish();
		return syncAccount(key).thenRun(() -> askBacklog(key));
	}

	/**
	 * The player's answer to "Back up your existing screenshots?".
	 */
	public void backlog(boolean backUp)
	{
		submit(() ->
		{
			String key = active;
			if (key == null)
			{
				return;
			}
			MediaIndex index = index(key);
			index.setBacklogAsked(true);
			backlogs.remove(key);
			try
			{
				for (JourneyStore.ScreenshotFile f : files.listScreenshots(key))
				{
					if (f.isCloudCopy())
					{
						continue;
					}
					MediaIndex.Entry e = index.getEntries().computeIfAbsent(mediaId(f.getName()), k -> new MediaIndex.Entry());
					if (e.getState() == null)
					{
						e.setName(f.getName());
						e.setTime(f.getModified());
						e.setState(backUp ? MediaIndex.State.QUEUED : MediaIndex.State.LOCAL);
					}
				}
			}
			catch (IOException e)
			{
				log.warn("Unable to list RuneJourney screenshots", e);
			}
			saveIndex(key);
			if (backUp)
			{
				syncNow();
			}
			publish();
		});
	}

	/**
	 * The side panel was opened. At the login screen that's a good moment to catch up with other PCs.
	 */
	public void onPanelOpened()
	{
		submit(() ->
		{
			if (!loggedIn && System.currentTimeMillis() - lastCycle > 60_000)
			{
				lastCycle = 0;
				tick();
			}
		});
	}

	/**
	 * "Sync now", or a collection log just synced from the game. If a sync is running, another
	 * follows it.
	 */
	public void syncNow()
	{
		submit(() ->
		{
			// The player asked: the public page goes too, however recently it went
			publishNow = true;
			backoffUntil = 0;
			lastCycle = 0;
			if (running.isDone())
			{
				tick();
			}
			else
			{
				running.whenCompleteAsync((v, e) ->
				{
					lastCycle = 0;
					tick();
				}, executor);
			}
		});
	}

	/**
	 * For tests: a sync as the timer runs it.
	 */
	void cycle()
	{
		submit(() ->
		{
			backoffUntil = 0;
			lastCycle = 0;
			tick();
		});
	}

	/**
	 * "Remove from cloud": the screenshot stays on this PC.
	 */
	public CompletableFuture<Void> removeFromCloud(String key, String name)
	{
		return onExecutor(() ->
		{
			SyncState st = state(key);
			String id = mediaId(name);
			if (!usable() || st.getProfileId() == null)
			{
				return CompletableFuture.<Void>completedFuture(null);
			}
			return api.deleteMedia(session(), st.getProfileId(), id).thenRunAsync(() ->
			{
				MediaIndex.Entry e = index(key).getEntries().get(id);
				if (e != null)
				{
					e.setInCloud(false);
					e.setState(MediaIndex.State.LOCAL);
				}
				saveIndex(key);
				mediaFull = false;
				publish();
			}, executor);
		});
	}

	/**
	 * Opens a screenshot that's only in the cloud (taken on another PC), keeping a copy on this PC.
	 */
	public CompletableFuture<BufferedImage> openFromCloud(String key, String name)
	{
		return onExecutor(() ->
		{
			SyncState st = state(key);
			if (!usable() || st.getProfileId() == null)
			{
				throw new CompletionException(new IOException("Cloud sync isn't connected"));
			}
			String id = mediaId(name);
			return api.media(session(), st.getProfileId(), id)
				.thenCompose(found ->
				{
					if (found.getMedia() == null)
					{
						throw new CompletionException(new IOException("That screenshot isn't in the cloud"));
					}
					return api.downloadFile(session(), st.getProfileId(), found.getMedia(), MAX_DOWNLOAD);
				})
				.thenApplyAsync(bytes -> unchecked(() ->
				{
					byte[] plain = CloudCrypto.open(dataKey, creds.getDataKeyId(), bytes,
						CloudCrypto.binding(creds.getUserUuid(), st.getProfileId(), MEDIA, MEDIA_PREFIX + id, null));
					MediaCodec.Decoded decoded = MediaCodec.decode(gson, plain);
					files.writeCloudCopy(key, name, decoded.getJpeg());
					return ImageIO.read(new ByteArrayInputStream(decoded.getJpeg()));
				}), executor);
		});
	}

	// ------------------------------------------------------------------
	// Connecting
	// ------------------------------------------------------------------

	private void loadCredentials()
	{
		try
		{
			CloudCredentials read = files.readCredentials();
			if (read != null)
			{
				creds = read;
				dataKey = creds.getDataKey() != null ? CloudCrypto.decodeKey(creds.getDataKey()) : null;
			}
		}
		catch (IOException | GeneralSecurityException e)
		{
			log.warn("Unable to read RuneJourney cloud credentials", e);
		}
		publish();
	}

	private void connectNow(String apiKey)
	{
		String server = server();
		if (creds.getDeviceId() == null)
		{
			creds.setDeviceId(CloudCrypto.randomHex(16));
		}
		connecting = true;
		problem = null;
		publish();
		Api.Session s = new Api.Session(server, apiKey, creds.getDeviceId());
		attempt(() -> api.me(s)).thenComposeAsync(me -> unchecked(() ->
		{
			byte[] key = CloudCrypto.decodeKey(me.getDataKey().getKey());
			if (creds.getUserUuid() != null && !creds.getUserUuid().equals(me.getUser().getUuid()))
			{
				// A different Discord account: every account here starts again with it
				states.clear();
				resolved.clear();
			}
			creds.setServer(server);
			creds.setApiKey(apiKey);
			creds.setUserUuid(me.getUser().getUuid());
			creds.setUserName(me.getUser().getName() != null ? me.getUser().getName() : me.getUser().getDiscordUsername());
			creds.setDataKeyId(me.getDataKey().getId());
			creds.setDataKey(me.getDataKey().getKey());
			creds.setKeyRejected(false);
			creds.setRegistered(false);
			dataKey = key;
			usage = me.getMedia();
			saveCredentials();
			return ensureDevice();
		}), executor).whenCompleteAsync((v, e) ->
		{
			connecting = false;
			if (e != null)
			{
				Throwable cause = CloudException.cause(e);
				problem = cause instanceof CloudException && ((CloudException) cause).isKeyRejected() && !creds.isConnected()
					? "That key doesn't work. Check you copied all of it, or make a new one."
					: describe(cause);
			}
			else if (active != null && loggedIn)
			{
				loginSync(active);
			}
			publish();
		}, executor);
	}

	private CompletableFuture<Void> ensureDevice()
	{
		if (creds.isRegistered())
		{
			return CompletableFuture.completedFuture(null);
		}
		return attempt(() -> api.registerDevice(session())).thenAcceptAsync(reply ->
		{
			creds.setRegistered(true);
			// Named after the key it connected with
			if (reply.getDevice() != null && reply.getDevice().getName() != null)
			{
				creds.setDeviceName(reply.getDevice().getName());
			}
			saveCredentials();
			publish();
		}, executor);
	}

	// ------------------------------------------------------------------
	// Accounts
	// ------------------------------------------------------------------

	private void loginSync(String key)
	{
		active = key;
		loggedIn = true;
		savedElsewhere.remove(key);
		queue(() -> login(key)).whenComplete((v, e) -> release(key));
		publish();
	}

	/**
	 * Runs in the queue, so nothing else is working on the account.
	 */
	private CompletableFuture<Void> login(String key)
	{
		// Without the account's lock another window has it, and this one doesn't save or sync it
		if (!usable() || !files.isLocked(key))
		{
			return CompletableFuture.completedFuture(null);
		}
		// Another window may have played the account (or become a new device) since this one last
		// looked at its files
		reloadCredentials();
		invalidate(key);
		return withLock(key, true, () ->
		{
			SyncState st = state(key);
			if (Boolean.FALSE.equals(st.getConsent()) || st.isChoosing())
			{
				return CompletableFuture.completedFuture(null);
			}
			if (st.getConsent() == null)
			{
				askConsent(key);
				return CompletableFuture.completedFuture(null);
			}
			if (!st.isLinked())
			{
				return link(key, st);
			}

			int gen = service.getGeneration();
			if (st.getDeviceId() != null && !st.getDeviceId().equals(me()))
			{
				forkAccount(key, st, st.getDeviceId());
			}
			if (service.needsRestore(key))
			{
				// profile.json was lost: this PC's copy comes back from the cloud before anything is uploaded
				service.link(key, gen, me(), Hlc.zero(), false);
				st.setCursor(0);
				st.setRestoring(true);
				saveState(key);
			}
			else if (!service.isLinked(key))
			{
				service.link(key, gen, me(), clock(key), false);
			}
			service.checkUnsynced(key, gen, me());
			return syncAccount(key);
		});
	}

	private void askConsent(String key)
	{
		ensureDevice()
			.thenCompose(v -> attempt(() -> api.resolve(session(), fingerprint(key), false)))
			.handleAsync((r, e) ->
			{
				savedElsewhere.put(key, e == null);
				Throwable cause = e == null ? null : CloudException.cause(e);
				if (cause != null && !(cause instanceof CloudException && ((CloudException) cause).is("profile_not_found")))
				{
					failed(cause);
				}
				publish();
				return null;
			}, executor);
		publish();
	}

	/**
	 * Saves the account to the cloud from this PC, for the first time or again after its cloud data
	 * was deleted. Everything this PC has is uploaded. Runs in the queue, holding the account's lock.
	 */
	private CompletableFuture<Void> link(String key, SyncState st)
	{
		int gen = service.getGeneration();
		return ensureDevice()
			.thenComposeAsync(v -> attempt(() -> api.resolve(session(), fingerprint(key), true)), executor)
			.thenComposeAsync(r ->
			{
				if (!key.equals(service.getProfileKey()) || service.getGeneration() != gen)
				{
					return CompletableFuture.<Void>completedFuture(null);
				}
				st.setProfileId(r.getProfile().getId());
				st.setSeq(r.getSeq());
				st.setCursor(0);
				st.setDeviceId(me());
				st.setUserUuid(creds.getUserUuid());
				st.getSent().clear();
				st.setPending(null);
				st.setRestoring(false);
				st.setPublishedHash(null);
				publicSettings(st, r.getProfile().getPublicSettings());
				writeOutbox(key, new HashMap<>());
				resolved.add(key);
				boolean cloudEmpty = r.isCreated() || r.getProfile().getCursor() == 0;
				if (!cloudEmpty && service.hasHistory())
				{
					st.setChoosing(true);
					saveState(key);
					publish();
					return CompletableFuture.<Void>completedFuture(null);
				}
				service.link(key, gen, me(), clock(key), cloudEmpty);
				service.exportAll(key, gen);
				st.setLinked(true);
				saveState(key);
				save();
				publish();
				return syncAccount(key).thenRun(() -> askBacklog(key));
			}, executor);
	}

	private void askBacklog(String key)
	{
		submit(() ->
		{
			if (screenshots() && !index(key).isBacklogAsked())
			{
				publish();
			}
		});
	}

	/**
	 * This RuneLite folder was copied from another PC that still uses its device ID: carries on as a
	 * new device.
	 */
	private void fork(String key, SyncState st)
	{
		String old = me();
		// Another window on this PC may have become the new device already
		reloadCredentials();
		if (old.equals(me()))
		{
			creds.setDeviceId(CloudCrypto.randomHex(16));
			creds.setRegistered(false);
			resolved.clear();
			saveCredentials();
			log.info("RuneJourney cloud: this RuneLite folder was copied from another PC, so it now syncs as a new device");
			note("This RuneLite folder was copied from another PC, so it now syncs as a new device");
		}
		forkAccount(key, st, old);
		// Sync again soon under the new ID
		lastCycle = 0;
	}

	/**
	 * Picks up a new device ID another RuneLite window on this PC has taken.
	 */
	private void reloadCredentials()
	{
		try
		{
			CloudCredentials read = files.readCredentials();
			if (read != null && read.getDeviceId() != null && !read.getDeviceId().equals(creds.getDeviceId()))
			{
				creds = read;
				dataKey = read.getDataKey() != null ? CloudCrypto.decodeKey(read.getDataKey()) : null;
				resolved.clear();
			}
		}
		catch (IOException | GeneralSecurityException e)
		{
			log.warn("Unable to read RuneJourney cloud credentials", e);
		}
	}

	/**
	 * The cloud copy of an account was deleted (on the website): stops syncing it until the player
	 * says to save it again.
	 */
	private void forget(String key, SyncState st)
	{
		st.setLinked(false);
		st.setConsent(null);
		st.setProfileId(null);
		st.setCursor(0);
		st.getSent().clear();
		st.setPending(null);
		st.setRestoring(false);
		writeOutbox(key, new HashMap<>());
		resolved.remove(key);
		saveState(key);
		if (key.equals(active))
		{
			askConsent(key);
		}
	}

	private static boolean isGone(Throwable cause)
	{
		return cause instanceof CloudException && ((CloudException) cause).is("profile_not_found");
	}

	/**
	 * Forgets what's cached about an account, so its files are read again.
	 */
	private void invalidate(String key)
	{
		states.remove(key);
		clocks.remove(key);
		indexes.remove(key);
		outboxes.remove(key);
		resolved.remove(key);
		backlogs.remove(key);
	}

	/**
	 * Runs work on an account while holding its lock, so no other RuneLite window works on it at the
	 * same time. If this window didn't already hold it, the account's files are read again first;
	 * the loaded account is skipped if another window changed it (it's reloaded at the next login).
	 */
	private CompletableFuture<Void> withLock(String key, boolean loaded, java.util.function.Supplier<CompletableFuture<Void>> work)
	{
		boolean fresh = !files.isLocked(key);
		if (!files.tryLock(key, CloudFiles.SYNC))
		{
			return CompletableFuture.completedFuture(null);
		}
		if (fresh)
		{
			invalidate(key);
			boolean changed;
			try
			{
				changed = loaded && files.changedOnDisk(key);
			}
			catch (IOException e)
			{
				changed = true;
			}
			if (changed)
			{
				files.unlock(key, CloudFiles.SYNC);
				return CompletableFuture.completedFuture(null);
			}
		}
		return attempt(work).whenCompleteAsync((v, e) -> files.unlock(key, CloudFiles.SYNC), executor);
	}

	private void forkAccount(String key, SyncState st, String oldId)
	{
		// The account's parts move to the new ID when it's next loaded, if it isn't now
		if (key.equals(service.getProfileKey()) && service.forkDevice(key, service.getGeneration(), oldId))
		{
			st.setDeviceId(me());
		}
		st.setCursor(0);
		st.setSeq(0);
		st.getSent().clear();
		st.setPending(null);
		resolved.remove(key);
		writeOutbox(key, new HashMap<>());
		saveState(key);
		save();
	}

	// ------------------------------------------------------------------
	// Syncing
	// ------------------------------------------------------------------

	private void tick()
	{
		if (!usable() || !running.isDone() || System.currentTimeMillis() < backoffUntil)
		{
			return;
		}
		long every = loggedIn ? PLAYING_EVERY : IDLE_EVERY;
		if (System.currentTimeMillis() - lastCycle < every)
		{
			return;
		}
		lastCycle = System.currentTimeMillis();
		String key = active;
		boolean playing = loggedIn;
		queue(() ->
		{
			// While logged in, only the window playing the account (holding its lock) syncs it
			CompletableFuture<Void> work = key != null && isLinked(key) && (!playing || files.isLocked(key))
				? syncAccount(key)
				: CompletableFuture.completedFuture(null);
			return work.handleAsync((v, e) ->
			{
				CompletableFuture<Void> others = drainOthers(key);
				return e == null ? others : others.thenRun(() ->
				{
					throw new CompletionException(CloudException.cause(e));
				});
			}, executor).thenCompose(f -> f);
		});
	}

	/**
	 * Runs one piece of work after any already queued, so syncs never overlap.
	 */
	private CompletableFuture<Void> queue(java.util.function.Supplier<CompletableFuture<Void>> work)
	{
		CompletableFuture<Void> next = running.handle((v, e) -> (Void) null).thenComposeAsync(v ->
		{
			syncing = true;
			publish();
			try
			{
				return work.get();
			}
			catch (RuntimeException e)
			{
				CompletableFuture<Void> f = new CompletableFuture<>();
				f.completeExceptionally(e);
				return f;
			}
		}, executor).handleAsync((v, e) ->
		{
			syncing = false;
			if (e != null)
			{
				failed(e);
				if (!(CloudException.cause(e) instanceof StopSync))
				{
					backoff = Math.min(MAX_BACKOFF, Math.max(MIN_BACKOFF, backoff * 2));
					backoffUntil = System.currentTimeMillis() + backoff;
				}
			}
			else
			{
				backoff = 0;
				problem = null;
			}
			publish();
			return (Void) null;
		}, executor);
		running = next;
		return next;
	}

	/**
	 * Pulls other PCs' changes, then pushes this PC's, then backs up screenshots.
	 */
	private CompletableFuture<Void> syncAccount(String key)
	{
		return withLock(key, key.equals(service.getProfileKey()), () ->
		{
			SyncState st = state(key);
			if (st.getProfileId() == null && !st.isLinked())
			{
				return CompletableFuture.completedFuture(null);
			}
			int gen = service.getGeneration();
			note("sync started for {}{}", key, key.equals(service.getProfileKey()) && service.playerName() != null ? " (" + service.playerName() + ")" : "");
			boolean restore = st.isRestoring();
			return refreshUsage()
				.thenComposeAsync(v -> ensureDevice(), executor)
				.thenComposeAsync(v -> ensureProfile(key, st), executor)
				.thenComposeAsync(v -> pull(key, st, gen, restore, mediaCheck(key, st)), executor)
				.thenComposeAsync(v -> push(key, st, gen), executor)
				.thenComposeAsync(v -> uploadMedia(key, st, MEDIA_COMMITS_PER_SYNC), executor)
				.thenComposeAsync(v -> publishPage(key, st, takePublishNow()), executor)
				.thenComposeAsync(v -> publishCharacter(key, st), executor)
				.thenComposeAsync(v -> publishCollectionLog(key, st), executor)
				.thenComposeAsync(v -> publishAchievements(key, st), executor)
				.handleAsync((v, e) ->
				{
					Throwable cause = e == null ? null : CloudException.cause(e);
					if (isGone(cause))
					{
						note("sync for {}: its cloud data was deleted, so it asks again before saving", key);
						forget(key, st);
						throw new StopSync(null);
					}
					if (cause != null)
					{
						throw cause instanceof CompletionException ? (CompletionException) cause : new CompletionException(cause);
					}
					note("sync finished for {}", key);
					return (Void) null;
				}, executor);
		});
	}

	private boolean takePublishNow()
	{
		boolean now = publishNow;
		publishNow = false;
		return now;
	}

	private CompletableFuture<Void> refreshUsage()
	{
		return attempt(() -> api.me(session())).thenAcceptAsync(me ->
		{
			usage = me.getMedia();
			if (mediaFull && usage != null && usage.getUsedBytes() + usage.getReservedBytes() < usage.getQuotaBytes())
			{
				// Room again: screenshots waiting for it can go
				mediaFull = false;
				indexes.forEach((k, index) -> index.getEntries().values().forEach(e ->
				{
					if (e.getState() == MediaIndex.State.WAITING)
					{
						e.setState(MediaIndex.State.QUEUED);
					}
				}));
			}
		}, executor);
	}

	/**
	 * Looks up the account's cloud profile once per session, which also catches a copied folder: the
	 * server has seen more commits from this device ID than this PC made.
	 */
	private CompletableFuture<Void> ensureProfile(String key, SyncState st)
	{
		if (st.getProfileId() != null && resolved.contains(key))
		{
			return CompletableFuture.completedFuture(null);
		}
		boolean create = st.getProfileId() == null;
		return attempt(() -> api.resolve(session(), fingerprint(key), create)).handleAsync((r, e) ->
		{
			if (e != null)
			{
				// A profile_not_found (deleted on the website) is handled by syncAccount
				throw new CompletionException(CloudException.cause(e));
			}
			if (!r.getProfile().getId().equals(st.getProfileId()))
			{
				// Saved again from another PC after being deleted: everything here goes up again
				st.setProfileId(r.getProfile().getId());
				st.setCursor(0);
				st.getSent().clear();
				st.setSeq(r.getSeq());
				writeOutbox(key, new HashMap<>());
				service.exportAll(key, service.getGeneration());
			}
			else if (st.getPending() == null && r.getSeq() > st.getSeq())
			{
				fork(key, st);
				throw new StopSync(null);
			}
			else if (st.getPending() == null)
			{
				st.setSeq(r.getSeq());
			}
			publicSettings(st, r.getProfile().getPublicSettings());
			resolved.add(key);
			saveState(key);
			return null;
		}, executor);
	}

	/**
	 * Notes the account's public page settings from the server. Switching the page on, or showing
	 * more of the journey, means it's published again.
	 */
	private void publicSettings(SyncState st, Api.PublicSettings now)
	{
		if (now == null)
		{
			return;
		}
		Api.PublicSettings was = st.getPublicSettings();
		if (was == null || was.isEnabled() != now.isEnabled() || !was.getSections().equals(now.getSections()))
		{
			st.setPublishedHash(null);
		}
		if (!now.isEnabled())
		{
			st.setPublicProblem(null);
		}
		st.setPublicSettings(now);
	}

	/**
	 * Publishes the account's public page, if it's public and anything on it changed. While the
	 * account is played that's at most every few minutes, as the page changes with every XP drop.
	 * A failure here never stops the sync; it's tried again next time.
	 *
	 * @param force publish now, if anything changed, however recently it was published
	 */
	private CompletableFuture<Void> publishPage(String key, SyncState st, boolean force)
	{
		Api.PublicSettings settings = st.getPublicSettings();
		if (settings == null || !settings.isEnabled() || settings.isBlocked() || st.getProfileId() == null)
		{
			return CompletableFuture.completedFuture(null);
		}
		if (!force && loggedIn && st.getPublishedHash() != null && System.currentTimeMillis() - st.getLastPublished() < PUBLISH_EVERY)
		{
			return CompletableFuture.completedFuture(null);
		}
		PublicSnapshot page = service.publicSnapshot(key, service.getGeneration(), new HashSet<>(settings.getSections()));
		if (page == null)
		{
			return CompletableFuture.completedFuture(null);
		}
		return refreshHiscores(st, page, force).thenComposeAsync(v ->
		{
			Hiscores.Entry ranked = st.getHiscores();
			if (ranked != null && page.getName().equalsIgnoreCase(ranked.getName()))
			{
				Hiscores.merge(page, ranked);
			}
			return sendPage(key, st, settings, page);
		}, executor);
	}

	/**
	 * Looks the account up on the hiscores, if its page shows kills or the collection log and it
	 * hasn't been looked up for a while. A failed lookup never stops the page; it's tried later.
	 */
	private CompletableFuture<Void> refreshHiscores(SyncState st, PublicSnapshot page, boolean asked)
	{
		long now = System.currentTimeMillis();
		Hiscores.Entry known = st.getHiscores();
		// When the player asks for a sync, sooner
		long every = asked ? HISCORES_ASKED_EVERY : HISCORES_EVERY;
		long retry = asked ? HISCORES_ASKED_EVERY : HISCORES_RETRY;
		boolean fresh = known != null && page.getName().equalsIgnoreCase(known.getName()) && now - known.getFetchedAt() < every;
		if ((page.getKills() == null && page.getCollection() == null) || fresh || now - st.getHiscoresTriedAt() < retry)
		{
			return CompletableFuture.completedFuture(null);
		}
		st.setHiscoresTriedAt(now);
		CompletableFuture<HiscoreResult> lookup;
		try
		{
			lookup = hiscores.lookup(page.getName(), Hiscores.endpoint(page.getWorld()));
		}
		catch (RuntimeException e)
		{
			note("hiscores lookup failed", e);
			return CompletableFuture.completedFuture(null);
		}
		return lookup.handleAsync((result, e) ->
		{
			if (e == null)
			{
				// Not on the hiscores at all is a result too
				st.setHiscores(Hiscores.from(page.getName(), result, System.currentTimeMillis()));
				Hiscores.Entry found = st.getHiscores();
				note("hiscores for {}: {} bosses, {} clue tiers, {} minigames", page.getName(), found.getBosses().size(), found.getClues().size(), found.getActivities().size());
			}
			else
			{
				note("hiscores lookup failed", e);
			}
			return (Void) null;
		}, executor);
	}

	private CompletableFuture<Void> sendPage(String key, SyncState st, Api.PublicSettings settings, PublicSnapshot page)
	{
		// Only screenshots in the cloud can be shown; one backed up later is shown from then on
		if (page.getTimeline() != null)
		{
			MediaIndex index = index(key);
			page.getTimeline().forEach(e ->
			{
				MediaIndex.Entry media = e.getScreenshot() == null ? null : index.getEntries().get(e.getScreenshot());
				if (media == null || !media.isInCloud())
				{
					e.setScreenshot(null);
				}
			});
		}
		fit(page);
		// Hashed before the time is set, so only real changes count
		String hash = CloudCrypto.sha256(gson.toJson(page));
		if (hash.equals(st.getPublishedHash()))
		{
			return CompletableFuture.completedFuture(null);
		}
		page.setGeneratedAt(OffsetDateTime.now().withNano(0).toString());
		return attempt(() -> api.publish(session(), st.getProfileId(), page)).handleAsync((reply, e) ->
		{
			st.setLastPublished(System.currentTimeMillis());
			Throwable cause = e == null ? null : CloudException.cause(e);
			if (cause == null)
			{
				st.setPublishedHash(hash);
				st.setPublicProblem(null);
				settings.setUrl(reply.getUrl());
note("public page published ({} bytes)", gson.toJson(page).length());
			}
			else if (cause instanceof CloudException)
			{
				CloudException ce = (CloudException) cause;
				if (ce.isKeyRejected() || isGone(ce))
				{
					throw new CompletionException(ce);
				}
				if (ce.is("not_public"))
				{
					// Switched off on the website meanwhile
					settings.setEnabled(false);
					settings.setUrl(null);
				}
				else if (ce.is("name_taken"))
				{
					// Tried again once anything on the page changes
					st.setPublishedHash(hash);
					st.setPublicProblem("Another RuneJourney player already has a public page for " + page.getName() + ".");
				}
				else if (ce.getStatus() == 422)
				{
					st.setPublishedHash(hash);
					st.setPublicProblem("The website didn't accept the public page. Updating RuneJourney may fix it.");
					note("public page refused: {}", ce.getMessage());
				}
			}
			saveState(key);
			publish();
			return (Void) null;
		}, executor);
	}

	private static boolean showsCharacter(Api.PublicSettings settings)
	{
		return settings != null && settings.isEnabled() && !settings.isBlocked() && settings.getSections().contains(CHARACTER);
	}

	/**
	 * Sends the character captured for the account's public page, if the page doesn't already show
	 * that look. A failure here never stops the sync either.
	 */
	private CompletableFuture<Void> publishCharacter(String key, SyncState st)
	{
		Captured captured = characters.get(key);
		Api.PublicSettings settings = st.getPublicSettings();
		if (captured == null || !showsCharacter(settings) || st.getPublicProblem() != null || st.getProfileId() == null)
		{
			return CompletableFuture.completedFuture(null);
		}
		if (captured.getLook().equals(settings.getCharacter()))
		{
			characters.remove(key);
			return CompletableFuture.completedFuture(null);
		}
		byte[] body = captured.getModel().encode();
		if (body.length > MAX_PAGE_BYTES)
		{
			// Too big for the website: shown in the next outfit instead
			refusedLooks.add(captured.getLook());
			characters.remove(key);
			publish();
			return CompletableFuture.completedFuture(null);
		}
		return attempt(() -> api.publishCharacter(session(), st.getProfileId(), captured.getLook(), body)).handleAsync((v, e) ->
		{
			Throwable cause = e == null ? null : CloudException.cause(e);
			if (cause == null)
			{
				settings.setCharacter(captured.getLook());
				characters.remove(key, captured);
				note("character published");
			}
			else if (cause instanceof CloudException)
			{
				CloudException ce = (CloudException) cause;
				if (ce.isKeyRejected() || isGone(ce))
				{
					throw new CompletionException(ce);
				}
				if (ce.is("not_public"))
				{
					settings.setEnabled(false);
					settings.setUrl(null);
				}
				else if (ce.getStatus() == 422)
				{
					refusedLooks.add(captured.getLook());
					characters.remove(key, captured);
					note("character refused: {}", ce.getMessage());
				}
				else
				{
					// The page isn't published yet, say: tried again next sync
					note("character not sent: {}", ce.getMessage());
				}
			}
			else
			{
				note("character not sent", cause);
			}
			saveState(key);
			publish();
			return (Void) null;
		}, executor);
	}

	/**
	 * Sends the account's whole collection log for its public page, once it's been synced from the
	 * game, if the page doesn't already show this version of it.
	 */
	private CompletableFuture<Void> publishCollectionLog(String key, SyncState st)
	{
		Api.PublicSettings settings = st.getPublicSettings();
		if (settings == null || !settings.isEnabled() || settings.isBlocked() || !settings.getSections().contains(COLLECTION)
			|| st.getPublicProblem() != null || st.getProfileId() == null)
		{
			return CompletableFuture.completedFuture(null);
		}
		PublicCollectionLog clog = service.publicCollectionLog(key, service.getGeneration());
		if (clog == null)
		{
			return CompletableFuture.completedFuture(null);
		}
		String json = gson.toJson(clog);
		String hash = CloudCrypto.sha256(json);
		if (hash.equals(settings.getCollectionLog()) || refusedLogs.contains(hash))
		{
			return CompletableFuture.completedFuture(null);
		}
		if (json.length() > MAX_PAGE_BYTES)
		{
			refusedLogs.add(hash);
			note("collection log too big to publish: {} bytes", json.length());
			return CompletableFuture.completedFuture(null);
		}
		return attempt(() -> api.publishCollectionLog(session(), st.getProfileId(), hash, clog)).handleAsync((v, e) ->
		{
			Throwable cause = e == null ? null : CloudException.cause(e);
			if (cause == null)
			{
				settings.setCollectionLog(hash);
note("collection log published: {} tabs", clog.getTabs().size());
			}
			else if (cause instanceof CloudException)
			{
				CloudException ce = (CloudException) cause;
				if (ce.isKeyRejected() || isGone(ce))
				{
					throw new CompletionException(ce);
				}
				if (ce.is("not_public"))
				{
					settings.setEnabled(false);
					settings.setUrl(null);
				}
				else if (ce.getStatus() == 422)
				{
					refusedLogs.add(hash);
					note("collection log refused: {}", ce.getMessage());
				}
				else
				{
					note("collection log not sent: {}", ce.getMessage());
				}
			}
			else
			{
				note("collection log not sent", cause);
			}
			saveState(key);
			publish();
			return (Void) null;
		}, executor);
	}

	/**
	 * Sends the account's quests and combat tasks for its public page, once they've been read from
	 * the game, if the page doesn't already show them as they are. They go with the collection log.
	 */
	private CompletableFuture<Void> publishAchievements(String key, SyncState st)
	{
		Api.PublicSettings settings = st.getPublicSettings();
		if (settings == null || !settings.isEnabled() || settings.isBlocked() || !settings.getSections().contains(COLLECTION)
			|| st.getPublicProblem() != null || st.getProfileId() == null)
		{
			return CompletableFuture.completedFuture(null);
		}
		PublicAchievements achievements = service.publicAchievements(key, service.getGeneration());
		if (achievements == null)
		{
			return CompletableFuture.completedFuture(null);
		}
		// Not when they were read, which is new every login
		String hash = CloudCrypto.sha256(gson.toJson(achievements.getQuests()) + gson.toJson(achievements.getCombatTasks()));
		if (hash.equals(settings.getAchievements()) || refusedAchievements.contains(hash))
		{
			return CompletableFuture.completedFuture(null);
		}
		int size = gson.toJson(achievements).length();
		if (size > MAX_PAGE_BYTES)
		{
			refusedAchievements.add(hash);
			note("quests and combat tasks too big to publish: {} bytes", size);
			return CompletableFuture.completedFuture(null);
		}
		return attempt(() -> api.publishAchievements(session(), st.getProfileId(), hash, achievements)).handleAsync((v, e) ->
		{
			Throwable cause = e == null ? null : CloudException.cause(e);
			if (cause == null)
			{
				settings.setAchievements(hash);
note("quests and combat tasks published: {} quests, {} tasks", achievements.getQuests().size(), achievements.getCombatTasks().size());
			}
			else if (cause instanceof CloudException)
			{
				CloudException ce = (CloudException) cause;
				if (ce.isKeyRejected() || isGone(ce))
				{
					throw new CompletionException(ce);
				}
				if (ce.is("not_public"))
				{
					settings.setEnabled(false);
					settings.setUrl(null);
				}
				else if (ce.getStatus() == 422)
				{
					refusedAchievements.add(hash);
					note("quests and combat tasks refused: {}", ce.getMessage());
				}
				else
				{
					note("quests and combat tasks not sent: {}", ce.getMessage());
				}
			}
			else
			{
				note("quests and combat tasks not sent", cause);
			}
			saveState(key);
			publish();
			return (Void) null;
		}, executor);
	}

	/**
	 * Keeps the page within the website's request limit by showing fewer timeline events.
	 */
	private void fit(PublicSnapshot page)
	{
		while (page.getTimeline() != null && !page.getTimeline().isEmpty()
			&& gson.toJson(page).length() > MAX_PAGE_BYTES)
		{
			List<PublicSnapshot.Event> timeline = page.getTimeline();
			page.setTimeline(new ArrayList<>(timeline.subList(0, timeline.size() * 4 / 5)));
		}
	}

	/**
	 * Makes the account's public page public or private, or changes whether it's in the website's
	 * search. Null leaves a setting as it is. Which parts of the journey it shows is chosen on the
	 * website.
	 */
	public void setPublic(Boolean enabled, Boolean searchable)
	{
		submit(() ->
		{
			String key = active;
			if (key == null)
			{
				return;
			}
			queue(() -> withLock(key, true, () ->
			{
				SyncState st = state(key);
				if (!usable() || st.getProfileId() == null || !isLinked(key))
				{
					return CompletableFuture.completedFuture(null);
				}
				return attempt(() -> api.updatePublic(session(), st.getProfileId(), enabled, searchable)).thenComposeAsync(reply ->
				{
					publicSettings(st, reply.getPublicSettings());
					saveState(key);
					publish();
					return publishPage(key, st, true);
				}, executor);
			}));
		});
	}

	@Value
	private static class Captured
	{
		String look;
		CharacterModel model;
	}

	@Value
	private static class Fetched
	{
		Api.Change change;
		Envelope.Doc doc;
	}

	/**
	 * Before a pull: if the account's screenshots were last checked against a different cloud
	 * profile, the pull starts from the beginning and notes which screenshots the cloud has, so the
	 * rest can go up again.
	 *
	 * @return the set to note them in, or null if there's no need
	 */
	private Set<String> mediaCheck(String key, SyncState st)
	{
		if (st.getProfileId() == null || st.getProfileId().equals(index(key).getProfileId()))
		{
			return null;
		}
		st.setCursor(0);
		return new HashSet<>();
	}

	/**
	 * After a pull from the beginning: screenshots this PC thought were in the cloud, but aren't,
	 * go up again if they're on this PC, and are forgotten if they aren't.
	 */
	private void reconcileMedia(String key, SyncState st, Set<String> inCloud)
	{
		Set<String> onPc = new HashSet<>();
		try
		{
			for (JourneyStore.ScreenshotFile f : files.listScreenshots(key))
			{
				onPc.add(f.getName());
			}
		}
		catch (IOException e)
		{
			// Checked again next sync
			log.debug("Unable to list screenshots", e);
			return;
		}
		MediaIndex index = index(key);
		int requeued = 0;
		for (Iterator<Map.Entry<String, MediaIndex.Entry>> it = index.getEntries().entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<String, MediaIndex.Entry> entry = it.next();
			MediaIndex.Entry e = entry.getValue();
			if (inCloud.contains(entry.getKey()) || (!e.isInCloud() && e.getState() != MediaIndex.State.UPLOADED))
			{
				continue;
			}
			e.setInCloud(false);
			e.setCloudBytes(0);
			if (e.getName() != null && onPc.contains(e.getName()))
			{
				e.setState(mediaFull ? MediaIndex.State.WAITING : MediaIndex.State.QUEUED);
				requeued++;
			}
			else
			{
				deleteCloudCopy(key, entry.getKey(), e);
				it.remove();
			}
		}
		index.setProfileId(st.getProfileId());
		if (requeued > 0)
		{
			note("{} screenshots weren't in the cloud, so go up again", requeued);
		}
	}

	private CompletableFuture<Void> pull(String key, SyncState st, int gen, boolean restore, Set<String> inCloud)
	{
		return attempt(() -> api.changes(session(), st.getProfileId(), st.getCursor())).thenComposeAsync(page ->
		{
			List<CompletableFuture<Fetched>> docs = new ArrayList<>();
			List<CompletableFuture<Void>> thumbs = new ArrayList<>();
			for (Api.Change c : page.getChanges())
			{
				if (JOURNEY.equals(c.getKind()))
				{
					if (c.isDeleted() || (!restore && me().equals(c.getDeviceId())))
					{
						continue;
					}
					if (!Envelope.readable(c.getSchema()))
					{
						updateNeeded = true;
						throw new StopSync("A newer RuneJourney saved this account. Update RuneJourney to keep syncing.");
					}
					docs.add(api.downloadFile(session(), st.getProfileId(), c.getId(), MAX_DOWNLOAD)
						.thenApplyAsync(bytes -> new Fetched(c, openDoc(st, c, bytes)), executor));
				}
				else
				{
					if (inCloud != null && !c.isDeleted() && c.getDocKey() != null && c.getDocKey().startsWith(MEDIA_PREFIX))
					{
						inCloud.add(c.getDocKey().substring(MEDIA_PREFIX.length()));
					}
					CompletableFuture<Void> thumb = mediaChange(key, st, c);
					if (thumb != null)
					{
						thumbs.add(thumb);
					}
				}
			}
			List<CompletableFuture<?>> all = new ArrayList<>(docs);
			all.addAll(thumbs);
			return CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).thenComposeAsync(v ->
			{
				Map<String, ProfileSlice> profiles = new HashMap<>();
				Map<String, Map<String, DaySlice>> days = new HashMap<>();
				for (CompletableFuture<Fetched> f : docs)
				{
					Fetched fetched = f.join();
					String device = fetched.getChange().getDeviceId();
					if (fetched.getDoc().getProfile() != null)
					{
						profiles.put(device, fetched.getDoc().getProfile());
					}
					else if (fetched.getDoc().getDay() != null && Envelope.dateOf(fetched.getChange().getDocKey()) != null)
					{
						days.computeIfAbsent(Envelope.dateOf(fetched.getChange().getDocKey()), k -> new HashMap<>()).put(device, fetched.getDoc().getDay());
					}
				}
				if ((!profiles.isEmpty() || !days.isEmpty())
					&& !service.applyRemote(key, gen, me(), clock(key), profiles, days, restore))
				{
					// A different account was loaded meanwhile; this one carries on next time
					throw new StopSync(null);
				}
				st.setCursor(page.getCursor());
				if (!page.isMore() && inCloud != null)
				{
					reconcileMedia(key, st, inCloud);
				}
				if (page.getProfile() != null)
				{
					publicSettings(st, page.getProfile().getPublicSettings());
				}
				if (!page.isMore() && restore)
				{
					// This PC's own copy is back: uploads can start again
					st.setRestoring(false);
				}
				saveState(key);
				saveIndex(key);
				if (!profiles.isEmpty() || !days.isEmpty())
				{
					save();
				}
				return page.isMore() ? pull(key, st, gen, restore, inCloud) : CompletableFuture.completedFuture(null);
			}, executor);
		}, executor);
	}

	private Envelope.Doc openDoc(SyncState st, Api.Change c, byte[] file)
	{
		return unchecked(() ->
		{
			if (c.getSha256() != null && !c.getSha256().equalsIgnoreCase(CloudCrypto.sha256(file)))
			{
				throw new IOException("Downloaded document doesn't match its checksum");
			}
			byte[] zipped = CloudCrypto.open(dataKey, creds.getDataKeyId(), file,
				CloudCrypto.binding(creds.getUserUuid(), st.getProfileId(), JOURNEY, c.getDocKey(), c.getDeviceId()));
			String json = new String(CloudCrypto.gunzip(zipped, MAX_DOCUMENT), StandardCharsets.UTF_8);
			Envelope.Doc doc = gson.fromJson(json, Envelope.Doc.class);
			if (doc == null || !Envelope.readable(doc.getSchema()))
			{
				updateNeeded = true;
				throw new StopSync("A newer RuneJourney saved this account. Update RuneJourney to keep syncing.");
			}
			return doc;
		});
	}

	private CompletableFuture<Void> push(String key, SyncState st, int gen)
	{
		List<JourneyService.SyncDoc> docs = service.exportOwn(key, gen, me(), clock(key));
		if (!docs.isEmpty())
		{
			Map<String, String> outbox = readOutbox(key);
			for (JourneyService.SyncDoc doc : docs)
			{
				if (CloudCrypto.sha256(doc.getJson()).equals(st.getSent().get(doc.getDocKey())))
				{
					outbox.remove(doc.getDocKey());
				}
				else
				{
					outbox.put(doc.getDocKey(), doc.getJson());
				}
			}
			writeOutbox(key, outbox);
			// This PC's parts changed: save them with the days
			save();
		}
		saveState(key);
		return drain(key, st);
	}

	/**
	 * Uploads everything in the account's outbox.
	 */
	private CompletableFuture<Void> drain(String key, SyncState st)
	{
		if (st.getPending() != null)
		{
			return commit(key, st).thenComposeAsync(v -> drain(key, st), executor);
		}
		Map<String, String> outbox = readOutbox(key);
		if (outbox.entrySet().removeIf(e -> CloudCrypto.sha256(e.getValue()).equals(st.getSent().get(e.getKey()))))
		{
			writeOutbox(key, outbox);
		}
		if (outbox.isEmpty())
		{
			return CompletableFuture.completedFuture(null);
		}
		List<String> batch = outbox.keySet().stream()
			.sorted(Comparator.comparing((String k) -> !Envelope.PROFILE.equals(k)).thenComparing(k -> k))
			.limit(MAX_FILES)
			.collect(Collectors.toList());
		List<Prepared> prepared = new ArrayList<>();
		for (String docKey : batch)
		{
			String json = outbox.get(docKey);
			byte[] sealed = unchecked(() -> CloudCrypto.seal(dataKey, creds.getDataKeyId(),
				CloudCrypto.gzip(json.getBytes(StandardCharsets.UTF_8)),
				CloudCrypto.binding(creds.getUserUuid(), st.getProfileId(), JOURNEY, docKey, me())));
			prepared.add(new Prepared(JOURNEY, docKey, sealed, CloudCrypto.sha256(json), null));
		}
		return upload(key, st, prepared).thenComposeAsync(v -> drain(key, st), executor);
	}

	@Value
	private static class Prepared
	{
		String kind;
		String docKey;
		byte[] body;
		/**
		 * For a journey document, the hash of its JSON.
		 */
		String docHash;
		/**
		 * For a screenshot or thumbnail, its media ID.
		 */
		String mediaId;
	}

	/**
	 * Asks for signed URLs, sends each file straight to the storage, then commits them.
	 */
	private CompletableFuture<Void> upload(String key, SyncState st, List<Prepared> prepared)
	{
		List<Api.FileSpec> specs = new ArrayList<>();
		for (Prepared p : prepared)
		{
			specs.add(new Api.FileSpec(p.getKind(), p.getDocKey(), p.getBody().length, CloudCrypto.sha256(p.getBody()),
				JOURNEY.equals(p.getKind()) ? Envelope.SCHEMA : null));
		}
		return attempt(() -> api.uploads(session(), st.getProfileId(), specs)).thenComposeAsync(reply ->
		{
			Map<String, Api.Upload> byFile = new HashMap<>();
			reply.getUploads().forEach(u -> byFile.put(u.getKind() + " " + u.getDocKey(), u));
			List<CompletableFuture<Void>> puts = new ArrayList<>();
			for (Prepared p : prepared)
			{
				Api.Upload u = byFile.get(p.getKind() + " " + p.getDocKey());
				if (u == null)
				{
					throw new CompletionException(new IOException("The cloud didn't return an upload for " + p.getDocKey()));
				}
				puts.add(api.uploadFile(session(), st.getProfileId(), u.getUploadId(), p.getBody()));
			}
			return CompletableFuture.allOf(puts.toArray(new CompletableFuture[0])).thenApply(v -> reply);
		}, executor).thenComposeAsync(reply ->
		{
			SyncState.PendingCommit pc = new SyncState.PendingCommit();
			pc.setChangeId(CloudCrypto.randomHex(16));
			pc.setSeq(st.getSeq());
			pc.setSentAt(System.currentTimeMillis());
			reply.getUploads().forEach(u -> pc.getUploadIds().add(u.getUploadId()));
			for (Prepared p : prepared)
			{
				if (p.getDocHash() != null)
				{
					pc.getDocs().put(p.getDocKey(), p.getDocHash());
				}
				if (p.getMediaId() != null && MEDIA.equals(p.getKind()))
				{
					pc.getMedia().add(p.getMediaId());
				}
			}
			st.setPending(pc);
			saveState(key);
			return commit(key, st);
		}, executor);
	}

	private CompletableFuture<Void> commit(String key, SyncState st)
	{
		SyncState.PendingCommit pc = st.getPending();
		return attempt(() -> api.commit(session(), st.getProfileId(), pc.getChangeId(), pc.getSeq(), pc.getUploadIds()))
			.handleAsync((result, e) ->
			{
				if (e == null)
				{
					committed(key, st, pc, result);
					return null;
				}
				Throwable cause = CloudException.cause(e);
				if (cause instanceof CloudException)
				{
					CloudException ce = (CloudException) cause;
					if (ce.is("sequence_mismatch"))
					{
						st.setPending(null);
						fork(key, st);
						throw new StopSync(null);
					}
					if (ce.getStatus() == 410 || ce.getStatus() == 422 || ce.is("change_id_reused"))
					{
						// Never applied: the files go again next time
						st.setPending(null);
						saveState(key);
					}
				}
				throw new CompletionException(cause);
			}, executor);
	}

	private void committed(String key, SyncState st, SyncState.PendingCommit pc, Api.Committed result)
	{
		st.setSeq(result.getSeq());
		st.setPending(null);
		st.setLastSync(System.currentTimeMillis());
		Map<String, String> outbox = readOutbox(key);
		pc.getDocs().forEach((docKey, hash) ->
		{
			st.getSent().put(docKey, hash);
			String waiting = outbox.get(docKey);
			if (waiting != null && CloudCrypto.sha256(waiting).equals(hash))
			{
				outbox.remove(docKey);
			}
		});
		writeOutbox(key, outbox);
		if (!pc.getMedia().isEmpty())
		{
			MediaIndex index = index(key);
			for (String id : pc.getMedia())
			{
				MediaIndex.Entry e = index.getEntries().get(id);
				if (e != null)
				{
					e.setState(MediaIndex.State.UPLOADED);
					e.setInCloud(true);
				}
			}
			saveIndex(key);
		}
		saveState(key);
		publish();
	}

	/**
	 * Uploads waiting from accounts that aren't loaded, e.g. one played earlier on this PC.
	 */
	private CompletableFuture<Void> drainOthers(String except)
	{
		List<String> keys;
		try
		{
			keys = files.syncedProfiles();
		}
		catch (IOException e)
		{
			return CompletableFuture.completedFuture(null);
		}
		CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
		for (String key : keys)
		{
			if (key.equals(except))
			{
				continue;
			}
			chain = chain.thenComposeAsync(v -> withLock(key, false, () ->
			{
				SyncState st = state(key);
				if (!st.isLinked() || st.isRestoring() || st.getProfileId() == null || !me().equals(st.getDeviceId())
					|| (readOutbox(key).isEmpty() && st.getPending() == null))
				{
					return CompletableFuture.<Void>completedFuture(null);
				}
				return drain(key, st).handleAsync((x, e) ->
				{
					Throwable cause = e == null ? null : CloudException.cause(e);
					if (isGone(cause))
					{
						forget(key, st);
					}
					else if (cause != null)
					{
						note("uploads for another account will be retried", cause);
					}
					return (Void) null;
				}, executor);
			}), executor);
		}
		return chain;
	}

	// ------------------------------------------------------------------
	// Screenshots
	// ------------------------------------------------------------------

	public static String mediaId(String name)
	{
		return CloudCrypto.sha256("media:" + name).substring(0, 32);
	}

	/**
	 * A screenshot changed in the cloud. Thumbnails of ones taken on other PCs are fetched for the
	 * gallery.
	 *
	 * @return the thumbnail download, or null if there's nothing to fetch
	 */
	private CompletableFuture<Void> mediaChange(String key, SyncState st, Api.Change c)
	{
		if (c.getDocKey() == null || !c.getDocKey().startsWith(MEDIA_PREFIX))
		{
			return null;
		}
		String id = c.getDocKey().substring(MEDIA_PREFIX.length());
		MediaIndex index = index(key);
		MediaIndex.Entry e = index.getEntries().get(id);
		if (c.isDeleted())
		{
			if (e != null)
			{
				e.setInCloud(false);
				if (e.getState() == MediaIndex.State.UPLOADED)
				{
					e.setState(MediaIndex.State.LOCAL);
				}
				deleteCloudCopy(key, id, e);
			}
			return null;
		}
		if (e == null)
		{
			e = new MediaIndex.Entry();
			e.setState(MediaIndex.State.UPLOADED);
			index.getEntries().put(id, e);
		}
		e.setInCloud(true);
		if (MEDIA.equals(c.getKind()))
		{
			e.setCloudBytes(c.getSize());
		}
		// Thumbnails are only needed for screenshots taken on other PCs
		if (!THUMB.equals(c.getKind()) || me().equals(c.getDeviceId())
			|| (e.getName() != null && hasThumb(key, id)))
		{
			return null;
		}
		MediaIndex.Entry entry = e;
		return api.downloadFile(session(), st.getProfileId(), c.getId(), MAX_DOWNLOAD).thenAcceptAsync(bytes ->
		{
			try
			{
				byte[] plain = CloudCrypto.open(dataKey, creds.getDataKeyId(), bytes,
					CloudCrypto.binding(creds.getUserUuid(), st.getProfileId(), THUMB, c.getDocKey(), null));
				MediaCodec.Decoded thumb = MediaCodec.decode(gson, plain);
				MediaCodec.Header h = thumb.getHeader();
				if (entry.getName() == null && h != null && JourneyStore.isScreenshotName(h.getName()))
				{
					entry.setName(h.getName());
					entry.setTitle(h.getTitle());
					entry.setTime(h.getTime());
				}
				files.writeThumb(key, id, thumb.getJpeg());
			}
			catch (IOException | GeneralSecurityException ex)
			{
				note("couldn't read a screenshot thumbnail", ex);
			}
		}, executor).exceptionally(ex -> null);
	}

	private boolean hasThumb(String key, String id)
	{
		try
		{
			return files.readThumb(key, id) != null;
		}
		catch (IOException e)
		{
			return false;
		}
	}

	/**
	 * Removed from the cloud: other PCs drop the thumbnail and any copy they downloaded. The
	 * original on the PC that took it stays.
	 */
	private void deleteCloudCopy(String key, String id, MediaIndex.Entry e)
	{
		try
		{
			files.deleteThumb(key, id);
			if (e.getName() != null)
			{
				files.deleteCloudCopy(key, e.getName());
			}
		}
		catch (IOException ex)
		{
			log.debug("Unable to delete a cloud copy", ex);
		}
	}

	private CompletableFuture<Void> uploadMedia(String key, SyncState st, int commits)
	{
		if (!screenshots() || mediaFull || commits <= 0 || st.isRestoring())
		{
			return CompletableFuture.completedFuture(null);
		}
		MediaIndex index = index(key);
		List<Map.Entry<String, MediaIndex.Entry>> queued = index.getEntries().entrySet().stream()
			.filter(e -> e.getValue().getState() == MediaIndex.State.QUEUED && e.getValue().getName() != null)
			.sorted(Comparator.comparingLong((Map.Entry<String, MediaIndex.Entry> e) -> e.getValue().getTime()).reversed())
			.limit(MEDIA_PER_COMMIT)
			.collect(Collectors.toList());
		if (queued.isEmpty())
		{
			return CompletableFuture.completedFuture(null);
		}

		List<Prepared> prepared = new ArrayList<>();
		for (Map.Entry<String, MediaIndex.Entry> q : queued)
		{
			String id = q.getKey();
			MediaIndex.Entry e = q.getValue();
			BufferedImage image;
			try
			{
				image = files.readScreenshot(key, e.getName());
			}
			catch (IOException ex)
			{
				image = null;
			}
			if (image == null)
			{
				// Deleted before it could go
				index.getEntries().remove(id);
				continue;
			}
			MediaCodec.Header h = new MediaCodec.Header();
			h.setName(e.getName());
			h.setTitle(e.getTitle());
			h.setTime(e.getTime());
			BufferedImage img = image;
			byte[] full = unchecked(() -> CloudCrypto.seal(dataKey, creds.getDataKeyId(), MediaCodec.full(gson, img, h, MAX_MEDIA_BYTES),
				CloudCrypto.binding(creds.getUserUuid(), st.getProfileId(), MEDIA, MEDIA_PREFIX + id, null)));
			byte[] thumb = unchecked(() -> CloudCrypto.seal(dataKey, creds.getDataKeyId(), MediaCodec.thumb(gson, img, h),
				CloudCrypto.binding(creds.getUserUuid(), st.getProfileId(), THUMB, MEDIA_PREFIX + id, null)));
			e.setCloudBytes(full.length + thumb.length);
			prepared.add(new Prepared(MEDIA, MEDIA_PREFIX + id, full, null, id));
			prepared.add(new Prepared(THUMB, MEDIA_PREFIX + id, thumb, null, id));
		}
		saveIndex(key);
		if (prepared.isEmpty())
		{
			return uploadMedia(key, st, commits - 1);
		}
		return upload(key, st, prepared).handleAsync((v, e) ->
		{
			Throwable cause = e == null ? null : CloudException.cause(e);
			if (cause instanceof CloudException && ((CloudException) cause).is("media_quota_exceeded"))
			{
				// Journey sync carries on; screenshots wait until there's room
				mediaFull = true;
				Api.Error details = ((CloudException) cause).getDetails();
				if (details != null && details.getMedia() != null)
				{
					usage = details.getMedia();
				}
				index.getEntries().values().forEach(x ->
				{
					if (x.getState() == MediaIndex.State.QUEUED)
					{
						x.setState(MediaIndex.State.WAITING);
					}
				});
				saveIndex(key);
				publish();
				return CompletableFuture.<Void>completedFuture(null);
			}
			if (cause != null)
			{
				throw new CompletionException(cause);
			}
			return uploadMedia(key, st, commits - 1);
		}, executor).thenCompose(f -> f);
	}

	// ------------------------------------------------------------------
	// State
	// ------------------------------------------------------------------

	private boolean usable()
	{
		return config.cloudSync() && creds.isConnected() && !creds.isKeyRejected() && !updateNeeded && dataKey != null
			&& server().equals(creds.getServer());
	}

	private boolean isLinked(String key)
	{
		SyncState st = state(key);
		return Boolean.TRUE.equals(st.getConsent()) && st.isLinked() && !st.isChoosing();
	}

	/**
	 * The server this build talks to: always the RuneJourney website, unless RuneLite is in
	 * developer mode and another server is set.
	 */
	public String server()
	{
		String set = developerMode ? config.cloudServer() : null;
		return set != null && !set.trim().isEmpty() ? set.trim() : SERVER;
	}

	private String me()
	{
		return creds.getDeviceId();
	}

	private Api.Session session()
	{
		return new Api.Session(creds.getServer(), creds.getApiKey(), creds.getDeviceId());
	}

	/**
	 * Finds the account's cloud profile without telling the server which account it is: only the
	 * same account, saved by the same player, gives the same fingerprint.
	 */
	private String fingerprint(String key)
	{
		return CloudCrypto.sha256(creds.getUserUuid() + ":" + key);
	}

	private Hlc clock(String key)
	{
		return clocks.computeIfAbsent(key, k -> new Hlc(state(k).getClock()));
	}

	private SyncState state(String key)
	{
		SyncState st = states.get(key);
		if (st == null)
		{
			try
			{
				st = files.readSyncState(key);
			}
			catch (IOException e)
			{
				log.warn("Unable to read RuneJourney sync state", e);
			}
			if (st == null || (st.getUserUuid() != null && !st.getUserUuid().equals(creds.getUserUuid())))
			{
				// Never synced here, or saved to a different player's cloud: start again
				st = new SyncState();
			}
			states.put(key, st);
		}
		return st;
	}

	private void saveState(String key)
	{
		SyncState st = states.get(key);
		if (st == null)
		{
			return;
		}
		Hlc hlc = clocks.get(key);
		if (hlc != null)
		{
			st.setClock(hlc.last());
		}
		if (st.getUserUuid() == null)
		{
			st.setUserUuid(creds.getUserUuid());
		}
		try
		{
			files.writeSyncState(key, st);
		}
		catch (IOException e)
		{
			log.warn("Unable to save RuneJourney sync state", e);
		}
	}

	private MediaIndex index(String key)
	{
		return indexes.computeIfAbsent(key, k ->
		{
			try
			{
				return files.readMediaIndex(k);
			}
			catch (IOException e)
			{
				log.warn("Unable to read RuneJourney screenshot index", e);
				return new MediaIndex();
			}
		});
	}

	private void saveIndex(String key)
	{
		MediaIndex index = indexes.get(key);
		if (index == null)
		{
			return;
		}
		try
		{
			files.writeMediaIndex(key, index);
		}
		catch (IOException e)
		{
			log.warn("Unable to save RuneJourney screenshot index", e);
		}
	}

	/**
	 * A copy of the account's documents waiting to upload.
	 */
	private Map<String, String> readOutbox(String key)
	{
		Map<String, String> outbox = outboxes.get(key);
		if (outbox == null)
		{
			try
			{
				outbox = files.readOutbox(key);
			}
			catch (IOException e)
			{
				log.warn("Unable to read RuneJourney uploads", e);
				outbox = new HashMap<>();
			}
			outboxes.put(key, outbox);
		}
		return new HashMap<>(outbox);
	}

	private void writeOutbox(String key, Map<String, String> outbox)
	{
		outboxes.put(key, new HashMap<>(outbox));
		try
		{
			files.writeOutbox(key, outbox);
		}
		catch (IOException e)
		{
			log.warn("Unable to save RuneJourney uploads", e);
		}
	}

	private void saveCredentials()
	{
		try
		{
			files.writeCredentials(creds);
		}
		catch (IOException e)
		{
			log.warn("Unable to save RuneJourney cloud credentials", e);
		}
	}

	private void save()
	{
		Runnable s = saver;
		if (s != null)
		{
			s.run();
		}
	}

	private void release(String key)
	{
		if (key.equals(holdKey))
		{
			holdKey = null;
		}
	}

	// ------------------------------------------------------------------
	// Status
	// ------------------------------------------------------------------

	private void publish()
	{
		ready = creds.isConnected() && !creds.isKeyRejected();
		CloudStatus.Connection connection;
		if (!config.cloudSync())
		{
			connection = CloudStatus.Connection.OFF;
		}
		else if (connecting)
		{
			connection = CloudStatus.Connection.CONNECTING;
		}
		else if (creds.isConnected() && creds.isKeyRejected())
		{
			connection = CloudStatus.Connection.KEY_REJECTED;
		}
		else if (creds.isConnected() && server().equals(creds.getServer()))
		{
			connection = CloudStatus.Connection.CONNECTED;
		}
		else
		{
			connection = CloudStatus.Connection.NOT_CONNECTED;
		}

		CloudStatus.CloudStatusBuilder b = CloudStatus.builder()
			.connection(connection)
			.userName(creds.getUserName())
			.deviceName(creds.getDeviceName())
			.problem(problem)
			.updateNeeded(updateNeeded)
			.syncing(syncing)
			.screenshots(screenshots())
			.mediaFull(mediaFull)
			.prompt(CloudStatus.Prompt.NONE);
		if (usage != null)
		{
			b.mediaUsed(usage.getUsedBytes()).mediaQuota(usage.getQuotaBytes());
		}

		String key = active;
		Map<String, MediaIndex.Entry> view = Collections.emptyMap();
		String wantsCharacter = null;
		if (key != null && key.equals(service.getProfileKey()))
		{
			SyncState st = state(key);
			if (connection == CloudStatus.Connection.CONNECTED && isLinked(key) && showsCharacter(st.getPublicSettings()))
			{
				Set<String> looks = new HashSet<>(refusedLooks);
				if (st.getPublicSettings().getCharacter() != null)
				{
					looks.add(st.getPublicSettings().getCharacter());
				}
				Captured captured = characters.get(key);
				if (captured != null)
				{
					looks.add(captured.getLook());
				}
				characterLooks = looks;
				wantsCharacter = key;
			}
			b.account(service.playerName())
				.consent(st.getConsent())
				.linked(isLinked(key))
				.readOnly(loggedIn && !files.isLocked(key))
				.lastSync(st.getLastSync())
				.waiting(readOutbox(key).size());
			Api.PublicSettings pub = st.getPublicSettings();
			if (pub != null)
			{
				b.publicEnabled(pub.isEnabled())
					.publicSearchable(pub.isSearchable())
					.publicBlocked(pub.isBlocked())
					.publicUrl(pub.isEnabled() ? pub.getUrl() : null)
					.publicProblem(st.getPublicProblem());
				if (showsCharacter(pub))
				{
					b.publicCharacter(characters.containsKey(key) ? CloudStatus.Character.SENDING
						: pub.getCharacter() != null ? CloudStatus.Character.SHOWN : CloudStatus.Character.WAITING);
				}
			}
			if (connection == CloudStatus.Connection.CONNECTED && !b.build().isReadOnly())
			{
				if (st.getConsent() == null && savedElsewhere.containsKey(key))
				{
					b.prompt(CloudStatus.Prompt.CONSENT).savedElsewhere(savedElsewhere.get(key));
				}
				else if (st.isChoosing())
				{
					b.prompt(CloudStatus.Prompt.CHOOSE);
				}
				else if (isLinked(key) && screenshots() && !index(key).isBacklogAsked())
				{
					long[] backlog = backlog(key);
					if (backlog[0] > 0)
					{
						b.prompt(CloudStatus.Prompt.BACKLOG).backlogCount((int) backlog[0]).backlogBytes(backlog[1]);
					}
					else
					{
						index(key).setBacklogAsked(true);
						saveIndex(key);
					}
				}
			}
			// While screenshots aren't backed up, the gallery shows none as being in the cloud
			if (screenshots())
			{
				MediaIndex index = index(key);
				b.mediaWaiting((int) index.getEntries().values().stream()
					.filter(e -> e.getState() == MediaIndex.State.QUEUED || e.getState() == MediaIndex.State.WAITING).count());
				view = new HashMap<>();
				for (Map.Entry<String, MediaIndex.Entry> e : index.getEntries().entrySet())
				{
					view.put(e.getKey(), copy(e.getValue()));
				}
			}
		}
		mediaView = Collections.unmodifiableMap(view);
		characterFor = wantsCharacter;
		status = b.build();
		Runnable listener = onChange;
		if (listener != null)
		{
			listener.run();
		}
	}

	/**
	 * [count, estimated cloud bytes] of screenshots taken before connecting.
	 */
	private long[] backlog(String key)
	{
		long[] cached = backlogs.get(key);
		if (cached != null && System.currentTimeMillis() - cached[2] < 60_000)
		{
			return cached;
		}
		long count = 0;
		long bytes = 0;
		try
		{
			MediaIndex index = index(key);
			for (JourneyStore.ScreenshotFile f : files.listScreenshots(key))
			{
				if (!f.isCloudCopy() && !index.getEntries().containsKey(mediaId(f.getName())))
				{
					count++;
					bytes += (long) (f.getSize() * JPEG_RATIO);
				}
			}
		}
		catch (IOException e)
		{
			log.debug("Unable to list screenshots", e);
		}
		long[] result = {count, bytes, System.currentTimeMillis()};
		backlogs.put(key, result);
		return result;
	}

	private static MediaIndex.Entry copy(MediaIndex.Entry e)
	{
		MediaIndex.Entry c = new MediaIndex.Entry();
		c.setName(e.getName());
		c.setTitle(e.getTitle());
		c.setTime(e.getTime());
		c.setState(e.getState());
		c.setInCloud(e.isInCloud());
		c.setCloudBytes(e.getCloudBytes());
		return c;
	}

	/**
	 * Notes a failure: a revoked key stops syncing until a new one is pasted. Must run on the
	 * executor. Returns null, for use in {@code handleAsync}.
	 */
	private Void failed(Throwable e)
	{
		Throwable cause = CloudException.cause(e);
		if (cause instanceof StopSync)
		{
			if (cause.getMessage() != null)
			{
				problem = cause.getMessage();
			}
		}
		else
		{
			if (cause instanceof CloudException && ((CloudException) cause).isKeyRejected() && creds.isConnected())
			{
				creds.setKeyRejected(true);
				saveCredentials();
			}
			problem = describe(cause);
			// No URLs: signed ones work for anyone who has them
			note("cloud sync failed: {}", cause.getClass().getSimpleName() + ": " + problem);
		}
		publish();
		return null;
	}

	private static String describe(Throwable e)
	{
		if (e instanceof CloudException)
		{
			CloudException ce = (CloudException) e;
			if (ce.isKeyRejected())
			{
				return "This key no longer works. Your journey is still saved on this PC; paste a new key to keep syncing.";
			}
			if (ce.is("too_many_devices") || ce.is("too_many_profiles") || ce.is("journey_limit_exceeded") || ce.is("public_blocked"))
			{
				return ce.getMessage();
			}
			if (ce.getStatus() == 429)
			{
				return "The cloud is busy. RuneJourney will try again shortly.";
			}
			return "Cloud sync hit a problem (" + (ce.getCode() != null ? ce.getCode() : "HTTP " + ce.getStatus()) + "). It will try again.";
		}
		if (e instanceof StopSync)
		{
			return e.getMessage();
		}
		if (e instanceof IOException)
		{
			return "Couldn't reach the cloud. RuneJourney will keep trying.";
		}
		return "Cloud sync hit a problem. It will try again.";
	}

	// ------------------------------------------------------------------
	// Threading helpers
	// ------------------------------------------------------------------

	/**
	 * Stops a sync without it counting as a failure (when the message is null).
	 */
	private static class StopSync extends RuntimeException
	{
		StopSync(String message)
		{
			super(message, null, false, false);
		}
	}

	private interface IoCall<T>
	{
		T call() throws IOException, GeneralSecurityException;
	}

	private static <T> T unchecked(IoCall<T> call)
	{
		try
		{
			return call.call();
		}
		catch (IOException | GeneralSecurityException e)
		{
			throw new CompletionException(e);
		}
	}

	/**
	 * Starts a call, turning anything it throws straight away into a failed future.
	 */
	private static <T> CompletableFuture<T> attempt(java.util.function.Supplier<CompletableFuture<T>> call)
	{
		try
		{
			return call.get();
		}
		catch (RuntimeException e)
		{
			CompletableFuture<T> f = new CompletableFuture<>();
			f.completeExceptionally(e);
			return f;
		}
	}

	private <T> CompletableFuture<T> onExecutor(java.util.function.Supplier<CompletableFuture<T>> work)
	{
		Executor exec = executor;
		if (exec == null)
		{
			CompletableFuture<T> f = new CompletableFuture<>();
			f.completeExceptionally(new IOException("Cloud sync isn't running"));
			return f;
		}
		return CompletableFuture.supplyAsync(() -> attempt(work), exec).thenCompose(f -> f);
	}

	/**
	 * @return false if sync isn't running
	 */
	private boolean submit(Runnable task)
	{
		Executor exec = executor;
		if (exec == null)
		{
			return false;
		}
		try
		{
			exec.execute(() -> runSafely(task));
			return true;
		}
		catch (java.util.concurrent.RejectedExecutionException e)
		{
			return false;
		}
	}

	private static void runSafely(Runnable task)
	{
		try
		{
			task.run();
		}
		catch (RuntimeException e)
		{
			log.warn("RuneJourney cloud sync error", e);
		}
	}
}
