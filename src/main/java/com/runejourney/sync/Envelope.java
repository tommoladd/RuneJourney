package com.runejourney.sync;

import com.runejourney.model.*;
import lombok.Data;

public final class Envelope
{
	public static final int MAJOR = 1;
	public static final int MINOR = 0;
	public static final String SCHEMA = MAJOR + "." + MINOR;
	public static final String PROFILE = "profile";
	private static final String DAY_PREFIX = "day:";

	private Envelope()
	{
	}

	@Data
	public static class Doc
	{
		private String schema;
		private ProfileSlice profile;
		private DaySlice day;
	}

	public static boolean readable(String schema)
	{
		if (schema == null)
		{
			return false;
		}
		int dot = schema.indexOf('.');
		try
		{
			return Integer.parseInt(dot < 0 ? schema : schema.substring(0, dot)) <= MAJOR;
		}
		catch (NumberFormatException e)
		{
			return false;
		}
	}

	public static String dayKey(String date)
	{
		return DAY_PREFIX + date;
	}

	public static String dateOf(String docKey)
	{
		return docKey != null && docKey.startsWith(DAY_PREFIX) ? docKey.substring(DAY_PREFIX.length()) : null;
	}

	public static Doc profile(ProfileSlice slice)
	{
		Doc d = new Doc();
		d.setSchema(SCHEMA);
		d.setProfile(slice);
		return d;
	}

	public static Doc day(DaySlice slice)
	{
		Doc d = new Doc();
		d.setSchema(SCHEMA);
		d.setDay(slice);
		return d;
	}
}
