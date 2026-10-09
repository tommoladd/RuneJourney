package com.runejourney.cloud;

import com.google.gson.Gson;
import com.runejourney.RuneJourneyConfig;
import com.runejourney.model.ClogItem;
import com.runejourney.model.DayRecord;
import com.runejourney.model.EventType;
import com.runejourney.model.JourneyEvent;
import com.runejourney.model.ProfileData;
import com.runejourney.planner.Counters;
import com.runejourney.service.JourneyService;
import com.runejourney.service.JourneyStore;
import com.runejourney.service.PublicAchievements;
import com.runejourney.service.PublicCollectionLog;
import com.runejourney.service.PublicSnapshot;
import com.runejourney.service.TestServices;
import com.runejourney.sync.Envelope;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import net.runelite.api.Skill;
import net.runelite.client.hiscore.HiscoreResult;
import net.runelite.client.hiscore.HiscoreSkill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * PCs syncing through a pretend cloud, with real encryption and the real upload steps.
 */
public class SyncManagerTest
{
	private static final String ACCOUNT = "1234567890";

	private final Gson gson = new Gson();
	private final FakeCloudServer server = new FakeCloudServer();
	private final RuneJourneyConfig config = new RuneJourneyConfig()
	{
		@Override
		public boolean cloudSync()
		{
			return true;
		}

		@Override
		public String cloudServer()
		{
			return "https://cloud.test";
		}

		@Override
		public boolean chatAnnouncements()
		{
			return false;
		}

		@Override
		public boolean screenshotMilestoneLevels()
		{
			return false;
		}

		@Override
		public boolean encouragement()
		{
			return false;
		}
	};

	private class Pc
	{
		final MemoryFiles files;
		final JourneyService service;
		final SyncManager sync;
		int tick;

		Pc()
		{
			this(new MemoryFiles(), null, new TreeMap<>());
		}

		Pc(Hiscores.Lookup hiscores)
		{
			this(new MemoryFiles(), null, new TreeMap<>(), hiscores);
		}

		Pc(MemoryFiles files, ProfileData profile, TreeMap<String, DayRecord> days)
		{
			this(files, profile, days, (name, endpoint) -> CompletableFuture.completedFuture(null));
		}

		Pc(MemoryFiles files, ProfileData profile, TreeMap<String, DayRecord> days, Hiscores.Lookup hiscores)
		{
			this.files = files;
			service = TestServices.journey(config, gson);
			service.install(ACCOUNT, new JourneyStore.Loaded(profile, days));
			sync = new SyncManager(service, server, files, config, gson, true, hiscores);
			// Saving clears what's waiting to be saved, as it would on disk
			sync.setSaver(service::collectWrites);
			sync.start(Runnable::run);
		}

		Pc connected()
		{
			sync.connect(FakeCloudServer.KEY);
			assertEquals(CloudStatus.Connection.CONNECTED, sync.status().getConnection());
			return this;
		}

		/**
		 * Logs in: cloud sync runs (here, straight away) before XP is counted.
		 */
		void login(long agility)
		{
			files.tryLock(ACCOUNT, CloudFiles.SESSION);
			sync.onLogin(ACCOUNT);
			assertFalse(sync.isHolding(ACCOUNT));
			service.setBaseline(Collections.singletonMap(Skill.AGILITY.name(), agility), ++tick);
		}

		void train(long agility)
		{
			service.onXp(Skill.AGILITY, agility, ++tick);
			service.onTick(tick);
		}

		void sync()
		{
			// As the timer does; "Sync now" is {@link SyncManager#syncNow}
			sync.cycle();
		}

		CloudStatus status()
		{
			return sync.status();
		}

		long agilityToday()
		{
			List<DayRecord> days = service.daysBetween(LocalDate.now(), LocalDate.now());
			return days.isEmpty() ? 0 : days.get(0).getSkillXp().getOrDefault(Skill.AGILITY.name(), 0L);
		}
	}

	/**
	 * An account saved to the cloud from one PC, and found again on another.
	 */
	private Pc[] twoPcs()
	{
		Pc a = new Pc().connected();
		a.login(1_000_000);
		a.sync.consent(true);
		a.train(1_050_000);
		a.sync();

		Pc b = new Pc().connected();
		b.login(1_050_000);
		b.sync.consent(true);
		return new Pc[]{a, b};
	}

