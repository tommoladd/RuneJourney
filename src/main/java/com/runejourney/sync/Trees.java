package com.runejourney.sync;

import com.google.gson.*;
import com.runejourney.model.*;
import java.util.*;

public final class Trees
{
	private Trees()
	{
	}

	public static Gson withoutSync(Gson gson)
	{
		return gson.newBuilder().addSerializationExclusionStrategy(new ExclusionStrategy()
		{
			@Override
			public boolean shouldSkipField(FieldAttributes f)
			{
				return "sync".equals(f.getName())
					&& (f.getDeclaringClass() == DayRecord.class || f.getDeclaringClass() == ProfileData.class);
			}

			@Override
			public boolean shouldSkipClass(Class<?> clazz)
			{
				return false;
			}
		}).create();
	}

	static void add(JsonObject into, JsonObject from, int sign)
	{
		for (Map.Entry<String, JsonElement> e : from.entrySet())
		{
			JsonElement value = e.getValue();
			JsonElement current = into.get(e.getKey());
			if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())
			{
				long before = current != null && current.isJsonPrimitive() && current.getAsJsonPrimitive().isNumber() ? current.getAsLong() : 0;
				into.addProperty(e.getKey(), before + sign * value.getAsLong());
			}
			else if (value.isJsonObject())
			{
				JsonObject child = current != null && current.isJsonObject() ? current.getAsJsonObject() : new JsonObject();
				add(child, value.getAsJsonObject(), sign);
				into.add(e.getKey(), child);
			}
		}
	}

	static void prune(JsonObject o)
	{
		List<String> empty = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : o.entrySet())
		{
			JsonElement value = e.getValue();
			if (value.isJsonObject())
			{
				prune(value.getAsJsonObject());
				if (value.getAsJsonObject().size() == 0)
				{
					empty.add(e.getKey());
				}
			}
			else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && value.getAsLong() == 0)
			{
				empty.add(e.getKey());
			}
		}
		empty.forEach(o::remove);
	}

	static long getLong(JsonObject o, String key)
	{
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : 0;
	}

	static boolean getBoolean(JsonObject o, String key)
	{
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
	}

	static String getString(JsonObject o, String key)
	{
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	static JsonObject object(JsonObject o, String key)
	{
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
	}

	static void copy(JsonObject from, JsonObject to, Collection<String> keys)
	{
		for (String key : keys)
		{
			JsonElement value = from.get(key);
			if (value == null || value.isJsonNull())
			{
				to.remove(key);
			}
			else
			{
				to.add(key, value.deepCopy());
			}
		}
	}

	static JsonObject only(JsonObject from, Collection<String> keys)
	{
		JsonObject out = new JsonObject();
		copy(from, out, keys);
		return out;
	}

	static boolean present(JsonElement e)
	{
		return e != null && !e.isJsonNull();
	}
}
