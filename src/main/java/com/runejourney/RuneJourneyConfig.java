package com.runejourney;

import com.runejourney.planner.Intensity;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(RuneJourneyConfig.GROUP)
public interface RuneJourneyConfig extends Config
{
	String GROUP = "runejourney";

	@ConfigSection(
		name = "Journey",
		description = "What gets recorded in your Journey",
		position = 0
	)
	String journeySection = "journey";

	@ConfigSection(
		name = "Screenshots",
		description = "Automatically capture screenshots of important moments",
		position = 1
	)
	String screenshotSection = "screenshots";

	@ConfigSection(
		name = "Planner",
		description = "Goal planning preferences",
		position = 2
	)
	String plannerSection = "planner";

	@ConfigSection(
		name = "Wealth",
		description = "Net worth tracking (bank, inventory and equipment at GE prices). Stays on your computer.",
		position = 3
	)
	String wealthSection = "wealth";

	@ConfigSection(
		name = "Overlay",
		description = "An optional in-game overlay showing your goals and progress",
		position = 4
	)
	String overlaySection = "overlay";

	@ConfigSection(
		name = "Wrapped",
		description = "Your weekly RuneJourney Wrapped",
		position = 5
	)
	String wrappedSection = "wrapped";

	@ConfigSection(
		name = "Encouragement",
		description = "Friendly chat messages every so much XP in a skill. Slower skills cheer you on more often.",
		position = 6,
		closedByDefault = true
	)
	String encouragementSection = "encouragement";

