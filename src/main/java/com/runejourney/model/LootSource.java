package com.runejourney.model;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Loot from one source (an NPC, minigame, chest, clue tier...) over a day or range.
 */
@Data
@NoArgsConstructor
public class LootSource
{
	private long value;
	/**
	 * How many times the source paid out: kills, reward claims, caskets opened...
	 */
	private int times;
	/**
	 * Item name to totals.
	 */
	private Map<String, ItemTotal> items = new HashMap<>();

	public void add(LootSource other)
	{
		value += other.value;
		times += other.times;
		other.items.forEach((name, t) -> items.computeIfAbsent(name, k -> new ItemTotal()).add(t.getQuantity(), t.getValue()));
	}

	public LootSource copy()
	{
		LootSource c = new LootSource();
		c.add(this);
		return c;
	}
}
