package com.runejourney.cloud;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Value;

/**
 * Requests and replies of the RuneJourney cloud API (/api/v1).
 */
public final class Api
{
	private Api()
	{
	}

	/**
	 * Who's calling: the server, the player's key and this RuneLite install.
	 */
	@Value
	public static class Session
	{
		String server;
		String apiKey;
		String deviceId;
	}

	@Data
	public static class Me
	{
		private User user;
		@SerializedName("data_key")
		private DataKey dataKey;
	}

	@Data
	public static class User
	{
		private String uuid;
		private String name;
		@SerializedName("discord_username")
		private String discordUsername;
	}

	@Data
	public static class DataKey
	{
		private int id;
		private String key;
	}

	@Data
	public static class Profile
	{
		private String id;
		private String label;
		private long cursor;
		@SerializedName("public")
		private PublicSettings publicSettings;
	}

	/**
	 * An account's public page settings. They live on the server and can be changed on the website
	 * too.
	 */
	@Data
	public static class PublicSettings
	{
		private boolean enabled;
		/**
		 * Listed in the website's search.
		 */
		private boolean searchable;
		/**
		 * The parts of the journey the page shows: character, skills, kills, collection, timeline,
		 * screenshots, notes, goals, records, wealth.
		 */
		private List<String> sections = new ArrayList<>();
		/**
		 * The look of the character model on the page (see {@link CharacterModel#look}), or null.
		 */
		private String character;
		/**
		 * Which version of the collection log the page shows (the hash the plugin sent), or null.
		 */
		@SerializedName("collection_log")
		private String collectionLog;
		/**
		 * Which version of the quests and combat tasks the page shows (the hash the plugin sent), or null.
		 */
		private String achievements;
		/**
		 * Taken down after a report; it can't be made public again.
		 */
		private boolean blocked;
		/**
		 * The page's address once it's published.
		 */
		private String url;
	}

	@Data
	public static class PublicReply
	{
		@SerializedName("public")
		private PublicSettings publicSettings;
	}

	@Data
	public static class Published
	{
		private String url;
	}

	@Data
	public static class Device
	{
		@SerializedName("device_id")
		private String deviceId;
		/**
		 * Named after the key the PC connected with.
		 */
		private String name;
	}

	@Data
	public static class Registered
	{
		private Device device;
	}

	@Data
	public static class Resolved
	{
		private Profile profile;
		private int seq;
		private boolean created;
	}

	@Data
	public static class Change
	{
		private String kind;
		@SerializedName("doc_key")
		private String docKey;
		@SerializedName("device_id")
		private String deviceId;
		private long cursor;
		private long size;
		private String sha256;
		private String schema;
		private boolean deleted;
		/**
		 * What the file is fetched by, through the API.
		 */
		private long id;
	}

	@Data
	public static class Changes
	{
		private List<Change> changes = new ArrayList<>();
		private long cursor;
		private boolean more;
		private Profile profile;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class FileSpec
	{
		private String kind;
		@SerializedName("doc_key")
		private String docKey;
		private long size;
		private String sha256;
		private String schema;
	}

	@Data
	public static class Upload
	{
		@SerializedName("upload_id")
		private String uploadId;
		private String kind;
		@SerializedName("doc_key")
		private String docKey;
	}

	@Data
	public static class Uploads
	{
		private List<Upload> uploads = new ArrayList<>();
	}

	@Data
	public static class Committed
	{
		private long cursor;
		private int seq;
	}

	/**
	 * Why the API turned a request down.
	 */
	@Data
	public static class Error
	{
		private String error;
		private String message;
		private Integer seq;
	}
}