	@Test
	public void asksBeforeSavingAnAccount()
	{
		Pc a = new Pc().connected();
		a.login(1_000_000);
		assertEquals(CloudStatus.Prompt.CONSENT, a.status().getPrompt());
		assertFalse(a.status().isSavedElsewhere());
		assertEquals(0, server.count("journey"));

		a.sync.consent(true);
		assertTrue(server.count("journey") > 0);
		assertEquals(CloudStatus.Prompt.NONE, a.status().getPrompt());
	}

	@Test
	public void notOnThisPcUploadsNothing()
	{
		Pc a = new Pc().connected();
		a.login(1_000_000);
		a.sync.consent(false);
		a.train(1_100_000);
		a.sync();
		assertEquals(0, server.count("journey"));
	}

	@Test
	public void anotherPcSeesTheJourney()
	{
		Pc[] pcs = twoPcs();
		assertTrue(pcs[1].status().isLinked());
		assertEquals(50_000, pcs[1].agilityToday());

		pcs[1].train(1_070_000);
		pcs[1].sync();
		pcs[0].sync();
		assertEquals(70_000, pcs[0].agilityToday());
		assertEquals(70_000, pcs[1].agilityToday());
	}

	/**
	 * B was offline while A played. Logging in on B fetches A's records first, so A's XP isn't
	 * counted again as gained while away.
	 */
	@Test
	public void loginFetchesOtherPcsRecordsBeforeCountingXp()
	{
		Pc[] pcs = twoPcs();
		Pc a = pcs[0];
		Pc b = pcs[1];
		b.service.resetSessionState();
		a.service.resetSessionState();
		a.login(1_050_000);
		a.train(1_200_000);
		a.sync();

		b.login(1_200_000);
		assertEquals(0, b.service.daysBetween(LocalDate.now(), LocalDate.now()).get(0).getOfflineXp());
		assertEquals(200_000, b.agilityToday());
	}

	@Test
	public void aLostReplyIsResentNotRepeated()
	{
		Pc[] pcs = twoPcs();
		Pc a = pcs[0];
		server.loseReplies = 1;
		a.train(1_080_000);
		a.sync();
		assertNotNull(a.status().getProblem());

		a.sync();
		assertNull(a.status().getProblem());
		assertEquals(2, server.devices.size());
		pcs[1].sync();
		assertEquals(80_000, pcs[1].agilityToday());
	}

	@Test
	public void aRevokedKeyStopsSyncingButKeepsTheJourney()
	{
		Pc a = twoPcs()[0];
		server.revoked = true;
		a.train(1_090_000);
		a.sync();
		assertEquals(CloudStatus.Connection.KEY_REJECTED, a.status().getConnection());
		assertEquals(90_000, a.agilityToday());
	}

	/**
	 * A RuneLite folder copied to another PC: both carry on, and the copy becomes a new device
	 * without anything being counted twice.
	 */
	@Test
	public void aCopiedFolderBecomesANewDevice()
	{
		Pc a = new Pc().connected();
		a.login(1_000_000);
		a.sync.consent(true);
		a.train(1_050_000);
		a.sync();

		JourneyStore.Loaded saved = TestServices.saved(a.service);
		Pc copy = new Pc(a.files.copy(), saved.getProfile(), saved.getDays());
		a.train(1_060_000);
		a.sync();

		copy.service.resetSessionState();
		copy.login(1_060_000);
		copy.train(1_075_000);
		copy.sync();
		copy.sync();
		a.sync();

		assertEquals(2, server.devices.size());
		assertEquals(75_000, a.agilityToday());
		assertEquals(75_000, copy.agilityToday());
	}

