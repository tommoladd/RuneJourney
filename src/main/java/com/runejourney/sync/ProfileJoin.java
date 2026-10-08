package com.runejourney.sync;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.runejourney.model.ProfileData;
import com.runejourney.model.ProfileSlice;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

/**
 * The account's long-lived state, kept by several PCs. Every field has a fixed rule for combining
 * the PCs' copies, so the result is the same whatever order the copies arrive in.
 */
public final class ProfileJoin
{
	enum Rule
	{
		/**
		 * Stays on this PC: never uploaded, and kept as it is here.
		 */
		LOCAL,
		/**
		 * Only ever goes up: the highest wins.
		 */
		MAX,
		/**
		 * The earliest that's set.
		 */
		EARLIEST_SET,
		/**
		 * Once true, stays true.
		 */
		ANY,
		/**
		 * Highest for each key.
		 */
		MAP_MAX,
		/**
		 * Each PC counts its own share: added up for each key.
		 */
		MAP_ADD,
		/**
		 * The newest change wins, by logical clock.
		 */
		NEWEST,
		/**
		 * The newest change wins, for each key separately.
		 */
		MAP_NEWEST,
		/**
		 * The latest date.
		 */
		LATEST_DATE,
		/**
		 * The longest session and the date it was set.
		 */
		LONGEST_SESSION,
		/**
		 * See {@link GoalJoin}.
		 */
		GOALS
	}

	static final Map<String, Rule> RULES = new LinkedHashMap<>();

	static
	{
		RULES.put("playerName", Rule.LOCAL);
		RULES.put("createdAt", Rule.EARLIEST_SET);
		RULES.put("lastXp", Rule.MAP_MAX);
		RULES.put("lastXpAt", Rule.MAX);
		RULES.put("killCounts", Rule.MAP_MAX);
		RULES.put("savedMethods", Rule.MAP_NEWEST);
		RULES.put("detectedMethods", Rule.MAP_NEWEST);
		RULES.put("dismissedRates", Rule.MAP_NEWEST);
		RULES.put("killTimes", Rule.MAP_ADD);
		RULES.put("clueCounts", Rule.MAP_MAX);
		RULES.put("combatAchievementPoints", Rule.NEWEST);
		RULES.put("combatTasks", Rule.MAX);
		RULES.put("combatTasksFromGame", Rule.ANY);
		RULES.put("questPoints", Rule.MAX);
		RULES.put("collectionLogSlots", Rule.MAX);
		RULES.put("collectionLogTotal", Rule.MAX);
		RULES.put("bankCash", Rule.NEWEST);
		RULES.put("bankCashKnown", Rule.ANY);
		RULES.put("inventoryCash", Rule.NEWEST);
		RULES.put("geSlots", Rule.NEWEST);
		RULES.put("holdings", Rule.MAP_NEWEST);
		RULES.put("wealthParts", Rule.MAP_NEWEST);
		RULES.put("bankValueKnown", Rule.ANY);
		// Before holdings were tracked; cleared when loaded
		RULES.put("bankValue", Rule.LOCAL);
		RULES.put("inventoryValue", Rule.LOCAL);
		RULES.put("equipmentValue", Rule.LOCAL);
		RULES.put("bestWealth", Rule.MAX);
		RULES.put("collectionLog", Rule.MAP_NEWEST);
		RULES.put("collectionLogTabs", Rule.NEWEST);
		RULES.put("collectionLogSyncedAt", Rule.MAX);
		RULES.put("overlayGoalId", Rule.NEWEST);
		RULES.put("longestSessionMillis", Rule.LONGEST_SESSION);
		RULES.put("longestSessionDate", Rule.LONGEST_SESSION);
		RULES.put("bestPlayStreak", Rule.MAX);
		RULES.put("wrappedSeen", Rule.LATEST_DATE);
		RULES.put("wrappedNotified", Rule.LATEST_DATE);
		RULES.put("goals", Rule.GOALS);
		RULES.put("session", Rule.NEWEST);
		RULES.put("bestXpDay", Rule.MAX);
		RULES.put("bestLootDay", Rule.MAX);
		RULES.put("preferredMethods", Rule.MAP_NEWEST);
		RULES.put("sync", Rule.LOCAL);
	}

