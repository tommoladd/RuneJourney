package com.runejourney.sync;

import com.google.gson.Gson;
import com.runejourney.model.DayRecord;
import com.runejourney.model.DaySlice;
import com.runejourney.model.DaySync;
import com.runejourney.model.EventType;
import com.runejourney.model.ItemTotal;
import com.runejourney.model.JourneyEvent;
import com.runejourney.model.LootSource;
import com.runejourney.planner.Skills;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class DaySlicesTest
{
	private static final String DATE = "2026-10-02";
	private final Gson gson = Trees.withoutSync(new Gson());

	private static Hlc clock(long start)
	{
		return new Hlc(start, () -> 0);
	}

	private static JourneyEvent event(String id, long time, String title)
	{
		JourneyEvent e = new JourneyEvent(time, EventType.NOTE, title, null, null, null, false, 0);
		e.setId(id);
		return e;
	}

	private static DaySlice slice(DayRecord day)
	{
		DaySlice s = new DaySlice();
		s.setDay(day);
		return s;
	}

	private static DayRecord played(long millis, String skill, long from, long to)
	{
		DayRecord d = new DayRecord(DATE);
		d.setPlayMillis(millis);
		if (skill != null)
		{
			d.setXpGained(to - from);
			d.getSkillXp().put(skill, to - from);
			d.getXpRanges().put(skill, new ArrayList<>(Collections.singletonList(new long[]{from, to})));
		}
		return d;
	}

	/**
	 * Every field of a day must have a combining rule, or a new field would silently go missing
	 * (or be counted twice) between PCs.
	 */
	@Test
	public void everyFieldHasARule()
	{
		DayRecord d = new DayRecord(DATE);
		d.setAwayFrom(DATE);
		d.setSync(new DaySync());
		Set<String> keys = new Gson().toJsonTree(d).getAsJsonObject().keySet();
		Set<String> covered = new HashSet<>(DaySlices.ADDITIVE);
		covered.addAll(DaySlices.SPECIAL);
		assertEquals(keys, covered);
	}

	@Test
	public void countsAddUpAcrossPcs()
	{
		DayRecord a = played(60_000, "AGILITY", 1_000, 2_000);
		a.getBossKills().put("Vorkath", 3);
		a.getLootBySource().computeIfAbsent("Vorkath", k -> new LootSource()).getItems().put("Dragon bones", new ItemTotal(3, 9_000));
		DayRecord b = played(30_000, "AGILITY", 5_000, 5_500);
		b.getBossKills().put("Vorkath", 2);
		b.getLootBySource().computeIfAbsent("Vorkath", k -> new LootSource()).getItems().put("Dragon bones", new ItemTotal(2, 6_000));
		Map<String, DaySlice> slices = new HashMap<>();
		slices.put("a", slice(a));
		slices.put("b", slice(b));

		DayRecord d = DaySlices.combine(gson, DATE, slices);
		assertEquals(90_000, d.getPlayMillis());
		assertEquals(1_500, d.getXpGained());
		assertEquals(5, (int) d.getBossKills().get("Vorkath"));
		assertEquals(5, d.getLootBySource().get("Vorkath").getItems().get("Dragon bones").getQuantity());
		assertEquals(2, d.getXpRanges().get("AGILITY").size());
	}

	/**
	 * This PC's part is everything the other parts don't hold, and combining it back gives the day
	 * exactly, whatever was recorded.
	 */
	@Test
	public void ownPartRoundTrips()
	{
		Random random = new Random(42);
		for (int round = 0; round < 50; round++)
		{
			Map<String, DaySlice> remotes = new HashMap<>();
			long xp = 1_000_000;
			for (String device : new String[]{"b", "c"})
			{
				DayRecord r = played(random.nextInt(100_000), "MINING", xp, xp + random.nextInt(50_000) + 1);
				xp = r.getXpRanges().get("MINING").get(0)[1] + random.nextInt(1_000);
				r.getEvents().add(event(device + "-e", random.nextInt(1000), "From " + device));
				remotes.put(device, slice(r));
			}
			DayRecord view = DaySlices.combine(gson, DATE, remotes);
			// This PC plays on top
			view.setPlayMillis(view.getPlayMillis() + 5_000);
			view.getSkillXp().merge("MINING", 700L, Long::sum);
			view.setXpGained(view.getXpGained() + 700);
			view.getXpRanges().get("MINING").add(new long[]{xp, xp + 700});
			view.getEvents().add(event("a-e", 2_000, "Mine"));
			view.getEvents().get(0).setNote("A note on another PC's event");

			DaySlice own = DaySlices.reconcile(gson, view, "a", remotes, clock(0));
			assertEquals(5_000, own.getDay().getPlayMillis());
			assertEquals(700, own.getDay().getXpGained());
			assertEquals(1, own.getDay().getEvents().size());

			Map<String, DaySlice> all = new HashMap<>(remotes);
			all.put("a", own);
			assertTrue(DaySlices.matches(gson, view, all));
		}
	}

	@Test
	public void sameEventFromTwoPcsShowsOnce()
	{
		DayRecord a = new DayRecord(DATE);
		a.getEvents().add(event("level|ATTACK|80", 100, "Level 80 Attack"));
		DayRecord b = new DayRecord(DATE);
		b.getEvents().add(event("level|ATTACK|80", 500, "Level 80 Attack (gained while away)"));
		Map<String, DaySlice> slices = new HashMap<>();
		slices.put("a", slice(a));
		slices.put("b", slice(b));

		DayRecord d = DaySlices.combine(gson, DATE, slices);
		assertEquals(1, d.getEvents().size());
		assertEquals("Level 80 Attack", d.getEvents().get(0).getTitle());

		// Only the PC whose copy is shown keeps it
		assertEquals(1, DaySlices.reconcile(gson, d, "a", slices, clock(0)).getDay().getEvents().size());
		assertEquals(0, DaySlices.reconcile(gson, d, "b", slices, clock(0)).getDay().getEvents().size());
	}

	@Test
	public void deleteBeatsANewerNote()
	{
		DayRecord a = new DayRecord(DATE);
		a.getEvents().add(event("x", 100, "Drop"));
		Map<String, DaySlice> slices = new HashMap<>();
		slices.put("a", slice(a));
		slices.put("b", slice(new DayRecord(DATE)));
		DayRecord view = DaySlices.combine(gson, DATE, slices);

		// A deletes it; B, not having seen that, writes a note on it later
		DayRecord deletedOnA = DaySlices.combine(gson, DATE, slices);
		deletedOnA.getEvents().clear();
		DaySlice aNext = DaySlices.reconcile(gson, deletedOnA, "a", slices, clock(10));
		view.getEvents().get(0).setNote("Great drop");
		DaySlice bNext = DaySlices.reconcile(gson, view, "b", slices, clock(100));

		Map<String, DaySlice> after = new HashMap<>();
		after.put("a", aNext);
		after.put("b", bNext);
		assertTrue(DaySlices.combine(gson, DATE, after).getEvents().isEmpty());
	}

	@Test
	public void newestNoteWins()
	{
		DayRecord a = new DayRecord(DATE);
		a.getEvents().add(event("x", 100, "Drop"));
		Map<String, DaySlice> slices = new HashMap<>();
		slices.put("a", slice(a));
		slices.put("b", slice(new DayRecord(DATE)));

		DayRecord onA = DaySlices.combine(gson, DATE, slices);
		onA.getEvents().get(0).setNote("First");
		DaySlice aNext = DaySlices.reconcile(gson, onA, "a", slices, clock(10));
		DayRecord onB = DaySlices.combine(gson, DATE, slices);
		onB.getEvents().get(0).setNote("Second");
		DaySlice bNext = DaySlices.reconcile(gson, onB, "b", slices, clock(20));

		Map<String, DaySlice> after = new HashMap<>();
		after.put("a", aNext);
		after.put("b", bNext);
		assertEquals("Second", DaySlices.combine(gson, DATE, after).getEvents().get(0).getNote());
	}

	/**
	 * B's login found XP that A had already recorded while playing (A's upload hadn't arrived yet).
	 */
	@Test
	public void offlineXpAnotherPcRecordedIsTrimmed()
	{
		long from = Skills.xpForLevel(80) - 1_000;
		long to = Skills.xpForLevel(81) + 500;
		DayRecord a = played(600_000, "AGILITY", from, to);
		DayRecord b = new DayRecord(DATE);
		b.setXpGained(to - from + 100);
		b.getSkillXp().put("AGILITY", to - from + 100);
		b.setOfflineXp(to - from);
		b.getOfflineSkillXp().put("AGILITY", to - from);
		b.setLevelsGained(1);
		b.getOfflineRanges().put("AGILITY", new ArrayList<>(Collections.singletonList(new long[]{from, to})));
		b.getXpRanges().put("AGILITY", new ArrayList<>(Collections.singletonList(new long[]{to, to + 100})));

		Map<String, DaySlice> slices = new HashMap<>();
		slices.put("a", slice(a));
		slices.put("b", slice(b));
		Map<String, Map<String, DaySlice>> byDate = new HashMap<>();
		byDate.put(DATE, slices);
		Map<String, Set<String>> changed = DaySlices.trim(byDate);

		assertEquals(Collections.singleton("b"), changed.get(DATE));
		assertEquals(0, b.getOfflineXp());
		assertEquals(100, b.getXpGained());
		assertEquals(0, b.getLevelsGained());
		assertTrue(b.getOfflineRanges().isEmpty());
		DayRecord d = DaySlices.combine(gson, DATE, slices);
		assertEquals(to - from + 100, d.getXpGained());
		assertEquals(1, d.getXpRanges().get("AGILITY").size());

		// Trimming again changes nothing
		assertTrue(DaySlices.trim(byDate).isEmpty());
	}

	/**
	 * Two PCs found the same mobile XP at login: only the earlier record (then the lower device ID)
	 * keeps it.
	 */
	@Test
	public void theSameXpFoundTwiceCountsOnce()
	{
		DayRecord a = new DayRecord(DATE);
		a.setOfflineXp(1_000);
		a.getOfflineSkillXp().put("COOKING", 1_000L);
		a.setXpGained(1_000);
		a.getSkillXp().put("COOKING", 1_000L);
		a.getOfflineRanges().put("COOKING", new ArrayList<>(Collections.singletonList(new long[]{5_000, 6_000})));
		DayRecord b = new DayRecord(DATE);
		b.setOfflineXp(1_500);
		b.getOfflineSkillXp().put("COOKING", 1_500L);
		b.setXpGained(1_500);
		b.getSkillXp().put("COOKING", 1_500L);
		b.getOfflineRanges().put("COOKING", new ArrayList<>(Collections.singletonList(new long[]{4_500, 6_000})));

		Map<String, DaySlice> slices = new HashMap<>();
		slices.put("a", slice(a));
		slices.put("b", slice(b));
		Map<String, Map<String, DaySlice>> byDate = new HashMap<>();
		byDate.put(DATE, slices);
		DaySlices.trim(byDate);

		assertEquals(1_000, a.getOfflineXp());
		assertEquals(500, b.getOfflineXp());
		assertArrayEquals(new long[]{4_500, 5_000}, b.getOfflineRanges().get("COOKING").get(0));
		assertEquals(1_500, DaySlices.combine(gson, DATE, slices).getXpGained());
	}

	/**
	 * Away XP (from a gap that began on an earlier day) is trimmed against XP recorded on any day.
	 */
	@Test
	public void awayXpIsTrimmedAgainstEarlierDays()
	{
		DayRecord monday = played(1_000, "FISHING", 100, 300);
		monday.setDate("2026-09-28");
		DayRecord wednesday = new DayRecord("2026-09-30");
		wednesday.setAwayXp(400);
		wednesday.getAwaySkillXp().put("FISHING", 400L);
		wednesday.setAwayFrom("2026-09-27");
		wednesday.getAwayRanges().put("FISHING", new ArrayList<>(Collections.singletonList(new long[]{0, 400})));

		Map<String, Map<String, DaySlice>> byDate = new HashMap<>();
		byDate.put("2026-09-28", Collections.singletonMap("a", slice(monday)));
		byDate.put("2026-09-30", Collections.singletonMap("b", slice(wednesday)));
		DaySlices.trim(byDate);

		assertEquals(200, wednesday.getAwayXp());
		assertEquals(2, wednesday.getAwayRanges().get("FISHING").size());
	}

	@Test
	public void newestSnapshotWins()
	{
		DayRecord a = new DayRecord(DATE);
		a.getSnapshot().put("ATTACK", 100L);
		DayRecord b = new DayRecord(DATE);
		b.getSnapshot().put("ATTACK", 200L);
		DaySlice sa = slice(a);
		sa.setSnapshotClock(5);
		DaySlice sb = slice(b);
		sb.setSnapshotClock(3);
		Map<String, DaySlice> slices = new HashMap<>();
		slices.put("a", sa);
		slices.put("b", sb);
		assertEquals(100L, (long) DaySlices.combine(gson, DATE, slices).getSnapshot().get("ATTACK"));
	}

	@Test
	public void deletedScreenshotStaysDeleted()
	{
		DayRecord a = new DayRecord(DATE);
		JourneyEvent shot = event("x", 100, "Pet");
		shot.setScreenshot("pet.png");
		a.getEvents().add(shot);
		Map<String, DaySlice> slices = new HashMap<>();
		slices.put("a", slice(a));
		DayRecord onB = DaySlices.combine(gson, DATE, slices);
		onB.getEvents().get(0).setScreenshot(null);
		DaySlice b = DaySlices.reconcile(gson, onB, "b", slices, clock(0));
		Map<String, DaySlice> after = new HashMap<>(slices);
		after.put("b", b);
		assertNull(DaySlices.combine(gson, DATE, after).getEvents().get(0).getScreenshot());
	}

	@Test
	public void schemaVersions()
	{
		assertTrue(Envelope.readable("1.0"));
		assertTrue(Envelope.readable("1.7"));
		assertTrue(!Envelope.readable("2.0"));
		assertTrue(!Envelope.readable(null));
	}
}
