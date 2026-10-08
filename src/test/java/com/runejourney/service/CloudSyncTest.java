package com.runejourney.service;

import com.google.gson.Gson;
import com.runejourney.RuneJourneyConfig;
import com.runejourney.model.DayRecord;
import com.runejourney.model.DaySlice;
import com.runejourney.model.EventType;
import com.runejourney.model.Goal;
import com.runejourney.model.GoalType;
import com.runejourney.model.JourneyEvent;
import com.runejourney.model.ProfileData;
import com.runejourney.model.ProfileSlice;
import com.runejourney.planner.BossData;
import com.runejourney.planner.Skills;
import com.runejourney.planner.TrainingMethods;
import com.runejourney.sync.Envelope;
import com.runejourney.sync.Hlc;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Several PCs syncing one account through a pretend cloud that keeps each PC's latest documents.
 */
public class CloudSyncTest
{
	private static final String KEY = "acct";
	private static final String TODAY = LocalDate.now().toString();

	private final Gson gson = new Gson();
	private final RuneJourneyConfig config = new RuneJourneyConfig()
	{
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
		public boolean screenshotGoals()
		{
			return false;
		}

		@Override
		public boolean encouragement()
		{
			return false;
		}
	};
	/**
	 * Device ID to its latest documents.
	 */
	private final Map<String, Map<String, String>> cloud = new HashMap<>();

	private class Pc
	{
		final String id;
		final JourneyService service;
		final Hlc hlc = new Hlc(0);
		int tick;

		Pc(String id, ProfileData profile, TreeMap<String, DayRecord> days)
		{
			this.id = id;
			service = new JourneyService(null, null, config, gson, null, new TrainingMethods(gson), new BossData(gson));
			service.install(KEY, new JourneyStore.Loaded(profile, days));
		}

		Pc(String id)
		{
			this(id, null, new TreeMap<>());
		}

		int gen()
		{
			return service.getGeneration();
		}

		void link(boolean first)
		{
			assertTrue(service.link(KEY, gen(), id, hlc, first));
		}

		void upload()
		{
			for (JourneyService.SyncDoc doc : service.exportOwn(KEY, gen(), id, hlc))
			{
				cloud.computeIfAbsent(id, k -> new HashMap<>()).put(doc.getDocKey(), doc.getJson());
			}
		}

		void pull()
		{
			Map<String, ProfileSlice> profiles = new HashMap<>();
			Map<String, Map<String, DaySlice>> days = new HashMap<>();
			cloud.forEach((device, docs) ->
			{
				if (device.equals(id))
				{
					return;
				}
				docs.forEach((docKey, json) ->
				{
					Envelope.Doc doc = gson.fromJson(json, Envelope.Doc.class);
					if (doc.getProfile() != null)
					{
						profiles.put(device, doc.getProfile());
					}
					else
					{
						days.computeIfAbsent(Envelope.dateOf(docKey), k -> new HashMap<>()).put(device, doc.getDay());
					}
				});
			});
			service.applyRemote(KEY, gen(), id, hlc, profiles, days, false);
		}

		void login(Skill skill, long xp)
		{
			service.setBaseline(Collections.singletonMap(skill.name(), xp), ++tick);
		}

		void train(Skill skill, long xp)
		{
			service.onXp(skill, xp, ++tick);
			service.onTick(tick);
		}

		void logout()
		{
			service.resetSessionState();
		}

		DayRecord today()
		{
			List<DayRecord> days = service.daysBetween(LocalDate.now(), LocalDate.now());
			return days.isEmpty() ? new DayRecord(TODAY) : days.get(0);
		}

		String journey()
		{
			List<DayRecord> days = service.daysBetween(LocalDate.parse("2000-01-01"), LocalDate.now());
			for (DayRecord d : days)
			{
				// Ranges are compared as XP totals below; the order of a skill's list doesn't matter
				d.setXpRanges(null);
				d.setOfflineRanges(null);
				d.setAwayRanges(null);
			}
			return gson.toJson(days);
		}
	}

	private static void assertNoDuplicateEvents(Pc pc)
	{
		Set<String> ids = new HashSet<>();
		for (DayRecord d : pc.service.daysBetween(LocalDate.parse("2000-01-01"), LocalDate.now()))
		{
			for (JourneyEvent e : d.getEvents())
			{
				assertTrue("Duplicate event " + e.getId(), ids.add(e.getId()));
			}
		}
	}

	private static ProfileData lastSeen(Skill skill, long xp)
	{
		ProfileData p = new ProfileData();
		p.getLastXp().put(skill.name(), xp);
		p.setLastXpAt(System.currentTimeMillis());
		return p;
	}

