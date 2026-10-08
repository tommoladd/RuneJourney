package com.runejourney.sync;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.runejourney.model.DayRecord;
import com.runejourney.model.DaySlice;
import com.runejourney.model.JourneyEvent;
import com.runejourney.model.NoteEdit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * A day recorded on several PCs. Each PC keeps its own part (a {@link DaySlice}) and the day is all
 * the parts combined. Combining gives the same day whatever order the parts arrive in, and repeating
 * a part changes nothing.
 * <ul>
 *   <li>Counts add up: each PC's part holds what it counted itself.</li>
 *   <li>XP ranges join; XP found at login that another record already counts is trimmed away.</li>
 *   <li>Journey events join by ID. A delete beats everything; the newest note wins.</li>
 *   <li>The end-of-day snapshot is the newest one.</li>
 * </ul>
 */
public final class DaySlices
{
	/**
	 * Counts that add up across PCs.
	 */
	public static final Set<String> ADDITIVE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		"playMillis", "xpGained", "skillXp", "levelsGained", "lootValue", "bossKills", "deaths", "collectionLogSlots",
		"questsCompleted", "personalBests", "slayerTasks", "cluesCompleted", "pets", "clues", "clueLootValue",
		"skillingIncome", "offlineXp", "offlineSkillXp", "awayXp", "awaySkillXp", "awayLevels", "skillingIncomeBySkill",
		"lootBySource", "suppliesCost", "suppliesUsed", "combatTasks", "combatTaskPoints")));
	/**
	 * Fields combined one by one below.
	 */
	public static final Set<String> SPECIAL = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		"date", "awayFrom", "xpRanges", "offlineRanges", "awayRanges", "snapshot", "events", "sync")));

	private DaySlices()
	{
	}

	/**
	 * The day as every PC together recorded it.
	 *
	 * @param gson    a Gson that leaves out sync parts
	 * @param slices  every PC's part, by device ID
	 */
	public static DayRecord combine(Gson gson, String date, Map<String, DaySlice> slices)
	{
		List<String> devices = devices(slices);
		JsonObject counts = new JsonObject();
		for (String device : devices)
		{
			Trees.add(counts, counts(gson, slices.get(device).getDay()), 1);
		}
		Trees.prune(counts);
		DayRecord d = gson.fromJson(counts, DayRecord.class);
		d.setDate(date);

		DaySlice snapshotFrom = null;
		for (String device : devices)
		{
			DaySlice slice = slices.get(device);
			DayRecord part = slice.getDay();
			d.setXpRanges(XpRanges.union(d.getXpRanges(), part.getXpRanges()));
			d.setOfflineRanges(XpRanges.union(d.getOfflineRanges(), part.getOfflineRanges()));
			d.setAwayRanges(XpRanges.union(d.getAwayRanges(), part.getAwayRanges()));
			d.setAwayFrom(earliest(d.getAwayFrom(), part.getAwayFrom()));
			if (snapshotFrom == null || slice.getSnapshotClock() > snapshotFrom.getSnapshotClock()
				|| (slice.getSnapshotClock() == snapshotFrom.getSnapshotClock() && snapshotFrom.getDay().getSnapshot().isEmpty()))
			{
				snapshotFrom = slice;
			}
		}
		if (snapshotFrom != null)
		{
			d.setSnapshot(new HashMap<>(snapshotFrom.getDay().getSnapshot()));
		}
		d.setEvents(events(devices, slices));
		return d;
	}

	/**
	 * This PC's new part of a day: what the day holds that no other PC's part does, keeping its
	 * earlier part's edits. Combining the result with the other parts gives back {@code view}.
	 *
	 * @param view   the day as it is now on this PC: every part combined, plus anything recorded or
	 *               edited here since this PC's part was last worked out
	 * @param me     this PC's device ID
	 * @param slices every part as of when this PC's part was last worked out, including its own
	 */
	public static DaySlice reconcile(Gson gson, DayRecord view, String me, Map<String, DaySlice> slices, Hlc hlc)
	{
		DaySlice own = slices.get(me);
		if (own != null && own.getDay() == null)
		{
			own = null;
		}
		DayRecord ownDay = own != null ? own.getDay() : new DayRecord(view.getDate());
		Map<String, DaySlice> remotes = new HashMap<>();
		slices.forEach((device, slice) ->
		{
			if (!device.equals(me) && slice != null && slice.getDay() != null)
			{
				remotes.put(device, slice);
			}
		});
		DayRecord base = combine(gson, view.getDate(), slices);

		JsonObject counts = counts(gson, view);
		for (DaySlice r : remotes.values())
		{
			Trees.add(counts, counts(gson, r.getDay()), -1);
		}
		Trees.prune(counts);
		DayRecord day = gson.fromJson(counts, DayRecord.class);
		day.setDate(view.getDate());

		day.setXpRanges(mine(view, ownDay, remotes, DayRecord::getXpRanges));
		day.setOfflineRanges(mine(view, ownDay, remotes, DayRecord::getOfflineRanges));
		day.setAwayRanges(mine(view, ownDay, remotes, DayRecord::getAwayRanges));

		String awayFrom = ownDay.getAwayFrom();
		if (!Objects.equals(view.getAwayFrom(), base.getAwayFrom()) || (awayFrom == null && day.getAwayXp() > ownDay.getAwayXp()))
		{
			awayFrom = earliest(awayFrom, view.getAwayFrom());
		}
		day.setAwayFrom(awayFrom);

		DaySlice next = new DaySlice();
		if (!view.getSnapshot().equals(base.getSnapshot()))
		{
			day.setSnapshot(new HashMap<>(view.getSnapshot()));
			next.setSnapshotClock(hlc.next());
		}
		else
		{
			day.setSnapshot(new HashMap<>(ownDay.getSnapshot()));
			next.setSnapshotClock(own != null ? own.getSnapshotClock() : 0);
		}

		if (own != null)
		{
			next.getNotes().putAll(own.getNotes());
			next.getDeleted().addAll(own.getDeleted());
			next.getScreenshotsDeleted().addAll(own.getScreenshotsDeleted());
		}
		Map<String, JourneyEvent> before = byId(base.getEvents());
		Set<String> remoteIds = new HashSet<>();
		remotes.values().forEach(r -> r.getDay().getEvents().forEach(e -> remoteIds.add(e.getId())));
		Set<String> kept = new HashSet<>();
		List<JourneyEvent> events = new ArrayList<>();
		for (JourneyEvent e : view.getEvents())
		{
			if (e.getId() == null)
			{
				continue;
			}
			kept.add(e.getId());
			// Recorded here, or recorded here too and this PC's copy is the one shown
			if (!remoteIds.contains(e.getId()) || me.equals(owner(e.getId(), slices)))
			{
				JourneyEvent c = e.copy();
				c.setNote(null);
				events.add(c);
			}
			JourneyEvent was = before.get(e.getId());
			if (!Objects.equals(e.getNote(), was != null ? was.getNote() : null))
			{
				next.getNotes().put(e.getId(), new NoteEdit(e.getNote(), hlc.next()));
			}
			if (was != null && was.getScreenshot() != null && e.getScreenshot() == null)
			{
				next.getScreenshotsDeleted().add(e.getId());
			}
		}
		for (JourneyEvent e : base.getEvents())
		{
			if (!kept.contains(e.getId()))
			{
				next.getDeleted().add(e.getId());
			}
		}
		day.setEvents(events);
		next.setDay(day);
		return next;
	}

	/**
	 * Whether a day is exactly its parts combined, i.e. nothing has changed since this PC's part was
	 * last worked out.
	 */
	public static boolean matches(Gson gson, DayRecord view, Map<String, DaySlice> slices)
	{
		DayRecord combined = combine(gson, view.getDate(), slices);
		JsonObject a = counts(gson, view);
		Trees.prune(a);
		JsonObject b = counts(gson, combined);
		Trees.prune(b);
		if (!a.equals(b)
			|| !XpRanges.same(view.getXpRanges(), combined.getXpRanges())
			|| !XpRanges.same(view.getOfflineRanges(), combined.getOfflineRanges())
			|| !XpRanges.same(view.getAwayRanges(), combined.getAwayRanges())
			|| !Objects.equals(view.getAwayFrom(), combined.getAwayFrom())
			|| !view.getSnapshot().equals(combined.getSnapshot())
			|| view.getEvents().size() != combined.getEvents().size())
		{
			return false;
		}
		Map<String, JourneyEvent> mine = byId(view.getEvents());
		for (JourneyEvent e : combined.getEvents())
		{
			if (!e.equals(mine.get(e.getId())))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Drops XP found at login (offline or away XP) that another record already counts: XP another PC
	 * recorded while playing, or the same XP found at an earlier login. Only the record that found it
	 * loses it, and every PC makes the same choice, so each part can be trimmed by any PC.
	 * <p>
	 * Online XP is never trimmed: an account can only play on one PC at a time.
	 *
	 * @param byDate every synced day's parts, by date then device ID; trimmed parts are changed in place
	 * @return the dates that changed, with the devices whose parts changed
	 */
	public static Map<String, Set<String>> trim(Map<String, Map<String, DaySlice>> byDate)
	{
		Map<String, List<Claim>> claims = new HashMap<>();
		byDate.forEach((date, slices) -> slices.forEach((device, slice) ->
		{
			DayRecord d = slice.getDay();
			if (d == null)
			{
				return;
			}
			d.getOfflineRanges().forEach((skill, list) -> list.forEach(r ->
				claims.computeIfAbsent(skill, k -> new ArrayList<>()).add(new Claim(date, device, false, skill, r, slice))));
			d.getAwayRanges().forEach((skill, list) -> list.forEach(r ->
				claims.computeIfAbsent(skill, k -> new ArrayList<>()).add(new Claim(date, device, true, skill, r, slice))));
		}));
		Map<String, Set<String>> changed = new TreeMap<>();
		if (claims.isEmpty())
		{
			return changed;
		}

		Map<String, List<long[]>> online = new HashMap<>();
		byDate.values().forEach(slices -> slices.values().forEach(slice ->
		{
			if (slice.getDay() != null)
			{
				slice.getDay().getXpRanges().forEach((skill, list) ->
				{
					if (claims.containsKey(skill))
					{
						online.computeIfAbsent(skill, k -> new ArrayList<>()).addAll(list);
					}
				});
			}
		}));

		Map<long[], List<long[]>> keep = new IdentityHashMap<>();
		Map<long[], Claim> trimmed = new IdentityHashMap<>();
		claims.forEach((skill, list) ->
		{
			list.sort(Comparator.comparing((Claim c) -> c.date).thenComparing(c -> c.device)
				.thenComparing(c -> c.away).thenComparingLong(c -> c.range[0]));
			List<long[]> covered = XpRanges.normalize(online.getOrDefault(skill, Collections.emptyList()));
			for (Claim c : list)
			{
				List<long[]> left = XpRanges.subtract(Collections.singletonList(c.range), covered);
				covered = XpRanges.union(covered, Collections.singletonList(c.range));
				if (XpRanges.length(left) != c.range[1] - c.range[0])
				{
					keep.put(c.range, left);
					trimmed.put(c.range, c);
				}
			}
		});

		// Rebuild each changed list, adjusting the counts that included the dropped XP
		Map<DaySlice, Set<String>> done = new IdentityHashMap<>();
		for (Claim c : trimmed.values())
		{
			Map<String, List<long[]>> ranges = c.away ? c.slice.getDay().getAwayRanges() : c.slice.getDay().getOfflineRanges();
			if (!done.computeIfAbsent(c.slice, k -> new HashSet<>()).add(c.away + "|" + c.skill))
			{
				continue;
			}
			List<long[]> was = ranges.get(c.skill);
			List<long[]> now = new ArrayList<>();
			for (long[] r : was)
			{
				now.addAll(keep.getOrDefault(r, Collections.singletonList(r)));
			}
			long xp = XpRanges.length(was) - XpRanges.length(now);
			int levels = XpRanges.levels(was) - XpRanges.levels(now);
			if (now.isEmpty())
			{
				ranges.remove(c.skill);
			}
			else
			{
				ranges.put(c.skill, now);
			}
			dropFound(c.slice.getDay(), c.away, c.skill, xp, levels);
			changed.computeIfAbsent(c.date, k -> new HashSet<>()).add(c.device);
		}
		return changed;
	}

	private static class Claim
	{
		final String date;
		final String device;
		final boolean away;
		final String skill;
		final long[] range;
		final DaySlice slice;

		Claim(String date, String device, boolean away, String skill, long[] range, DaySlice slice)
		{
			this.date = date;
			this.device = device;
			this.away = away;
			this.skill = skill;
			this.range = range;
			this.slice = slice;
		}
	}

	private static void dropFound(DayRecord d, boolean away, String skill, long xp, int levels)
	{
		if (away)
		{
			d.setAwayXp(d.getAwayXp() - xp);
			reduce(d.getAwaySkillXp(), skill, xp);
			d.setAwayLevels(Math.max(0, d.getAwayLevels() - levels));
		}
		else
		{
			d.setOfflineXp(d.getOfflineXp() - xp);
			reduce(d.getOfflineSkillXp(), skill, xp);
			d.setXpGained(d.getXpGained() - xp);
			reduce(d.getSkillXp(), skill, xp);
			d.setLevelsGained(Math.max(0, d.getLevelsGained() - levels));
		}
	}

	private static void reduce(Map<String, Long> xp, String skill, long by)
	{
		xp.computeIfPresent(skill, (k, v) -> v - by > 0 ? v - by : null);
	}

	/**
	 * Events joined by ID. When PCs recorded the same event (one with a fixed ID), the earliest copy
	 * is shown.
	 */
	private static List<JourneyEvent> events(List<String> devices, Map<String, DaySlice> slices)
	{
		Set<String> deleted = new HashSet<>();
		Set<String> shotsDeleted = new HashSet<>();
		Map<String, NoteEdit> notes = new HashMap<>();
		for (String device : devices)
		{
			DaySlice s = slices.get(device);
			deleted.addAll(s.getDeleted());
			shotsDeleted.addAll(s.getScreenshotsDeleted());
			s.getNotes().forEach((id, edit) ->
			{
				NoteEdit best = notes.get(id);
				if (best == null || edit.getClock() > best.getClock())
				{
					notes.put(id, edit);
				}
			});
		}

		Map<String, JourneyEvent> shown = new LinkedHashMap<>();
		for (String device : devices)
		{
			for (JourneyEvent e : slices.get(device).getDay().getEvents())
			{
				if (e.getId() == null || deleted.contains(e.getId()))
				{
					continue;
				}
				JourneyEvent best = shown.get(e.getId());
				if (best == null || e.getTime() < best.getTime())
				{
					shown.put(e.getId(), e);
				}
			}
		}

		List<JourneyEvent> out = new ArrayList<>();
		for (JourneyEvent e : shown.values())
		{
			JourneyEvent c = e.copy();
			NoteEdit note = notes.get(e.getId());
			c.setNote(note != null ? note.getText() : null);
			if (shotsDeleted.contains(e.getId()))
			{
				c.setScreenshot(null);
			}
			out.add(c);
		}
		out.sort(Comparator.comparingLong(JourneyEvent::getTime).thenComparing(JourneyEvent::getId));
		return out;
	}

	/**
	 * The PC whose copy of an event is shown: the earliest, then the lowest device ID.
	 */
	private static String owner(String id, Map<String, DaySlice> slices)
	{
		String owner = null;
		long time = Long.MAX_VALUE;
		for (String device : devices(slices))
		{
			for (JourneyEvent e : slices.get(device).getDay().getEvents())
			{
				if (id.equals(e.getId()) && e.getTime() < time)
				{
					owner = device;
					time = e.getTime();
				}
			}
		}
		return owner;
	}

	/**
	 * This PC's XP ranges: its earlier ones, plus any now in the day that no other PC's part has.
	 */
	private static Map<String, List<long[]>> mine(DayRecord view, DayRecord own, Map<String, DaySlice> remotes,
		Function<DayRecord, Map<String, List<long[]>>> kind)
	{
		Map<String, List<long[]>> others = new HashMap<>();
		for (DaySlice r : remotes.values())
		{
			others = XpRanges.union(others, kind.apply(r.getDay()));
		}
		return XpRanges.union(kind.apply(own), XpRanges.subtract(kind.apply(view), others));
	}

	private static JsonObject counts(Gson gson, DayRecord d)
	{
		return Trees.only(gson.toJsonTree(d).getAsJsonObject(), ADDITIVE);
	}

	private static Map<String, JourneyEvent> byId(List<JourneyEvent> events)
	{
		Map<String, JourneyEvent> out = new HashMap<>();
		for (JourneyEvent e : events)
		{
			if (e.getId() != null)
			{
				out.put(e.getId(), e);
			}
		}
		return out;
	}

	private static String earliest(String a, String b)
	{
		if (a == null)
		{
			return b;
		}
		return b == null || a.compareTo(b) <= 0 ? a : b;
	}

	/**
	 * Device IDs in a fixed order, so ties always go the same way. Parts without a day are left out.
	 */
	private static List<String> devices(Map<String, DaySlice> slices)
	{
		List<String> devices = new ArrayList<>();
		slices.forEach((device, slice) ->
		{
			if (slice != null && slice.getDay() != null)
			{
				devices.add(device);
			}
		});
		Collections.sort(devices);
		return devices;
	}
}
