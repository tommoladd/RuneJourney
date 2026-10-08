package com.runejourney.cloud;

import com.runejourney.service.PublicSnapshot;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.hiscore.HiscoreResult;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class HiscoresTest
{
	static HiscoreResult result()
	{
		Map<HiscoreSkill, Skill> skills = new EnumMap<>(HiscoreSkill.class);
		skills.put(HiscoreSkill.ATTACK, new Skill(100, 99, 13_034_431));
		skills.put(HiscoreSkill.VARDORVIS, new Skill(5_000, 1_453, -1));
		skills.put(HiscoreSkill.THEATRE_OF_BLOOD, new Skill(9_000, 129, -1));
		skills.put(HiscoreSkill.THE_GAUNTLET, new Skill(9_000, 40, -1));
		skills.put(HiscoreSkill.ZULRAH, new Skill(-1, -1, -1));
		skills.put(HiscoreSkill.CLUE_SCROLL_ALL, new Skill(1, 30, -1));
		skills.put(HiscoreSkill.CLUE_SCROLL_ELITE, new Skill(1, 26, -1));
		skills.put(HiscoreSkill.CLUE_SCROLL_HARD, new Skill(1, 4, -1));
		skills.put(HiscoreSkill.LAST_MAN_STANDING, new Skill(1, 520, -1));
		skills.put(HiscoreSkill.COLLECTIONS_LOGGED, new Skill(1, 640, -1));
		return new HiscoreResult("Tommo Ladd", skills);
	}

	@Test
	public void readsBossesCluesAndMinigames()
	{
		Hiscores.Entry e = Hiscores.from("Tommo Ladd", result(), 1L);

		assertEquals(1_453, (int) e.getBosses().get(HiscoreSkill.VARDORVIS.getName()));
		assertFalse("Unranked", e.getBosses().containsKey(HiscoreSkill.ZULRAH.getName()));
		assertFalse("Skills aren't kills", e.getBosses().containsKey(HiscoreSkill.ATTACK.getName()));
		assertEquals(26, (int) e.getClues().get("Elite"));
		assertEquals(2, e.getClues().size());
		assertEquals(520, (int) e.getActivities().get(HiscoreSkill.LAST_MAN_STANDING.getName()));
		assertEquals(640, e.getCollections());
		assertTrue(Hiscores.from("Nobody", null, 1L).getBosses().isEmpty());
	}

	@Test
	public void theHigherCountWinsForEachBoss()
	{
		PublicSnapshot page = new PublicSnapshot();
		PublicSnapshot.Kills kills = new PublicSnapshot.Kills();
		// Seen by RuneJourney since the hiscores updated, or written slightly differently in chat
		kills.getBosses().put("Vardorvis", 1_460);
		kills.getBosses().put("Gauntlet", 41);
		kills.getBosses().put("Theatre of Blood", 100);
		kills.getBosses().put("Bryophyta", 3);
		kills.getClues().put("Elite", 24);
		page.setKills(kills);
		PublicSnapshot.Collection collection = new PublicSnapshot.Collection();
		collection.setCollectionLog(601);
		page.setCollection(collection);

		Hiscores.merge(page, Hiscores.from("Tommo Ladd", result(), 1L));

		Map<String, Integer> bosses = page.getKills().getBosses();
		assertEquals(1_460, (int) bosses.get("Vardorvis"));
		assertEquals(41, (int) bosses.get(HiscoreSkill.THE_GAUNTLET.getName()));
		assertFalse(bosses.containsKey("Gauntlet"));
		assertEquals(129, (int) bosses.get(HiscoreSkill.THEATRE_OF_BLOOD.getName()));
		assertEquals("Not on the hiscores, still shown", 3, (int) bosses.get("Bryophyta"));
		assertEquals("Most kills first", "Vardorvis", new ArrayList<>(bosses.keySet()).get(0));
		assertEquals(26, (int) page.getKills().getClues().get("Elite"));
		assertEquals(520, (int) page.getKills().getActivities().get(HiscoreSkill.LAST_MAN_STANDING.getName()));
		assertEquals(640, (int) page.getCollection().getCollectionLog());
	}

	@Test
	public void eachWorldHasItsOwnHiscores()
	{
		assertEquals(HiscoreEndpoint.NORMAL, Hiscores.endpoint("main"));
		assertEquals(HiscoreEndpoint.SEASONAL, Hiscores.endpoint("seasonal"));
		assertEquals(HiscoreEndpoint.FRESH_START_WORLD, Hiscores.endpoint("fresh-start"));
		assertEquals("theatreofblood", Hiscores.key("Theatre of Blood"));
		assertEquals("gauntlet", Hiscores.key("The Gauntlet"));
		assertNull(new PublicSnapshot.Kills().getActivities());
	}
}
