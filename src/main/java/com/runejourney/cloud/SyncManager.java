package com.runejourney.cloud;

import com.google.gson.Gson;
import com.runejourney.RuneJourneyConfig;
import com.runejourney.model.*;
import com.runejourney.service.*;
import com.runejourney.sync.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.hiscore.*;

@Slf4j
@Singleton
public class SyncManager
{
	public static final String SERVER = "https://runejourney.org";
	static final String JOURNEY = "journey";
	static final String CHARACTER = "character";
	private static final long HOLD_MILLIS = 8_000;
	private static final long PLAYING_EVERY = 2 * 60_000L;
	private static final long IDLE_EVERY = 5 * 60_000L;
	private static final long MIN_BACKOFF = 60_000L;
	private static final long MAX_BACKOFF = 15 * 60_000L;
	private static final int MAX_FILES = 50;
	private static final long PUBLISH_EVERY = 10 * 60_000L;
	private static final int MAX_PAGE_BYTES = 240_000;
	private static final long HISCORES_EVERY = 3 * 60 * 60_000L;
	private static final long HISCORES_RETRY = 30 * 60_000L;
	private static final long HISCORES_ASKED_EVERY = 5 * 60_000L;
	static final String COLLECTION = "collection";
	private static final int MAX_DOWNLOAD = 6 * 1024 * 1024;
	private static final int MAX_DOCUMENT = 16 * 1024 * 1024;

	private final JourneyService service;
	private final CloudApi api;
	private final CloudFiles files;
	private final RuneJourneyConfig config;
	private final Gson gson;
	private final boolean developerMode;
	private final Hiscores.Lookup hiscores;
	private final SyncLog syncLog;

	@Setter
	private Runnable onChange;
	@Setter
	private Runnable saver;

	private Executor executor;
	private ScheduledFuture<?> timer;

	private CloudCredentials creds = new CloudCredentials();
	private byte[] dataKey;
	private final Map<String, SyncState> states = new HashMap<>();
	private final Map<String, Hlc> clocks = new HashMap<>();
	private final Map<String, Map<String, String>> outboxes = new HashMap<>();
	private final Set<String> resolved = new HashSet<>();
	private final Map<String, Boolean> savedElsewhere = new HashMap<>();
	private final Map<String, Captured> characters = new HashMap<>();
	private final Set<String> refusedLooks = new HashSet<>();
	private final Set<String> refusedLogs = new HashSet<>();
	private final Set<String> refusedAchievements = new HashSet<>();
	private volatile String active;
	private boolean loggedIn;
	private CompletableFuture<Void> running = CompletableFuture.completedFuture(null);
	private boolean syncing;
	private long lastCycle;
	private long backoff;
	private long backoffUntil;
	private boolean publishNow;
	private boolean connecting;
	private boolean updateNeeded;
	private String problem;

	private volatile CloudStatus status = CloudStatus.builder().connection(CloudStatus.Connection.OFF).prompt(CloudStatus.Prompt.NONE).build();
	private volatile boolean ready;
	private volatile String holdKey;
	private volatile long holdUntil;
	private volatile String characterFor;
	private volatile Set<String> characterLooks = Collections.emptySet();

