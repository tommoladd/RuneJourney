package com.runejourney.service;

import com.google.gson.annotations.SerializedName;
import java.util.*;
import lombok.*;

@Data
public class PublicAchievements
{
	@SerializedName("synced_at")
	private String syncedAt;
	private List<Quest> quests = new ArrayList<>();
	@SerializedName("combat_tasks")
	private List<CombatTask> combatTasks = new ArrayList<>();

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Quest
	{
		public static final String FINISHED = "finished";
		public static final String IN_PROGRESS = "in_progress";
		public static final String NOT_STARTED = "not_started";

		private String name;
		private String state;
		private int points;
		private boolean members;
		private boolean miniquest;
		private String parent;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class CombatTask
	{
		private int id;
		private String name;
		private String description;
		private int tier;
		private String type;
		private String monster;
		private boolean done;
	}
}
