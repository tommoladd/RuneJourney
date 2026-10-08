package com.runejourney;

import com.google.inject.Provides;
import com.runejourney.cloud.CharacterModel;
import com.runejourney.cloud.CloudFiles;
import com.runejourney.cloud.SyncManager;
import com.runejourney.planner.Counters;
import com.runejourney.planner.Skills;
import com.runejourney.service.ChatParser;
import com.runejourney.service.JourneyService;
import com.runejourney.service.JourneyStore;
import com.runejourney.service.ScreenshotService;
import com.runejourney.model.ClogItem;
import com.runejourney.ui.Icons;
import com.runejourney.ui.RuneJourneyOverlay;
import com.runejourney.ui.ReportWindowManager;
import com.runejourney.ui.ScreenshotWindowManager;
import com.runejourney.ui.RuneJourneyPanel;
import com.runejourney.wrapped.WrappedPlayer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.ScriptID;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.widgets.Widget;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

@Slf4j
@PluginDescriptor(
	name = "RuneJourney",
	description = "Your account's personal history, goals and adaptive plans, automatically recorded while you play",
	tags = {"journey", "goals", "planner", "history", "timeline", "milestones", "recap"},
	internalName = "runejourney"
)
public class RuneJourneyPlugin extends Plugin
{
	/**
	 * Save unsaved progress roughly every 30 seconds.
	 */
	private static final int SAVE_INTERVAL_TICKS = 50;
	/**
	 * Refresh the sidebar at most every ~3 seconds while playing.
	 */
	private static final int UI_INTERVAL_TICKS = 5;
	/**
	 * Refresh purchase goal prices roughly every 5 minutes.
	 */
	private static final int PRICE_REFRESH_TICKS = 500;
	/**
	 * Look for a new outfit to show on the public page every ~3 seconds.
	 */
	private static final int CHARACTER_INTERVAL_TICKS = 5;
	private static final EnumSet<WorldType> UNTRACKED_WORLDS = EnumSet.of(
		WorldType.BETA_WORLD, WorldType.NOSAVE_MODE, WorldType.TOURNAMENT_WORLD, WorldType.QUEST_SPEEDRUNNING,
		WorldType.LAST_MAN_STANDING, WorldType.PVP_ARENA);

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private JourneyService service;

	@Inject
	private JourneyStore store;

	@Inject
	private ScreenshotService screenshots;

	@Inject
	private IncomeTracker incomeTracker;

	@Inject
	private SupplyTracker supplyTracker;

	@Inject
	private WealthTracker wealthTracker;

	@Inject
	private ReportWindowManager reportWindows;

	@Inject
	private ScreenshotWindowManager screenshotWindows;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private RuneJourneyOverlay overlay;

	@Inject
	private WrappedPlayer wrappedPlayer;

	@Inject
	private RuneJourneyConfig config;

	@Inject
	private SyncManager sync;

	@Inject
	private CollectionLogSync collectionLogSync;

	@Inject
	private AchievementsReader achievementsReader;

	private final Map<Skill, Integer> lastStatXp = new EnumMap<>(Skill.class);
	/**
	 * Grand Exchange updates that arrive during login, before the profile is ready.
	 */
	private final List<GrandExchangeOfferChanged> pendingOffers = new ArrayList<>();
	/**
	 * Skill XP when the player logged in, kept while cloud sync fetches other PCs' records so XP
	 * gained meanwhile isn't mistaken for XP gained while away.
	 */
	private Map<String, Long> heldXp;

	@Getter
	private ScheduledExecutorService executor;

	private RuneJourneyPanel panel;
	private NavigationButton navButton;

	private volatile String loadingKey;
	/**
	 * Counts logins, so a logout's late cleanup can't undo a later login.
	 */
	private volatile int session;
	/**
	 * The account whose lock this window holds for playing it. Only used on the executor.
	 */
	private String sessionLock;
	private int lastUiVersion = -1;
	private int lastUiTick;

