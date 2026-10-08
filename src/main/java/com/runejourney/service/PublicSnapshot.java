package com.runejourney.service;

import com.google.gson.annotations.SerializedName;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * What an account's public page shows, as published to the RuneJourney website. Only the sections
 * the player switched on are filled in; the rest stay null and are left out.
 */
@Data
public class PublicSnapshot
{
	public static final String SCHEMA = "1.0";

	private String schema = SCHEMA;
	/**
	 * The RuneScape name. The only time it's uploaded, and only for accounts made public.
	 */
	private String name;
	/**
	 * main, seasonal, deadman or fresh-start.
	 */
	private String world;
	@SerializedName("generated_at")
	private String generatedAt;

	/**
	 * Skill name to XP.
	 */
	private Map<String, Long> skills;
	private Kills kills;
	private Collection collection;
	/**
	 * Newest first.
	 */
	private List<Event> timeline;
	private List<GoalRow> goals;
	private List<RecordRow> records;
	private Wealth wealth;

	@Data
	public static class Kills
	{
		private Map<String, Integer> bosses = new LinkedHashMap<>();
		private Map<String, Integer> clues = new LinkedHashMap<>();
		/**
		 * Minigame scores from the hiscores, such as Last Man Standing, or null.
		 */
		private Map<String, Integer> activities;
	}

	@Data
	public static class Collection
	{
		@SerializedName("quest_points")
		private Integer questPoints;
		@SerializedName("collection_log")
		private Integer collectionLog;
		@SerializedName("collection_log_total")
		private Integer collectionLogTotal;
		@SerializedName("combat_tasks")
		private Integer combatTasks;
		@SerializedName("combat_points")
		private Long combatPoints;
	}

	@Data
	public static class Event
	{
		private String id;
		private String date;
		private long time;
		private String type;
		private String title;
		private String detail;
		private String skill;
		private Long value;
		private Boolean highlight;
		private String note;
		private Boolean memory;
		/**
		 * The media ID of the moment's screenshot, when it's in the cloud and the page shows screenshots.
		 */
		private String screenshot;
	}

	@Data
	public static class GoalRow
	{
		private String name;
		private String type;
		private double progress;
		@SerializedName("target_date")
		private String targetDate;
		@SerializedName("completed_at")
		private String completedAt;
	}

	@Data
	public static class RecordRow
	{
		private String title;
		private String value;
		private String date;
	}

	@Data
	public static class Wealth
	{
		@SerializedName("net_worth")
		private long netWorth;
		@SerializedName("as_of")
		private String asOf;
	}
}