	private static long xp(DayRecord d, Skill skill)
	{
		return d.getSkillXp().getOrDefault(skill.name(), 0L);
	}

	@Test
	public void twoSessionsOnTheSameDay()
	{
		Pc a = new Pc("a", lastSeen(Skill.AGILITY, 12_000_000), new TreeMap<>());
		a.link(true);
		a.login(Skill.AGILITY, 12_000_000);
		a.train(Skill.AGILITY, 12_250_000);
		a.logout();
		a.upload();

		// B last saw the account before A played, and pulls before counting XP at login
		Pc b = new Pc("b", lastSeen(Skill.AGILITY, 12_000_000), new TreeMap<>());
		b.link(false);
		b.pull();
		b.login(Skill.AGILITY, 12_250_000);
		b.train(Skill.AGILITY, 12_350_000);
		b.upload();
		a.pull();

		assertEquals(350_000, xp(a.today(), Skill.AGILITY));
		assertEquals(0, a.today().getOfflineXp());
		assertEquals(a.journey(), b.journey());
		assertEquals(2 * 600, a.today().getPlayMillis());
	}

	/**
	 * B's pull timed out, so its login found XP that A had recorded while playing. Once B sees A's
	 * records the XP is only counted once, on both PCs.
	 */
	@Test
	public void xpFoundAtLoginIsNotCountedTwice()
	{
		long start = Skills.xpForLevel(70) - 5_000;
		Pc a = new Pc("a", lastSeen(Skill.MINING, start), new TreeMap<>());
		a.link(true);
		a.login(Skill.MINING, start);
		a.train(Skill.MINING, start + 20_000);
		a.logout();

		Pc b = new Pc("b", lastSeen(Skill.MINING, start), new TreeMap<>());
		b.link(false);
		b.login(Skill.MINING, start + 20_000);
		assertEquals(20_000, b.today().getOfflineXp());
		b.train(Skill.MINING, start + 21_000);

		a.upload();
		b.upload();
		b.pull();
		a.pull();
		b.upload();
		a.pull();

		for (Pc pc : new Pc[]{a, b})
		{
			assertEquals(21_000, xp(pc.today(), Skill.MINING));
			assertEquals(21_000, pc.today().getXpGained());
			assertEquals(0, pc.today().getOfflineXp());
			assertEquals(1, pc.today().getLevelsGained());
			assertEquals(1, pc.today().getEvents().stream().filter(e -> e.getType() == EventType.LEVEL).count());
			assertNoDuplicateEvents(pc);
		}
		assertEquals(a.journey(), b.journey());
	}

	@Test
	public void goalRenamedOnOnePcAndCompletedOnAnother()
	{
		Pc a = new Pc("a", lastSeen(Skill.ATTACK, 1_000_000), new TreeMap<>());
		a.link(true);
		a.login(Skill.ATTACK, 1_000_000);
		Goal g = new Goal();
		g.setType(GoalType.CUSTOM);
		g.setName("Fire cape");
		assertEquals(null, a.service.createGoal(g));
		String id = a.service.goalProgress().get(0).getGoal().getId();
		a.upload();

		Pc b = new Pc("b");
		b.link(false);
		b.pull();
		assertEquals("Fire cape", b.service.goal(id).getName());

		Goal edit = a.service.goal(id);
		edit.setName("Infernal cape");
		assertEquals(null, a.service.updateGoal(edit));
		b.service.completeGoalManually(id);

		a.upload();
		b.upload();
		a.pull();
		b.pull();
		a.upload();
		b.upload();
		a.pull();
		b.pull();

		for (Pc pc : new Pc[]{a, b})
		{
			Goal done = pc.service.goal(id);
			assertEquals("Infernal cape", done.getName());
			assertTrue(done.isComplete());
			assertNoDuplicateEvents(pc);
		}
		assertEquals(a.journey(), b.journey());
	}

	@Test
	public void aDeleteBeatsANoteWrittenElsewhere()
	{
		Pc a = new Pc("a");
		a.link(true);
		a.service.onDeath(1);
		a.upload();
		Pc b = new Pc("b");
		b.link(false);
		b.pull();
		String id = b.today().getEvents().get(0).getId();

		a.service.deleteEvent(TODAY, id);
		b.service.setEventNote(TODAY, id, "Forgot to pray");
		a.upload();
		b.upload();
		a.pull();
		b.pull();

		assertTrue(a.today().getEvents().isEmpty());
		assertTrue(b.today().getEvents().isEmpty());
	}

