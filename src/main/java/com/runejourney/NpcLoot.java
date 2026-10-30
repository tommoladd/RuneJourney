package com.runejourney;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import lombok.Value;
import net.runelite.client.game.ItemStack;

/**
 * Picks one report of each NPC drop. The game's own loot tracker (ServerNpcLoot) also sees loot
 * that lands away from the NPC or after it has gone, like Yama's, which RuneLite's floor check
 * (NpcLootReceived) misses, so it wins whenever it's turned on. Floor loot waits a few ticks in
 * case the game's report follows, and is only used if it doesn't.
 */
class NpcLoot
{
	static final int WAIT_TICKS = 5;
	// The game reports stolen items as NPC loot too, but thieving is counted as skilling
	private static final Pattern PICKPOCKET = Pattern.compile("You pick (the )?.+'s? pocket.*");

	@Value
	static class Drop
	{
		String source;
		Collection<ItemStack> items;
		int tick;
	}

	private final List<Drop> waiting = new ArrayList<>();
	private boolean serverLoot;
	private int pickpocketTick = Integer.MIN_VALUE;

	void reset()
	{
		waiting.clear();
		serverLoot = false;
		pickpocketTick = Integer.MIN_VALUE;
	}

	void onChatMessage(String message, int tick)
	{
		if (PICKPOCKET.matcher(message).matches())
		{
			pickpocketTick = tick;
		}
	}

	/**
	 * @return whether to record this drop
	 */
	boolean onServerLoot(int tick)
	{
		serverLoot = true;
		// Anything still waiting was seen on the floor too, so the game will report it as well
		waiting.clear();
		return pickpocketTick == Integer.MIN_VALUE || tick - pickpocketTick > 1;
	}

	void onFloorLoot(String source, Collection<ItemStack> items, int tick)
	{
		if (!serverLoot)
		{
			waiting.add(new Drop(source, items, tick));
		}
	}

	/**
	 * Floor loot the game didn't report, to record now.
	 */
	List<Drop> due(int tick)
	{
		List<Drop> due = new ArrayList<>();
		waiting.removeIf(d ->
		{
			if (tick - d.getTick() < WAIT_TICKS)
			{
				return false;
			}
			due.add(d);
			return true;
		});
		return due;
	}
}
