package com.runejourney.service;

import com.google.gson.annotations.SerializedName;
import java.util.*;
import lombok.Data;

@Data
public class PublicCollectionLog
{
	@SerializedName("synced_at")
	private String syncedAt;
	private List<Tab> tabs = new ArrayList<>();
	private Map<String, String> items = new LinkedHashMap<>();

	@Data
	public static class Tab
	{
		private String name;
		private List<Page> pages = new ArrayList<>();
	}

	@Data
	public static class Page
	{
		private String name;
		private List<int[]> items = new ArrayList<>();
	}
}
