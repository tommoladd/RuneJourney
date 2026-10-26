package com.runejourney.model;

import lombok.*;

@Data
@NoArgsConstructor
public class GoalItem
{
	private int id;
	private String name;
	private int quantity;
	private int collected;
	private long obtainedAt;
	private String source;

	public GoalItem(int id, String name)
	{
		this.id = id;
		this.name = name;
	}

	public boolean isObtained()
	{
		return obtainedAt > 0;
	}

	public int target()
	{
		return Math.max(1, quantity);
	}

	public double progress()
	{
		return isObtained() ? 1 : Math.min(1, collected / (double) target());
	}
}