	@ConfigItem(
		keyName = "logEveryLevel",
		name = "Record every level",
		description = "Add every level up to the Journey, not just milestone levels",
		section = journeySection,
		position = 0
	)
	default boolean logEveryLevel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "milestoneLevels",
		name = "Milestone levels",
		description = "Comma separated skill levels treated as milestones",
		section = journeySection,
		position = 1
	)
	default String milestoneLevels()
	{
		return "50,70,80,90,92,95,99";
	}

	@ConfigItem(
		keyName = "totalLevelMilestones",
		name = "Total level milestones",
		description = "Comma separated total levels treated as milestones",
		section = journeySection,
		position = 2
	)
	default String totalLevelMilestones()
	{
		return "500,1000,1250,1500,1750,2000,2100,2200,2250,2300";
	}

	@ConfigItem(
		keyName = "xpMilestoneInterval",
		name = "Skill XP milestone every",
		description = "Record a milestone each time a skill passes a multiple of this much XP (0 to only record 10m, 20m, 50m...)",
		section = journeySection,
		position = 3
	)
	default int xpMilestoneInterval()
	{
		return 1_000_000;
	}

	@ConfigItem(
		keyName = "totalXpMilestoneInterval",
		name = "Total XP milestone every",
		description = "Record a milestone each time your total XP passes a multiple of this (0 to disable)",
		section = journeySection,
		position = 4
	)
	default int totalXpMilestoneInterval()
	{
		return 10_000_000;
	}

	@ConfigItem(
		keyName = "kcMilestoneInterval",
		name = "Kill count milestone every",
		description = "Record a milestone every this many kills, as well as your 1st, 10th and 25th kill (0 for 1, 10, 25, 50, 100, 250, 500, 1000)",
		section = journeySection,
		position = 5
	)
	default int kcMilestoneInterval()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "recordEveryClue",
		name = "Record every clue",
		description = "Add every clue scroll completion (with its loot) to the Journey. When off, only milestones and valuable caskets are recorded",
		section = journeySection,
		position = 6
	)
	default boolean recordEveryClue()
	{
		return true;
	}

	@ConfigItem(
		keyName = "valuableDropThreshold",
		name = "Valuable drop value",
		description = "Drops worth at least this much are added to your Journey",
		section = journeySection,
		position = 7
	)
	default int valuableDropThreshold()
	{
		return 1_000_000;
	}

	@ConfigItem(
		keyName = "recordDeaths",
		name = "Record deaths",
		description = "Add deaths to your Journey timeline",
		section = journeySection,
		position = 8
	)
	default boolean recordDeaths()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatAnnouncements",
		name = "Chat announcements",
		description = "Show a chat message when a goal is completed or a personal record is set",
		section = journeySection,
		position = 9
	)
	default boolean chatAnnouncements()
	{
		return true;
	}

	@ConfigItem(
		keyName = "screenshotMilestoneLevels",
		name = "Milestone levels",
		description = "Screenshot milestone levels (including 99s)",
		section = screenshotSection,
		position = 0
	)
	default boolean screenshotMilestoneLevels()
	{
		return true;
	}

	@ConfigItem(
		keyName = "screenshotPets",
		name = "Pets",
		description = "Screenshot pet drops",
		section = screenshotSection,
		position = 1
	)
	default boolean screenshotPets()
	{
		return true;
	}

	@ConfigItem(
		keyName = "screenshotCollectionLog",
		name = "Collection log",
		description = "Screenshot new collection log slots",
		section = screenshotSection,
		position = 2
	)
	default boolean screenshotCollectionLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "screenshotValuableDrops",
		name = "Valuable drops",
		description = "Screenshot valuable drops",
		section = screenshotSection,
		position = 3
	)
	default boolean screenshotValuableDrops()
	{
		return true;
	}

	@ConfigItem(
		keyName = "screenshotQuests",
		name = "Quests & diaries",
		description = "Screenshot quest and achievement diary completions",
		section = screenshotSection,
		position = 4
	)
	default boolean screenshotQuests()
	{
		return true;
	}

	@ConfigItem(
		keyName = "screenshotPersonalBests",
		name = "Personal bests",
		description = "Screenshot new personal bests",
		section = screenshotSection,
		position = 5
	)
	default boolean screenshotPersonalBests()
	{
		return true;
	}

	@ConfigItem(
		keyName = "screenshotKcMilestones",
		name = "Kill count milestones",
		description = "Screenshot first kills and kill count milestones",
		section = screenshotSection,
		position = 6
	)
	default boolean screenshotKcMilestones()
	{
		return true;
	}

	@ConfigItem(
		keyName = "screenshotGoals",
		name = "Goal completion",
		description = "Screenshot when a goal is completed",
		section = screenshotSection,
		position = 7
	)
	default boolean screenshotGoals()
	{
		return true;
	}

	@ConfigItem(
		keyName = "screenshotDeaths",
		name = "Deaths",
		description = "Screenshot deaths",
		section = screenshotSection,
		position = 8
	)
	default boolean screenshotDeaths()
	{
		return false;
	}

	@ConfigItem(
		keyName = "intensity",
		name = "Training intensity",
		description = "Used to pick generic XP/hr estimates until RuneJourney has learned your own rates",
		section = plannerSection,
		position = 0
	)
	default Intensity intensity()
	{
		return Intensity.BALANCED;
	}

	@ConfigItem(
		keyName = "hoursPerWeek",
		name = "Available hours/week",
		description = "Roughly how many hours a week you expect to play, used to check target dates",
		section = plannerSection,
		position = 1
	)
	@Range(min = 1, max = 168)
	default int hoursPerWeek()
	{
		return 20;
	}

	@ConfigItem(
		keyName = "usePersonalRates",
		name = "Use my own XP rates",
		description = "Once enough training has been observed, use your real XP/hr instead of generic estimates",
		section = plannerSection,
		position = 2
	)
	default boolean usePersonalRates()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackWealth",
		name = "Track net worth",
		description = "Value your bank (when you open it), inventory and equipment at GE prices to chart your net worth",
		section = wealthSection,
		position = 0
	)
	default boolean trackWealth()
	{
		return true;
	}

	@ConfigItem(
		keyName = "wealthMilestones",
		name = "Net worth milestones",
		description = "Comma separated amounts (e.g. 100m, 1b) to celebrate in your Journey",
		section = wealthSection,
		position = 1
	)
	default String wealthMilestones()
	{
		return "10m,25m,50m,100m,250m,500m,1b,2b,5b,10b";
	}

	@ConfigItem(
		keyName = "trackSupplies",
		name = "Track supplies used",
		description = "Count the GE value of food you eat and potion doses you drink, and show profit after supplies",
		section = wealthSection,
		position = 2
	)
	default boolean trackSupplies()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show overlay",
		description = "Show RuneJourney progress on the game screen",
		section = overlaySection,
		position = 0
	)
	default boolean showOverlay()
	{
		return false;
	}

	@ConfigItem(
		keyName = "overlayGoal",
		name = "Goal",
		description = "Show the goal pinned from the My Goals tab (or your first goal if none is pinned)",
		section = overlaySection,
		position = 1
	)
	default boolean overlayGoal()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overlayWeekPlan",
		name = "This week's plan",
		description = "Show the goal's weekly targets",
		section = overlaySection,
		position = 2
	)
	default boolean overlayWeekPlan()
	{
		return true;
	}

	@Range(min = 1, max = 10)
	@ConfigItem(
		keyName = "overlayWeekRows",
		name = "Weekly plan rows",
		description = "How many skills from the weekly plan to show",
		section = overlaySection,
		position = 3
	)
	default int overlayWeekRows()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "overlayToday",
		name = "Today",
		description = "Show today's time played, XP and income",
		section = overlaySection,
		position = 4
	)
	default boolean overlayToday()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overlaySession",
		name = "Active session",
		description = "Show the goal session you've started, if any",
		section = overlaySection,
		position = 5
	)
	default boolean overlaySession()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overlayStreak",
		name = "Play streak",
		description = "Show how many days in a row you've played",
		section = overlaySection,
		position = 6
	)
	default boolean overlayStreak()
	{
		return false;
	}

	@ConfigItem(
		keyName = "overlayNetWorth",
		name = "Net worth",
		description = "Show your net worth and today's change",
		section = overlaySection,
		position = 7
	)
	default boolean overlayNetWorth()
	{
		return false;
	}

	@ConfigItem(
		keyName = "wrappedNotify",
		name = "Tell me when it's ready",
		description = "Post a chat message when last week's Wrapped is ready to watch",
		section = wrappedSection,
		position = 0
	)
	default boolean wrappedNotify()
	{
		return true;
	}

	@ConfigItem(
		keyName = "wrappedInGame",
		name = "Play over the game",
		description = "Show Wrapped over the game screen when logged in (otherwise it opens in its own window)",
		section = wrappedSection,
		position = 1
	)
	default boolean wrappedInGame()
	{
		return true;
	}

	@ConfigItem(
		keyName = "encouragement",
		name = "Encouraging messages",
		description = "Send an encouraging chat message each time you gain the amount of XP set below in a skill",
		section = encouragementSection,
		position = 0
	)
	default boolean encouragement()
	{
		return true;
	}

	@ConfigItem(
		keyName = "encourageAttack",
		name = "Attack XP gap",
		description = "Encourage you every this much Attack XP (0 = never)",
		section = encouragementSection,
		position = 1
	)
	default int encourageAttack()
	{
		return 500_000;
	}

	@ConfigItem(
		keyName = "encourageStrength",
		name = "Strength XP gap",
		description = "Encourage you every this much Strength XP (0 = never)",
		section = encouragementSection,
		position = 2
	)
	default int encourageStrength()
	{
		return 500_000;
	}

	@ConfigItem(
		keyName = "encourageDefence",
		name = "Defence XP gap",
		description = "Encourage you every this much Defence XP (0 = never)",
		section = encouragementSection,
		position = 3
	)
	default int encourageDefence()
	{
		return 500_000;
	}

	@ConfigItem(
		keyName = "encourageRanged",
		name = "Ranged XP gap",
		description = "Encourage you every this much Ranged XP (0 = never)",
		section = encouragementSection,
		position = 4
	)
	default int encourageRanged()
	{
		return 500_000;
	}

	@ConfigItem(
		keyName = "encouragePrayer",
		name = "Prayer XP gap",
		description = "Encourage you every this much Prayer XP (0 = never)",
		section = encouragementSection,
		position = 5
	)
	default int encouragePrayer()
	{
		return 1_000_000;
	}

	@ConfigItem(
		keyName = "encourageMagic",
		name = "Magic XP gap",
		description = "Encourage you every this much Magic XP (0 = never)",
		section = encouragementSection,
		position = 6
	)
	default int encourageMagic()
	{
		return 500_000;
	}

	@ConfigItem(
		keyName = "encourageRunecraft",
		name = "Runecraft XP gap",
		description = "Encourage you every this much Runecraft XP (0 = never)",
		section = encouragementSection,
		position = 7
	)
	default int encourageRunecraft()
	{
		return 250_000;
	}

	@ConfigItem(
		keyName = "encourageConstruction",
		name = "Construction XP gap",
		description = "Encourage you every this much Construction XP (0 = never)",
		section = encouragementSection,
		position = 8
	)
	default int encourageConstruction()
	{
		return 1_000_000;
	}

	@ConfigItem(
		keyName = "encourageHitpoints",
		name = "Hitpoints XP gap",
		description = "Encourage you every this much Hitpoints XP (0 = never)",
		section = encouragementSection,
		position = 9
	)
	default int encourageHitpoints()
	{
		return 500_000;
	}

	@ConfigItem(
		keyName = "encourageAgility",
		name = "Agility XP gap",
		description = "Encourage you every this much Agility XP (0 = never)",
		section = encouragementSection,
		position = 10
	)
	default int encourageAgility()
	{
		return 250_000;
	}

	@ConfigItem(
		keyName = "encourageHerblore",
		name = "Herblore XP gap",
		description = "Encourage you every this much Herblore XP (0 = never)",
		section = encouragementSection,
		position = 11
	)
	default int encourageHerblore()
	{
		return 1_000_000;
	}

	@ConfigItem(
		keyName = "encourageThieving",
		name = "Thieving XP gap",
		description = "Encourage you every this much Thieving XP (0 = never)",
		section = encouragementSection,
		position = 12
	)
	default int encourageThieving()
	{
		return 500_000;
	}

	@ConfigItem(
		keyName = "encourageCrafting",
		name = "Crafting XP gap",
		description = "Encourage you every this much Crafting XP (0 = never)",
		section = encouragementSection,
		position = 13
	)
	default int encourageCrafting()
	{
		return 1_000_000;
	}

	@ConfigItem(
		keyName = "encourageFletching",
		name = "Fletching XP gap",
		description = "Encourage you every this much Fletching XP (0 = never)",
		section = encouragementSection,
		position = 14
	)
	default int encourageFletching()
	{
		return 1_000_000;
	}

	@ConfigItem(
		keyName = "encourageSlayer",
		name = "Slayer XP gap",
		description = "Encourage you every this much Slayer XP (0 = never)",
		section = encouragementSection,
		position = 15
	)
	default int encourageSlayer()
	{
		return 250_000;
	}

	@ConfigItem(
		keyName = "encourageHunter",
		name = "Hunter XP gap",
		description = "Encourage you every this much Hunter XP (0 = never)",
		section = encouragementSection,
		position = 16
	)
	default int encourageHunter()
	{
		return 250_000;
	}

	@ConfigItem(
		keyName = "encourageMining",
		name = "Mining XP gap",
		description = "Encourage you every this much Mining XP (0 = never)",
		section = encouragementSection,
		position = 17
	)
	default int encourageMining()
	{
		return 250_000;
	}

	@ConfigItem(
		keyName = "encourageSmithing",
		name = "Smithing XP gap",
		description = "Encourage you every this much Smithing XP (0 = never)",
		section = encouragementSection,
		position = 18
	)
	default int encourageSmithing()
	{
		return 1_000_000;
	}

	@ConfigItem(
		keyName = "encourageFishing",
		name = "Fishing XP gap",
		description = "Encourage you every this much Fishing XP (0 = never)",
		section = encouragementSection,
		position = 19
	)
	default int encourageFishing()
	{
		return 250_000;
	}

	@ConfigItem(
		keyName = "encourageCooking",
		name = "Cooking XP gap",
		description = "Encourage you every this much Cooking XP (0 = never)",
		section = encouragementSection,
		position = 20
	)
	default int encourageCooking()
	{
		return 1_000_000;
	}

	@ConfigItem(
		keyName = "encourageFiremaking",
		name = "Firemaking XP gap",
		description = "Encourage you every this much Firemaking XP (0 = never)",
		section = encouragementSection,
		position = 21
	)
	default int encourageFiremaking()
	{
		return 500_000;
	}

	@ConfigItem(
		keyName = "encourageWoodcutting",
		name = "Woodcutting XP gap",
		description = "Encourage you every this much Woodcutting XP (0 = never)",
		section = encouragementSection,
		position = 22
	)
	default int encourageWoodcutting()
	{
		return 250_000;
	}

	@ConfigItem(
		keyName = "encourageFarming",
		name = "Farming XP gap",
		description = "Encourage you every this much Farming XP (0 = never)",
		section = encouragementSection,
		position = 23
	)
	default int encourageFarming()
	{
		return 500_000;
	}

	@ConfigItem(
		keyName = "encourageSailing",
		name = "Sailing XP gap",
		description = "Encourage you every this much Sailing XP (0 = never)",
		section = encouragementSection,
		position = 24
	)
	default int encourageSailing()
	{
		return 250_000;
	}
}