	/**
	 * A copied RuneLite folder carries on under a new device ID; what both folders recorded before
	 * the copy is only counted once.
	 */
	@Test
	public void aCopiedFolderIsNotCountedTwice()
	{
		Pc a = new Pc("a", lastSeen(Skill.FISHING, 500_000), new TreeMap<>());
		a.link(true);
		a.login(Skill.FISHING, 500_000);
		a.train(Skill.FISHING, 510_000);
		a.upload();

		// The folder is copied, then both carry on
		Pc c = copyOf(a, "c");
		a.train(Skill.FISHING, 515_000);
		a.upload();

		c.logout();
		assertTrue(c.service.forkDevice(KEY, c.gen(), "a"));
		c.pull();
		c.login(Skill.FISHING, 515_000);
		c.train(Skill.FISHING, 520_000);
		c.upload();
		a.pull();

		assertEquals(20_000, xp(a.today(), Skill.FISHING));
		assertEquals(20_000, xp(c.today(), Skill.FISHING));
		assertEquals(a.journey(), c.journey());
	}

	@Test
	public void useTheCloudJourneyReplacesThisPcs()
	{
		Pc a = new Pc("a", lastSeen(Skill.COOKING, 100_000), new TreeMap<>());
		a.link(true);
		a.login(Skill.COOKING, 100_000);
		a.train(Skill.COOKING, 140_000);
		a.upload();

		TreeMap<String, DayRecord> old = new TreeMap<>();
		DayRecord earlier = new DayRecord("2026-01-01");
		earlier.setPlayMillis(60_000);
		old.put(earlier.getDate(), earlier);
		Pc b = new Pc("b", lastSeen(Skill.COOKING, 90_000), old);
		assertTrue(b.service.hasHistory());
		assertTrue(b.service.replaceWithCloud(KEY, b.gen(), "b"));
		b.pull();

		assertEquals(a.journey(), b.journey());
	}

	/**
	 * Three PCs taking turns, exchanging documents in a random order, always end up with the same
	 * journey, with each XP drop counted once.
	 */
	@Test
	public void randomTurnsConverge()
	{
		Random random = new Random(7);
		int foundAtLogin = 0;
		for (int round = 0; round < 10; round++)
		{
			cloud.clear();
			List<Pc> pcs = new ArrayList<>();
			for (String id : new String[]{"a", "b", "c"})
			{
				pcs.add(new Pc(id, lastSeen(Skill.WOODCUTTING, 1_000_000), new TreeMap<>()));
			}
			pcs.get(0).link(true);
			pcs.get(1).link(false);
			pcs.get(2).link(false);

			long xp = 1_000_000;
			for (int turn = 0; turn < 8; turn++)
			{
				Pc pc = pcs.get(random.nextInt(3));
				// Usually pulls before logging in; sometimes the pull fails
				if (random.nextInt(4) != 0)
				{
					pc.pull();
				}
				long before = pc.today().getOfflineXp();
				pc.login(Skill.WOODCUTTING, xp);
				if (pc.today().getOfflineXp() > before)
				{
					foundAtLogin++;
				}
				for (int i = 0; i < 1 + random.nextInt(4); i++)
				{
					xp += 1 + random.nextInt(40_000);
					pc.train(Skill.WOODCUTTING, xp);
				}
				if (random.nextBoolean())
				{
					pc.service.onDeath(pc.tick);
				}
				pc.logout();
				if (random.nextInt(3) != 0)
				{
					pc.upload();
				}
			}
			// Everyone eventually catches up
			for (int sweep = 0; sweep < 3; sweep++)
			{
				List<Pc> order = new ArrayList<>(pcs);
				Collections.shuffle(order, random);
				for (Pc pc : order)
				{
					pc.upload();
					pc.pull();
				}
			}
			for (Pc pc : pcs)
			{
				pc.upload();
			}
			for (Pc pc : pcs)
			{
				pc.pull();
				assertEquals("round " + round, xp - 1_000_000, xp(pc.today(), Skill.WOODCUTTING));
				assertNoDuplicateEvents(pc);
			}
			assertEquals(pcs.get(0).journey(), pcs.get(1).journey());
			assertEquals(pcs.get(0).journey(), pcs.get(2).journey());
		}
		// Some logins found XP another PC had already recorded, which was then trimmed
		assertTrue(foundAtLogin > 0);
	}

	/**
	 * Another PC made from a copy of this one's saved files, as when a RuneLite folder is copied.
	 * It still has the original's device ID in its sync parts until it forks.
	 */
	private Pc copyOf(Pc pc, String id)
	{
		JourneyStore.Loaded saved = pc.service.saved();
		return new Pc(id, saved.getProfile(), saved.getDays());
	}
}
