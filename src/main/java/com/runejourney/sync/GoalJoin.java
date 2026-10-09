package com.runejourney.sync;

import com.google.gson.*;
import com.runejourney.model.ProfileSlice;
import java.util.*;

final class GoalJoin
{
	static final Set<String> SAME = keys("id", "type", "createdAt", "startXp", "startTotalLevel", "startCount");
	static final Set<String> SPEC = keys("name", "skill", "targetXp", "targetLevel", "targetDate", "hoursPerWeek", "notes",
		"counter", "targetCount", "relative", "quantity", "fixedPrice", "items");
	static final Set<String> PLAN = keys("weekStart", "weekStartXp", "weekTargets", "lastWeekResults", "weeksPlanned", "weeksMet",
		"planStreak", "bestPlanStreak");
	static final Set<String> DONE = keys("completedAt", "story");
	static final Set<String> ANY = keys("affordable");
	static final Set<String> SUM = keys("purchasedQuantity", "purchaseSpent");
	static final Set<String> ITEM_STATE = keys("obtainedAt", "source");

	private GoalJoin()
	{
	}

	static JsonArray combine(List<String> devices, Map<String, JsonArray> goals, Map<String, ProfileSlice> slices)
	{
		Set<String> deleted = new HashSet<>();
		devices.forEach(d -> deleted.addAll(slices.get(d).getDeletedGoals()));
		Map<String, Map<String, JsonObject>> copies = new LinkedHashMap<>();
		for (String device : devices)
		{
			for (JsonElement e : goals.getOrDefault(device, new JsonArray()))
			{
				String id = Trees.getString(e.getAsJsonObject(), "id");
				if (id != null && !deleted.contains(id))
				{
					copies.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(device, e.getAsJsonObject());
				}
			}
		}

		List<JsonObject> out = new ArrayList<>();
		copies.forEach((id, byDevice) ->
		{
			JsonObject g = new JsonObject();
			JsonObject first = byDevice.values().iterator().next();
			Trees.copy(first, g, SAME);
			Trees.copy(first, g, DONE);
			Trees.copy(byDevice.get(newest(byDevice, slices, clock(id, "spec"))), g, SPEC);
			Trees.copy(byDevice.get(latestPlan(byDevice, slices, id)), g, PLAN);

			String doneBy = null;
			long done = 0;
			boolean affordable = false;
			for (Map.Entry<String, JsonObject> e : byDevice.entrySet())
			{
				long at = Trees.getLong(e.getValue(), "completedAt");
				if (at > 0 && (done == 0 || at < done))
				{
					done = at;
					doneBy = e.getKey();
				}
				affordable |= Trees.getBoolean(e.getValue(), "affordable");
			}
			if (doneBy != null)
			{
				Trees.copy(byDevice.get(doneBy), g, DONE);
			}
			g.addProperty("affordable", affordable);
			for (String key : SUM)
			{
				long total = 0;
				for (JsonObject copy : byDevice.values())
				{
					total += Trees.getLong(copy, key);
				}
				g.addProperty(key, total);
			}

			if (g.has("items") && g.get("items").isJsonArray())
			{
				for (JsonElement item : g.getAsJsonArray("items"))
				{
					itemState(id, item.getAsJsonObject(), byDevice, slices);
				}
			}
			out.add(g);
		});
		out.sort(Comparator.comparingLong((JsonObject g) -> Trees.getLong(g, "createdAt"))
			.thenComparing(g -> Trees.getString(g, "id")));
		JsonArray array = new JsonArray();
		out.forEach(array::add);
		return array;
	}

	static JsonArray reconcile(JsonArray viewGoals, JsonArray baseGoals, JsonArray ownGoals, ProfileSlice next, Hlc hlc)
	{
		Map<String, JsonObject> view = byId(viewGoals);
		Map<String, JsonObject> base = byId(baseGoals);
		Map<String, JsonObject> own = byId(ownGoals);
		JsonArray out = new JsonArray();
		view.forEach((id, vg) ->
		{
			JsonObject bg = base.get(id);
			if (bg == null)
			{
				JsonObject ng = vg.deepCopy();
				next.getClocks().put(clock(id, "spec"), hlc.next());
				next.getClocks().put(clock(id, "plan"), hlc.next());
				for (JsonElement item : items(vg))
				{
					next.getClocks().put(clock(id, "item." + itemKey(item.getAsJsonObject())), hlc.next());
				}
				out.add(ng);
				return;
			}

			JsonObject og = own.get(id);
			JsonObject ng;
			if (og != null)
			{
				ng = og.deepCopy();
			}
			else
			{
				ng = vg.deepCopy();
				SUM.forEach(k -> ng.addProperty(k, 0));
			}
			if (!Objects.equals(spec(vg), spec(bg)))
			{
				Trees.copy(vg, ng, SPEC);
				next.getClocks().put(clock(id, "spec"), hlc.next());
			}
			if (!Objects.equals(Trees.only(vg, PLAN), Trees.only(bg, PLAN)))
			{
				Trees.copy(vg, ng, PLAN);
				next.getClocks().put(clock(id, "plan"), hlc.next());
			}
			Map<String, JsonObject> baseItems = itemsByKey(bg);
			for (JsonElement e : items(vg))
			{
				JsonObject item = e.getAsJsonObject();
				String key = itemKey(item);
				JsonObject was = baseItems.get(key);
				if (was == null || !Trees.only(item, ITEM_STATE).equals(Trees.only(was, ITEM_STATE)))
				{
					JsonObject mine = itemsByKey(ng).get(key);
					if (mine == null)
					{
						mine = item.deepCopy();
						if (!ng.has("items") || !ng.get("items").isJsonArray())
						{
							ng.add("items", new JsonArray());
						}
						ng.getAsJsonArray("items").add(mine);
					}
					Trees.copy(item, mine, ITEM_STATE);
					next.getClocks().put(clock(id, "item." + key), hlc.next());
				}
			}
			if (Trees.getLong(vg, "completedAt") != Trees.getLong(bg, "completedAt"))
			{
				Trees.copy(vg, ng, DONE);
			}
			if (Trees.getBoolean(vg, "affordable") && !Trees.getBoolean(bg, "affordable"))
			{
				ng.addProperty("affordable", true);
			}
			for (String key : SUM)
			{
				ng.addProperty(key, Trees.getLong(ng, key) + Trees.getLong(vg, key) - Trees.getLong(bg, key));
			}
			out.add(ng);
		});
		base.keySet().forEach(id ->
		{
			if (!view.containsKey(id))
			{
				next.getDeletedGoals().add(id);
			}
		});
		return out;
	}

