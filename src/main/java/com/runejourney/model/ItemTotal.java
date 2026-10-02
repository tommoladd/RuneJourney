package com.runejourney.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A running quantity and GP value for one item, e.g. within a loot source or supplies used.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ItemTotal
{
	private long quantity;
	private long value;

	public void add(long quantity, long value)
	{
		this.quantity += quantity;
		this.value += value;
	}
}
