package com.runejourney.service;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A public account's quests and combat tasks, for its page: every one in the game, and how far the
 * player has got with it.
 */
@Data
public class PublicAchievements
{
	/**
	 * When it was read from the game.
	 */
	@SerializedName("synced_at")
	private String syncedAt;
	/**
	 * In the game's order, sub-quests (such as Recipe for Disaster's) after their quest.
	 */
	private List<Quest> quests = new ArrayList<>();
	/**
	 * Easiest first.
	 */
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
		/**
		 * {@link #FINISHED}, {@link #IN_PROGRESS} or {@link #NOT_STARTED}.
		 */
		private String state;
		private int points;
		private boolean members;
		private boolean miniquest;
		/**
		 * The quest a sub-quest is part of, or null.
		 */
		private String parent;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class CombatTask
	{
		/**
		 * The game's ID for the task, which is also its bit in the completed-task varps.
		 */
		private int id;
		private String name;
		private String description;
		/**
		 * 1 (Easy) to 6 (Grandmaster), which is also how many points it's worth.
		 */
		private int tier;
		/**
		 * Such as Kill Count or Mechanical.
		 */
		private String type;
		/**
		 * The boss or monster it's for, or None.
		 */
		private String monster;
		private boolean done;
	}
}
