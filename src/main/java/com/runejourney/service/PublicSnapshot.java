package com.runejourney.service;

import com.google.gson.annotations.SerializedName;
import java.util.*;
import lombok.Data;

@Data
public class PublicSnapshot
{
	public static final String SCHEMA = "1.0";

	private String schema = SCHEMA;
	private String name;
	private String world;
	@SerializedName("generated_at")
	private String generatedAt;

	private Map<String, Long> skills;
	private Kills kills;
	private Collection collection;
	private List<Event> timeline;
	private List<GoalRow> goals;
	private List<RecordRow> records;
	private Wealth wealth;

	@Data
	public static class Kills
	{
		private Map<String, Integer> bosses = new LinkedHashMap<>();
		private Map<String, Integer> clues = new LinkedHashMap<>();
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