	private ProfileJoin()
	{
	}

	/**
	 * Every PC's copy combined. Fields that stay on this PC are taken from {@code local}.
	 *
	 * @param gson a Gson that leaves out sync parts
	 */
	public static ProfileData combine(Gson gson, ProfileData local, Map<String, ProfileSlice> slices)
	{
		List<String> devices = new ArrayList<>();
		Map<String, JsonObject> trees = new HashMap<>();
		slices.forEach((device, slice) ->
		{
			if (slice != null && slice.getProfile() != null)
			{
				devices.add(device);
				trees.put(device, gson.toJsonTree(slice.getProfile()).getAsJsonObject());
			}
		});
		Collections.sort(devices);
		JsonObject mine = local == null ? new JsonObject() : gson.toJsonTree(local).getAsJsonObject();

		JsonObject out = new JsonObject();
		RULES.forEach((field, rule) ->
		{
			switch (rule)
			{
				case LOCAL:
					Trees.copy(mine, out, Collections.singleton(field));
					break;
				case MAX:
					maxOf(devices, trees, field).ifPresent(v -> out.addProperty(field, v));
					break;
				case EARLIEST_SET:
					earliestSet(devices, trees, field).ifPresent(v -> out.addProperty(field, v));
					break;
				case ANY:
					out.addProperty(field, devices.stream().anyMatch(d -> Trees.getBoolean(trees.get(d), field)));
					break;
				case MAP_MAX:
					out.add(field, mapMax(devices, trees, field));
					break;
				case MAP_ADD:
				{
					JsonObject sum = new JsonObject();
					devices.forEach(d -> Trees.add(sum, Trees.object(trees.get(d), field), 1));
					Trees.prune(sum);
					out.add(field, sum);
					break;
				}
				case NEWEST:
				{
					JsonElement v = newest(devices, trees, slices, field, field);
					if (Trees.present(v))
					{
						out.add(field, v.deepCopy());
					}
					break;
				}
				case MAP_NEWEST:
					out.add(field, mapNewest(devices, trees, slices, field));
					break;
				case LATEST_DATE:
				{
					String latest = null;
					for (String d : devices)
					{
						String v = Trees.getString(trees.get(d), field);
						if (v != null && (latest == null || v.compareTo(latest) > 0))
						{
							latest = v;
						}
					}
					if (latest != null)
					{
						out.addProperty(field, latest);
					}
					break;
				}
				case LONGEST_SESSION:
				{
					String best = null;
					for (String d : devices)
					{
						if (best == null || Trees.getLong(trees.get(d), "longestSessionMillis") > Trees.getLong(trees.get(best), "longestSessionMillis"))
						{
							best = d;
						}
					}
					if (best != null)
					{
						Trees.copy(trees.get(best), out, Collections.singleton(field));
					}
					break;
				}
				case GOALS:
				{
					Map<String, JsonArray> goals = new HashMap<>();
					devices.forEach(d -> goals.put(d, array(trees.get(d), field)));
					out.add(field, GoalJoin.combine(devices, goals, slices));
					break;
				}
			}
		});
		return gson.fromJson(out, ProfileData.class);
	}

