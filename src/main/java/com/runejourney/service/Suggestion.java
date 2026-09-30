package com.runejourney.service;

import lombok.Value;
import net.runelite.api.Skill;

@Value
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
}
