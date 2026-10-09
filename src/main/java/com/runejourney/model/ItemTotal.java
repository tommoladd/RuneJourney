package com.runejourney.model;

import lombok.*;

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
