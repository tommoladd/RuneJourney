package com.runejourney.service;

import com.google.gson.Gson;
import com.runejourney.RuneJourneyConfig;
import com.runejourney.model.ClogItem;
import com.runejourney.model.DayRecord;
import com.runejourney.model.EventType;
import com.runejourney.model.Goal;
import com.runejourney.model.GoalItem;
import com.runejourney.model.GoalSession;
import com.runejourney.model.GoalType;
import com.runejourney.model.ItemTotal;
import com.runejourney.model.JourneyEvent;
import com.runejourney.model.LootSource;
import com.runejourney.model.ObservedRate;
import com.runejourney.model.ProfileData;
import com.runejourney.planner.BossData;
import com.runejourney.planner.Counters;
import com.runejourney.planner.GoalPlanner;
import com.runejourney.planner.GoalProgress;
import com.runejourney.planner.RateSource;
import com.runejourney.planner.Skills;
import com.runejourney.planner.TrainingMethod;
import com.runejourney.planner.TrainingMethods;
import com.runejourney.planner.XpRates;
import com.runejourney.util.Format;
import java.io.IOException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

/**
 * Owns the loaded profile and all Journey state. Every public method is synchronized: the client
 * thread records gameplay while the Swing thread reads snapshots for the sidebar. Methods that
 * return data always return copies.
 */
@Slf4j
@Singleton
public class JourneyService implements RateSource
{
	private static final int TICK_MILLIS = 600;
	/**
	 * Gaps between XP drops longer than this are treated as not actively training.
	 */
	private static final int ACTIVE_GAP_TICKS = 100;
	/**
	 * How close (in ticks) a kill count message must be to relate it to a PB, drop or pet.
	 */
	private static final int PB_WINDOW_TICKS = 3;
	private static final int LOOT_WINDOW_TICKS = 10;
	private static final int CLUE_WINDOW_TICKS = 5;
	private static final long[] XP_MILESTONES = {10_000_000L, 20_000_000L, 50_000_000L, 100_000_000L, 150_000_000L, 200_000_000L};
	private static final int[] KC_MILESTONES = {1, 10, 25, 50, 100, 250, 500, 1000};
	private static final String XP_RECORD_TITLE = "Most XP in a day";
	private static final String LOOT_RECORD_TITLE = "Best loot day";
	private static final String KILLS_RECORD_TITLE = "Most boss kills in a day";
	private static final String SESSION_RECORD_TITLE = "Longest session";
	private static final String STREAK_RECORD_TITLE = "Longest play streak";
	/**
	 * Play streaks only become records worth mentioning after this many days.
	 */
	private static final int MIN_STREAK_RECORD = 3;
	public static final String OWN_RATE = "My own XP rate";
	/**
	 * Kills needed before the player's own kill times are trusted.
	 */
	private static final int MIN_OBSERVED_KILLS = 5;
	/**
	 * Longest gap between kills that still counts as one bossing trip.
	 */
	private static final long MAX_KILL_GAP_MILLIS = 20 * 60_000L;

	private final JourneyStore store;
	private final ScreenshotService screenshots;
	private final RuneJourneyConfig config;
	private final Gson gson;
	private final ChatMessageManager chatMessageManager;
	private final TrainingMethods trainingMethods;
	private final BossData bossData;
	private final Encouragement encouragement = new Encouragement(new java.util.Random());

	@Getter
	private volatile String profileKey;
	/**
	 * Account whose profile is being loaded, so a slow load can't install a stale profile.
	 */
	private String pendingKey;
	private ProfileData profile;
	private final TreeMap<String, DayRecord> days = new TreeMap<>();
	private final Set<String> dirtyDays = new HashSet<>();
	private boolean profileDirty;

	private final Map<String, Long> xp = new HashMap<>();
	@Getter
	private volatile boolean baselineSet;
	private final Map<String, Integer> lastXpTick = new HashMap<>();
	private String lastRollDate;
	private int currentTick;

	private String lastKcBoss;
	private int lastKcCount;
	private int lastKcTick = Integer.MIN_VALUE / 2;
	private String pendingPbTime;
	private int pendingPbTick;
	/**
	 * Wall-clock time of the previous kill per boss this session, for learning kill times.
	 */
	private final Map<String, Long> lastKillMillis = new HashMap<>();
	private PendingClue pendingClue;
	/**
	 * Time logged in this session, for the longest-session record.
	 */
	private long sessionMillis;
	private String lastStreakCheck;

	/**
	 * A clue completion is reported by a chat message and the reward inventory, which can arrive in
	 * either order, so they're combined here.
	 */
	private static class PendingClue
	{
		String tier;
		int count;
		List<LootItem> items;
		int tick;
	}

	/**
	 * Incremented on any change so the UI knows when to refresh.
	 */
	@Getter
	private volatile int version;
	/**
	 * Incremented only when Journey events are added or removed.
	 */
	@Getter
	private volatile int eventVersion;

	@Inject
	JourneyService(JourneyStore store, ScreenshotService screenshots, RuneJourneyConfig config, Gson gson,
		ChatMessageManager chatMessageManager, TrainingMethods trainingMethods, BossData bossData)
	{
		this.bossData = bossData;
		this.store = store;
		this.screenshots = screenshots;
		this.config = config;
		this.gson = gson;
		this.chatMessageManager = chatMessageManager;
		this.trainingMethods = trainingMethods;
	}

	// ------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------

	public synchronized boolean isReady()
	{
		return profile != null;
	}

	public synchronized void install(String key, JourneyStore.Loaded loaded)
	{
		profileKey = key;
		profile = loaded.getProfile() != null ? loaded.getProfile() : new ProfileData();
		if (profile.getCreatedAt() == 0)
		{
			profile.setCreatedAt(System.currentTimeMillis());
			profileDirty = true;
		}
		days.clear();
		days.putAll(loaded.getDays());
		dirtyDays.clear();
		cleanEventText();
		migrateWealth();
		resetSessionState();
		changed(true);
	}

	/**
	 * Profiles saved before item holdings were tracked only have a value per container. Keep the
	 * last bank value until the bank is next opened; inventory and equipment are re-read on login.
	 */
	private void migrateWealth()
	{
		if (profile.getWealthParts().isEmpty() && profile.isBankValueKnown() && profile.getBankValue() > 0)
		{
			profile.getWealthParts().put(BANK, profile.getBankValue());
			profileDirty = true;
		}
		if (profile.getBankValue() != 0 || profile.getInventoryValue() != 0 || profile.getEquipmentValue() != 0)
		{
			profile.setBankValue(0);
			profile.setInventoryValue(0);
			profile.setEquipmentValue(0);
			profileDirty = true;
		}
	}

