package com.runejourney.cloud;

import com.google.gson.annotations.SerializedName;
import java.util.*;
import lombok.*;

public final class Api
{
	private Api()
	{
	}

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

	@Data
	public static class PublicSettings
	{
		private boolean enabled;
		private boolean searchable;
		private List<String> sections = new ArrayList<>();
		private String character;
		@SerializedName("collection_log")
		private String collectionLog;
		private String achievements;
		private boolean blocked;
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

	@Data
	public static class Error
	{
		private String error;
		private String message;
		private Integer seq;
	}
}