	/**
	 * This PC's new copy: anything changed here since it was last worked out is taken from
	 * {@code view} and stamped with a new clock, so it wins over older changes on other PCs.
	 *
	 * @param view   the profile as it is now on this PC
	 * @param slices every copy as of when this PC's copy was last worked out, including its own
	 */
	public static ProfileSlice reconcile(Gson gson, ProfileData view, String me, Map<String, ProfileSlice> slices, Hlc hlc)
	{
		ProfileSlice own = slices.get(me);
		Map<String, JsonObject> remotes = new HashMap<>();
		slices.forEach((device, slice) ->
		{
			if (!device.equals(me) && slice != null && slice.getProfile() != null)
			{
				remotes.put(device, gson.toJsonTree(slice.getProfile()).getAsJsonObject());
			}
		});
		JsonObject v = gson.toJsonTree(view).getAsJsonObject();
		JsonObject base = gson.toJsonTree(combine(gson, view, slices)).getAsJsonObject();
		JsonObject o = own != null && own.getProfile() != null ? gson.toJsonTree(own.getProfile()).getAsJsonObject() : new JsonObject();

		ProfileSlice next = new ProfileSlice();
		if (own != null)
		{
			next.getClocks().putAll(own.getClocks());
			next.getDeletedGoals().addAll(own.getDeletedGoals());
		}
		JsonObject out = new JsonObject();
		RULES.forEach((field, rule) ->
		{
			switch (rule)
			{
				case LOCAL:
					break;
				case MAX:
				case EARLIEST_SET:
				case ANY:
				case MAP_MAX:
				case LATEST_DATE:
				case LONGEST_SESSION:
					// The combined value already includes every copy's
					Trees.copy(v, out, Collections.singleton(field));
					break;
				case MAP_ADD:
				{
					JsonObject m = Trees.object(v, field).deepCopy();
					remotes.values().forEach(r -> Trees.add(m, Trees.object(r, field), -1));
					Trees.prune(m);
					out.add(field, m);
					break;
				}
				case NEWEST:
					if (!Objects.equals(v.get(field), base.get(field)))
					{
						Trees.copy(v, out, Collections.singleton(field));
						next.getClocks().put(field, hlc.next());
					}
					else
					{
						Trees.copy(o, out, Collections.singleton(field));
					}
					break;
				case MAP_NEWEST:
				{
					JsonObject vm = Trees.object(v, field);
					JsonObject bm = Trees.object(base, field);
					JsonObject om = Trees.object(o, field).deepCopy();
					Set<String> keys = new TreeSet<>(vm.keySet());
					keys.addAll(bm.keySet());
					for (String key : keys)
					{
						if (!Objects.equals(vm.get(key), bm.get(key)))
						{
							if (vm.has(key))
							{
								om.add(key, vm.get(key).deepCopy());
							}
							else
							{
								om.remove(key);
							}
							next.getClocks().put(field + "." + key, hlc.next());
						}
					}
					out.add(field, om);
					break;
				}
				case GOALS:
					out.add(field, GoalJoin.reconcile(array(v, field), array(base, field), array(o, field), next, hlc));
					break;
			}
		});
		next.setProfile(gson.fromJson(out, ProfileData.class));
		return next;
	}