	private static void itemState(String goalId, JsonObject item, Map<String, JsonObject> byDevice, Map<String, ProfileSlice> slices)
	{
		String key = itemKey(item);
		String clockKey = clock(goalId, "item." + key);
		JsonObject best = null;
		long bestClock = -1;
		for (Map.Entry<String, JsonObject> e : byDevice.entrySet())
		{
			JsonObject theirs = itemsByKey(e.getValue()).get(key);
			if (theirs == null)
			{
				continue;
			}
			long c = slices.get(e.getKey()).getClocks().getOrDefault(clockKey, 0L);
			if (best == null || c > bestClock || (c == bestClock && Trees.getLong(theirs, "obtainedAt") > 0 && Trees.getLong(best, "obtainedAt") == 0))
			{
				best = theirs;
				bestClock = c;
			}
		}
		if (best != null)
		{
			Trees.copy(best, item, ITEM_STATE);
		}
	}

	private static String newest(Map<String, JsonObject> byDevice, Map<String, ProfileSlice> slices, String clockKey)
	{
		String best = null;
		long bestClock = -1;
		for (String device : byDevice.keySet())
		{
			long c = slices.get(device).getClocks().getOrDefault(clockKey, 0L);
			if (c > bestClock)
			{
				best = device;
				bestClock = c;
			}
		}
		return best;
	}

	private static String latestPlan(Map<String, JsonObject> byDevice, Map<String, ProfileSlice> slices, String id)
	{
		String best = null;
		String bestWeek = null;
		long bestClock = -1;
		for (Map.Entry<String, JsonObject> e : byDevice.entrySet())
		{
			String week = Trees.getString(e.getValue(), "weekStart");
			long c = slices.get(e.getKey()).getClocks().getOrDefault(clock(id, "plan"), 0L);
			int cmp = best == null ? 1 : compare(week, bestWeek);
			if (cmp > 0 || (cmp == 0 && c > bestClock))
			{
				best = e.getKey();
				bestWeek = week;
				bestClock = c;
			}
		}
		return best;
	}

	private static int compare(String a, String b)
	{
		if (a == null)
		{
			return b == null ? 0 : -1;
		}
		return b == null ? 1 : a.compareTo(b);
	}

	private static JsonObject spec(JsonObject goal)
	{
		JsonObject spec = Trees.only(goal, SPEC);
		if (spec.has("items") && spec.get("items").isJsonArray())
		{
			for (JsonElement item : spec.getAsJsonArray("items"))
			{
				ITEM_STATE.forEach(item.getAsJsonObject()::remove);
			}
		}
		return spec;
	}

	static String clock(String goalId, String part)
	{
		return "goal." + goalId + "." + part;
	}

	private static JsonArray items(JsonObject goal)
	{
		JsonElement items = goal.get("items");
		return items != null && items.isJsonArray() ? items.getAsJsonArray() : new JsonArray();
	}

	private static Map<String, JsonObject> itemsByKey(JsonObject goal)
	{
		Map<String, JsonObject> out = new LinkedHashMap<>();
		for (JsonElement e : items(goal))
		{
			out.putIfAbsent(itemKey(e.getAsJsonObject()), e.getAsJsonObject());
		}
		return out;
	}

	static String itemKey(JsonObject item)
	{
		long id = Trees.getLong(item, "id");
		if (id > 0)
		{
			return "id:" + id;
		}
		String name = Trees.getString(item, "name");
		return "name:" + (name == null ? "" : name.toLowerCase(Locale.ENGLISH));
	}

	private static Map<String, JsonObject> byId(JsonArray goals)
	{
		Map<String, JsonObject> out = new LinkedHashMap<>();
		if (goals != null)
		{
			for (JsonElement e : goals)
			{
				String id = Trees.getString(e.getAsJsonObject(), "id");
				if (id != null)
				{
					out.put(id, e.getAsJsonObject());
				}
			}
		}
		return out;
	}

	private static Set<String> keys(String... keys)
	{
		return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(keys)));
	}
}
