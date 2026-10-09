package com.runejourney.sync;

import com.runejourney.planner.Skills;
import java.util.*;

public final class XpRanges
{
	private XpRanges()
	{
	}

	public static List<long[]> normalize(Collection<long[]> ranges)
	{
		List<long[]> sorted = new ArrayList<>();
		for (long[] r : ranges)
		{
			if (r != null && r.length == 2 && r[1] > r[0])
			{
				sorted.add(new long[]{r[0], r[1]});
			}
		}
		sorted.sort((a, b) -> Long.compare(a[0], b[0]));
		List<long[]> out = new ArrayList<>();
		for (long[] r : sorted)
		{
			long[] last = out.isEmpty() ? null : out.get(out.size() - 1);
			if (last != null && r[0] <= last[1])
			{
				last[1] = Math.max(last[1], r[1]);
			}
			else
			{
				out.add(r);
			}
		}
		return out;
	}

	public static List<long[]> union(Collection<long[]> a, Collection<long[]> b)
	{
		List<long[]> all = new ArrayList<>(a);
		all.addAll(b);
		return normalize(all);
	}

	public static List<long[]> subtract(Collection<long[]> a, Collection<long[]> b)
	{
		List<long[]> out = new ArrayList<>();
		List<long[]> cut = normalize(b);
		for (long[] r : normalize(a))
		{
			long from = r[0];
			for (long[] c : cut)
			{
				if (c[1] <= from || c[0] >= r[1])
				{
					continue;
				}
				if (c[0] > from)
				{
					out.add(new long[]{from, c[0]});
				}
				from = Math.max(from, c[1]);
				if (from >= r[1])
				{
					break;
				}
			}
			if (from < r[1])
			{
				out.add(new long[]{from, r[1]});
			}
		}
		return out;
	}

	public static long length(Collection<long[]> ranges)
	{
		long total = 0;
		for (long[] r : ranges)
		{
			total += Math.max(0, r[1] - r[0]);
		}
		return total;
	}

	public static int levels(Collection<long[]> ranges)
	{
		int total = 0;
		for (long[] r : ranges)
		{
			total += Math.max(0, Skills.level(r[1]) - Skills.level(r[0]));
		}
		return total;
	}

	public static Map<String, List<long[]>> union(Map<String, List<long[]>> a, Map<String, List<long[]>> b)
	{
		Map<String, List<long[]>> out = new HashMap<>();
		Set<String> skills = new HashSet<>(a.keySet());
		skills.addAll(b.keySet());
		for (String skill : skills)
		{
			List<long[]> joined = union(orEmpty(a.get(skill)), orEmpty(b.get(skill)));
			if (!joined.isEmpty())
			{
				out.put(skill, joined);
			}
		}
		return out;
	}

	public static Map<String, List<long[]>> subtract(Map<String, List<long[]>> a, Map<String, List<long[]>> b)
	{
		Map<String, List<long[]>> out = new HashMap<>();
		a.forEach((skill, ranges) ->
		{
			List<long[]> left = subtract(ranges, orEmpty(b.get(skill)));
			if (!left.isEmpty())
			{
				out.put(skill, left);
			}
		});
		return out;
	}

	public static Map<String, List<long[]>> normalize(Map<String, List<long[]>> ranges)
	{
		return union(ranges, new HashMap<>());
	}

	public static boolean same(Map<String, List<long[]>> a, Map<String, List<long[]>> b)
	{
		Map<String, List<long[]>> x = normalize(a);
		Map<String, List<long[]>> y = normalize(b);
		if (!x.keySet().equals(y.keySet()))
		{
			return false;
		}
		for (Map.Entry<String, List<long[]>> e : x.entrySet())
		{
			List<long[]> other = y.get(e.getKey());
			if (other.size() != e.getValue().size())
			{
				return false;
			}
			for (int i = 0; i < other.size(); i++)
			{
				if (other.get(i)[0] != e.getValue().get(i)[0] || other.get(i)[1] != e.getValue().get(i)[1])
				{
					return false;
				}
			}
		}
		return true;
	}

	private static List<long[]> orEmpty(List<long[]> ranges)
	{
		return ranges == null ? new ArrayList<>() : ranges;
	}
}
