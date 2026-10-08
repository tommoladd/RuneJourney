package com.runejourney.service;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * A public account's whole collection log, for its page: every tab and page in the game's order,
 * and each item's quantity (0 if it hasn't been obtained).
 */
@Data
public class PublicCollectionLog
{
	/**
	 * When it was synced from the game.
	 */
	@SerializedName("synced_at")
	private String syncedAt;
	private List<Tab> tabs = new ArrayList<>();
	/**
	 * Item ID to its name, once for each item.
	 */
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
		/**
		 * [item ID, quantity] for each item on the page, in the game's order.
		 */
		private List<int[]> items = new ArrayList<>();
	}
}
