package com.runejourney.service;

import lombok.AllArgsConstructor;
import lombok.Value;
import net.runelite.api.Skill;

@Value
@AllArgsConstructor
public class Suggestion
{
	String title;
	String detail;
	/**
	 * Optional skill for the icon.
	 */
	Skill skill;
	/**
	 * 0-1 progress to show as a bar, or negative for none.
	 */
	double progress;
	/**
	 * Name of the saved training method this shows, which can be renamed or deleted from it, or null.
	 */
	String savedMethod;

	public Suggestion(String title, String detail, Skill skill, double progress)
	{
		this(title, detail, skill, progress, null);
	}
}