	@Test
	public void useTheCloudJourneyBacksUpThisPcsFirst()
	{
		Pc a = new Pc().connected();
		a.login(1_000_000);
		a.sync.consent(true);
		a.train(1_020_000);
		a.sync();

		TreeMap<String, DayRecord> old = new TreeMap<>();
		DayRecord earlier = new DayRecord("2026-01-01");
		earlier.setPlayMillis(3_600_000);
		old.put(earlier.getDate(), earlier);
		Pc b = new Pc(new MemoryFiles(), null, old).connected();
		b.login(1_020_000);
		assertTrue(b.status().isSavedElsewhere());
		b.sync.consent(true);
		assertEquals(CloudStatus.Prompt.CHOOSE, b.status().getPrompt());

		b.sync.choose(true);
		assertEquals(1, b.files.backups());
		assertTrue(b.service.daysBetween(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-01")).isEmpty());
		assertEquals(20_000, b.agilityToday());
	}

	@Test
	public void nothingReadableIsUploaded()
	{
		Pc[] pcs = twoPcs();
		pcs[0].sync();
		for (byte[] file : server.storedBytes())
		{
			String text = new String(file, StandardCharsets.ISO_8859_1);
			assertFalse(text.contains("AGILITY"));
			assertFalse(text.contains("skillXp"));
			assertFalse(text.contains("level-99"));
		}
	}

	@Test
	public void aNewerVersionsDataStopsSyncing()
	{
		Pc[] pcs = twoPcs();
		server.profiles.values().forEach(p -> p.objects.values().forEach(o ->
		{
			if ("journey".equals(o.kind))
			{
				o.schema = "2.0";
				o.cursor = ++p.cursor;
			}
		}));
		pcs[1].sync();
		assertTrue(pcs[1].status().isUpdateNeeded());
		assertTrue(pcs[1].status().getProblem().contains("Update RuneJourney"));
	}

	/**
	 * Two RuneLite windows on one PC take turns playing an account. The second window must use the
	 * first one's latest files, not what it saw before, or it would think the folder was copied.
	 */
	@Test
	public void twoWindowsOnOnePcTakeTurnsWithoutForking()
	{
		Pc a = new Pc().connected();
		a.login(1_000_000);
		a.sync.consent(true);
		a.train(1_050_000);
		a.sync();

		// Another window starts while A plays; it can't take the account
		Pc b = new Pc(a.files.window(), null, new TreeMap<>());
		b.sync();
		a.train(1_080_000);
		a.sync();

		// A logs out; B logs in to the same account, reading the files A saved
		a.service.resetSessionState();
		a.sync.onLogout(ACCOUNT);
		a.files.unlock(ACCOUNT, CloudFiles.SESSION);
		b.service.install(ACCOUNT, TestServices.saved(a.service));
		b.login(1_080_000);
		b.train(1_100_000);
		b.sync();

		// Then back to A, which last saw the account before B played it
		b.service.resetSessionState();
		b.sync.onLogout(ACCOUNT);
		b.files.unlock(ACCOUNT, CloudFiles.SESSION);
		a.service.install(ACCOUNT, TestServices.saved(b.service));
		a.login(1_100_000);
		a.train(1_120_000);
		a.sync();

		assertEquals(1, server.devices.size());
		assertNull(a.status().getProblem());
		assertNull(b.status().getProblem());
		Pc c = new Pc().connected();
		c.login(1_120_000);
		c.sync.consent(true);
		assertEquals(120_000, c.agilityToday());
	}

	/**
	 * profile.json was lost. Its copy comes back from the cloud, even if the first try fails, and
	 * the blank profile is never uploaded over it.
	 */
	@Test
	public void aLostProfileIsRestoredEvenIfTheFirstTryFails()
	{
		Pc a = new Pc().connected();
		a.login(1_000_000);
		a.sync.consent(true);
		a.service.onKillCount("Zulrah", 47, ++a.tick);
		a.sync();

		JourneyStore.Loaded saved = TestServices.saved(a.service);
		a.service.install(ACCOUNT, new JourneyStore.Loaded(null, saved.getDays()));
		server.failChanges = 1;
		a.login(1_000_000);
		a.train(1_001_000);
		assertNull(a.service.counterValues().get(Counters.kc("Zulrah")));

		a.sync();
		assertEquals(47L, (long) a.service.counterValues().get(Counters.kc("Zulrah")));
		Pc other = new Pc().connected();
		other.login(1_001_000);
		other.sync.consent(true);
		assertEquals(47L, (long) other.service.counterValues().get(Counters.kc("Zulrah")));
	}

	/**
	 * The player deleted the account's cloud data on the website, then saved it again: everything
	 * on this PC goes up again, not just what changed since.
	 */
	@Test
	public void savingAgainAfterDeletingUploadsEverything()
	{
		TreeMap<String, DayRecord> old = new TreeMap<>();
		DayRecord earlier = new DayRecord("2026-01-01");
		earlier.setPlayMillis(3_600_000);
		old.put(earlier.getDate(), earlier);
		Pc a = new Pc(new MemoryFiles(), null, old).connected();
		a.login(1_000_000);
		a.sync.consent(true);
		assertTrue(server.hasDoc("day:2026-01-01"));

		server.profiles.clear();
		a.train(1_010_000);
		a.sync();
		assertEquals(CloudStatus.Prompt.CONSENT, a.status().getPrompt());

		a.sync.consent(true);
		assertTrue(server.hasDoc("day:2026-01-01"));
		assertTrue(server.hasDoc(Envelope.PROFILE));
	}

	/**
	 * Only a developer can point the plugin at another server; everyone else always talks to
	 * runejourney.org, whatever is saved in their settings.
	 */
	@Test
	public void theServerCanOnlyBeChangedInDeveloperMode()
	{
		JourneyService service = TestServices.journey(config, gson);
		assertEquals("https://runejourney.org", new SyncManager(service, server, new MemoryFiles(), config, gson, false).server());
		assertEquals("https://cloud.test", new SyncManager(service, server, new MemoryFiles(), config, gson, true).server());
	}

	@Test
	public void aPcIsNamedAfterItsKey()
	{
		Pc a = new Pc().connected();
		assertEquals(FakeCloudServer.KEY_NAME, a.status().getDeviceName());
	}

	/**
	 * A public account's page is published after a sync, only when something on it changed, and
	 * while playing only every so often.
	 */
	@Test
	public void aPublicPageIsPublishedWhenItChanges()
	{
		Pc a = new Pc().connected();
		a.service.updatePlayerName("Zezima");
		a.login(1_000_000);
		a.sync.consent(true);
		a.train(1_050_000);
		server.pub().setEnabled(true);
		a.sync();

		PublicSnapshot page = server.page();
		assertNotNull(page);
		assertEquals("Zezima", page.getName());
		assertEquals("main", page.getWorld());
		assertNotNull(page.getGeneratedAt());
		assertEquals(1_050_000L, (long) page.getSkills().get("AGILITY"));
		assertNull("Net worth is off by default", page.getWealth());
		assertEquals("https://cloud.test/journeys/zezima", a.status().getPublicUrl());
		assertEquals(1, server.publishAttempts);

		// Nothing changed: not sent again
		a.sync();
		assertEquals(1, server.publishAttempts);

		// Playing changes it, but it waits a while; logging out sends it
		a.train(1_060_000);
		a.sync();
		assertEquals(1, server.publishAttempts);
		a.service.resetSessionState();
		a.sync.onLogout(ACCOUNT);
		assertEquals(2, server.publishAttempts);
		assertEquals(1_060_000L, (long) server.page().getSkills().get("AGILITY"));
	}

	/**
	 * What sync did goes in plugin-data/runejourney/cloud/sync.log, for working out a problem.
	 */
	@Test
	public void theSyncLogSaysWhatHappened()
	{
		Pc a = new Pc().connected();
		a.service.updatePlayerName("Zezima");
		a.login(1_000_000);
		a.sync.consent(true);
		server.pub().setEnabled(true);
		a.sync();

		String log = a.files.syncLog.toString();
		assertTrue(log, log.contains("sync started for " + ACCOUNT + " (Zezima)"));
		assertTrue(log, log.contains("public page published"));
		assertTrue(log, log.contains("sync finished for " + ACCOUNT));
		assertFalse("Never the key", log.contains(FakeCloudServer.KEY));

		server.revoked = true;
		a.train(1_010_000);
		a.sync();
		assertTrue(a.files.syncLog.toString().contains("cloud sync failed"));
	}

	/**
	 * "Sync now" sends the page straight away, however recently it went.
	 */
	@Test
	public void syncNowPublishesThePageStraightAway()
	{
		Pc a = new Pc().connected();
		a.service.updatePlayerName("Zezima");
		a.login(1_000_000);
		a.sync.consent(true);
		server.pub().setEnabled(true);
		a.sync();
		assertEquals(1, server.publishAttempts);

		a.train(1_060_000);
		a.sync();
		assertEquals("Waits while playing", 1, server.publishAttempts);

		a.sync.syncNow();
		assertEquals(2, server.publishAttempts);
		assertEquals(1_060_000L, (long) server.page().getSkills().get("AGILITY"));

		a.sync();
		assertEquals("Only that once", 2, server.publishAttempts);
	}

	/**
	 * Notes and memories are the player's own words, so they stay off the page unless chosen.
	 */
	@Test
	public void notesAndMemoriesOnlyShowWhenChosen()
	{
		Pc a = new Pc().connected();
		a.service.updatePlayerName("Zezima");
		a.login(1_000_000);
		a.sync.consent(true);
		a.service.onDeath(++a.tick);
		String death = a.service.daysBetween(LocalDate.now(), LocalDate.now()).get(0).getEvents().get(0).getId();
		a.service.setEventNote(LocalDate.now().toString(), death, "Forgot to pray");
		JourneyEvent memory = new JourneyEvent(System.currentTimeMillis() - 5_000, EventType.NOTE, "First fire cape", null, null, null, true, 0);
		a.service.addMemories(LocalDate.now(), new ArrayList<>(Collections.singletonList(memory)), Collections.emptyList());
		server.pub().setEnabled(true);
		a.sync();

		List<PublicSnapshot.Event> timeline = server.page().getTimeline();
		assertTrue(timeline.stream().noneMatch(e -> "First fire cape".equals(e.getTitle())));
		assertTrue(timeline.stream().allMatch(e -> e.getNote() == null));

		// Shown once the player chooses to, on the website
		server.pub().getSections().add("notes");
		a.sync();
		timeline = server.page().getTimeline();
		assertTrue(timeline.stream().anyMatch(e -> "First fire cape".equals(e.getTitle()) && Boolean.TRUE.equals(e.getMemory())));
		assertTrue(timeline.stream().anyMatch(e -> "Forgot to pray".equals(e.getNote())));
	}

	@Test
	public void aTakenNameIsShownAndNotRetriedUntilSomethingChanges()
	{
		Pc a = new Pc().connected();
		a.service.updatePlayerName("Zezima");
		a.login(1_000_000);
		a.sync.consent(true);
		server.pub().setEnabled(true);
		server.nameTaken = true;
		a.sync();
		assertTrue(a.status().getPublicProblem().contains("Zezima"));
		assertNull(a.status().getProblem());

		a.sync();
		assertEquals(1, server.publishAttempts);
	}

	@Test
	public void aPageCanBeMadePublicAndPrivateFromThePanel()
	{
		Pc a = new Pc().connected();
		a.service.updatePlayerName("Zezima");
		a.login(1_000_000);
		a.sync.consent(true);
		assertFalse(a.status().isPublicEnabled());

		a.sync.setPublic(true, null);
		assertTrue(a.status().isPublicEnabled());
		assertNotNull(server.page());
		assertEquals("https://cloud.test/journeys/zezima", a.status().getPublicUrl());

		a.sync.setPublic(null, true);
		assertTrue(server.pub().isSearchable());

		a.sync.setPublic(false, null);
		assertFalse(a.status().isPublicEnabled());
		assertNull(server.page());
	}


	/**
	 * The character is sent once per look while the page shows it, and not captured otherwise.
	 */
	@Test
	public void aPublicPageShowsTheCharacterInEachNewLook()
	{
		Pc a = new Pc().connected();
		a.service.updatePlayerName("Zezima");
		a.login(1_000_000);
		a.sync.consent(true);
		assertFalse("Not public", a.sync.wantsCharacter(ACCOUNT, "red"));

		server.pub().setEnabled(true);
		a.sync();
		assertTrue(a.sync.wantsCharacter(ACCOUNT, "red"));
		a.sync.setCharacter(ACCOUNT, "red", character());
		assertFalse("Waiting to be sent", a.sync.wantsCharacter(ACCOUNT, "red"));
		a.sync();
		assertEquals(1, server.characterUploads);
		assertEquals("red", server.pub().getCharacter());
		byte[] model = CharacterModelTest.gunzip(server.profiles.values().iterator().next().character);
		assertEquals("RJM1", new String(model, 0, 4, StandardCharsets.US_ASCII));

		// Already on the page
		a.sync();
		assertEquals(1, server.characterUploads);
		assertFalse(a.sync.wantsCharacter(ACCOUNT, "red"));
		assertTrue(a.sync.wantsCharacter(ACCOUNT, "blue"));

		// Another PC showed a different look: this one's is wanted again
		server.pub().setCharacter("green");
		a.sync();
		assertTrue(a.sync.wantsCharacter(ACCOUNT, "red"));

		// Hidden on the website
		server.pub().getSections().remove("character");
		server.pub().setCharacter(null);
		a.sync();
		assertFalse(a.sync.wantsCharacter(ACCOUNT, "blue"));
		assertFalse(a.sync.wantsCharacter("another account", "blue"));
	}

	@Test
	public void aRefusedCharacterIsNotSentAgain()
	{
		Pc a = new Pc().connected();
		a.service.updatePlayerName("Zezima");
		a.login(1_000_000);
		a.sync.consent(true);
		server.pub().setEnabled(true);
		server.refuseCharacter = true;
		a.sync();
		a.sync.setCharacter(ACCOUNT, "red", character());
		a.sync();
		assertEquals(1, server.characterUploads);
		assertNull(a.status().getProblem());

		a.sync();
		assertEquals(1, server.characterUploads);
		assertFalse(a.sync.wantsCharacter(ACCOUNT, "red"));
		assertTrue(a.sync.wantsCharacter(ACCOUNT, "blue"));
	}

	@Test
	public void theCharacterWaitsForThePage()
	{
		Pc a = new Pc().connected();
		a.service.updatePlayerName("Zezima");
		a.login(1_000_000);
		a.sync.consent(true);
		server.pub().setEnabled(true);
		server.nameTaken = true;
		a.sync();
		a.sync.setCharacter(ACCOUNT, "red", character());
		a.sync();
		assertEquals("Not sent while the page can't be published", 0, server.characterUploads);

		server.nameTaken = false;
		a.service.updatePlayerName("Zezima Two");
		a.sync.onLogout(ACCOUNT);
		assertEquals(1, server.characterUploads);
		assertEquals("red", server.pub().getCharacter());
	}

	/**
	 * The whole collection log goes to the page once it's been synced from the game, and again only
	 * when it changes.
	 */
	@Test
	public void theCollectionLogIsPublishedWhenItChanges()
	{
		Pc a = new Pc().connected();
		a.service.updatePlayerName("Zezima");
		a.login(1_000_000);
		a.sync.consent(true);
		server.pub().setEnabled(true);
		a.sync();
		assertEquals("Not synced from the game yet", 0, server.logUploads);

		a.service.onCollectionLog(clogTabs(), clogPages(false));
		a.sync();
		assertEquals(1, server.logUploads);
		PublicCollectionLog log = server.profiles.values().iterator().next().log;
		assertEquals("Bosses", log.getTabs().get(0).getName());
		assertEquals("Vardorvis", log.getTabs().get(0).getPages().get(0).getName());
		assertEquals(28_285, log.getTabs().get(0).getPages().get(0).getItems().get(0)[0]);
		assertEquals(2, log.getTabs().get(0).getPages().get(0).getItems().get(0)[1]);
		assertEquals(0, log.getTabs().get(0).getPages().get(0).getItems().get(1)[1]);
		assertEquals("Ultor vestige", log.getItems().get("28285"));
		assertNotNull(server.pub().getCollectionLog());

		a.sync();
		assertEquals("Unchanged", 1, server.logUploads);

		a.service.onCollectionLog(clogTabs(), clogPages(true));
		a.sync();
		assertEquals(2, server.logUploads);

		// Hidden on the website
		server.pub().getSections().remove("collection");
		server.pub().setCollectionLog(null);
		a.sync();
		assertEquals(2, server.logUploads);
	}

	/**
	 * Quests and combat tasks go to the page once they've been read from the game, and again only
	 * when they change: not each time they're read, which is every login.
	 */
	@Test
	public void questsAndCombatTasksArePublishedWhenTheyChange()
	{
		Pc a = new Pc().connected();
		a.service.updatePlayerName("Zezima");
		a.login(1_000_000);
		a.sync.consent(true);
		server.pub().setEnabled(true);
		a.sync();
		assertEquals("Not read from the game yet", 0, server.achievementUploads);

		a.service.onAchievements(quests(PublicAchievements.Quest.IN_PROGRESS), combatTasks(false));
		a.sync();
		assertEquals(1, server.achievementUploads);
		PublicAchievements sent = server.profiles.values().iterator().next().achievements;
		assertEquals("Cook's Assistant", sent.getQuests().get(0).getName());
		assertEquals("in_progress", sent.getQuests().get(0).getState());
		assertEquals("Noxious Foe", sent.getCombatTasks().get(0).getName());
		assertFalse(sent.getCombatTasks().get(0).isDone());
		assertNotNull(sent.getSyncedAt());
		assertNotNull(server.pub().getAchievements());

		// Read again, and back as they were by the next sync: the page already shows them
		a.service.onAchievements(quests(PublicAchievements.Quest.FINISHED), combatTasks(true));
		a.service.onAchievements(quests(PublicAchievements.Quest.IN_PROGRESS), combatTasks(false));
		a.sync();
		assertEquals("Unchanged", 1, server.achievementUploads);

		a.service.onAchievements(quests(PublicAchievements.Quest.FINISHED), combatTasks(true));
		a.sync();
		assertEquals(2, server.achievementUploads);
		sent = server.profiles.values().iterator().next().achievements;
		assertEquals("finished", sent.getQuests().get(0).getState());
		assertTrue(sent.getCombatTasks().get(0).isDone());

		// Hidden on the website
		server.pub().getSections().remove("collection");
		server.pub().setAchievements(null);
		a.service.onAchievements(quests(PublicAchievements.Quest.NOT_STARTED), combatTasks(false));
		a.sync();
		assertEquals(2, server.achievementUploads);
	}

	@Test
	public void theHiscoresFillInBossKillsOnce()
	{
		int[] lookups = {0};
		Pc a = new Pc((name, endpoint) ->
		{
			lookups[0]++;
			return CompletableFuture.completedFuture(HiscoresTest.result());
		}).connected();
		a.service.updatePlayerName("Zezima");
		a.login(1_000_000);
		a.sync.consent(true);
		server.pub().setEnabled(true);
		a.sync();

		assertEquals(1, lookups[0]);
		assertEquals(1_453, (int) server.page().getKills().getBosses().get(HiscoreSkill.VARDORVIS.getName()));
		assertEquals(26, (int) server.page().getKills().getClues().get("Elite"));
		assertEquals(640, (int) server.page().getCollection().getCollectionLog());

		a.sync();
		assertEquals("Looked up every few hours", 1, lookups[0]);
	}

	@Test
	public void aFailedHiscoresLookupNeverStopsThePage()
	{
		Pc a = new Pc((name, endpoint) ->
		{
			CompletableFuture<HiscoreResult> f = new CompletableFuture<>();
			f.completeExceptionally(new java.io.IOException("hiscores down"));
			return f;
		}).connected();
		a.service.updatePlayerName("Zezima");
		a.login(1_000_000);
		a.sync.consent(true);
		server.pub().setEnabled(true);
		a.sync();

		assertNotNull(server.page());
		assertNull(a.status().getProblem());
	}

	private static List<PublicAchievements.Quest> quests(String state)
	{
		return Arrays.asList(
			new PublicAchievements.Quest("Cook's Assistant", state, 1, false, false, null),
			new PublicAchievements.Quest("Alfred Grimhand's Barcrawl", PublicAchievements.Quest.NOT_STARTED, 0, true, true, null));
	}

	private static List<PublicAchievements.CombatTask> combatTasks(boolean done)
	{
		return Collections.singletonList(new PublicAchievements.CombatTask(0, "Noxious Foe", "Kill an Aberrant Spectre.", 1, "Kill Count", "Aberrant Spectre", done));
	}

	private static Map<String, List<String>> clogTabs()
	{
		Map<String, List<String>> tabs = new LinkedHashMap<>();
		tabs.put("Bosses", Collections.singletonList("Vardorvis"));
		return tabs;
	}

	private static Map<String, List<ClogItem>> clogPages(boolean axe)
	{
		Map<String, List<ClogItem>> pages = new LinkedHashMap<>();
		pages.put("Vardorvis", Arrays.asList(new ClogItem(28_285, "Ultor vestige", true, 2), new ClogItem(28_319, "Executioner's axe head", axe, axe ? 1 : 0)));
		return pages;
	}

	private static CharacterModel character()
	{
		return CharacterModel.of(3, new float[]{0, 64, 0}, new float[]{0, 0, -200}, new float[]{0, 0, 0},
			1, new int[]{0}, new int[]{1}, new int[]{2}, new int[]{960}, new int[]{960}, new int[]{-1}, null, null);
	}
}
