package com.runejourney.model;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClogItem
{
	private int id;
	private String name;
	private boolean obtained;
	private int quantity;

	public ClogItem(int id, String name, boolean obtained)
	{
		this(id, name, obtained, 0);
	}
}
