package com.runejourney.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An item on a collection log page, as last seen in the game.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClogItem
{
	private int id;
	private String name;
	private boolean obtained;
	/**
	 * How many have been obtained, when known (from a full collection log sync), else 0.
	 */
	private int quantity;

	public ClogItem(int id, String name, boolean obtained)
	{
		this(id, name, obtained, 0);
	}
}