	@Override
	protected void startUp() throws Exception
	{
		executor = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "RuneJourney");
			t.setDaemon(true);
			return t;
		});
		store.setRoot(getPluginDirectory());
		screenshots.setExecutor(executor);
		screenshots.setListener(sync::onScreenshot);
		wrappedPlayer.setExecutor(executor, service::getProfileKey);
		sync.setSaver(this::save);
		sync.start(executor);

		panel = injector.getInstance(RuneJourneyPanel.class);
		navButton = NavigationButton.builder()
			.tooltip("RuneJourney")
			.icon(Icons.navIcon())
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		overlayManager.add(overlay);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			loadProfile();
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		clientToolbar.removeNavigation(navButton);
		wrappedPlayer.close();
		overlayManager.remove(overlay);
		overlay.setData(null);
		reportWindows.close();
		screenshotWindows.close();
		incomeTracker.reset();
		supplyTracker.reset();
		wealthTracker.reset();
		lastStatXp.clear();
		pendingOffers.clear();
		heldXp = null;
		sync.stop();
		sync.setSaver(null);
		screenshots.setListener(null);
		// Finish the play session first so its records are included in the final save
		service.resetSessionState();
		save();
		executor.submit(() ->
		{
			sessionLock = null;
			store.unlockAll();
		});
		// Let the final save finish without blocking the shutdown thread
		executor.shutdown();
		screenshots.clear();
		screenshots.setExecutor(null);
		service.unload();
		loadingKey = null;
		lastUiVersion = -1;
		panel = null;
		navButton = null;
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		service.resetSessionState();
		Future<?> saved = save();
		if (saved != null)
		{
			event.waitFor(saved);
		}
		// A last upload, if it's quick
		event.waitFor(sync.onExit());
	}

	@Provides
	RuneJourneyConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RuneJourneyConfig.class);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			loadProfile();
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			incomeTracker.reset();
			supplyTracker.reset();
			wealthTracker.reset();
			collectionLogSync.reset();
			achievementsReader.refresh();
			lastStatXp.clear();
			pendingOffers.clear();
			heldXp = null;
			// The in-game view can't show on the login screen
			if (wrappedPlayer.isPlayingInGame())
			{
				wrappedPlayer.close();
			}
			service.resetSessionState();
			save();
			screenshots.clear();
			refreshPanel(true);

			// Upload what's left, then let another RuneLite window have the account
			String key = service.getProfileKey();
			if (key != null)
			{
				int loggedOut = session;
				sync.onLogout(key).whenComplete((v, e) ->
				{
					ExecutorService exec = executor;
					if (exec != null && !exec.isShutdown())
					{
						exec.submit(() ->
						{
							// Unless the player logged back in meanwhile (logging into another account releases it anyway)
							if (session == loggedOut)
							{
								releaseSessionLock();
							}
						});
					}
				});
			}
		}
	}

	private String profileKey()
	{
		long hash = client.getAccountHash();
		if (hash == -1)
		{
			return null;
		}
		EnumSet<WorldType> types = client.getWorldType();
		for (WorldType t : UNTRACKED_WORLDS)
		{
			if (types.contains(t))
			{
				return null;
			}
		}
		String key = Long.toString(hash);
		if (types.contains(WorldType.SEASONAL))
		{
			key += "-seasonal";
		}
		else if (types.contains(WorldType.DEADMAN))
		{
			key += "-deadman";
		}
		else if (types.contains(WorldType.FRESH_START_WORLD))
		{
			key += "-fsw";
		}
		return key;
	}

	private void loadProfile()
	{
		String key = profileKey();
		if (key == null)
		{
			if (service.getProfileKey() != null)
			{
				service.resetSessionState();
				save();
				service.unload();
				overlay.setData(null);
				// After the save, which still needs it
				executor.submit(this::releaseSessionLock);
			}
			pendingOffers.clear();
			return;
		}
		if (key.equals(loadingKey))
		{
			return;
		}
		if (key.equals(service.getProfileKey()))
		{
			// The profile stays loaded at the login screen. Logging back in (not just loading a new
			// area, which also reports LOGGED_IN) checks whether another window played it meanwhile.
			if (!service.isBaselineSet())
			{
				session++;
				reloadIfChanged(key);
				sync.onLogin(key);
			}
			return;
		}

		service.resetSessionState();
		save();
		service.unload();
		overlay.setData(null);
		loadingKey = key;
		session++;
		service.beginLoad(key);
		executor.submit(() ->
		{
			try
			{
				lock(key);
				JourneyStore.Loaded loaded = store.load(key);
				// Only installs if no other account or shutdown has happened since the load started
				if (service.installIfCurrent(key, loaded))
				{
					loadingKey = null;
					refreshPanel(true);
				}
			}
			catch (IOException e)
			{
				log.warn("Unable to load RuneJourney profile", e);
				loadingKey = null;
			}
		});
		// Runs after the load: fetches other PCs' changes before XP is counted
		sync.onLogin(key);
	}

	/**
	 * Takes the account's lock while it's played here, so another RuneLite window that has it open
	 * (e.g. logged out, at the login screen) doesn't save over it. Runs on the executor.
	 */
	private void lock(String key)
	{
		if (key.equals(sessionLock))
		{
			return;
		}
		releaseSessionLock();
		if (store.tryLock(key, CloudFiles.SESSION))
		{
			sessionLock = key;
		}
		else
		{
			log.info("RuneJourney: this account is open in another RuneLite window, so this window won't save it");
		}
	}

	/**
	 * Runs on the executor.
	 */
	private void releaseSessionLock()
	{
		if (sessionLock != null)
		{
			store.unlock(sessionLock, CloudFiles.SESSION);
			sessionLock = null;
		}
	}

	/**
	 * Reloads the profile if its files were changed by something other than this window, such as
	 * another RuneLite window playing the same account. Otherwise the copy in memory, which is now
	 * out of date, would be saved over them. Tracking waits until the check is done.
	 */
	private void reloadIfChanged(String key)
	{
		loadingKey = key;
		service.beginLoad(key);
		executor.submit(() ->
		{
			try
			{
				lock(key);
				if (store.changedOnDisk(key) && service.installIfCurrent(key, store.load(key)))
				{
					log.debug("Reloaded RuneJourney profile changed by another window");
					refreshPanel(true);
				}
			}
			catch (IOException e)
			{
				log.warn("Unable to check RuneJourney profile for changes", e);
			}
			finally
			{
				service.cancelLoad(key);
				if (key.equals(loadingKey))
				{
					loadingKey = null;
				}
			}
		});
	}

	/**
	 * Writes unsaved changes, off the client thread. While the account is played here this window
	 * holds its lock; otherwise it only saves if no other window has the account or changed it.
	 *
	 * @return the write, or null if there was nothing to save
	 */
	private Future<?> save()
	{
		String key = service.getProfileKey();
		Runnable writes = service.collectWrites();
		ExecutorService exec = executor;
		if (writes == null || key == null || exec == null || exec.isShutdown())
		{
			return null;
		}
		return exec.submit(() ->
		{
			if (store.isLocked(key))
			{
				writes.run();
				return;
			}
			if (!store.tryLock(key, CloudFiles.SAVE))
			{
				log.debug("Not saving RuneJourney: the account is open in another RuneLite window");
				return;
			}
			try
			{
				if (store.changedOnDisk(key))
				{
					log.debug("Not saving RuneJourney: another RuneLite window changed the account; it's reloaded on login");
				}
				else
				{
					writes.run();
				}
			}
			catch (IOException e)
			{
				log.warn("Unable to check RuneJourney files before saving", e);
			}
			finally
			{
				store.unlock(key, CloudFiles.SAVE);
			}
		});
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		int tick = client.getTickCount();
		if (!service.isReady() || loadingKey != null)
		{
			return;
		}

		if (!service.isBaselineSet())
		{
			if (client.getLocalPlayer() != null)
			{
				service.updatePlayerName(client.getLocalPlayer().getName());
			}
			Map<String, Long> xp = new HashMap<>();
			long sum = 0;
			for (Skill s : Skills.ALL)
			{
				long v = client.getSkillExperience(s);
				xp.put(s.name(), v);
				sum += v;
			}
			if (sum <= 0)
			{
				return;
			}
			if (sync.isHolding(service.getProfileKey()))
			{
				// Other PCs' records first, so XP they recorded isn't counted again as gained while away
				if (heldXp == null)
				{
					heldXp = xp;
				}
				return;
			}
			Map<String, Long> baseline = heldXp != null ? heldXp : xp;
			heldXp = null;
			service.setBaseline(baseline, tick);
			// XP gained while waiting
			for (Skill s : Skills.ALL)
			{
				if (xp.get(s.name()) > baseline.getOrDefault(s.name(), 0L))
				{
					service.onXp(s, xp.get(s.name()), tick);
				}
			}
			readCounters();
			refreshPurchasePrices();
			for (GrandExchangeOfferChanged offer : pendingOffers)
			{
				handleOffer(offer);
			}
			pendingOffers.clear();
		}

		incomeTracker.onTick(tick);
		collectionLogSync.onGameTick();
		achievementsReader.onGameTick();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			wealthTracker.onTick();
		}
		service.onTick(tick);
		screenshots.onTick(tick);

		if (tick % SAVE_INTERVAL_TICKS == 0)
		{
			save();
		}
		if (tick % PRICE_REFRESH_TICKS == 0)
		{
			refreshPurchasePrices();
		}
		if (tick % CHARACTER_INTERVAL_TICKS == 0)
		{
			captureCharacter();
		}
		if (tick - lastUiTick >= UI_INTERVAL_TICKS)
		{
			lastUiTick = tick;
			refreshPanel(false);
			overlay.setData(config.showOverlay() ? service.overlayData() : null);
		}
	}

	/**
	 * Copies the player's character for their public page, when it shows it and they're wearing
	 * something new. Only while they stand still, so it's caught in its idle pose.
	 */
	private void captureCharacter()
	{
		String key = service.getProfileKey();
		Player me = client.getLocalPlayer();
		if (key == null || me == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		PlayerComposition composition = me.getPlayerComposition();
		if (composition == null || composition.getTransformedNpcId() != -1)
		{
			return;
		}
		String look = CharacterModel.look(composition);
		if (!sync.wantsCharacter(key, look)
			|| me.getAnimation() != -1
			|| me.getPoseAnimation() != me.getIdlePoseAnimation()
			|| me.getSpotAnims().iterator().hasNext())
		{
			return;
		}
		Model model = me.getModel();
		if (model == null || model.getOverrideAmount() != 0)
		{
			return;
		}
		CharacterModel captured = CharacterModel.capture(model, client.getTextureProvider());
		if (captured != null)
		{
			sync.setCharacter(key, look, captured);
		}
	}

	private void refreshPanel(boolean force)
	{
		RuneJourneyPanel p = panel;
		if (p == null)
		{
			return;
		}
		int version = service.getVersion();
		if (!force && version == lastUiVersion)
		{
			return;
		}
		lastUiVersion = version;
		SwingUtilities.invokeLater(() -> p.refresh(force));
	}

	/**
	 * Clue tiers and the varbits holding their completion counts.
	 */
	private static final Map<Integer, String> CLUE_VARBITS = new HashMap<>();

	static
	{
		CLUE_VARBITS.put(VarbitID.COLLECTION_CLUES_BEGINNER_COMPLETED, "Beginner");
		CLUE_VARBITS.put(VarbitID.COLLECTION_CLUES_EASY_COMPLETED, "Easy");
		CLUE_VARBITS.put(VarbitID.COLLECTION_CLUES_MEDIUM_COMPLETED, "Medium");
		CLUE_VARBITS.put(VarbitID.COLLECTION_CLUES_HARD_COMPLETED, "Hard");
		CLUE_VARBITS.put(VarbitID.COLLECTION_CLUES_ELITE_COMPLETED, "Elite");
		CLUE_VARBITS.put(VarbitID.COLLECTION_CLUES_MASTER_COMPLETED, "Master");
	}

	/**
	 * Variables whose bits flag each completed combat task.
	 */
	static final int[] CA_TASK_VARPS = {
		VarPlayerID.CA_TASK_COMPLETED_0, VarPlayerID.CA_TASK_COMPLETED_1, VarPlayerID.CA_TASK_COMPLETED_2,
		VarPlayerID.CA_TASK_COMPLETED_3, VarPlayerID.CA_TASK_COMPLETED_4, VarPlayerID.CA_TASK_COMPLETED_5,
		VarPlayerID.CA_TASK_COMPLETED_6, VarPlayerID.CA_TASK_COMPLETED_7, VarPlayerID.CA_TASK_COMPLETED_8,
		VarPlayerID.CA_TASK_COMPLETED_9, VarPlayerID.CA_TASK_COMPLETED_10, VarPlayerID.CA_TASK_COMPLETED_11,
		VarPlayerID.CA_TASK_COMPLETED_12, VarPlayerID.CA_TASK_COMPLETED_13, VarPlayerID.CA_TASK_COMPLETED_14,
		VarPlayerID.CA_TASK_COMPLETED_15, VarPlayerID.CA_TASK_COMPLETED_16, VarPlayerID.CA_TASK_COMPLETED_17,
		VarPlayerID.CA_TASK_COMPLETED_18, VarPlayerID.CA_TASK_COMPLETED_19, VarPlayerID.CA_TASK_COMPLETED_20,
	};

	private int completedCombatTasks()
	{
		int count = 0;
		for (int varp : CA_TASK_VARPS)
		{
			count += Integer.bitCount(client.getVarpValue(varp));
		}
		return count;
	}

	private static boolean isCaTaskVarp(int varp)
	{
		for (int v : CA_TASK_VARPS)
		{
			if (v == varp)
			{
				return true;
			}
		}
		return false;
	}

	private void readCounters()
	{
		service.onCounter(Counters.CA_TASKS, completedCombatTasks());
		service.onCounter(Counters.QUEST_POINTS, client.getVarpValue(VarPlayerID.QP));
		service.onCounter(Counters.COLLECTION_LOG, client.getVarpValue(VarPlayerID.COLLECTION_COUNT));
		service.setCollectionLogTotal(client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX));
		CLUE_VARBITS.forEach((varbit, tier) -> service.onCounter(Counters.clues(tier), client.getVarbitValue(varbit)));
		// Price the bank and anything else held since last time, even if it hasn't been opened yet
		wealthTracker.invalidate();
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (!service.isBaselineSet())
		{
			return;
		}
		if (event.getVarpId() == VarPlayerID.QP)
		{
			service.onCounter(Counters.QUEST_POINTS, event.getValue());
			achievementsReader.refresh();
		}
		else if (event.getVarpId() == VarPlayerID.COLLECTION_COUNT)
		{
			service.onCounter(Counters.COLLECTION_LOG, event.getValue());
		}
		else if (event.getVarpId() == VarPlayerID.COLLECTION_COUNT_MAX)
		{
			service.setCollectionLogTotal(event.getValue());
		}
		else if (event.getVarbitId() == -1 && isCaTaskVarp(event.getVarpId()))
		{
			service.onCounter(Counters.CA_TASKS, completedCombatTasks());
			achievementsReader.refresh();
		}
		else if (event.getVarbitId() != -1 && CLUE_VARBITS.containsKey(event.getVarbitId()))
		{
			service.onCounter(Counters.clues(CLUE_VARBITS.get(event.getVarbitId())), event.getValue());
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		// Net worth and cash are read by WealthTracker each tick, not from these events
		int id = event.getContainerId();
		if (id == InventoryID.INV)
		{
			incomeTracker.onInventory(event.getItemContainer());
			incomeTracker.discard(supplyTracker.onInventory(event.getItemContainer(), client.getTickCount()));
			return;
		}
		if (id != InventoryID.TRAIL_REWARDINV)
		{
			return;
		}
		List<ItemStack> stacks = new ArrayList<>();
		for (Item item : event.getItemContainer().getItems())
		{
			if (item.getId() > 0 && item.getQuantity() > 0)
			{
				stacks.add(new ItemStack(item.getId(), item.getQuantity()));
			}
		}
		service.onClueReward(priced(stacks), client.getTickCount());
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		GrandExchangeOfferState state = event.getOffer().getState();
		if (!service.isReady() || !service.isBaselineSet())
		{
			// Offers are replayed while logging in; empty slots then aren't real collections
			if (state != GrandExchangeOfferState.EMPTY)
			{
				pendingOffers.add(event);
			}
			return;
		}
		handleOffer(event);
	}

	private void handleOffer(GrandExchangeOfferChanged event)
	{
		GrandExchangeOffer offer = event.getOffer();
		GrandExchangeOfferState state = offer.getState();
		boolean buy = state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY;
		String name = offer.getItemId() > 0 ? itemManager.getItemComposition(offer.getItemId()).getName() : null;
		service.onGrandExchangeOffer(event.getSlot(), offer.getItemId(), name, buy, state == GrandExchangeOfferState.EMPTY,
			offer.getQuantitySold(), offer.getSpent());
	}


	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.COLLECTION_DRAW_LIST && service.isReady())
		{
			readCollectionLogPage();
		}
		collectionLogSync.onScriptPostFired(event);
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		collectionLogSync.onScriptPreFired(event);
	}

	/**
	 * Reads the collection log page on screen: its title and which items are obtained (obtained
	 * items are drawn fully opaque, missing ones faded).
	 */
	private void readCollectionLogPage()
	{
		String title = null;
		for (int id : new int[]{InterfaceID.Collection.HEADER_TEXT, InterfaceID.Collection.HEADER})
		{
			Widget header = client.getWidget(id);
			if (header == null)
			{
				continue;
			}
			Widget[] children = header.getDynamicChildren();
			String text = children != null && children.length > 0 ? children[0].getText() : header.getText();
			text = text == null ? null : Text.removeTags(text).trim();
			if (text != null && !text.isEmpty() && !text.startsWith("Obtained"))
			{
				title = text;
				break;
			}
		}
		if (title == null)
		{
			return;
		}

		List<ClogItem> items = new ArrayList<>();
		for (int id : new int[]{InterfaceID.Collection.ITEMS, InterfaceID.Collection.ITEMS_CONTENTS})
		{
			Widget container = client.getWidget(id);
			if (container == null || container.getDynamicChildren() == null)
			{
				continue;
			}
			for (Widget w : container.getDynamicChildren())
			{
				if (w.getItemId() > 0)
				{
					String name = itemManager.getItemComposition(w.getItemId()).getName();
					items.add(new ClogItem(w.getItemId(), name, w.getOpacity() == 0));
				}
			}
			if (!items.isEmpty())
			{
				break;
			}
		}
		service.onCollectionLogPage(title, items);
	}

	/**
	 * Keeps purchase goals in step with the Grand Exchange price.
	 */
	private void refreshPurchasePrices()
	{
		for (JourneyService.PurchaseRef ref : service.purchaseRefs())
		{
			long price = itemManager.getItemPrice(ref.getItemId());
			if (price > 0)
			{
				service.setPurchaseTarget(ref.getGoalId(), price * ref.getQuantity());
			}
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Integer last = lastStatXp.put(event.getSkill(), event.getXp());
		if (last != null && event.getXp() > last)
		{
			incomeTracker.onXp(event.getSkill(), event.getXp() - last);
		}
		service.onXp(event.getSkill(), event.getXp(), client.getTickCount());
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		ChatParser.Result r = ChatParser.parse(Text.removeTags(event.getMessage()));
		if (r == null)
		{
			return;
		}

		int tick = client.getTickCount();
		switch (r.getKind())
		{
			case KILL_COUNT:
				service.onKillCount(r.getName(), r.getCount(), tick);
				break;
			case PERSONAL_BEST:
				service.onPersonalBest(r.getName(), tick);
				break;
			case COLLECTION_LOG:
				service.onCollectionLog(r.getName(), tick);
				break;
			case QUEST:
				service.onQuest(r.getName(), tick);
				break;
			case DIARY_TIER:
				service.onDiary(r.getName(), r.getDetail(), tick);
				break;
			case COMBAT_TASK:
				service.onCombatTask(r.getName(), r.getDetail(), r.getCount(), tick);
				break;
			case PET:
				service.onPet(false, tick);
				break;
			case DUPLICATE_PET:
				service.onPet(true, tick);
				break;
			case SLAYER_TASK:
				service.onSlayerTask();
				break;
			case CLUE:
				service.onClueCompleted(r.getName(), r.getCount(), tick);
				break;
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		incomeTracker.onInterfaceOpened(event.getGroupId());
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		incomeTracker.onInterfaceClosed(event.getGroupId(), client.getTickCount());
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			service.onDeath(client.getTickCount());
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		supplyTracker.onMenuOptionClicked(event.getMenuOption(), event.getItemId(), client.getTickCount());
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		service.onLoot(event.getNpc().getName(), priced(event.getItems()), client.getTickCount());
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		// NPC loot is handled by NpcLootReceived, which works without the Loot Tracker plugin.
		// Player (PvP) loot is intentionally ignored.
		if (event.getType() == LootRecordType.NPC || event.getType() == LootRecordType.PLAYER)
		{
			return;
		}
		// Clue caskets are read from the reward screen so they're recorded even without the Loot Tracker
		if (event.getName() != null && event.getName().startsWith("Clue Scroll"))
		{
			return;
		}
		// Pickpocketing is counted as Thieving income by the income tracker
		if (event.getType() == LootRecordType.PICKPOCKET)
		{
			return;
		}
		incomeTracker.suppress(client.getTickCount());
		service.onLoot(event.getName(), priced(event.getItems()), client.getTickCount());
	}

	private List<JourneyService.LootItem> priced(Collection<ItemStack> items)
	{
		List<JourneyService.LootItem> result = new ArrayList<>();
		for (ItemStack stack : items)
		{
			ItemComposition comp = itemManager.getItemComposition(stack.getId());
			long price = (long) itemManager.getItemPrice(stack.getId()) * stack.getQuantity();
			result.add(new JourneyService.LootItem(stack.getId(), comp.getName(), stack.getQuantity(), price));
		}
		return result;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (RuneJourneyConfig.GROUP.equals(event.getGroup()))
		{
			if (event.getKey().startsWith("cloud"))
			{
				sync.onConfigChanged();
			}
			refreshPanel(true);
		}
	}
}
