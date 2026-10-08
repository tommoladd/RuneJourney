package com.runejourney.sync;

import com.google.gson.Gson;
import com.runejourney.model.Goal;
import com.runejourney.model.GoalItem;
import com.runejourney.model.GoalSession;
import com.runejourney.model.GoalType;
import com.runejourney.model.ProfileData;
import com.runejourney.model.ProfileSlice;
import com.runejourney.model.ProfileSync;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ProfileJoinTest
{
	private final Gson gson = Trees.withoutSync(new Gson());

	private static Hlc clock(long start)
	{
		return new Hlc(start, () -> 0);
	}

	private static ProfileSlice slice(ProfileData p)
	{
		ProfileSlice s = new ProfileSlice();
		s.setProfile(p);
		return s;
	}

	private static Goal goal(String id, String name)
	{
		Goal g = new Goal();
		g.setId(id);
		g.setType(GoalType.ITEMS);
		g.setName(name);
		g.setCreatedAt(1_000);
		g.getItems().add(new GoalItem(7462, "Barrows gloves"));
		return g;
	}

	/**
	 * Every field of the profile (and of a goal) must have a combining rule.
	 */
	@Test
	public void everyFieldHasARule()
	{
		ProfileData p = new ProfileData();
		p.setPlayerName("x");
		p.setLongestSessionDate("x");
		p.setWrappedSeen("x");
		p.setWrappedNotified("x");
		p.setOverlayGoalId("x");
		p.setSession(new GoalSession());
		p.setSync(new ProfileSync());
		assertEquals(new Gson().toJsonTree(p).getAsJsonObject().keySet(), ProfileJoin.RULES.keySet());

		Goal g = goal("g", "x");
		g.setSkill("x");
		g.setTargetDate("x");
		g.setNotes("x");
		g.setCounter("x");
		g.setWeekStart("x");
		Set<String> covered = new HashSet<>();
		for (Set<String> part : new Set[]{GoalJoin.SAME, GoalJoin.SPEC, GoalJoin.PLAN, GoalJoin.DONE, GoalJoin.ANY, GoalJoin.SUM})
		{
			covered.addAll(part);
		}
		assertEquals(new Gson().toJsonTree(g).getAsJsonObject().keySet(), covered);
		GoalItem item = new GoalItem(1, "x");
		item.setSource("x");
		Set<String> itemFields = new Gson().toJsonTree(item).getAsJsonObject().keySet();
		assertTrue(itemFields.containsAll(GoalJoin.ITEM_STATE));
	}

	@Test
	public void countersOnlyGoUp()
	{
		ProfileData a = new ProfileData();
		a.getLastXp().put("ATTACK", 500L);
		a.getKillCounts().put("Zulrah", 10);
		a.setQuestPoints(100);
		ProfileData b = new ProfileData();
		b.getLastXp().put("ATTACK", 400L);
		b.getLastXp().put("MINING", 50L);
		b.getKillCounts().put("Zulrah", 12);
		b.setQuestPoints(90);
		b.setCombatTasksFromGame(true);
		Map<String, ProfileSlice> slices = new HashMap<>();
		slices.put("a", slice(a));
		slices.put("b", slice(b));

		ProfileData p = ProfileJoin.combine(gson, null, slices);
		assertEquals(500L, (long) p.getLastXp().get("ATTACK"));
		assertEquals(50L, (long) p.getLastXp().get("MINING"));
		assertEquals(12, (int) p.getKillCounts().get("Zulrah"));
		assertEquals(100, p.getQuestPoints());
		assertTrue(p.isCombatTasksFromGame());
	}

	@Test
	public void newestChangeWinsAndTheNameStaysLocal()
	{
		ProfileData shared = new ProfileData();
		shared.setOverlayGoalId("g1");
		Map<String, ProfileSlice> slices = new HashMap<>();
		slices.put("b", slice(shared));

		ProfileData mine = ProfileJoin.combine(gson, shared, slices);
		mine.setPlayerName("Zezima");
		mine.setOverlayGoalId("g2");
		ProfileSlice own = ProfileJoin.reconcile(gson, mine, "a", slices, clock(10));
		assertNull(own.getProfile().getPlayerName());

		slices.put("a", own);
		ProfileData p = ProfileJoin.combine(gson, mine, slices);
		assertEquals("g2", p.getOverlayGoalId());
		assertEquals("Zezima", p.getPlayerName());
		assertTrue(ProfileJoin.matches(gson, mine, slices));
	}

	@Test
	public void killTimesAddUp()
	{
		ProfileData a = new ProfileData();
		a.getKillTimes().put("Vorkath", rate(5, 600_000));
		ProfileData b = new ProfileData();
		b.getKillTimes().put("Vorkath", rate(3, 300_000));
		Map<String, ProfileSlice> slices = new HashMap<>();
		slices.put("a", slice(a));
		slices.put("b", slice(b));
		ProfileData view = ProfileJoin.combine(gson, null, slices);
		assertEquals(8, view.getKillTimes().get("Vorkath").getXp());

		// A kills two more; its own copy holds only its own kills
		view.getKillTimes().get("Vorkath").setXp(10);
		ProfileSlice own = ProfileJoin.reconcile(gson, view, "a", slices, clock(1));
		assertEquals(7, own.getProfile().getKillTimes().get("Vorkath").getXp());
	}

	@Test
	public void goalEditedOnOnePcAndCompletedOnAnother()
	{
		ProfileData start = new ProfileData();
		start.getGoals().add(goal("g", "Gloves"));
		Map<String, ProfileSlice> slices = new HashMap<>();
		slices.put("a", ProfileJoin.reconcile(gson, start, "a", new HashMap<>(), clock(1)));

		// A renames it at the login screen
		ProfileData onA = ProfileJoin.combine(gson, null, slices);
		onA.getGoals().get(0).setName("Barrows gloves");
		ProfileSlice a2 = ProfileJoin.reconcile(gson, onA, "a", slices, clock(100));

		// B, which had only seen the original, gets the gloves and completes it
		ProfileData onB = ProfileJoin.combine(gson, null, slices);
		onB.getGoals().get(0).getItems().get(0).setObtainedAt(5_000);
		onB.getGoals().get(0).setCompletedAt(5_000);
		ProfileSlice b2 = ProfileJoin.reconcile(gson, onB, "b", slices, clock(50));

		Map<String, ProfileSlice> after = new HashMap<>();
		after.put("a", a2);
		after.put("b", b2);
		Goal g = ProfileJoin.combine(gson, null, after).getGoals().get(0);
		assertEquals("Barrows gloves", g.getName());
		assertEquals(5_000, g.getCompletedAt());
		assertTrue(g.getItems().get(0).isObtained());
	}

	@Test
	public void purchasesAddUpAndDeletedGoalsStayDeleted()
	{
		ProfileData start = new ProfileData();
		start.getGoals().add(goal("g", "Buy"));
		start.getGoals().add(goal("h", "Other"));
		Map<String, ProfileSlice> slices = new HashMap<>();
		slices.put("a", ProfileJoin.reconcile(gson, start, "a", new HashMap<>(), clock(1)));

		ProfileData onA = ProfileJoin.combine(gson, null, slices);
		onA.getGoals().get(0).setPurchasedQuantity(2);
		ProfileSlice a2 = ProfileJoin.reconcile(gson, onA, "a", slices, clock(10));
		ProfileData onB = ProfileJoin.combine(gson, null, slices);
		onB.getGoals().get(0).setPurchasedQuantity(3);
		onB.getGoals().remove(1);
		ProfileSlice b2 = ProfileJoin.reconcile(gson, onB, "b", slices, clock(10));

		Map<String, ProfileSlice> after = new HashMap<>();
		after.put("a", a2);
		after.put("b", b2);
		ProfileData p = ProfileJoin.combine(gson, null, after);
		assertEquals(1, p.getGoals().size());
		assertEquals(5, p.getGoals().get(0).getPurchasedQuantity());
	}

	@Test
	public void thePlanForTheLatestWeekWins()
	{
		ProfileData start = new ProfileData();
		start.getGoals().add(goal("g", "Plan"));
		Map<String, ProfileSlice> slices = new HashMap<>();
		slices.put("a", ProfileJoin.reconcile(gson, start, "a", new HashMap<>(), clock(1)));

		ProfileData onA = ProfileJoin.combine(gson, null, slices);
		onA.getGoals().get(0).setWeekStart("2026-10-05");
		onA.getGoals().get(0).setWeeksPlanned(4);
		ProfileSlice a2 = ProfileJoin.reconcile(gson, onA, "a", slices, clock(5));
		ProfileData onB = ProfileJoin.combine(gson, null, slices);
		onB.getGoals().get(0).setWeekStart("2026-09-28");
		onB.getGoals().get(0).setWeeksPlanned(3);
		ProfileSlice b2 = ProfileJoin.reconcile(gson, onB, "b", slices, clock(50));

		Map<String, ProfileSlice> after = new HashMap<>();
		after.put("a", a2);
		after.put("b", b2);
		Goal g = ProfileJoin.combine(gson, null, after).getGoals().get(0);
		assertEquals("2026-10-05", g.getWeekStart());
		assertEquals(4, g.getWeeksPlanned());
	}

	@Test
	public void untickingAnItemLaterWins()
	{
		ProfileData start = new ProfileData();
		Goal g0 = goal("g", "Gloves");
		g0.getItems().get(0).setObtainedAt(1_000);
		start.getGoals().add(g0);
		Map<String, ProfileSlice> slices = new HashMap<>();
		slices.put("a", ProfileJoin.reconcile(gson, start, "a", new HashMap<>(), clock(1)));

		ProfileData onB = ProfileJoin.combine(gson, null, slices);
		onB.getGoals().get(0).getItems().get(0).setObtainedAt(0);
		slices.put("b", ProfileJoin.reconcile(gson, onB, "b", slices, clock(100)));
		assertFalse(ProfileJoin.combine(gson, null, slices).getGoals().get(0).getItems().get(0).isObtained());
	}

	private static com.runejourney.model.ObservedRate rate(long kills, long millis)
	{
		com.runejourney.model.ObservedRate r = new com.runejourney.model.ObservedRate();
		r.setXp(kills);
		r.setMillis(millis);
		return r;
	}
}
