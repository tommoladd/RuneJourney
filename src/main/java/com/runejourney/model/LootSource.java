package com.runejourney.model;

import java.util.*;
import lombok.*;

@Data
@NoArgsConstructor
public class LootSource
{
	private long value;
	private int times;
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