	/**
	 * Fixes entries saved before the parser removed the game's colour markers (e.g. "@ach_comp@").
	 */
	private void cleanEventText()
	{
		for (DayRecord d : days.values())
		{
			for (JourneyEvent e : d.getEvents())
			{
				String title = ChatParser.clean(e.getTitle());
				if (title != null && !title.equals(e.getTitle()))
				{
					e.setTitle(title);
					dirtyDays.add(d.getDate());
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// Wealth
	// ------------------------------------------------------------------

	public static final String BANK = "bank";

	private long netWorth()
	{
		return profile.getWealthParts().values().stream().mapToLong(Long::longValue).sum();
	}

	/**
	 * The items last seen in one container. The bank is only visible while it's open; the rest
	 * update as they change.
	 */
	public synchronized void onHoldings(String part, Map<Integer, Integer> items)
	{
		if (profile == null || !config.trackWealth())
		{
			return;
		}
		profile.getHoldings().put(part, new HashMap<>(items));
		profileDirty = true;
	}

	/**
	 * A copy of everything the account was last seen holding, for pricing.
	 */
	public synchronized Map<String, Map<Integer, Integer>> holdings()
	{
		Map<String, Map<Integer, Integer>> copy = new HashMap<>();
		if (profile != null)
		{
			profile.getHoldings().forEach((part, items) -> copy.put(part, new HashMap<>(items)));
		}
		return copy;
	}

	/**
	 * GE value of one container.
	 */
	public synchronized void onWealth(String part, long value)
	{
		onWealth(Collections.singletonMap(part, value));
	}

	/**
	 * GE value of several containers at once. Pass every container that changed together (e.g.
	 * an item moving from the bank to equipment) so the total is never counted mid-move.
	 */
	public synchronized void onWealth(Map<String, Long> parts)
	{
		if (profile == null || !config.trackWealth() || parts.isEmpty())
		{
			return;
		}
		boolean knownBefore = profile.isBankValueKnown();
		long before = netWorth();
		profile.getWealthParts().putAll(parts);
		if (parts.containsKey(BANK))
		{
			profile.setBankValueKnown(true);
		}
		long now = netWorth();
		if (now == before && knownBefore)
		{
			return;
		}
		profileDirty = true;
		if (!profile.isBankValueKnown())
		{
			return;
		}
		if (!knownBefore)
		{
			// First look at the bank: set the baseline quietly rather than celebrating every milestone
			profile.setBestWealth(Math.max(profile.getBestWealth(), now));
		}
		else if (now > profile.getBestWealth())
		{
			for (long m : wealthMilestones())
			{
				if (profile.getBestWealth() < m && now >= m)
				{
					JourneyEvent e = event(EventType.RECORD, "Net worth passed " + Format.compact(m), Format.compact(now) + " gp");
					e.setValue(m);
					e.setHighlight(true);
					addEvent(e, false);
					announce("Your net worth passed " + Format.compact(m) + "!");
				}
			}
			profile.setBestWealth(now);
		}
		if (baselineSet)
		{
			checkGoals();
		}
		changed(false);
	}

	private List<Long> wealthMilestones()
	{
		List<Long> out = new ArrayList<>();
		for (String part : config.wealthMilestones().split(","))
		{
			try
			{
				out.add(net.runelite.client.util.QuantityFormatter.parseQuantity(part.trim()));
			}
			catch (java.text.ParseException | NumberFormatException ignored)
			{
				// skip malformed entries
			}
		}
		Collections.sort(out);
		return out;
	}

	/**
	 * [net worth, change since the end of the previous recorded day], or null if the bank hasn't been seen.
	 */
	public synchronized long[] netWorthToday()
	{
		if (profile == null || !config.trackWealth() || !profile.isBankValueKnown())
		{
			return null;
		}
		long now = netWorth();
		Map.Entry<String, DayRecord> before = days.lowerEntry(LocalDate.now().toString());
		while (before != null && before.getValue().getSnapshot().get(Counters.WEALTH) == null)
		{
			before = days.lowerEntry(before.getKey());
		}
		Long start = before == null ? null : before.getValue().getSnapshot().get(Counters.WEALTH);
		return new long[]{now, start == null ? Long.MIN_VALUE : now - start};
	}

	// ------------------------------------------------------------------
	// Collection log
	// ------------------------------------------------------------------

	/**
	 * A collection log page the player just viewed. Obtained items tick off item goals quietly,
	 * since they may have been obtained long ago.
	 */
	public synchronized void onCollectionLogPage(String page, List<ClogItem> items)
	{
		if (profile == null || page == null || page.isEmpty() || items.isEmpty())
		{
			return;
		}
		List<ClogItem> old = profile.getCollectionLog().get(page);
		if (old != null && old.equals(items))
		{
			return;
		}
		profile.getCollectionLog().put(page, new ArrayList<>(items));
		profileDirty = true;

		long now = System.currentTimeMillis();
		for (Goal g : profile.getGoals())
		{
			if (g.getType() != GoalType.ITEMS || g.isComplete())
			{
				continue;
			}
			for (GoalItem wanted : g.getItems())
			{
				if (wanted.isObtained())
				{
					continue;
				}
				for (ClogItem ci : items)
				{
					if (ci.isObtained() && (ci.getId() == wanted.getId() || ci.getName().equalsIgnoreCase(wanted.getName())))
					{
						wanted.setObtainedAt(now);
						wanted.setSource("Collection log");
					}
				}
			}
		}
		if (baselineSet)
		{
			checkGoals();
		}
		changed(false);
	}

	/**
	 * Imported pages: name to [obtained, total], sorted by name.
	 */
	public synchronized Map<String, int[]> collectionLogPages()
	{
		Map<String, int[]> out = new TreeMap<>();
		if (profile != null)
		{
			profile.getCollectionLog().forEach((page, items) ->
				out.put(page, new int[]{(int) items.stream().filter(ClogItem::isObtained).count(), items.size()}));
		}
		return out;
	}

	public synchronized List<ClogItem> collectionLogPage(String page)
	{
		List<ClogItem> items = profile == null ? null : profile.getCollectionLog().get(page);
		List<ClogItem> copy = new ArrayList<>();
		if (items != null)
		{
			for (ClogItem ci : items)
			{
				copy.add(new ClogItem(ci.getId(), ci.getName(), ci.isObtained()));
			}
		}
		return copy;
	}

	// ------------------------------------------------------------------
	// Streaks and records
	// ------------------------------------------------------------------

	/**
	 * [current, best] days played in a row. The current streak survives until the end of a day off.
	 */
	public synchronized int[] playStreaks()
	{
		int best = 0;
		int run = 0;
		LocalDate prev = null;
		for (DayRecord d : days.values())
		{
			if (d.getPlayMillis() <= 0)
			{
				continue;
			}
			LocalDate date = LocalDate.parse(d.getDate());
			run = prev != null && prev.plusDays(1).equals(date) ? run + 1 : 1;
			best = Math.max(best, run);
			prev = date;
		}
		LocalDate today = LocalDate.now();
		int current = prev != null && (prev.equals(today) || prev.equals(today.minusDays(1))) ? run : 0;
		if (profile != null)
		{
			best = Math.max(best, profile.getBestPlayStreak());
		}
		return new int[]{current, best};
	}

	private void checkPlayStreak()
	{
		String date = LocalDate.now().toString();
		if (date.equals(lastStreakCheck))
		{
			return;
		}
		lastStreakCheck = date;
		int[] streaks = playStreaks();
		if (streaks[0] > profile.getBestPlayStreak())
		{
			if (streaks[0] >= MIN_STREAK_RECORD && profile.getBestPlayStreak() > 0)
			{
				JourneyEvent e = event(EventType.RECORD, STREAK_RECORD_TITLE, streaks[0] + " days in a row");
				e.setHighlight(true);
				addEvent(e, false);
			}
			profile.setBestPlayStreak(streaks[0]);
			profileDirty = true;
		}
	}

	private void finishPlaySession()
	{
		if (profile == null || !baselineSet || sessionMillis <= 0)
		{
			sessionMillis = 0;
			return;
		}
		if (sessionMillis > profile.getLongestSessionMillis())
		{
			// Only worth mentioning once there's a previous record to beat
			if (profile.getLongestSessionMillis() >= 30 * 60_000L)
			{
				JourneyEvent e = event(EventType.RECORD, SESSION_RECORD_TITLE,
					Format.duration(sessionMillis) + " (previous best " + Format.duration(profile.getLongestSessionMillis()) + ")");
				e.setHighlight(true);
				addEvent(e, false);
			}
			profile.setLongestSessionMillis(sessionMillis);
			profile.setLongestSessionDate(LocalDate.now().toString());
			profileDirty = true;
		}
		sessionMillis = 0;
	}

	/**
	 * Best value over days before today.
	 */
	private long bestDay(java.util.function.ToLongFunction<DayRecord> value)
	{
		String today = LocalDate.now().toString();
		long best = 0;
		for (DayRecord d : days.headMap(today, false).values())
		{
			best = Math.max(best, value.applyAsLong(d));
		}
		return best;
	}

	@Value
	public static class PersonalRecord
	{
		String title;
		String value;
		/**
		 * When it was set, or null.
		 */
		LocalDate date;
	}

	/**
	 * The player's personal records, best first within each kind.
	 */
	public synchronized List<PersonalRecord> records()
	{
		List<PersonalRecord> out = new ArrayList<>();
		if (profile == null)
		{
			return out;
		}
		dayRecord(out, "Most XP in a day", d -> d.getXpGained(), v -> Format.compact(v) + " XP");
		dayRecord(out, "Most time played in a day", d -> d.getPlayMillis(), Format::duration);
		dayRecord(out, "Best income day", d -> d.getLootValue() + d.getSkillingIncome(), v -> Format.compact(v) + " gp");
		dayRecord(out, "Most boss kills in a day", d -> d.getBossKills().values().stream().mapToLong(Integer::longValue).sum(),
			v -> Format.number(v) + " kills");
		dayRecord(out, "Most levels in a day", d -> d.getLevelsGained(), v -> v + (v == 1 ? " level" : " levels"));
		dayRecord(out, "Most clues in a day", d -> d.getCluesCompleted(), v -> v + (v == 1 ? " clue" : " clues"));
		dayRecord(out, "Most collection log slots in a day", d -> d.getCollectionLogSlots(), v -> v + " slots");

		// Best week (Monday to Sunday) for XP
		Map<LocalDate, Long> weeks = new TreeMap<>();
		for (DayRecord d : days.values())
		{
			weeks.merge(GoalPlanner.weekStart(LocalDate.parse(d.getDate())), d.getXpGained(), Long::sum);
		}
		weeks.entrySet().stream().max(Map.Entry.comparingByValue()).filter(e -> e.getValue() > 0).ifPresent(e ->
			out.add(new PersonalRecord("Most XP in a week", Format.compact(e.getValue()) + " XP", e.getKey())));

		// Biggest single drop
		JourneyEvent biggest = null;
		LocalDate biggestDate = null;
		for (DayRecord d : days.values())
		{
			for (JourneyEvent e : d.getEvents())
			{
				if (e.getType() == EventType.DROP && (biggest == null || e.getValue() > biggest.getValue()))
				{
					biggest = e;
					biggestDate = LocalDate.parse(d.getDate());
				}
			}
		}
		if (biggest != null && biggest.getValue() > 0)
		{
			out.add(new PersonalRecord("Biggest drop", biggest.getTitle() + " (" + Format.compact(biggest.getValue()) + ")", biggestDate));
		}

		long session = Math.max(profile.getLongestSessionMillis(), sessionMillis);
		if (session > 0)
		{
			out.add(new PersonalRecord(SESSION_RECORD_TITLE, Format.duration(session),
				sessionMillis >= profile.getLongestSessionMillis() ? LocalDate.now()
					: profile.getLongestSessionDate() == null ? null : LocalDate.parse(profile.getLongestSessionDate())));
		}
		int[] streaks = playStreaks();
		if (streaks[1] > 0)
		{
			out.add(new PersonalRecord(STREAK_RECORD_TITLE, streaks[1] + (streaks[1] == 1 ? " day" : " days"), null));
		}
		int bestPlan = profile.getGoals().stream().mapToInt(Goal::getBestPlanStreak).max().orElse(0);
		if (bestPlan > 0)
		{
			out.add(new PersonalRecord("Weekly plans met in a row", bestPlan + (bestPlan == 1 ? " week" : " weeks"), null));
		}
		if (profile.getBestWealth() > 0 && config.trackWealth())
		{
			out.add(new PersonalRecord("Highest net worth", Format.compact(profile.getBestWealth()) + " gp", null));
		}
		return out;
	}

	private void dayRecord(List<PersonalRecord> out, String title, java.util.function.ToLongFunction<DayRecord> value,
		java.util.function.LongFunction<String> format)
	{
		DayRecord best = null;
		long bestValue = 0;
		for (DayRecord d : days.values())
		{
			long v = value.applyAsLong(d);
			if (v > bestValue)
			{
				bestValue = v;
				best = d;
			}
		}
		if (best != null)
		{
			out.add(new PersonalRecord(title, format.apply(bestValue), LocalDate.parse(best.getDate())));
		}
	}

	// ------------------------------------------------------------------
	// Wrapped
	// ------------------------------------------------------------------

	/**
	 * Monday of the most recent finished week that has play in it, or null.
	 */
	public synchronized LocalDate latestWrappedWeek()
	{
		List<LocalDate> weeks = wrappedWeeks();
		LocalDate last = GoalPlanner.weekStart(LocalDate.now()).minusWeeks(1);
		return !weeks.isEmpty() && weeks.get(0).equals(last) ? last : null;
	}

	/**
	 * Finished weeks with any play, newest first. The current week isn't wrapped until it ends.
	 */
	public synchronized List<LocalDate> wrappedWeeks()
	{
		java.util.TreeSet<LocalDate> weeks = new java.util.TreeSet<>(Collections.reverseOrder());
		LocalDate thisWeek = GoalPlanner.weekStart(LocalDate.now());
		for (DayRecord d : days.values())
		{
			LocalDate monday = GoalPlanner.weekStart(LocalDate.parse(d.getDate()));
			if (d.getPlayMillis() > 0 && monday.isBefore(thisWeek))
			{
				weeks.add(monday);
			}
		}
		return new ArrayList<>(weeks);
	}

	/**
	 * True when last week's Wrapped is ready and hasn't been watched yet.
	 */
	public synchronized boolean isWrappedNew()
	{
		LocalDate latest = latestWrappedWeek();
		return latest != null && profile != null && !latest.toString().equals(profile.getWrappedSeen());
	}

	public synchronized void markWrappedSeen(LocalDate weekStart)
	{
		if (profile != null && weekStart.equals(latestWrappedWeek()))
		{
			profile.setWrappedSeen(weekStart.toString());
			profileDirty = true;
			changed(false);
		}
	}

	/**
	 * Announces a new Wrapped in chat, once per week.
	 */
	private void notifyWrapped()
	{
		LocalDate latest = latestWrappedWeek();
		if (latest == null || !config.wrappedNotify() || latest.toString().equals(profile.getWrappedNotified())
			|| latest.toString().equals(profile.getWrappedSeen()))
		{
			return;
		}
		profile.setWrappedNotified(latest.toString());
		profileDirty = true;
		String formatted = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("[RuneJourney] ")
			.append(ChatColorType.NORMAL)
			.append("Your Week Wrapped is ready! Open the RuneJourney panel to watch it.")
			.build();
		if (chatMessageManager != null)
		{
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(formatted)
				.build());
		}
	}

	public synchronized com.runejourney.wrapped.WrappedWeek buildWrapped(LocalDate weekStart)
	{
		LocalDate weekEnd = weekStart.plusDays(6);
		List<DayRecord> week = new ArrayList<>(days.subMap(weekStart.toString(), true, weekEnd.toString(), true).values());
		com.runejourney.wrapped.WrappedBuilder.Input.InputBuilder in = com.runejourney.wrapped.WrappedBuilder.Input.builder()
			.weekStart(weekStart)
			.player(profile == null ? null : profile.getPlayerName())
			.days(week);
		Map<String, Integer> ids = new HashMap<>();
		if (profile != null)
		{
			profile.getCollectionLog().values().forEach(items -> items.forEach(ci ->
			{
				if (ci.getId() > 0 && ci.getName() != null)
				{
					ids.put(ci.getName().toLowerCase(java.util.Locale.ENGLISH), ci.getId());
				}
			}));
			profile.getGoals().forEach(g -> g.getItems().forEach(gi ->
			{
				if (gi.getId() > 0)
				{
					ids.put(gi.getName().toLowerCase(java.util.Locale.ENGLISH), gi.getId());
				}
			}));
		}
		in.itemIds(ids);

		// Earlier weeks, for "biggest week yet"
		Map<LocalDate, List<DayRecord>> earlier = new TreeMap<>();
		for (DayRecord d : days.headMap(weekStart.toString(), false).values())
		{
			earlier.computeIfAbsent(GoalPlanner.weekStart(LocalDate.parse(d.getDate())), k -> new ArrayList<>()).add(d);
		}
		earlier.values().forEach(list -> in.earlierWeek(com.runejourney.wrapped.WrappedBuilder.weekTotals(list)));

		// Weekly plan results are only kept for the week that just ended
		if (profile != null && weekStart.equals(GoalPlanner.weekStart(LocalDate.now()).minusWeeks(1)))
		{
			for (Goal g : profile.getGoals())
			{
				if (g.getLastWeekResults().isEmpty())
				{
					continue;
				}
				long target = 0;
				long achieved = 0;
				for (long[] r : g.getLastWeekResults().values())
				{
					target += r[0];
					achieved += r[1];
				}
				in.planResult(new Object[]{g.getName(), achieved, target, Counters.isMoney(g.getCounter())});
			}
		}

		// Days in a row played, ending with the week
		int streak = 0;
		for (LocalDate d = weekEnd; ; d = d.minusDays(1))
		{
			DayRecord r = days.get(d.toString());
			if (r == null || r.getPlayMillis() <= 0)
			{
				if (d.equals(weekEnd))
				{
					continue;
				}
				break;
			}
			streak++;
			if (streak > 3650)
			{
				break;
			}
		}
		in.playStreak(streak);
		return com.runejourney.wrapped.WrappedBuilder.build(in.build());
	}

	// ------------------------------------------------------------------
	// Overlay
	// ------------------------------------------------------------------

	public synchronized String overlayGoalId()
	{
		return profile == null ? null : profile.getOverlayGoalId();
	}

	public synchronized void setOverlayGoal(String goalId)
	{
		if (profile != null)
		{
			profile.setOverlayGoalId(goalId);
			profileDirty = true;
			changed(false);
		}
	}

	/**
	 * Everything the overlay shows, prepared ahead of time so drawing each frame stays cheap.
	 */
	public synchronized OverlayData overlayData()
	{
		if (profile == null || !baselineSet)
		{
			return null;
		}
		OverlayData data = new OverlayData();

		if (config.overlayGoal() || config.overlayWeekPlan())
		{
			GoalProgress chosen = null;
			for (GoalProgress p : goalProgress())
			{
				if (p.isComplete())
				{
					continue;
				}
				if (p.getGoal().getId().equals(profile.getOverlayGoalId()))
				{
					chosen = p;
					break;
				}
				if (chosen == null)
				{
					chosen = p;
				}
			}
			if (chosen != null)
			{
				Goal g = chosen.getGoal();
				data.setGoalName(g.getName());
				data.setGoalPercent(g.getType() == GoalType.CUSTOM ? -1 : chosen.getPercent());
				data.setGoalDetail(overlayGoalDetail(chosen));
				if (config.overlayWeekPlan())
				{
					int rows = 0;
					for (GoalProgress.WeekRow w : chosen.getWeek())
					{
						if (rows++ >= config.overlayWeekRows())
						{
							break;
						}
						data.getWeek().add(new OverlayData.Row(w.getSkill().getName(),
							Format.compact(w.getAchieved()) + " / " + Format.compact(w.getTarget()),
							w.getAchieved() / (double) Math.max(1, w.getTarget())));
					}
					if (chosen.getCountWeekTarget() > 0)
					{
						data.getWeek().add(new OverlayData.Row(Counters.isMoney(g.getCounter()) ? "Savings" : Counters.label(g.getCounter()),
							Counters.isMoney(g.getCounter())
								? Format.compact(chosen.getCountWeekAchieved()) + " / " + Format.compact(chosen.getCountWeekTarget())
								: chosen.getCountWeekAchieved() + " / " + chosen.getCountWeekTarget(),
							chosen.getCountWeekAchieved() / (double) Math.max(1, chosen.getCountWeekTarget())));
					}
				}
				if (!config.overlayGoal())
				{
					data.setGoalName(null);
				}
			}
		}

		if (config.overlayToday())
		{
			DayRecord d = days.get(LocalDate.now().toString());
			if (d != null)
			{
				data.setToday(Format.duration(d.getPlayMillis()) + " · " + Format.compact(d.getXpGained()) + " XP · "
					+ Format.compact(d.getLootValue() + d.getSkillingIncome()) + " gp");
			}
		}
		if (config.overlaySession())
		{
			SessionView s = sessionView();
			if (s != null)
			{
				data.setSession(s.getGoalName() + " · " + Format.duration(s.getActiveMillis()) + " · "
					+ Format.compact((long) s.getXpPerHour()) + " XP/hr");
			}
		}
		if (config.overlayStreak())
		{
			int[] streaks = playStreaks();
			if (streaks[0] > 0)
			{
				data.setStreak(streaks[0] + (streaks[0] == 1 ? "-day streak" : "-day streak") + " (best " + streaks[1] + ")");
			}
		}
		if (config.overlayNetWorth())
		{
			long[] worth = netWorthToday();
			if (worth != null)
			{
				data.setNetWorth(Format.compact(worth[0]) + " gp" + (worth[1] == Long.MIN_VALUE ? ""
					: " (" + (worth[1] >= 0 ? "+" : "") + Format.compact(worth[1]) + " today)"));
			}
		}
		return data;
	}

	private static String overlayGoalDetail(GoalProgress p)
	{
		Goal g = p.getGoal();
		if (g.getType().isCounter())
		{
			return Counters.format(g.getCounter(), p.getCountCurrent()) + " / " + Counters.format(g.getCounter(), p.getCountTarget());
		}
		if (g.getType() == GoalType.ITEMS)
		{
			return p.getItemsObtained() + " / " + p.getItemsTotal() + " items";
		}
		if (g.getType() == GoalType.CUSTOM)
		{
			return null;
		}
		String time = p.getHoursRemaining() > 0 ? " · ~" + Format.hours(p.getHoursRemaining()) : "";
		if (g.getType() == GoalType.TOTAL_LEVEL)
		{
			return p.getLevelsRemaining() + " levels left" + time;
		}
		return Format.compact(p.getXpRemaining()) + " XP left" + time;
	}

	public synchronized void updatePlayerName(String name)
	{
		if (profile != null && name != null && !name.equals(profile.getPlayerName()))
		{
			profile.setPlayerName(name);
			profileDirty = true;
			changed(false);
		}
	}

	public synchronized void beginLoad(String key)
	{
		pendingKey = key;
	}

	/**
	 * Installs a loaded profile only if it's still the one wanted.
	 */
	public synchronized boolean installIfCurrent(String key, JourneyStore.Loaded loaded)
	{
		if (!key.equals(pendingKey))
		{
			return false;
		}
		pendingKey = null;
		install(key, loaded);
		return true;
	}

	public synchronized void unload()
	{
		pendingKey = null;
		profileKey = null;
		profile = null;
		days.clear();
		dirtyDays.clear();
		profileDirty = false;
		xp.clear();
		resetSessionState();
		changed(true);
	}

	/**
	 * Called on logout: forget in-memory skill state so the next login takes a fresh baseline.
	 */
	public synchronized void resetSessionState()
	{
		finishPlaySession();
		baselineSet = false;
		lastXpTick.clear();
		lastRollDate = null;
		lastKcBoss = null;
		pendingPbTime = null;
		lastKillMillis.clear();
		pendingClue = null;
	}

	/**
	 * Serializes all unsaved data. The returned task performs the disk IO and must run off the client thread.
	 */
	public synchronized Runnable collectWrites()
	{
		if (profile == null || (!profileDirty && dirtyDays.isEmpty()))
		{
			return null;
		}
		String key = profileKey;
		DayRecord todayRecord = days.get(LocalDate.now().toString());
		if (todayRecord != null && baselineSet)
		{
			todayRecord.setSnapshot(state());
		}
		String profileJson = profileDirty ? gson.toJson(profile) : null;
		Map<String, String> dayJson = new LinkedHashMap<>();
		for (String date : dirtyDays)
		{
			DayRecord d = days.get(date);
			if (d != null)
			{
				dayJson.put(date, gson.toJson(d));
			}
		}
		profileDirty = false;
		dirtyDays.clear();

		return () ->
		{
			try
			{
				if (profileJson != null)
				{
					store.writeProfile(key, profileJson);
				}
				for (Map.Entry<String, String> e : dayJson.entrySet())
				{
					store.writeDay(key, e.getKey(), e.getValue());
				}
			}
			catch (IOException e)
			{
				log.warn("Unable to save RuneJourney data", e);
			}
		};
	}

	private void changed(boolean events)
	{
		version++;
		if (events)
		{
			eventVersion++;
		}
	}

	private DayRecord today()
	{
		String date = LocalDate.now().toString();
		DayRecord d = days.computeIfAbsent(date, DayRecord::new);
		dirtyDays.add(date);
		return d;
	}

	// ------------------------------------------------------------------
	// Gameplay recording (client thread)
	// ------------------------------------------------------------------

	/**
	 * Takes the XP baseline after login. Levels gained while RuneJourney wasn't running
	 * (e.g. on mobile) are recorded, but their XP isn't credited to today.
	 */
	public synchronized void setBaseline(Map<String, Long> current, int tick)
	{
		if (profile == null)
		{
			return;
		}
		currentTick = tick;
		Map<String, Long> previous = new HashMap<>(profile.getLastXp());
		if (!previous.isEmpty())
		{
			// If a new week started while away, start it from the last known XP so XP gained on
			// another device counts towards this week's plan instead of vanishing into the boundary
			xp.clear();
			xp.putAll(previous);
			rollWeeksIfNeeded();

			DayRecord d = today();
			long awayTotal = 0;
			Map<String, Long> awayBySkill = new LinkedHashMap<>();
			for (Skill s : Skills.ALL)
			{
				long before = Skills.xp(previous, s);
				long now = Skills.xp(current, s);
				if (before <= 0 || now <= before)
				{
					continue;
				}
				long gained = now - before;
				awayTotal += gained;
				awayBySkill.put(s.getName(), gained);
				d.setXpGained(d.getXpGained() + gained);
				d.getSkillXp().merge(s.name(), gained, Long::sum);
				d.setOfflineXp(d.getOfflineXp() + gained);
				d.getOfflineSkillXp().merge(s.name(), gained, Long::sum);

				int oldLevel = Skills.level(before);
				int newLevel = Skills.level(now);
				if (newLevel > oldLevel)
				{
					d.setLevelsGained(d.getLevelsGained() + newLevel - oldLevel);
					JourneyEvent e = event(EventType.LEVEL, "Level " + newLevel + " " + s.getName(),
						Format.number(now) + " XP (gained while away)");
					e.setSkill(s.name());
					e.setValue(newLevel);
					e.setHighlight(isMilestoneLevel(newLevel));
					addEvent(e, false);
				}
			}
			if (awayTotal > 0)
			{
				String detail = awayBySkill.entrySet().stream()
					.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
					.limit(5)
					.map(e -> e.getKey() + " +" + Format.compact(e.getValue()))
					.collect(java.util.stream.Collectors.joining(", "));
				JourneyEvent e = event(EventType.NOTE, "While you were away: +" + Format.compact(awayTotal) + " XP", detail);
				addEvent(e, false);
			}
		}

		xp.clear();
		xp.putAll(current);
		profile.setLastXp(new HashMap<>(current));
		profileDirty = true;
		baselineSet = true;

		rollWeeksIfNeeded();
		checkGoals();
		notifyWrapped();
		changed(false);
	}

	public synchronized void onTick(int tick)
	{
		if (profile == null || !baselineSet)
		{
			return;
		}
		currentTick = tick;
		DayRecord d = today();
		d.setPlayMillis(d.getPlayMillis() + TICK_MILLIS);
		sessionMillis += TICK_MILLIS;
		checkPlayStreak();

		GoalSession session = profile.getSession();
		if (session != null)
		{
			session.setActiveMillis(session.getActiveMillis() + TICK_MILLIS);
			profileDirty = true;
		}

		if (pendingClue != null && tick - pendingClue.tick > CLUE_WINDOW_TICKS)
		{
			flushClue();
		}

		if (pendingPbTime != null && tick - pendingPbTick > PB_WINDOW_TICKS)
		{
			personalBest(null, pendingPbTime);
			pendingPbTime = null;
		}

		rollWeeksIfNeeded();
		changed(false);
	}

	public synchronized void onXp(Skill skill, long newXp, int tick)
	{
		if (profile == null || !baselineSet || !Skills.ALL.contains(skill))
		{
			return;
		}
		currentTick = tick;
		String key = skill.name();
		long old = Skills.xp(xp, skill);
		if (newXp <= old)
		{
			return;
		}

		long delta = newXp - old;
		int oldTotal = Skills.totalLevel(xp);
		xp.put(key, newXp);
		profile.getLastXp().put(key, newXp);
		profileDirty = true;

		DayRecord d = today();
		d.setXpGained(d.getXpGained() + delta);
		d.getSkillXp().merge(key, delta, Long::sum);

		Integer last = lastXpTick.put(key, tick);
		if (last != null && tick - last <= ACTIVE_GAP_TICKS)
		{
			ObservedRate rate = profile.getObservedRates().computeIfAbsent(key, k -> new ObservedRate());
			rate.setXp(rate.getXp() + delta);
			rate.setMillis(rate.getMillis() + (long) (tick - last) * TICK_MILLIS);
		}

		int oldLevel = Skills.level(old);
		int newLevel = Skills.level(newXp);
		if (newLevel > oldLevel)
		{
			d.setLevelsGained(d.getLevelsGained() + newLevel - oldLevel);
			levelUp(skill, newLevel, newXp, d);
			totalLevelMilestones(oldTotal, Skills.totalLevel(xp));
		}

		xpMilestones(skill, old, newXp);
		if (config.encouragement())
		{
			long gap = Encouragement.interval(config, skill);
			if (Encouragement.crossed(gap, old, newXp))
			{
				say(encouragement.message(skill, gap, newXp));
			}
		}

		checkDailyRecord(d.getXpGained(), profile.getBestXpDay(), XP_RECORD_TITLE, Format.compact(d.getXpGained()) + " XP");
		profile.setBestXpDay(Math.max(profile.getBestXpDay(), d.getXpGained()));
		checkGoals();
		changed(false);
	}

	private void xpMilestones(Skill skill, long old, long newXp)
	{
		TreeSet<Long> crossed = new TreeSet<>();
		for (long m : XP_MILESTONES)
		{
			if (old < m && newXp >= m)
			{
				crossed.add(m);
			}
		}
		long interval = config.xpMilestoneInterval();
		if (interval > 0)
		{
			for (long k = old / interval + 1; k <= newXp / interval; k++)
			{
				crossed.add(k * interval);
			}
		}
		for (long m : crossed)
		{
			boolean big = m % 10_000_000L == 0;
			JourneyEvent e = event(EventType.XP_MILESTONE, Format.compact(m) + " " + skill.getName() + " XP", null);
			e.setSkill(skill.name());
			e.setValue(m);
			e.setHighlight(big);
			addEvent(e, big && config.screenshotMilestoneLevels());
		}

		long totalInterval = config.totalXpMilestoneInterval();
		if (totalInterval > 0)
		{
			long totalNow = 0;
			for (Skill s : Skills.ALL)
			{
				totalNow += Skills.xp(xp, s);
			}
			long totalBefore = totalNow - (newXp - old);
			for (long k = totalBefore / totalInterval + 1; k <= totalNow / totalInterval; k++)
			{
				JourneyEvent e = event(EventType.XP_MILESTONE, Format.compact(k * totalInterval) + " total XP", null);
				e.setValue(k * totalInterval);
				e.setHighlight(true);
				addEvent(e, false);
			}
		}
	}

	private void levelUp(Skill skill, int level, long totalXp, DayRecord d)
	{
		boolean milestone = isMilestoneLevel(level);
		if (!milestone && !config.logEveryLevel())
		{
			return;
		}
		long todayXp = d.getSkillXp().getOrDefault(skill.name(), 0L);
		JourneyEvent e = event(EventType.LEVEL, "Level " + level + " " + skill.getName(),
			Format.number(totalXp) + " XP · +" + Format.number(todayXp) + " today");
		e.setSkill(skill.name());
		e.setValue(level);
		e.setHighlight(milestone);
		addEvent(e, milestone && config.screenshotMilestoneLevels());
	}

	private void totalLevelMilestones(int oldTotal, int newTotal)
	{
		Set<Integer> milestones = new TreeSet<>(parseInts(config.totalLevelMilestones()));
		milestones.add(Skills.MAX_TOTAL_LEVEL);
		for (int m : milestones)
		{
			if (oldTotal < m && newTotal >= m)
			{
				String title = m == Skills.MAX_TOTAL_LEVEL ? "Maxed! " + Format.number(m) + " total level" : "Reached " + Format.number(m) + " total level";
				JourneyEvent e = event(EventType.TOTAL_LEVEL, title, null);
				e.setHighlight(true);
				e.setValue(m);
				addEvent(e, config.screenshotMilestoneLevels());
			}
		}
	}

	private void checkDailyRecord(long todayValue, long previousBest, String title, String detail)
	{
		// Need some history before "records" mean anything
		if (previousBest <= 0 || todayValue <= previousBest || days.size() < 7)
		{
			return;
		}
		DayRecord d = today();
		for (JourneyEvent e : d.getEvents())
		{
			if (e.getType() == EventType.RECORD && title.equals(e.getTitle()))
			{
				return;
			}
		}
		JourneyEvent e = event(EventType.RECORD, title, detail + " (previous best " + Format.compact(previousBest) + ")");
		e.setHighlight(true);
		addEvent(e, false);
		announce("New personal record: " + title.toLowerCase() + "!");
	}

	public synchronized void onKillCount(String boss, int count, int tick)
	{
		if (profile == null)
		{
			return;
		}
		currentTick = tick;
		profile.getKillCounts().put(boss, count);
		profileDirty = true;
		DayRecord d = today();
		d.getBossKills().merge(boss, 1, Integer::sum);
		lastKcBoss = boss;
		lastKcCount = count;
		lastKcTick = tick;
		learnKillTime(boss);
		long killsToday = d.getBossKills().values().stream().mapToLong(Integer::longValue).sum();
		checkDailyRecord(killsToday, bestDay(r -> r.getBossKills().values().stream().mapToLong(Integer::longValue).sum()),
			KILLS_RECORD_TITLE, Format.number(killsToday) + " boss kills");

		if (isKcMilestone(count, config.kcMilestoneInterval()))
		{
			String title = count == 1 ? "First " + boss + " kill" : Format.number(count) + " " + boss + " KC";
			JourneyEvent e = event(EventType.BOSS_KC, title, null);
			e.setHighlight(true);
			e.setValue(count);
			addEvent(e, config.screenshotKcMilestones());
		}

		if (pendingPbTime != null && tick - pendingPbTick <= PB_WINDOW_TICKS)
		{
			personalBest(boss, pendingPbTime);
			pendingPbTime = null;
		}
		checkGoals();
		changed(false);
	}

	private void learnKillTime(String boss)
	{
		long now = System.currentTimeMillis();
		Long last = lastKillMillis.put(boss, now);
		if (last == null)
		{
			return;
		}
		double typical = bossData.minutesPerKill(boss);
		long maxGap = Math.max(MAX_KILL_GAP_MILLIS, (long) (typical * 3 * 60_000));
		long gap = now - last;
		if (gap > 0 && gap <= maxGap)
		{
			ObservedRate r = profile.getKillTimes().computeIfAbsent(boss, k -> new ObservedRate());
			r.setXp(r.getXp() + 1);
			r.setMillis(r.getMillis() + gap);
		}
	}

	public synchronized void onPersonalBest(String time, int tick)
	{
		if (profile == null)
		{
			return;
		}
		currentTick = tick;
		if (lastKcBoss != null && tick - lastKcTick <= PB_WINDOW_TICKS)
		{
			personalBest(lastKcBoss, time);
		}
		else
		{
			pendingPbTime = time;
			pendingPbTick = tick;
		}
	}

	private void personalBest(String boss, String time)
	{
		DayRecord d = today();
		d.setPersonalBests(d.getPersonalBests() + 1);
		String detail = time;
		if (boss != null && lastKcCount > 0)
		{
			detail += " · KC " + Format.number(lastKcCount);
		}
		JourneyEvent e = event(EventType.PERSONAL_BEST, boss != null ? "New PB: " + boss : "New personal best", detail);
		e.setHighlight(true);
		addEvent(e, config.screenshotPersonalBests());
	}

	@Value
	public static class LootItem
	{
		int id;
		String name;
		int quantity;
		long value;
	}

	public synchronized void onLoot(String source, List<LootItem> items, int tick)
	{
		if (profile == null)
		{
			return;
		}
		currentTick = tick;
		long total = 0;
		DayRecord d = today();
		for (LootItem item : items)
		{
			total += item.getValue();
			if (item.getValue() >= config.valuableDropThreshold())
			{
				String title = item.getQuantity() > 1 ? Format.number(item.getQuantity()) + " x " + item.getName() : item.getName();
				JourneyEvent e = event(EventType.DROP, title, Format.compact(item.getValue()) + " gp · " + sourceWithKc(source));
				e.setValue(item.getValue());
				e.setHighlight(true);
				addEvent(e, config.screenshotValuableDrops());
			}
		}
		itemsObtained(items, source);
		addLootSource(d, source, items, total);
		long before = d.getLootValue();
		d.setLootValue(before + total);
		checkDailyRecord(d.getLootValue(), profile.getBestLootDay(), LOOT_RECORD_TITLE, Format.compact(d.getLootValue()) + " gp");
		profile.setBestLootDay(Math.max(profile.getBestLootDay(), d.getLootValue()));
		profileDirty = true;
		changed(false);
	}

	private static void addLootSource(DayRecord d, String source, List<LootItem> items, long total)
	{
		LootSource s = d.getLootBySource().computeIfAbsent(source != null ? source : "Unknown source", k -> new LootSource());
		s.setTimes(s.getTimes() + 1);
		s.setValue(s.getValue() + total);
		for (LootItem item : items)
		{
			String name = item.getName() != null ? item.getName() : "Item " + item.getId();
			s.getItems().computeIfAbsent(name, k -> new ItemTotal()).add(item.getQuantity(), item.getValue());
		}
	}

	/**
	 * Food eaten or a potion dose drunk, valued at what was used up (leftovers already deducted).
	 */
	public synchronized void onSupplyUsed(String name, int quantity, long value)
	{
		if (profile == null || !config.trackSupplies() || value <= 0)
		{
			return;
		}
		DayRecord d = today();
		d.setSuppliesCost(d.getSuppliesCost() + value);
		d.getSuppliesUsed().computeIfAbsent(name, k -> new ItemTotal()).add(quantity, value);
		changed(false);
	}

	private String sourceWithKc(String source)
	{
		if (source == null)
		{
			return "Unknown source";
		}
		if (source.equalsIgnoreCase(lastKcBoss) && currentTick - lastKcTick <= LOOT_WINDOW_TICKS)
		{
			return source + " · KC " + Format.number(lastKcCount);
		}
		return source;
	}

	private String recentKcSource()
	{
		if (lastKcBoss != null && currentTick - lastKcTick <= LOOT_WINDOW_TICKS)
		{
			return lastKcBoss + " · KC " + Format.number(lastKcCount);
		}
		return null;
	}

	/**
	 * Net value of items gained (or used) while training a skill, e.g. pickpocketing or alching.
	 */
	public synchronized void onSkillingIncome(Skill skill, long value, List<LootItem> gained)
	{
		if (profile == null || !baselineSet)
		{
			return;
		}
		DayRecord d = today();
		d.setSkillingIncome(d.getSkillingIncome() + value);
		d.getSkillingIncomeBySkill().merge(skill.name(), value, Long::sum);
		if (!gained.isEmpty())
		{
			itemsObtained(gained, skill.getName());
		}
		changed(false);
	}

	public synchronized void onCollectionLog(String item, int tick)
	{
		if (profile == null)
		{
			return;
		}
		currentTick = tick;
		DayRecord d = today();
		d.setCollectionLogSlots(d.getCollectionLogSlots() + 1);
		JourneyEvent e = event(EventType.COLLECTION_LOG, item, recentKcSource());
		e.setHighlight(true);
		addEvent(e, config.screenshotCollectionLog());
		profile.setCollectionLogSlots(profile.getCollectionLogSlots() + 1);
		for (List<ClogItem> page : profile.getCollectionLog().values())
		{
			for (ClogItem ci : page)
			{
				if (ci.getName() != null && ci.getName().equalsIgnoreCase(item))
				{
					ci.setObtained(true);
				}
			}
		}
		profileDirty = true;
		itemsObtained(Collections.singletonList(new LootItem(-1, item, 1, 0)), recentKcSource());
		checkGoals();
	}

	public synchronized void onPet(boolean duplicate, int tick)
	{
		if (profile == null)
		{
			return;
		}
		currentTick = tick;
		DayRecord d = today();
		String source = recentKcSource();
		if (duplicate)
		{
			addEvent(event(EventType.PET, "Duplicate pet", source), false);
			return;
		}
		d.setPets(d.getPets() + 1);
		JourneyEvent e = event(EventType.PET, "New pet!", source);
		e.setHighlight(true);
		addEvent(e, config.screenshotPets());
	}

	public synchronized void onQuest(String quest, int tick)
	{
		if (profile == null)
		{
			return;
		}
		currentTick = tick;
		DayRecord d = today();
		d.setQuestsCompleted(d.getQuestsCompleted() + 1);
		JourneyEvent e = event(EventType.QUEST, "Completed " + quest, null);
		e.setHighlight(true);
		addEvent(e, config.screenshotQuests());
	}

	public synchronized void onDiary(String area, String tier, int tick)
	{
		if (profile == null)
		{
			return;
		}
		currentTick = tick;
		JourneyEvent e = event(EventType.DIARY, area + " " + tier + " Diary complete", null);
		e.setHighlight(true);
		addEvent(e, config.screenshotQuests());
	}

	public synchronized void onCombatTask(String task, String tier, int points, int tick)
	{
		if (profile == null)
		{
			return;
		}
		currentTick = tick;
		DayRecord d = today();
		d.setCombatTasks(d.getCombatTasks() + 1);
		d.setCombatTaskPoints(d.getCombatTaskPoints() + points);
		if (!profile.isCombatTasksFromGame())
		{
			profile.setCombatTasks(profile.getCombatTasks() + 1);
		}
		profile.setCombatAchievementPoints(profile.getCombatAchievementPoints() + points);
		profileDirty = true;
		JourneyEvent e = event(EventType.COMBAT_TASK, task, tier + " combat task · " + points + (points == 1 ? " point" : " points"));
		e.setHighlight(true);
		e.setValue(points);
		addEvent(e, false);
		checkGoals();
	}

	/**
	 * Calibrates the tracked CA points total, since the game doesn't expose it directly.
	 */
	public synchronized void setCombatAchievementPoints(long points)
	{
		if (profile != null && points >= 0)
		{
			profile.setCombatAchievementPoints(points);
			profileDirty = true;
			checkGoals();
			changed(false);
		}
	}

	/**
	 * Account counters read from game variables (quest points, collection log, clue counts).
	 */
	public synchronized void onCounter(String key, int value)
	{
		if (profile == null || value <= 0)
		{
			return;
		}
		boolean changedValue;
		if (Counters.QUEST_POINTS.equals(key))
		{
			changedValue = profile.getQuestPoints() != value;
			profile.setQuestPoints(value);
		}
		else if (Counters.COLLECTION_LOG.equals(key))
		{
			changedValue = profile.getCollectionLogSlots() != value;
			profile.setCollectionLogSlots(value);
		}
		else if (Counters.CA_TASKS.equals(key))
		{
			changedValue = profile.getCombatTasks() != value || !profile.isCombatTasksFromGame();
			profile.setCombatTasks(value);
			profile.setCombatTasksFromGame(true);
		}
		else if (Counters.isClues(key))
		{
			String tier = Counters.suffix(key);
			Integer old = profile.getClueCounts().get(tier);
			changedValue = old == null || old < value;
			if (changedValue)
			{
				profile.getClueCounts().put(tier, value);
			}
		}
		else
		{
			return;
		}
		if (changedValue)
		{
			profileDirty = true;
			if (baselineSet)
			{
				checkGoals();
			}
			changed(false);
		}
	}

	public synchronized void onSlayerTask()
	{
		if (profile == null)
		{
			return;
		}
		DayRecord d = today();
		d.setSlayerTasks(d.getSlayerTasks() + 1);
		changed(false);
	}

	public synchronized void onClueCompleted(String tier, int count, int tick)
	{
		if (profile == null)
		{
			return;
		}
		currentTick = tick;
		if (pendingClue == null || pendingClue.tier != null)
		{
			flushClue();
			pendingClue = new PendingClue();
			pendingClue.tick = tick;
		}
		pendingClue.tier = tier;
		pendingClue.count = count;
		maybeFinishClue();
	}

	public synchronized void onClueReward(List<LootItem> items, int tick)
	{
		if (profile == null || items.isEmpty())
		{
			return;
		}
		currentTick = tick;
		if (pendingClue == null || pendingClue.items != null)
		{
			flushClue();
			pendingClue = new PendingClue();
			pendingClue.tick = tick;
		}
		pendingClue.items = items;
		maybeFinishClue();
	}

	private void maybeFinishClue()
	{
		if (pendingClue != null && pendingClue.tier != null && pendingClue.items != null)
		{
			flushClue();
		}
	}

	/**
	 * Records a pending clue completion with whatever parts of it have arrived.
	 */
	private void flushClue()
	{
		PendingClue c = pendingClue;
		pendingClue = null;
		if (c == null)
		{
			return;
		}
		DayRecord d = today();
		String tier = c.tier;
		if (tier != null)
		{
			profile.getClueCounts().put(tier, c.count);
			d.setCluesCompleted(d.getCluesCompleted() + 1);
			d.getClues().merge(tier, 1, Integer::sum);
			profileDirty = true;
		}

		long value = 0;
		LootItem best = null;
		if (c.items != null)
		{
			for (LootItem item : c.items)
			{
				value += item.getValue();
				if (best == null || item.getValue() > best.getValue())
				{
					best = item;
				}
			}
			d.setLootValue(d.getLootValue() + value);
			d.setClueLootValue(d.getClueLootValue() + value);
			String source = tier != null ? tier + " clue" : "Clue scroll";
			itemsObtained(c.items, source);
			addLootSource(d, source, c.items, value);
		}

		boolean valuable = best != null && best.getValue() >= config.valuableDropThreshold();
		boolean milestone = tier != null && (c.count == 1 || c.count % 100 == 0);
		if (config.recordEveryClue() || valuable || milestone)
		{
			String title = tier == null ? "Clue scroll" : tier + " clue" + (c.count > 0 ? " #" + Format.number(c.count) : "");
			StringBuilder detail = new StringBuilder();
			if (c.items != null)
			{
				detail.append("Loot ").append(Format.compact(value)).append(" gp");
				if (best != null && best.getValue() > 0)
				{
					detail.append(" · Best: ").append(best.getQuantity() > 1 ? Format.number(best.getQuantity()) + " x " : "")
						.append(best.getName()).append(" (").append(Format.compact(best.getValue())).append(")");
				}
			}
			JourneyEvent e = event(EventType.CLUE, title, detail.length() > 0 ? detail.toString() : null);
			e.setValue(value);
			e.setHighlight(valuable || milestone);
			addEvent(e, valuable && config.screenshotValuableDrops());
		}
		checkGoals();
		changed(false);
	}

	public synchronized void onDeath(int tick)
	{
		if (profile == null)
		{
			return;
		}
		currentTick = tick;
		DayRecord d = today();
		d.setDeaths(d.getDeaths() + 1);
		if (config.recordDeaths())
		{
			addEvent(event(EventType.DEATH, "Oh dear, you are dead!", null), config.screenshotDeaths());
		}
		changed(false);
	}

	private JourneyEvent event(EventType type, String title, String detail)
	{
		JourneyEvent e = new JourneyEvent();
		e.setTime(System.currentTimeMillis());
		e.setType(type);
		e.setTitle(title);
		e.setDetail(detail);
		return e;
	}

	private void addEvent(JourneyEvent e, boolean screenshot)
	{
		if (screenshot && profileKey != null)
		{
			e.setScreenshot(screenshots.request(profileKey, e.getTitle(), currentTick));
		}
		today().getEvents().add(e);
		changed(true);
	}

	/**
	 * Adds memories the player recorded themselves, on any date. They go into the Journey but don't
	 * change that day's stats, so nothing is counted twice. Items in them tick off item goals.
	 */
	public synchronized void addMemories(LocalDate date, List<JourneyEvent> events, List<LootItem> items)
	{
		if (profile == null || events.isEmpty())
		{
			return;
		}
		DayRecord d = days.computeIfAbsent(date.toString(), DayRecord::new);
		dirtyDays.add(date.toString());
		d.getEvents().addAll(events);
		d.getEvents().sort(Comparator.comparingLong(JourneyEvent::getTime));
		if (!items.isEmpty())
		{
			itemsObtained(items, "Added manually");
			checkGoals();
		}
		changed(true);
	}

	public synchronized void deleteEvent(String date, long time, String title)
	{
		DayRecord d = days.get(date);
		if (d != null && d.getEvents().removeIf(e -> e.getTime() == time && title.equals(e.getTitle())))
		{
			dirtyDays.add(date);
			changed(true);
		}
	}

	/**
	 * Journey events that have a screenshot, by screenshot file name.
	 */
	public synchronized Map<String, JourneyEvent> screenshotEvents()
	{
		Map<String, JourneyEvent> result = new HashMap<>();
		for (DayRecord d : days.values())
		{
			for (JourneyEvent e : d.getEvents())
			{
				if (e.getScreenshot() != null)
				{
					result.put(e.getScreenshot(), copy(e));
				}
			}
		}
		return result;
	}

	/**
	 * A screenshot file was deleted: stop its Journey entries linking to it.
	 */
	public synchronized void forgetScreenshot(String name)
	{
		for (DayRecord d : days.values())
		{
			for (JourneyEvent e : d.getEvents())
			{
				if (name.equals(e.getScreenshot()))
				{
					e.setScreenshot(null);
					dirtyDays.add(d.getDate());
				}
			}
		}
		changed(true);
	}

	/**
	 * Adds, changes or (with a blank note) removes the player's note on a Journey event.
	 */
	public synchronized void setEventNote(String date, long time, String title, String note)
	{
		DayRecord d = days.get(date);
		if (d == null)
		{
			return;
		}
		String text = note == null || note.trim().isEmpty() ? null : note.trim();
		for (JourneyEvent e : d.getEvents())
		{
			if (e.getTime() == time && title.equals(e.getTitle()))
			{
				e.setNote(text);
				dirtyDays.add(date);
				changed(true);
				return;
			}
		}
	}

	private void announce(String message)
	{
		if (config.chatAnnouncements())
		{
			say(message);
		}
	}

	/**
	 * A "[RuneJourney] ..." message in the chatbox.
	 */
	private void say(String message)
	{
		if (chatMessageManager == null)
		{
			return;
		}
		String formatted = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("[RuneJourney] ")
			.append(ChatColorType.NORMAL)
			.append(message)
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(formatted)
			.build());
	}

	// ------------------------------------------------------------------
	// Goals
	// ------------------------------------------------------------------

	/**
	 * Skill XP plus account counters, keyed as in {@link Counters}.
	 */
	private Map<String, Long> state()
	{
		Map<String, Long> state = new HashMap<>(xp);
		if (profile == null)
		{
			return state;
		}
		profile.getKillCounts().forEach((boss, kc) -> state.put(Counters.kc(boss), (long) kc));
		long allClues = 0;
		for (Map.Entry<String, Integer> e : profile.getClueCounts().entrySet())
		{
			state.put(Counters.clues(e.getKey()), (long) e.getValue());
			allClues += e.getValue();
		}
		state.put(Counters.clues(Counters.ALL_TIERS), allClues);
		state.put(Counters.CA_POINTS, profile.getCombatAchievementPoints());
		state.put(Counters.CA_TASKS, (long) profile.getCombatTasks());
		state.put(Counters.QUEST_POINTS, (long) profile.getQuestPoints());
		state.put(Counters.COLLECTION_LOG, (long) profile.getCollectionLogSlots());
		state.put(Counters.CASH, profile.getBankCash() + profile.getInventoryCash());
		if (config.trackWealth() && profile.isBankValueKnown())
		{
			state.put(Counters.WEALTH, netWorth());
		}
		profile.getCollectionLog().forEach((page, items) ->
			state.put(Counters.clogPage(page), items.stream().filter(ClogItem::isObtained).count()));
		return state;
	}

	public synchronized Map<String, Long> counterValues()
	{
		return state();
	}

	/**
	 * Minutes per kill: the player's observed average after a few kills, otherwise a typical time.
	 */
	public synchronized double minutesPerKill(String boss)
	{
		ObservedRate r = profile == null ? null : profile.getKillTimes().get(boss);
		if (r != null && r.getXp() >= MIN_OBSERVED_KILLS)
		{
			return r.getMillis() / 60_000d / r.getXp();
		}
		return bossData.minutesPerKill(boss);
	}

	public synchronized boolean isPersonalKillTime(String boss)
	{
		ObservedRate r = profile == null ? null : profile.getKillTimes().get(boss);
		return r != null && r.getXp() >= MIN_OBSERVED_KILLS;
	}

	@Override
	public synchronized double counterHours(String counter, long from, long to)
	{
		if (to <= from)
		{
			return 0;
		}
		double minutes;
		if (Counters.isKc(counter))
		{
			minutes = minutesPerKill(Counters.suffix(counter));
		}
		else if (Counters.isClues(counter))
		{
			minutes = BossData.minutesPerClue(Counters.suffix(counter));
		}
		else
		{
			return -1;
		}
		return minutes <= 0 ? -1 : (to - from) * minutes / 60;
	}

	/**
	 * Boss names the player has kill counts for, followed by other known bosses.
	 */
	public synchronized List<String> knownBosses()
	{
		Set<String> names = new java.util.LinkedHashSet<>();
		if (profile != null)
		{
			profile.getKillCounts().entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.forEach(e -> names.add(e.getKey()));
		}
		for (BossData.Boss b : bossData.all())
		{
			names.add(b.getName());
		}
		return new ArrayList<>(names);
	}

	/**
	 * Coins and platinum tokens seen in the inventory, or in the bank when it's open.
	 */
	public synchronized void onCash(boolean bank, long gp)
	{
		if (profile == null)
		{
			return;
		}
		long before = profile.getBankCash() + profile.getInventoryCash();
		if (bank)
		{
			profile.setBankCash(gp);
			profile.setBankCashKnown(true);
		}
		else
		{
			profile.setInventoryCash(gp);
		}
		if (profile.getBankCash() + profile.getInventoryCash() != before)
		{
			profileDirty = true;
			if (baselineSet)
			{
				checkGoals();
			}
			changed(false);
		}
	}

	public synchronized boolean isBankCashKnown()
	{
		return profile != null && profile.isBankCashKnown();
	}

	@Value
	public static class PurchaseRef
	{
		String goalId;
		int itemId;
		int quantity;
	}

	/**
	 * Active purchase goals whose target follows the item's GE price.
	 */
	public synchronized List<PurchaseRef> purchaseRefs()
	{
		List<PurchaseRef> refs = new ArrayList<>();
		if (profile == null)
		{
			return refs;
		}
		for (Goal g : profile.getGoals())
		{
			if (g.getType() == GoalType.PURCHASE && !g.isComplete() && !g.isFixedPrice() && !g.getItems().isEmpty()
				&& g.getItems().get(0).getId() > 0)
			{
				refs.add(new PurchaseRef(g.getId(), g.getItems().get(0).getId(), Math.max(1, g.getQuantity())));
			}
		}
		return refs;
	}

	/**
	 * Updates a purchase goal's target when the item's price changes.
	 */
	public synchronized void setPurchaseTarget(String goalId, long target)
	{
		Goal g = findGoal(goalId);
		if (g == null || g.isComplete() || target <= 0 || g.getTargetCount() == target)
		{
			return;
		}
		g.setTargetCount(target);
		GoalPlanner.replan(g, this, config.hoursPerWeek(), System.currentTimeMillis());
		profileDirty = true;
		checkGoals();
		changed(false);
	}

	private ObservedRate observedRate(Skill skill)
	{
		if (profile == null)
		{
			return null;
		}
		ObservedRate r = profile.getObservedRates().get(skill.name());
		return r != null && r.getMillis() >= XpRates.MIN_OBSERVED_MILLIS && r.getXp() > 0 ? r : null;
	}

	/**
	 * The training method used for estimates: the player's chosen one, otherwise the one matching
	 * their configured intensity.
	 */
	private TrainingMethod method(Skill skill, long atXp)
	{
		String preferred = profile == null ? null : profile.getPreferredMethods().get(skill.name());
		TrainingMethod m = preferred == null ? null : trainingMethods.find(skill, preferred);
		return m != null ? m : trainingMethods.defaultFor(skill, config.intensity(), atXp);
	}

	@Override
	public synchronized boolean isPersonal(Skill skill)
	{
		if (observedRate(skill) == null)
		{
			return false;
		}
		String preferred = profile.getPreferredMethods().get(skill.name());
		return OWN_RATE.equals(preferred) || (preferred == null && config.usePersonalRates());
	}

	@Override
	public synchronized double hours(Skill skill, long fromXp, long toXp)
	{
		if (toXp <= fromXp)
		{
			return 0;
		}
		if (isPersonal(skill))
		{
			return (toXp - fromXp) / observedRate(skill).xpPerHour();
		}
		TrainingMethod m = method(skill, fromXp);
		return m == null ? (toXp - fromXp) / XpRates.defaultRate(skill, config.intensity()) : m.hours(fromXp, toXp);
	}

	@Override
	public synchronized double rate(Skill skill, long atXp)
	{
		if (isPersonal(skill))
		{
			return observedRate(skill).xpPerHour();
		}
		TrainingMethod m = method(skill, atXp);
		return m == null ? XpRates.defaultRate(skill, config.intensity()) : m.rateAt(atXp);
	}

	@Override
	public synchronized String methodName(Skill skill, long atXp)
	{
		if (isPersonal(skill))
		{
			return OWN_RATE;
		}
		TrainingMethod m = method(skill, atXp);
		return m == null ? "Typical training" : m.getName();
	}

	/**
	 * Method names the player can choose from for a skill, including their own observed rate when known.
	 */
	public synchronized List<String> methodChoices(Skill skill)
	{
		List<String> names = new ArrayList<>();
		for (TrainingMethod m : trainingMethods.forSkill(skill))
		{
			names.add(m.getName());
		}
		if (observedRate(skill) != null)
		{
			names.add(OWN_RATE);
		}
		return names;
	}

	public synchronized void setPreferredMethod(Skill skill, String method)
	{
		if (profile == null)
		{
			return;
		}
		profile.getPreferredMethods().put(skill.name(), method);
		profileDirty = true;
		for (Goal g : profile.getGoals())
		{
			GoalPlanner.replan(g, this, config.hoursPerWeek(), System.currentTimeMillis());
		}
		changed(false);
	}

	/**
	 * Applies edits to an existing goal and regenerates this week's plan.
	 *
	 * @return null on success, or a message explaining why the edit couldn't be applied
	 */
	public synchronized String updateGoal(Goal edited)
	{
		Goal goal = findGoal(edited.getId());
		if (goal == null)
		{
			return "That goal no longer exists.";
		}
		if (goal.getType() != GoalType.CUSTOM && !goal.isComplete() && GoalPlanner.isComplete(edited, state()))
		{
			return "You've already reached that target. Pick a higher one.";
		}
		goal.setName(edited.getName());
		goal.setTargetXp(edited.getTargetXp());
		goal.setTargetLevel(edited.getTargetLevel());
		goal.setTargetDate(edited.getTargetDate());
		goal.setHoursPerWeek(edited.getHoursPerWeek());
		goal.setNotes(edited.getNotes());
		if (goal.getType().isCounter())
		{
			goal.setTargetCount(edited.isRelative() ? goal.getStartCount() + edited.getTargetCount() : edited.getTargetCount());
			goal.setRelative(edited.isRelative());
		}
		if (goal.getType() == GoalType.PURCHASE)
		{
			goal.setItems(edited.getItems());
			goal.setQuantity(edited.getQuantity());
			goal.setFixedPrice(edited.isFixedPrice());
		}
		if (goal.getType() == GoalType.ITEMS && !edited.getItems().isEmpty())
		{
			// Keep obtained state for items that are still wanted
			List<GoalItem> merged = new ArrayList<>();
			for (GoalItem item : edited.getItems())
			{
				GoalItem existing = goal.getItems().stream()
					.filter(i -> i.getId() == item.getId() || i.getName().equalsIgnoreCase(item.getName()))
					.findFirst().orElse(null);
				merged.add(existing != null ? existing : item);
			}
			goal.setItems(merged);
		}
		GoalPlanner.replan(goal, this, config.hoursPerWeek(), System.currentTimeMillis());
		profileDirty = true;
		changed(false);
		return null;
	}

	public synchronized Goal goal(String id)
	{
		Goal g = findGoal(id);
		return g == null ? null : gson.fromJson(gson.toJson(g), Goal.class);
	}

	/**
	 * @return null on success, or a message explaining why the goal couldn't be created
	 */
	public synchronized String createGoal(Goal goal)
	{
		if (profile == null || !baselineSet)
		{
			return "Log in to create goals.";
		}
		if (goal.getType() == GoalType.ITEMS && goal.getItems().isEmpty())
		{
			return "Choose at least one item.";
		}
		if (goal.getType().isCounter())
		{
			long current = GoalPlanner.counter(state(), goal.getCounter());
			goal.setStartCount(current);
			if (goal.isRelative())
			{
				// The dialog stores "N more" in targetCount
				goal.setTargetCount(current + goal.getTargetCount());
			}
		}
		if (goal.getType() != GoalType.CUSTOM && GoalPlanner.isComplete(goal, state()))
		{
			return "You've already achieved that!";
		}
		goal.setId(UUID.randomUUID().toString());
		goal.setCreatedAt(System.currentTimeMillis());
		goal.setStartXp(state());
		goal.setStartTotalLevel(Skills.totalLevel(xp));
		GoalPlanner.rollWeek(goal, state(), this, config.hoursPerWeek(), LocalDate.now());
		profile.getGoals().add(goal);
		profileDirty = true;

		String detail = goal.getTargetDate() != null ? "Target: " + Format.date(GoalPlanner.parseDate(goal.getTargetDate())) : null;
		addEvent(event(EventType.GOAL_CREATED, "New goal: " + goal.getName(), detail), false);
		return null;
	}

	/**
	 * Marks matching items in item goals as obtained. Items match by id, or by name for sources
	 * that only report names (collection log messages).
	 */
	private void itemsObtained(List<LootItem> items, String source)
	{
		long now = System.currentTimeMillis();
		for (Goal g : profile.getGoals())
		{
			if (g.getType() != GoalType.ITEMS || g.isComplete())
			{
				continue;
			}
			for (GoalItem wanted : g.getItems())
			{
				if (wanted.isObtained())
				{
					continue;
				}
				for (LootItem item : items)
				{
					boolean match = (item.getId() > 0 && item.getId() == wanted.getId())
						|| (item.getName() != null && item.getName().equalsIgnoreCase(wanted.getName()));
					if (match)
					{
						wanted.setObtainedAt(now);
						wanted.setSource(source);
						profileDirty = true;
						long got = g.getItems().stream().filter(GoalItem::isObtained).count();
						JourneyEvent e = event(EventType.GOAL_PROGRESS, "Obtained " + wanted.getName(),
							got + " / " + g.getItems().size() + " for " + g.getName() + (source != null ? " · " + source : ""));
						e.setHighlight(true);
						addEvent(e, false);
						break;
					}
				}
			}
		}
	}

	/**
	 * Manually ticks an item in an item goal on or off, e.g. for items obtained before RuneJourney.
	 */
	public synchronized void setItemObtained(String goalId, int index, boolean obtained)
	{
		Goal g = findGoal(goalId);
		if (g == null || index < 0 || index >= g.getItems().size())
		{
			return;
		}
		GoalItem item = g.getItems().get(index);
		item.setObtainedAt(obtained ? System.currentTimeMillis() : 0);
		item.setSource(obtained ? "Marked manually" : null);
		profileDirty = true;
		checkGoals();
		changed(false);
	}

	public synchronized void deleteGoal(String id)
	{
		if (profile == null)
		{
			return;
		}
		if (profile.getSession() != null && id.equals(profile.getSession().getGoalId()))
		{
			profile.setSession(null);
		}
		profile.getGoals().removeIf(g -> id.equals(g.getId()));
		profileDirty = true;
		changed(false);
	}

	public synchronized void completeGoalManually(String id)
	{
		Goal goal = findGoal(id);
		if (goal != null && !goal.isComplete())
		{
			completeGoal(goal);
		}
	}

	private Goal findGoal(String id)
	{
		if (profile == null || id == null)
		{
			return null;
		}
		for (Goal g : profile.getGoals())
		{
			if (id.equals(g.getId()))
			{
				return g;
			}
		}
		return null;
	}

	private void checkGoals()
	{
		Map<String, Long> state = state();
		for (Goal g : profile.getGoals())
		{
			if (!g.isComplete() && g.getType() != GoalType.CUSTOM && GoalPlanner.isComplete(g, state))
			{
				completeGoal(g);
			}
			else if (g.getType() == GoalType.PURCHASE && !g.isComplete() && !g.isAffordable() && g.getTargetCount() > 0
				&& GoalPlanner.counter(state, Counters.CASH) >= g.getTargetCount())
			{
				g.setAffordable(true);
				profileDirty = true;
				String item = purchaseItemName(g);
				JourneyEvent e = event(EventType.GOAL_PROGRESS, "You can afford " + item + "!",
					Counters.format(Counters.CASH, GoalPlanner.counter(state, Counters.CASH)) + " saved · price "
						+ Counters.format(Counters.CASH, g.getTargetCount()));
				e.setHighlight(true);
				addEvent(e, false);
				announce("You can afford " + item + "! Buy it on the Grand Exchange to complete your goal.");
			}
		}
	}

	private static String purchaseItemName(Goal g)
	{
		if (g.getItems().isEmpty())
		{
			return g.getName();
		}
		return (g.getQuantity() > 1 ? Format.number(g.getQuantity()) + " x " : "") + g.getItems().get(0).getName();
	}

	/**
	 * A Grand Exchange slot changed. Only the change since the slot was last seen is counted, as the
	 * game replays every offer on login.
	 */
	public synchronized void onGrandExchangeOffer(int slot, int itemId, String itemName, boolean buy, boolean empty,
		int quantity, long spent)
	{
		if (profile == null)
		{
			return;
		}
		long[] previous = profile.getGeSlots().get(slot);
		if (empty)
		{
			if (previous != null)
			{
				profile.getGeSlots().remove(slot);
				profileDirty = true;
			}
			return;
		}
		boolean sameOffer = previous != null && previous[0] == itemId && previous[1] <= quantity;
		long boughtBefore = sameOffer ? previous[1] : 0;
		long spentBefore = sameOffer ? previous[2] : 0;
		profile.getGeSlots().put(slot, new long[]{itemId, quantity, spent});
		profileDirty = true;

		int bought = (int) (quantity - boughtBefore);
		if (!buy || bought <= 0)
		{
			return;
		}
		purchased(itemId, itemName, bought, Math.max(0, spent - spentBefore));
	}

	private void purchased(int itemId, String itemName, int quantity, long spent)
	{
		List<LootItem> items = Collections.singletonList(new LootItem(itemId, itemName, quantity, spent));
		itemsObtained(items, "Grand Exchange");

		for (Goal g : profile.getGoals())
		{
			if (g.getType() != GoalType.PURCHASE || g.isComplete() || g.getItems().isEmpty())
			{
				continue;
			}
			GoalItem wanted = g.getItems().get(0);
			boolean match = wanted.getId() == itemId || wanted.getName().equalsIgnoreCase(itemName);
			if (!match)
			{
				continue;
			}
			int needed = Math.max(1, g.getQuantity());
			int counted = Math.min(quantity, needed - g.getPurchasedQuantity());
			g.setPurchasedQuantity(g.getPurchasedQuantity() + counted);
			g.setPurchaseSpent(g.getPurchaseSpent() + spent * counted / Math.max(1, quantity));
			profileDirty = true;

			JourneyEvent e = event(EventType.GOAL_PROGRESS,
				"Bought " + (counted > 1 ? Format.number(counted) + " x " : "") + wanted.getName(),
				Counters.format(Counters.CASH, spent) + " on the Grand Exchange"
					+ (needed > 1 ? " · " + g.getPurchasedQuantity() + " / " + needed + " for " + g.getName() : ""));
			e.setValue(spent);
			e.setHighlight(true);
			addEvent(e, false);

			if (g.getPurchasedQuantity() >= needed)
			{
				completeGoal(g);
			}
			// One purchase completes one goal
			break;
		}
		changed(false);
	}

	private void completeGoal(Goal goal)
	{
		GoalSession session = profile.getSession();
		if (session != null && goal.getId().equals(session.getGoalId()))
		{
			endSession();
		}

		long now = System.currentTimeMillis();
		goal.setCompletedAt(now);
		goal.setStory(buildStory(goal, now));
		profileDirty = true;

		long daysTaken = Math.max(1, ChronoUnit.DAYS.between(toDate(goal.getCreatedAt()), toDate(now)));
		JourneyEvent e = event(EventType.GOAL_COMPLETED, goal.getName() + " achieved!",
			"Your journey took " + daysTaken + (daysTaken == 1 ? " day" : " days"));
		e.setHighlight(true);
		addEvent(e, config.screenshotGoals());
		announce("Goal complete: " + goal.getName() + "! Open RuneJourney to see your journey.");
	}

	private List<String> buildStory(Goal goal, long now)
	{
		List<String> story = new ArrayList<>();
		LocalDate start = toDate(goal.getCreatedAt());
		LocalDate end = toDate(now);
		story.add("Started|" + Format.date(start));
		story.add("Completed|" + Format.date(end));
		story.add("Duration|" + Math.max(1, ChronoUnit.DAYS.between(start, end)) + " days");
		if (goal.getType() == GoalType.PURCHASE && goal.getPurchaseSpent() > 0)
		{
			story.add("Paid|" + Counters.format(Counters.CASH, goal.getPurchaseSpent()));
		}

		long play = 0;
		String bestDay = null;
		long bestDayXp = 0;
		Map<String, Long> skillXp = new HashMap<>();
		for (DayRecord d : days.subMap(start.toString(), true, end.toString(), true).values())
		{
			play += d.getPlayMillis();
			if (d.getXpGained() > bestDayXp)
			{
				bestDayXp = d.getXpGained();
				bestDay = d.getDate();
			}
			d.getSkillXp().forEach((k, v) -> skillXp.merge(k, v, Long::sum));
		}
		story.add("Playtime|" + Format.duration(play));

		long gained = 0;
		for (Skill s : Skills.ALL)
		{
			Long startXp = goal.getStartXp().get(s.name());
			if (startXp != null)
			{
				gained += Math.max(0, Skills.xp(xp, s) - startXp);
			}
		}
		if (goal.getType() != GoalType.CUSTOM)
		{
			story.add("XP gained|" + Format.number(gained));
			story.add("Levels gained|" + Math.max(0, Skills.totalLevel(xp) - goal.getStartTotalLevel()));
		}
		if (bestDay != null)
		{
			story.add("Most productive day|" + Format.date(LocalDate.parse(bestDay)) + " · " + Format.compact(bestDayXp) + " XP");
		}
		skillXp.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(top ->
		{
			Skill s = Skills.parse(top.getKey());
			if (s != null)
			{
				story.add("Favourite skill|" + s.getName() + " · " + Format.compact(top.getValue()) + " XP");
			}
		});
		if (goal.getWeeksPlanned() > 0)
		{
			story.add("Weekly plans met|" + goal.getWeeksMet() + " / " + goal.getWeeksPlanned());
		}
		return story;
	}

	private static LocalDate toDate(long millis)
	{
		return java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
	}

	private void rollWeeksIfNeeded()
	{
		String date = LocalDate.now().toString();
		if (date.equals(lastRollDate))
		{
			return;
		}
		lastRollDate = date;
		for (Goal g : profile.getGoals())
		{
			long[] ended = GoalPlanner.rollWeek(g, state(), this, config.hoursPerWeek(), LocalDate.now());
			if (ended != null)
			{
				profileDirty = true;
				long diff = ended[1] - ended[0];
				String status = diff >= 0 ? "ahead by " + Format.compact(diff) : "behind by " + Format.compact(-diff);
				addEvent(event(EventType.WEEKLY_PLAN, "Week complete: " + g.getName(),
					Format.compact(ended[1]) + " / " + Format.compact(ended[0]) + " XP · " + status), false);
			}
			else if (g.getWeekStart() != null)
			{
				profileDirty = true;
			}
		}
	}

	public synchronized List<GoalProgress> goalProgress()
	{
		List<GoalProgress> result = new ArrayList<>();
		if (profile == null)
		{
			return result;
		}
		LocalDate today = LocalDate.now();
		long now = System.currentTimeMillis();
		for (Goal g : profile.getGoals())
		{
			Goal copy = gson.fromJson(gson.toJson(g), Goal.class);
			result.add(GoalPlanner.compute(copy, state(), this, config.hoursPerWeek(), today, now));
		}
		return result;
	}

	public synchronized void startSession(String goalId)
	{
		Goal goal = findGoal(goalId);
		if (goal == null || !baselineSet)
		{
			return;
		}
		if (profile.getSession() != null)
		{
			endSession();
		}
		GoalSession s = new GoalSession();
		s.setGoalId(goalId);
		s.setStartedAt(System.currentTimeMillis());
		s.setStartXp(state());
		s.setStartProgress(GoalPlanner.percent(goal, state()));
		profile.setSession(s);
		profileDirty = true;
		changed(false);
	}

	public synchronized void endSession()
	{
		if (profile == null || profile.getSession() == null)
		{
			return;
		}
		SessionView view = sessionView();
		profile.setSession(null);
		profileDirty = true;
		if (view != null && view.getActiveMillis() >= 60_000)
		{
			String detail = Format.duration(view.getActiveMillis()) + " · +" + Format.number(view.getXpGained()) + " XP · "
				+ Format.compact((long) view.getXpPerHour()) + " XP/hr";
			if (view.getCurrentProgress() > view.getStartProgress())
			{
				detail += " · " + Format.percent(view.getStartProgress()) + " -> " + Format.percent(view.getCurrentProgress());
			}
			addEvent(event(EventType.SESSION, view.getGoalName() + " session", detail), false);
		}
		changed(false);
	}

	public synchronized SessionView sessionView()
	{
		if (profile == null || profile.getSession() == null)
		{
			return null;
		}
		GoalSession s = profile.getSession();
		Goal goal = findGoal(s.getGoalId());
		if (goal == null)
		{
			return null;
		}

		Set<Skill> skills = goal.getType() == GoalType.CUSTOM
			? new HashSet<>(Skills.ALL)
			: GoalPlanner.resolveTargets(goal, s.getStartXp(), this).keySet();
		long gained = 0;
		long weekTarget = 0;
		long weekAchieved = 0;
		for (Skill skill : skills)
		{
			gained += Math.max(0, Skills.xp(xp, skill) - Skills.xp(s.getStartXp(), skill));
			Long t = goal.getWeekTargets().get(skill.name());
			if (t != null)
			{
				weekTarget += t;
				weekAchieved += Math.min(t, Math.max(0, Skills.xp(xp, skill) - goal.getWeekStartXp().getOrDefault(skill.name(), 0L)));
			}
		}
		double perHour = s.getActiveMillis() > 0 ? gained * 3_600_000d / s.getActiveMillis() : 0;
		return new SessionView(goal.getId(), goal.getName(), s.getActiveMillis(), gained, perHour, s.getStartProgress(),
			GoalPlanner.percent(goal, state()), weekTarget, weekAchieved);
	}

	// ------------------------------------------------------------------
	// Reading history
	// ------------------------------------------------------------------

	public synchronized String playerName()
	{
		return profile == null ? null : profile.getPlayerName();
	}

	/**
	 * Copies of the day records in a date range, for charts and exports.
	 */
	public synchronized List<DayRecord> daysBetween(LocalDate from, LocalDate to)
	{
		List<DayRecord> result = new ArrayList<>();
		for (DayRecord d : days.subMap(from.toString(), true, to.toString(), true).values())
		{
			result.add(copyDay(d));
		}
		return result;
	}

	/**
	 * A detached copy of a day, made field by field (much cheaper than a JSON round trip, which
	 * matters because it's done while holding the lock the game thread also needs).
	 */
	private DayRecord copyDay(DayRecord d)
	{
		DayRecord c = new DayRecord(d.getDate());
		c.setPlayMillis(d.getPlayMillis());
		c.setXpGained(d.getXpGained());
		c.setSkillXp(new HashMap<>(d.getSkillXp()));
		c.setLevelsGained(d.getLevelsGained());
		c.setLootValue(d.getLootValue());
		c.setBossKills(new HashMap<>(d.getBossKills()));
		c.setDeaths(d.getDeaths());
		c.setCollectionLogSlots(d.getCollectionLogSlots());
		c.setQuestsCompleted(d.getQuestsCompleted());
		c.setPersonalBests(d.getPersonalBests());
		c.setSlayerTasks(d.getSlayerTasks());
		c.setCluesCompleted(d.getCluesCompleted());
		c.setPets(d.getPets());
		c.setClues(new HashMap<>(d.getClues()));
		c.setClueLootValue(d.getClueLootValue());
		c.setSkillingIncome(d.getSkillingIncome());
		c.setSkillingIncomeBySkill(new HashMap<>(d.getSkillingIncomeBySkill()));
		Map<String, LootSource> sources = new HashMap<>();
		d.getLootBySource().forEach((k, v) -> sources.put(k, v.copy()));
		c.setLootBySource(sources);
		c.setSuppliesCost(d.getSuppliesCost());
		Map<String, ItemTotal> supplies = new HashMap<>();
		d.getSuppliesUsed().forEach((k, v) -> supplies.put(k, new ItemTotal(v.getQuantity(), v.getValue())));
		c.setSuppliesUsed(supplies);
		c.setOfflineXp(d.getOfflineXp());
		c.setOfflineSkillXp(new HashMap<>(d.getOfflineSkillXp()));
		c.setCombatTasks(d.getCombatTasks());
		c.setCombatTaskPoints(d.getCombatTaskPoints());
		c.setSnapshot(new HashMap<>(d.getSnapshot()));
		List<JourneyEvent> events = new ArrayList<>(d.getEvents().size());
		for (JourneyEvent e : d.getEvents())
		{
			events.add(copy(e));
		}
		c.setEvents(events);
		return c;
	}

	public synchronized LocalDate firstDay()
	{
		return days.isEmpty() ? LocalDate.now() : LocalDate.parse(days.firstKey());
	}

	public synchronized RangeSummary summarize(LocalDate from, LocalDate to)
	{
		RangeSummary r = new RangeSummary();
		r.setFrom(from);
		r.setTo(to);
		List<JourneyEvent> highlights = new ArrayList<>();
		for (DayRecord d : days.subMap(from.toString(), true, to.toString(), true).values())
		{
			if (d.getPlayMillis() > 0)
			{
				r.setDaysPlayed(r.getDaysPlayed() + 1);
			}
			r.setPlayMillis(r.getPlayMillis() + d.getPlayMillis());
			r.setXpGained(r.getXpGained() + d.getXpGained());
			r.setLevelsGained(r.getLevelsGained() + d.getLevelsGained());
			r.setLootValue(r.getLootValue() + d.getLootValue());
			r.setDeaths(r.getDeaths() + d.getDeaths());
			r.setCollectionLogSlots(r.getCollectionLogSlots() + d.getCollectionLogSlots());
			r.setQuestsCompleted(r.getQuestsCompleted() + d.getQuestsCompleted());
			r.setPersonalBests(r.getPersonalBests() + d.getPersonalBests());
			r.setPets(r.getPets() + d.getPets());
			r.setSlayerTasks(r.getSlayerTasks() + d.getSlayerTasks());
			r.setCluesCompleted(r.getCluesCompleted() + d.getCluesCompleted());
			r.setClueLootValue(r.getClueLootValue() + d.getClueLootValue());
			r.setSkillingIncome(r.getSkillingIncome() + d.getSkillingIncome());
			d.getSkillingIncomeBySkill().forEach((k, v) -> r.getSkillingIncomeBySkill().merge(k, v, Long::sum));
			d.getLootBySource().forEach((k, v) -> r.getLootBySource().computeIfAbsent(k, x -> new LootSource()).add(v));
			r.setSuppliesCost(r.getSuppliesCost() + d.getSuppliesCost());
			d.getSuppliesUsed().forEach((k, v) -> r.getSuppliesUsed().computeIfAbsent(k, x -> new ItemTotal()).add(v.getQuantity(), v.getValue()));
			r.setCombatTasks(r.getCombatTasks() + d.getCombatTasks());
			r.setCombatTaskPoints(r.getCombatTaskPoints() + d.getCombatTaskPoints());
			d.getClues().forEach((k, v) -> r.getClues().merge(k, v, Integer::sum));
			if (!d.getSnapshot().isEmpty())
			{
				if (r.getStartSnapshot().isEmpty())
				{
					r.getStartSnapshot().putAll(d.getSnapshot());
				}
				r.getEndSnapshot().clear();
				r.getEndSnapshot().putAll(d.getSnapshot());
			}
			d.getSkillXp().forEach((k, v) -> r.getSkillXp().merge(k, v, Long::sum));
			d.getBossKills().forEach((k, v) ->
			{
				r.getBossKillsByName().merge(k, v, Integer::sum);
				r.setBossKills(r.getBossKills() + v);
			});
			if (d.getXpGained() > r.getBiggestDayXp())
			{
				r.setBiggestDayXp(d.getXpGained());
				r.setBiggestDay(d.getDate());
			}
			for (JourneyEvent e : d.getEvents())
			{
				if (e.getType() == EventType.LEVEL && e.getSkill() != null && e.getValue() > 0)
				{
					int level = (int) e.getValue();
					r.getLevelRanges().merge(e.getSkill(), new int[]{level - 1, level},
						(a, b) -> new int[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
				}
				if (e.isHighlight())
				{
					highlights.add(e);
				}
				switch (e.getType())
				{
					case DROP:
						r.getDrops().add(copy(e));
						break;
					case COLLECTION_LOG:
						r.getCollectionLogItems().add(e.getTitle());
						break;
					case QUEST:
						r.getQuests().add(e.getTitle().replaceFirst("^Completed ", ""));
						break;
					case DIARY:
						r.getDiaries().add(e.getTitle());
						break;
					case COMBAT_TASK:
						r.getCombatTaskNames().add(e.getTitle() + (e.getDetail() != null ? " (" + e.getDetail() + ")" : ""));
						break;
					case PERSONAL_BEST:
						r.getPersonalBestList().add(e.getTitle() + (e.getDetail() != null ? " · " + e.getDetail() : ""));
						break;
					case PET:
						if (e.isHighlight())
						{
							r.getPetList().add(e.getDetail() != null ? e.getDetail() : "Pet");
						}
						break;
					case GOAL_COMPLETED:
						r.getGoalsCompleted().add(e.getTitle());
						break;
					default:
						break;
				}
			}
		}
		// The start snapshot is the end of the day before the range, when available
		Map.Entry<String, DayRecord> before = days.lowerEntry(from.toString());
		while (before != null && before.getValue().getSnapshot().isEmpty())
		{
			before = days.lowerEntry(before.getKey());
		}
		if (before != null)
		{
			r.getStartSnapshot().clear();
			r.getStartSnapshot().putAll(before.getValue().getSnapshot());
		}
		if (!to.isBefore(LocalDate.now()) && baselineSet)
		{
			r.getEndSnapshot().clear();
			r.getEndSnapshot().putAll(state());
		}
		r.getDrops().sort(Comparator.comparingLong(JourneyEvent::getValue).reversed());
		highlights.sort(Comparator.comparingLong(JourneyEvent::getTime).reversed());
		r.setBestMoment(bestMoment(highlights));
		for (int i = 0; i < Math.min(12, highlights.size()); i++)
		{
			r.getHighlights().add(copy(highlights.get(i)));
		}
		return r;
	}

	private JourneyEvent bestMoment(List<JourneyEvent> events)
	{
		JourneyEvent best = null;
		int bestRank = Integer.MAX_VALUE;
		for (JourneyEvent e : events)
		{
			int rank;
			switch (e.getType())
			{
				case GOAL_COMPLETED:
					rank = 0;
					break;
				case PET:
					rank = 1;
					break;
				case DROP:
					rank = 2;
					break;
				case COLLECTION_LOG:
					rank = 3;
					break;
				case TOTAL_LEVEL:
				case LEVEL:
					rank = e.getValue() >= Experience.MAX_REAL_LEVEL || e.getType() == EventType.TOTAL_LEVEL ? 4 : 6;
					break;
				default:
					rank = 5;
			}
			if (rank < bestRank || (rank == bestRank && best != null && e.getValue() > best.getValue()))
			{
				best = e;
				bestRank = rank;
			}
		}
		return best == null ? null : copy(best);
	}

	private JourneyEvent copy(JourneyEvent e)
	{
		JourneyEvent c = new JourneyEvent(e.getTime(), e.getType(), e.getTitle(), e.getDetail(), e.getSkill(), e.getScreenshot(), e.isHighlight(), e.getValue());
		c.setNote(e.getNote());
		return c;
	}

	/**
	 * Most recent days that contain matching events, newest first, with events newest first.
	 */
	public synchronized List<DayRecord> journeyDays(int limit, EventType.Category category, boolean highlightsOnly)
	{
		List<DayRecord> result = new ArrayList<>();
		for (DayRecord d : days.descendingMap().values())
		{
			if (result.size() >= limit)
			{
				break;
			}
			List<JourneyEvent> events = new ArrayList<>();
			for (JourneyEvent e : d.getEvents())
			{
				if ((category == null || e.getType().getCategory() == category) && (!highlightsOnly || e.isHighlight()))
				{
					events.add(copy(e));
				}
			}
			if (events.isEmpty())
			{
				continue;
			}
			Collections.reverse(events);
			DayRecord copy = new DayRecord(d.getDate());
			copy.setPlayMillis(d.getPlayMillis());
			copy.setXpGained(d.getXpGained());
			copy.setLootValue(d.getLootValue());
			copy.setEvents(events);
			result.add(copy);
		}
		return result;
	}

	// ------------------------------------------------------------------
	// Discover
	// ------------------------------------------------------------------

	public synchronized Map<String, List<Suggestion>> discover(int minutes)
	{
		Map<String, List<Suggestion>> sections = new LinkedHashMap<>();
		if (profile == null || !baselineSet)
		{
			return sections;
		}
		String time = Format.hours(minutes / 60d);

		// Goal work: skills and counters behind this week's plans, most behind first
		List<Suggestion> todo = new ArrayList<>();
		List<double[]> order = new ArrayList<>();
		for (GoalProgress p : goalProgress())
		{
			if (p.isComplete())
			{
				continue;
			}
			for (GoalProgress.WeekRow w : p.getWeek())
			{
				long left = w.getTarget() - w.getAchieved();
				if (left <= 0)
				{
					continue;
				}
				double rate = rate(w.getSkill(), Skills.xp(xp, w.getSkill()));
				long inTime = (long) Math.min(left, rate * minutes / 60d);
				todo.add(new Suggestion(w.getSkill().getName() + " · " + p.getGoal().getName(),
					"~+" + Format.compact(inTime) + " XP in " + time + " · " + Format.compact(left) + " left this week",
					w.getSkill(), w.getAchieved() / (double) Math.max(1, w.getTarget())));
				order.add(new double[]{left / (double) Math.max(1, w.getTarget()), todo.size() - 1});
			}

			Goal g = p.getGoal();
			long countLeft = p.getCountWeekTarget() - p.getCountWeekAchieved();
			if (g.getCounter() != null && countLeft > 0)
			{
				double perUnit = counterHours(g.getCounter(), 0, 1) * 60;
				String what = Counters.isKc(g.getCounter()) ? Counters.suffix(g.getCounter()) : Counters.label(g.getCounter());
				String detail;
				if (perUnit > 0)
				{
					long fit = (long) Math.floor(minutes / perUnit);
					if (fit < 1)
					{
						continue;
					}
					detail = "~" + unitText(g.getCounter(), Math.min(fit, countLeft)) + " in " + time + " · "
						+ unitText(g.getCounter(), countLeft) + " left this week";
				}
				else
				{
					detail = unitText(g.getCounter(), countLeft) + " left this week";
				}
				todo.add(new Suggestion(what + " · " + g.getName(), detail, null,
					p.getCountWeekAchieved() / (double) Math.max(1, p.getCountWeekTarget())));
				order.add(new double[]{countLeft / (double) Math.max(1, p.getCountWeekTarget()), todo.size() - 1});
			}
		}
		order.sort((a, b) -> Double.compare(b[0], a[0]));
		List<Suggestion> goalWork = new ArrayList<>();
		for (int i = 0; i < Math.min(6, order.size()); i++)
		{
			goalWork.add(todo.get((int) order.get(i)[1]));
		}
		sections.put("Work on your goals", goalWork);
		sections.put("Bossing & clues", bossing(minutes));
		sections.put("You're close", nearCompletion());

		List<Suggestion> rates = new ArrayList<>();
		for (Skill s : Skills.ALL)
		{
			ObservedRate r = profile.getObservedRates().get(s.name());
			if (r != null && r.getMillis() >= XpRates.MIN_OBSERVED_MILLIS && r.getXp() > 0)
			{
				rates.add(new Suggestion(s.getName() + ": " + Format.compact((long) r.xpPerHour()) + " XP/hr",
					"From " + Format.duration(r.getMillis()) + " of training · " + method(s, Skills.xp(xp, s)).getName() + " estimate "
						+ Format.compact((long) method(s, Skills.xp(xp, s)).rateAt(Skills.xp(xp, s))), s, -1));
			}
		}
		profile.getKillTimes().entrySet().stream()
			.filter(e -> e.getValue().getXp() >= MIN_OBSERVED_KILLS)
			.sorted((a, b) -> Long.compare(b.getValue().getXp(), a.getValue().getXp()))
			.limit(8)
			.forEach(e ->
			{
				double mins = e.getValue().getMillis() / 60_000d / e.getValue().getXp();
				double typical = bossData.minutesPerKill(e.getKey());
				rates.add(new Suggestion(e.getKey() + ": " + Format.clock(mins) + " per kill",
					"From " + e.getValue().getXp() + " timed kills" + (typical > 0 ? " · typical " + Format.clock(typical) : ""), null, -1));
			});
		sections.put("Your rates", rates);
		return sections;
	}

	private static String unitText(String counter, long value)
	{
		return Counters.isMoney(counter) ? Counters.format(counter, value) : value + " " + Counters.unit(counter);
	}

	/**
	 * Bosses, raids and clues that fit in the time available, favouring ones the player has done
	 * recently and ones close to a kill count milestone.
	 */
	private List<Suggestion> bossing(int minutes)
	{
		Map<String, Integer> recent = new HashMap<>();
		String since = LocalDate.now().minusDays(30).toString();
		for (DayRecord d : days.tailMap(since, true).values())
		{
			d.getBossKills().forEach((k, v) -> recent.merge(k, v, Integer::sum));
		}

		List<Suggestion> result = new ArrayList<>();
		List<double[]> order = new ArrayList<>();
		for (Map.Entry<String, Integer> kc : profile.getKillCounts().entrySet())
		{
			String boss = kc.getKey();
			double perKill = minutesPerKill(boss);
			if (perKill <= 0 || perKill > minutes)
			{
				continue;
			}
			int fit = (int) Math.floor(minutes / perKill);
			int next = nextKcMilestone(kc.getValue(), config.kcMilestoneInterval());
			int toNext = next - kc.getValue();

			StringBuilder detail = new StringBuilder("~").append(fit).append(fit == 1 ? " kill" : " kills")
				.append(" in ").append(Format.hours(minutes / 60d));
			if (toNext <= fit)
			{
				detail.append(" · reach ").append(Format.number(next)).append(" KC!");
			}
			else
			{
				detail.append(" · ").append(toNext).append(" to ").append(Format.number(next)).append(" KC");
			}
			detail.append(" · ").append(Format.clock(perKill)).append(" per kill").append(isPersonalKillTime(boss) ? " (yours)" : "");

			int recentKills = recent.getOrDefault(boss, 0);
			double score = recentKills + (toNext <= fit ? 50 : 0) + Math.log1p(kc.getValue());
			result.add(new Suggestion(boss + " (" + Format.number(kc.getValue()) + " KC)", detail.toString(), null,
				kc.getValue() / (double) next));
			order.add(new double[]{score, result.size() - 1});
		}

		// Clues: suggest the tiers the player actually does
		profile.getClueCounts().entrySet().stream()
			.filter(e -> e.getValue() > 0)
			.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
			.limit(2)
			.forEach(e ->
			{
				double perClue = BossData.minutesPerClue(e.getKey());
				int fit = (int) Math.floor(minutes / perClue);
				if (fit >= 1)
				{
					result.add(new Suggestion(e.getKey() + " clues (" + Format.number(e.getValue()) + " done)",
						"~" + fit + (fit == 1 ? " clue" : " clues") + " in " + Format.hours(minutes / 60d), null, -1));
					order.add(new double[]{5 + e.getValue() / 100d, result.size() - 1});
				}
			});

		order.sort((a, b) -> Double.compare(b[0], a[0]));
		List<Suggestion> sorted = new ArrayList<>();
		for (int i = 0; i < Math.min(6, order.size()); i++)
		{
			sorted.add(result.get((int) order.get(i)[1]));
		}
		return sorted;
	}

	private List<Suggestion> nearCompletion()
	{
		List<Suggestion> close = new ArrayList<>();
		List<double[]> order = new ArrayList<>();
		Set<Integer> milestones = new HashSet<>(parseInts(config.milestoneLevels()));

		for (Skill s : Skills.ALL)
		{
			long cur = Skills.xp(xp, s);
			int level = Skills.level(cur);
			if (level >= Experience.MAX_REAL_LEVEL)
			{
				continue;
			}
			long levelStart = Skills.xpForLevel(level);
			long next = Skills.xpForLevel(level + 1);
			long left = next - cur;
			double hours = hours(s, cur, next);
			double fraction = (cur - levelStart) / (double) Math.max(1, next - levelStart);
			boolean milestone = milestones.contains(level + 1);
			if (hours <= (milestone ? 3 : 0.75) || fraction >= 0.9)
			{
				close.add(new Suggestion((level + 1) + " " + s.getName(),
					Format.number(left) + " XP remaining · ~" + Format.hours(hours), s, fraction));
				order.add(new double[]{milestone ? hours / 3 : hours, close.size() - 1});
			}
		}

		int total = Skills.totalLevel(xp);
		for (int m : new TreeSet<>(parseInts(config.totalLevelMilestones())))
		{
			if (m > total)
			{
				if (m - total <= 10)
				{
					close.add(new Suggestion(Format.number(m) + " Total level", (m - total) + " levels to go", null, -1));
					order.add(new double[]{0.1, close.size() - 1});
				}
				break;
			}
		}

		for (Map.Entry<String, Integer> kc : profile.getKillCounts().entrySet())
		{
			int next = nextKcMilestone(kc.getValue(), config.kcMilestoneInterval());
			int left = next - kc.getValue();
			if (left <= Math.max(3, next / 20))
			{
				close.add(new Suggestion(Format.number(next) + " " + kc.getKey() + " KC",
					left + (left == 1 ? " kill" : " kills") + " to go", null, kc.getValue() / (double) next));
				order.add(new double[]{left / 10d, close.size() - 1});
			}
		}

		for (Map.Entry<String, List<ClogItem>> page : profile.getCollectionLog().entrySet())
		{
			List<ClogItem> items = page.getValue();
			long got = items.stream().filter(ClogItem::isObtained).count();
			long left = items.size() - got;
			if (got > 0 && left > 0 && left <= 2)
			{
				String missing = items.stream().filter(i -> !i.isObtained()).map(ClogItem::getName)
					.collect(java.util.stream.Collectors.joining(", "));
				close.add(new Suggestion(page.getKey() + " log", left + (left == 1 ? " item left: " : " items left: ") + missing,
					null, got / (double) items.size()));
				order.add(new double[]{left / 2d, close.size() - 1});
			}
		}

		for (Map.Entry<String, Integer> clue : profile.getClueCounts().entrySet())
		{
			int next = (clue.getValue() / 100 + 1) * 100;
			int left = next - clue.getValue();
			if (clue.getValue() > 0 && left <= 5)
			{
				close.add(new Suggestion(Format.number(next) + " " + clue.getKey() + " clues",
					left + (left == 1 ? " clue" : " clues") + " to go", null, clue.getValue() / (double) next));
				order.add(new double[]{left / 5d, close.size() - 1});
			}
		}

		for (Goal g : profile.getGoals())
		{
			double pct = GoalPlanner.percent(g, state());
			if (!g.isComplete() && g.getType() != GoalType.CUSTOM && pct >= 0.9)
			{
				close.add(new Suggestion(g.getName(), Format.percent(pct) + " complete", Skills.parse(g.getSkill()), pct));
				order.add(new double[]{(1 - pct) * 10, close.size() - 1});
			}
		}

		order.sort(Comparator.comparingDouble(a -> a[0]));
		List<Suggestion> sorted = new ArrayList<>();
		for (int i = 0; i < Math.min(8, order.size()); i++)
		{
			sorted.add(close.get((int) order.get(i)[1]));
		}
		return sorted;
	}

	public synchronized Map<String, Long> currentXp()
	{
		return new HashMap<>(xp);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private boolean isMilestoneLevel(int level)
	{
		return level == Experience.MAX_REAL_LEVEL || parseInts(config.milestoneLevels()).contains(level);
	}

	static boolean isKcMilestone(int count, int interval)
	{
		if (count == 1 || count == 10 || count == 25)
		{
			return true;
		}
		if (interval > 0)
		{
			return count % interval == 0;
		}
		for (int m : KC_MILESTONES)
		{
			if (count == m)
			{
				return true;
			}
		}
		return count > 1000 && count % 1000 == 0;
	}

	static int nextKcMilestone(int count, int interval)
	{
		for (int m = count + 1; m <= count + 1000; m++)
		{
			if (isKcMilestone(m, interval))
			{
				return m;
			}
		}
		return (count / 1000 + 1) * 1000;
	}

	static List<Integer> parseInts(String csv)
	{
		List<Integer> out = new ArrayList<>();
		if (csv == null)
		{
			return out;
		}
		for (String part : csv.split(","))
		{
			try
			{
				out.add(Integer.parseInt(part.trim()));
			}
			catch (NumberFormatException ignored)
			{
				// skip malformed entries
			}
		}
		return out;
	}
}