	@Inject
	SyncManager(JourneyService service, CloudApi api, JourneyStore files, RuneJourneyConfig config, Gson gson,
		@Named("developerMode") boolean developerMode, HiscoreClient hiscoreClient, SyncLog syncLog)
	{
		this(service, api, (CloudFiles) files, config, gson, developerMode, hiscoreClient::lookupAsync, syncLog);
	}

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
		for (; arg < args.length; arg++)
		{
			out.append(": ").append(args[arg]);
		}
		syncLog.write("%s", out);
	}

	public void start(ScheduledExecutorService executor)
	{
		this.executor = executor;
		submit(this::loadCredentials);
		timer = executor.scheduleWithFixedDelay(() -> runSafely(this::tick), 30, 30, TimeUnit.SECONDS);
	}

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

	public boolean isHolding(String key)
	{
		return key != null && key.equals(holdKey) && System.currentTimeMillis() < holdUntil;
	}

	public void onLogin(String key)
	{
		if (ready && config.cloudSync())
		{
			holdUntil = System.currentTimeMillis() + HOLD_MILLIS;
			holdKey = key;
		}
		submit(() -> loginSync(key));
	}

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

	public boolean wantsCharacter(String key, String look)
	{
		return key != null && key.equals(characterFor) && !characterLooks.contains(look);
	}

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
				queue(() -> withLock(key, key.equals(service.getProfileKey()), () -> publishCharacter(key, state(key))));
			}
		});
	}

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
		return syncAccount(key);
	}

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

	public void syncNow()
	{
		submit(() ->
		{
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

	void cycle()
	{
		submit(() ->
		{
			backoffUntil = 0;
			lastCycle = 0;
			tick();
		});
	}

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
			if (reply.getDevice() != null && reply.getDevice().getName() != null)
			{
				creds.setDeviceName(reply.getDevice().getName());
			}
			saveCredentials();
			publish();
		}, executor);
	}

	private void loginSync(String key)
	{
		active = key;
		loggedIn = true;
		savedElsewhere.remove(key);
		queue(() -> login(key)).whenComplete((v, e) -> release(key));
		publish();
	}

	private CompletableFuture<Void> login(String key)
	{
		if (!usable() || !files.isLocked(key))
		{
			return CompletableFuture.completedFuture(null);
		}
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
				return syncAccount(key);
			}, executor);
	}

	private void fork(String key, SyncState st)
	{
		String old = me();
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
		lastCycle = 0;
	}

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

	private void invalidate(String key)
	{
		states.remove(key);
		clocks.remove(key);
		outboxes.remove(key);
		resolved.remove(key);
	}

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
			return ensureDevice()
				.thenComposeAsync(v -> ensureProfile(key, st), executor)
				.thenComposeAsync(v -> pull(key, st, gen, restore), executor)
				.thenComposeAsync(v -> push(key, st, gen), executor)
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
				throw new CompletionException(CloudException.cause(e));
			}
			if (!r.getProfile().getId().equals(st.getProfileId()))
			{
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

	private CompletableFuture<Void> refreshHiscores(SyncState st, PublicSnapshot page, boolean asked)
	{
		long now = System.currentTimeMillis();
		Hiscores.Entry known = st.getHiscores();
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
		fit(page);
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
					settings.setEnabled(false);
					settings.setUrl(null);
				}
				else if (ce.is("name_taken"))
				{
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

	private void fit(PublicSnapshot page)
	{
		while (page.getTimeline() != null && !page.getTimeline().isEmpty()
			&& gson.toJson(page).length() > MAX_PAGE_BYTES)
		{
			List<PublicSnapshot.Event> timeline = page.getTimeline();
			page.setTimeline(new ArrayList<>(timeline.subList(0, timeline.size() * 4 / 5)));
		}
	}

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

	private CompletableFuture<Void> pull(String key, SyncState st, int gen, boolean restore)
	{
		return attempt(() -> api.changes(session(), st.getProfileId(), st.getCursor())).thenComposeAsync(page ->
		{
			List<CompletableFuture<Fetched>> docs = new ArrayList<>();
			for (Api.Change c : page.getChanges())
			{
				if (!JOURNEY.equals(c.getKind()) || c.isDeleted() || (!restore && me().equals(c.getDeviceId())))
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
			return CompletableFuture.allOf(docs.toArray(new CompletableFuture[0])).thenComposeAsync(v ->
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
					throw new StopSync(null);
				}
				st.setCursor(page.getCursor());
				if (page.getProfile() != null)
				{
					publicSettings(st, page.getProfile().getPublicSettings());
				}
				if (!page.isMore() && restore)
				{
					st.setRestoring(false);
				}
				saveState(key);
				if (!profiles.isEmpty() || !days.isEmpty())
				{
					save();
				}
				return page.isMore() ? pull(key, st, gen, restore) : CompletableFuture.completedFuture(null);
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
			save();
		}
		saveState(key);
		return drain(key, st);
	}

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
			prepared.add(new Prepared(JOURNEY, docKey, sealed, CloudCrypto.sha256(json)));
		}
		return upload(key, st, prepared).thenComposeAsync(v -> drain(key, st), executor);
	}

	@Value
	private static class Prepared
	{
		String kind;
		String docKey;
		byte[] body;
		String docHash;
	}

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
		saveState(key);
		publish();
	}

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
			.prompt(CloudStatus.Prompt.NONE);

		String key = active;
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
			}
		}
		characterFor = wantsCharacter;
		status = b.build();
		Runnable listener = onChange;
		if (listener != null)
		{
			listener.run();
		}
	}

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
