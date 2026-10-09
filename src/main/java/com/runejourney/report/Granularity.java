package com.runejourney.report;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.*;
import java.util.Locale;
import lombok.*;

@Getter
@RequiredArgsConstructor
public enum Granularity
{
	AUTO("Automatic"),
	DAY("Day"),
	WEEK("Week"),
	MONTH("Month");

	private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
	private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);

	private final String label;

	@Override
	public String toString()
	{
		return label;
	}

	public Granularity resolve(LocalDate from, LocalDate to)
	{
		if (this != AUTO)
		{
			return this;
		}
		long days = ChronoUnit.DAYS.between(from, to) + 1;
		if (days <= 45)
		{
			return DAY;
		}
		return days <= 200 ? WEEK : MONTH;
	}

	public LocalDate bucketStart(LocalDate d)
	{
		switch (this)
		{
			case WEEK:
				return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
			case MONTH:
				return d.withDayOfMonth(1);
			default:
				return d;
		}
	}

	public LocalDate next(LocalDate start)
	{
		switch (this)
		{
			case WEEK:
				return start.plusWeeks(1);
			case MONTH:
				return start.plusMonths(1);
			default:
				return start.plusDays(1);
		}
	}

	public String label(LocalDate start)
	{
		switch (this)
		{
			case WEEK:
				return "w/c " + start.format(DAY_LABEL);
			case MONTH:
				return start.format(MONTH_LABEL);
			default:
				return start.format(DAY_LABEL);
		}
	}

	public String noun()
	{
		return name().toLowerCase(Locale.ENGLISH);
	}
}
