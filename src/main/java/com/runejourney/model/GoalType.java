package com.runejourney.model;

import lombok.*;

@Getter
@RequiredArgsConstructor
public enum GoalType
{
	SKILL("Skill level / XP"),
	TOTAL_LEVEL("Total level"),
	BASE_LEVEL("Base level (all skills)"),
	MAX_CAPE("Max Cape"),
	BOSS_KC("Boss kill count"),
	CLUES("Clue scrolls"),
	ITEMS("Obtain items"),
	MONEY("Save money"),
	PURCHASE("Purchase an item"),
	NET_WORTH("Reach a net worth"),
	CLOG_CATEGORY("Complete a collection log page"),
	COMBAT_ACHIEVEMENTS("Combat Achievement points"),
	COMBAT_TASKS("Combat tasks"),
	QUEST_POINTS("Quest points"),
	COLLECTION_LOG("Collection log slots"),
	CUSTOM("Custom");

	private final String label;

	public boolean isCounter()
	{
		return this == BOSS_KC || this == CLUES || this == COMBAT_ACHIEVEMENTS || this == COMBAT_TASKS || this == QUEST_POINTS
			|| this == COLLECTION_LOG
			|| this == MONEY || this == PURCHASE || this == NET_WORTH || this == CLOG_CATEGORY;
	}

	public boolean isSkilling()
	{
		return this == SKILL || this == TOTAL_LEVEL || this == BASE_LEVEL || this == MAX_CAPE;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
