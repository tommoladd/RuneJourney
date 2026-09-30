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
}
