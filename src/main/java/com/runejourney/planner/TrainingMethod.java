package com.runejourney.planner;

import lombok.Getter;

public class TrainingMethod
{
	@Getter
	private String name;
	private long[][] rates;

	TrainingMethod()
	{
	}

	public TrainingMethod(String name, long[][] rates)
	{
		this.name = name;
		this.rates = rates;
	}

	public double rateAt(long xp)
	{
		double rate = rates[0][1];
		for (long[] step : rates)
		{
			if (xp >= step[0])
			{
				rate = step[1];
			}
		}
		return Math.max(1, rate);
	}

	public double hours(long fromXp, long toXp)
	{
		double hours = 0;
		long xp = fromXp;
		while (xp < toXp)
		{
			long next = toXp;
			for (long[] step : rates)
			{
				if (step[0] > xp && step[0] < next)
				{
					next = step[0];
				}
			}
			hours += (next - xp) / rateAt(xp);
			xp = next;
		}
		return hours;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
