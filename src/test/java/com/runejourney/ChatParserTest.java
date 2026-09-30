package com.runejourney;

import com.runejourney.service.ChatParser;
import static com.runejourney.service.ChatParser.Kind.CLUE;
import static com.runejourney.service.ChatParser.Kind.COLLECTION_LOG;
import static com.runejourney.service.ChatParser.Kind.COMBAT_TASK;
import static com.runejourney.service.ChatParser.Kind.DIARY_TIER;
import static com.runejourney.service.ChatParser.Kind.DUPLICATE_PET;
import static com.runejourney.service.ChatParser.Kind.KILL_COUNT;
import static com.runejourney.service.ChatParser.Kind.PERSONAL_BEST;
import static com.runejourney.service.ChatParser.Kind.PET;
import static com.runejourney.service.ChatParser.Kind.QUEST;
import static com.runejourney.service.ChatParser.Kind.SLAYER_TASK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class ChatParserTest
{
	@Test
	public void killCounts()
	{
		ChatParser.Result r = ChatParser.parse("Your Zulrah kill count is: 1,284.");
		assertEquals(KILL_COUNT, r.getKind());
		assertEquals("Zulrah", r.getName());
		assertEquals(1284, r.getCount());

		r = ChatParser.parse("Your completed Theatre of Blood count is: 284.");
		assertEquals("Theatre of Blood", r.getName());
		assertEquals(284, r.getCount());

		r = ChatParser.parse("Your Barrows chest count is: 57.");
		assertEquals("Barrows", r.getName());

		r = ChatParser.parse("Your Gauntlet completion count is: 12.");
		assertEquals("Gauntlet", r.getName());
	}

	@Test
	public void personalBests()
	{
		ChatParser.Result r = ChatParser.parse("Fight duration: 1:23.40 (new personal best)");
		assertEquals(PERSONAL_BEST, r.getKind());
		assertEquals("1:23.40", r.getName());

		r = ChatParser.parse("Theatre of Blood completion time: 17:42 (new personal best)");
		assertEquals("17:42", r.getName());

		r = ChatParser.parse("Challenge duration: 1:02:03 (new personal best).");
		assertEquals("1:02:03", r.getName());

		assertNull(ChatParser.parse("Fight duration: 1:23. Personal best: 1:10"));
	}

	@Test
	public void collectionLogQuestsAndDiaries()
	{
		ChatParser.Result r = ChatParser.parse("New item added to your collection log: Avernic defender hilt");
		assertEquals(COLLECTION_LOG, r.getKind());
		assertEquals("Avernic defender hilt", r.getName());

		r = ChatParser.parse("Congratulations, you've completed a quest: Dragon Slayer II");
		assertEquals(QUEST, r.getKind());
		assertEquals("Dragon Slayer II", r.getName());

		r = ChatParser.parse("Congratulations! You have completed all of the elite tasks in the Kandarin area. Speak to someone.");
		assertEquals(DIARY_TIER, r.getKind());
		assertEquals("Kandarin", r.getName());
		assertEquals("Elite", r.getDetail());

		r = ChatParser.parse("Congratulations, you've completed a hard combat task: Just Like That (3 points).");
		assertEquals(COMBAT_TASK, r.getKind());
		assertEquals("Just Like That", r.getName());
		assertEquals("Hard", r.getDetail());
		assertEquals(3, r.getCount());

		// Points fall back to the tier's value when the message doesn't include them
		r = ChatParser.parse("Congratulations, you've completed an elite combat task: Perfect Zulrah.");
		assertEquals(4, r.getCount());

		// Machine-readable prefixes are ignored
		r = ChatParser.parse("CA_ID:1234|Congratulations, you've completed a master combat task: Perfect Olm (Solo) (5 points).");
		assertEquals(COMBAT_TASK, r.getKind());
		assertEquals("Perfect Olm (Solo)", r.getName());
		assertEquals("Master", r.getDetail());
		assertEquals(5, r.getCount());

		// The game marks the task name with a colour marker
		r = ChatParser.parse("Congratulations, you've completed an easy combat task: @ach_comp@Scurrius Novice (1 point).");
		assertEquals("Scurrius Novice", r.getName());
		assertEquals(1, r.getCount());

		r = ChatParser.parse("Congratulations, you've completed a grandmaster combat task: Inferno Grandmaster.");
		assertEquals(6, r.getCount());

		r = ChatParser.parse("Your subdued Wintertodt count is: 250.");
		assertEquals("Wintertodt", r.getName());
		assertEquals(250, r.getCount());
	}

	@Test
	public void petsSlayerAndClues()
	{
		assertEquals(PET, ChatParser.parse("You have a funny feeling like you're being followed.").getKind());
		assertEquals(PET, ChatParser.parse("You feel something weird sneaking into your backpack.").getKind());
		assertEquals(DUPLICATE_PET, ChatParser.parse("You have a funny feeling like you would have been followed...").getKind());

		ChatParser.Result r = ChatParser.parse("You've completed 482 tasks and received 15 points, giving you a total of 1,020; return to a Slayer master.");
		assertEquals(SLAYER_TASK, r.getKind());
		assertEquals(482, r.getCount());

		r = ChatParser.parse("You have completed 57 elite Treasure Trails.");
		assertEquals(CLUE, r.getKind());
		assertEquals("Elite", r.getName());

		assertNull(ChatParser.parse("Welcome to Old School RuneScape."));
	}
}
