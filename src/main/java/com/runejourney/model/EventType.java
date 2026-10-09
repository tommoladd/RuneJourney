package com.runejourney.model;

import java.awt.Color;
import lombok.*;

@Getter
@RequiredArgsConstructor
public enum EventType
{
	LEVEL("Level", new Color(0x4CAF50), Category.SKILLS),
	XP_MILESTONE("XP Milestone", new Color(0x8BC34A), Category.SKILLS),
	TOTAL_LEVEL("Total Level", new Color(0xCDDC39), Category.SKILLS),
	BOSS_KC("Kill Count", new Color(0xE57373), Category.PVM),
	PERSONAL_BEST("Personal Best", new Color(0xFF7043), Category.PVM),
	DROP("Drop", new Color(0xFFC107), Category.LOOT),
	COLLECTION_LOG("Collection Log", new Color(0xFFD54F), Category.LOOT),
	CLUE("Clue Scroll", new Color(0xFFE082), Category.LOOT),
	PET("Pet", new Color(0xF06292), Category.LOOT),
	QUEST("Quest", new Color(0x64B5F6), Category.ACCOUNT),
	DIARY("Achievement Diary", new Color(0x4FC3F7), Category.ACCOUNT),
	COMBAT_TASK("Combat Achievement", new Color(0xBA68C8), Category.PVM),
	DEATH("Death", new Color(0x9E9E9E), Category.PVM),
	GOAL_CREATED("New Goal", new Color(0x26C6DA), Category.GOALS),
	GOAL_COMPLETED("Goal Complete", new Color(0x00E5FF), Category.GOALS),
	GOAL_PROGRESS("Goal Progress", new Color(0x4DD0E1), Category.GOALS),
	WEEKLY_PLAN("Weekly Plan", new Color(0x80CBC4), Category.GOALS),
	SESSION("Session", new Color(0x80DEEA), Category.GOALS),
	RECORD("Personal Record", new Color(0xFFAB40), Category.ACCOUNT),
	NOTE("Memory", new Color(0xD7CCC8), Category.ACCOUNT);

	public enum Category
	{
		SKILLS, PVM, LOOT, ACCOUNT, GOALS
	}

	private final String label;
	private final Color color;
	private final Category category;
}