	/**
	 * Whether the profile is exactly the copies combined, i.e. nothing has changed since this PC's
	 * copy was last worked out.
	 */
	public static boolean matches(Gson gson, ProfileData view, Map<String, ProfileSlice> slices)
	{
		JsonObject v = gson.toJsonTree(view).getAsJsonObject();
		JsonObject c = gson.toJsonTree(combine(gson, view, slices)).getAsJsonObject();
		for (Map.Entry<String, Rule> e : RULES.entrySet())
		{
			if (e.getValue() == Rule.LOCAL)
			{
				continue;
			}
			JsonElement a = v.get(e.getKey());
			JsonElement b = c.get(e.getKey());
			if (e.getValue() == Rule.GOALS)
			{
				a = byIdObject(a);
				b = byIdObject(b);
			}
			else if (e.getValue() == Rule.MAP_ADD)
			{
				a = pruned(a);
				b = pruned(b);
			}
			if (!Objects.equals(emptyToNull(a), emptyToNull(b)))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Every clock in a copy, so this PC's clock can move past them.
	 */
	public static long latestClock(ProfileSlice slice)
	{
		long latest = 0;
		for (long c : slice.getClocks().values())
		{
			latest = Math.max(latest, c);
		}
		return latest;
	}

	private static JsonElement emptyToNull(JsonElement e)
	{
		if (e == null || e.isJsonNull() || (e.isJsonObject() && e.getAsJsonObject().size() == 0)
			|| (e.isJsonArray() && e.getAsJsonArray().size() == 0))
		{
			return null;
		}
		return e;
	}

	private static JsonElement pruned(JsonElement e)
	{
		if (e == null || !e.isJsonObject())
		{
			return e;
		}
		JsonObject copy = e.getAsJsonObject().deepCopy();
		Trees.prune(copy);
		return copy;
	}

	private static JsonElement byIdObject(JsonElement goals)
	{
		JsonObject out = new JsonObject();
		if (goals != null && goals.isJsonArray())
		{
			for (JsonElement g : goals.getAsJsonArray())
			{
				out.add(String.valueOf(Trees.getString(g.getAsJsonObject(), "id")), g);
			}
		}
		return out;
	}

	private static JsonArray array(JsonObject o, String field)
	{
		JsonElement e = o.get(field);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
	}

	private static OptionalLong maxOf(List<String> devices, Map<String, JsonObject> trees, String field)
	{
		Long best = null;
		for (String d : devices)
		{
			JsonElement e = trees.get(d).get(field);
			if (Trees.present(e))
			{
				best = best == null ? e.getAsLong() : Math.max(best, e.getAsLong());
			}
		}
		return best == null ? OptionalLong.empty() : OptionalLong.of(best);
	}

	private static OptionalLong earliestSet(List<String> devices, Map<String, JsonObject> trees, String field)
	{
		long best = 0;
		for (String d : devices)
		{
			long v = Trees.getLong(trees.get(d), field);
			if (v > 0 && (best == 0 || v < best))
			{
				best = v;
			}
		}
		return best == 0 ? OptionalLong.empty() : OptionalLong.of(best);
	}

	private static JsonObject mapMax(List<String> devices, Map<String, JsonObject> trees, String field)
	{
		JsonObject out = new JsonObject();
		for (String d : devices)
		{
			for (Map.Entry<String, JsonElement> e : Trees.object(trees.get(d), field).entrySet())
			{
				if (!e.getValue().isJsonPrimitive())
				{
					continue;
				}
				long v = e.getValue().getAsLong();
				if (!out.has(e.getKey()) || out.get(e.getKey()).getAsLong() < v)
				{
					out.addProperty(e.getKey(), v);
				}
			}
		}
		return out;
	}

	/**
	 * The value with the newest clock. With equal clocks (copies made before syncing), a value
	 * beats no value, then the first device ID wins.
	 */
	private static JsonElement newest(List<String> devices, Map<String, JsonObject> trees, Map<String, ProfileSlice> slices,
		String clockKey, String field)
	{
		JsonElement best = null;
		long bestClock = -1;
		for (String d : devices)
		{
			long c = slices.get(d).getClocks().getOrDefault(clockKey, 0L);
			JsonElement v = trees.get(d).get(field);
			if (c > bestClock || (c == bestClock && Trees.present(v) && !Trees.present(best)))
			{
				best = v;
				bestClock = c;
			}
		}
		return best;
	}

	private static JsonObject mapNewest(List<String> devices, Map<String, JsonObject> trees, Map<String, ProfileSlice> slices, String field)
	{
		Set<String> keys = new TreeSet<>();
		String prefix = field + ".";
		for (String d : devices)
		{
			keys.addAll(Trees.object(trees.get(d), field).keySet());
			for (String clock : slices.get(d).getClocks().keySet())
			{
				if (clock.startsWith(prefix))
				{
					keys.add(clock.substring(prefix.length()));
				}
			}
		}
		JsonObject out = new JsonObject();
		for (String key : keys)
		{
			JsonElement best = null;
			long bestClock = -1;
			for (String d : devices)
			{
				long c = slices.get(d).getClocks().getOrDefault(prefix + key, 0L);
				JsonElement v = Trees.object(trees.get(d), field).get(key);
				if (c > bestClock || (c == bestClock && Trees.present(v) && !Trees.present(best)))
				{
					best = v;
					bestClock = c;
				}
			}
			if (Trees.present(best))
			{
				out.add(key, best.deepCopy());
			}
		}
		return out;
	}
}
