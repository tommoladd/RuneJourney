package com.runejourney.service;

import lombok.*;
import net.runelite.api.Skill;

@Value
@AllArgsConstructor
public class Suggestion
{
	String title;
	String detail;
	Skill skill;
	double progress;
	String savedMethod;

	public Suggestion(String title, String detail, Skill skill, double progress)
	{
		this(title, detail, skill, progress, null);
	}
}
