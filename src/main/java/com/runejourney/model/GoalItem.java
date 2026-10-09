package com.runejourney.model;

import lombok.*;

@Data
@NoArgsConstructor
public class GoalItem
{
	private int id;
	private String name;
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
}
