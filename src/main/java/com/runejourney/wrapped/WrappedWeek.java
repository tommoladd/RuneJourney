package com.runejourney.wrapped;

import java.time.LocalDate;
import java.util.*;
import lombok.Data;

@Data
public class WrappedWeek
{
	public enum ValueFormat
	{
		NONE, COUNT, GP, DURATION
	}

	@Data
	public static class Slide
	{
		private String eyebrow;
		private double value = Double.NaN;
		private ValueFormat format = ValueFormat.NONE;
		private String headline;
		private String unit;
		private String subtitle;
		private List<String> lines = new ArrayList<>();
		private List<String> lineIcons = new ArrayList<>();
		private String icon;
		private String screenshot;
		private List<String[]> summary = new ArrayList<>();
	}

	private LocalDate weekStart;
	private LocalDate weekEnd;
	private String player;
	private int theme;
	private List<Slide> slides = new ArrayList<>();
}
