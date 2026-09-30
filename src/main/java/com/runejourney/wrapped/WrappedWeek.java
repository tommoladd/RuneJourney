package com.runejourney.wrapped;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A week's "Wrapped": an ordered set of slides celebrating what the player did.
 */
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
		/**
		 * Small line above the headline, e.g. "This week you killed".
		 */
		private String eyebrow;
		/**
		 * Number to count up to, or NaN when the headline is text.
		 */
		private double value = Double.NaN;
		private ValueFormat format = ValueFormat.NONE;
		/**
		 * Text shown instead of a number, e.g. a goal or boss name.
		 */
		private String headline;
		/**
		 * Word under the number, e.g. "bosses".
		 */
		private String unit;
		private String subtitle;
		private List<String> lines = new ArrayList<>();
		/**
		 * Icon keys (see {@link WrappedIcons}) for each line, or null entries for none.
		 */
		private List<String> lineIcons = new ArrayList<>();
		/**
		 * Big illustration shown above the slide's text.
		 */
		private String icon;
		/**
		 * Screenshot file for the "best moment" slide.
		 */
		private String screenshot;
		/**
		 * The final summary slide shows a grid of stats instead of one number.
		 */
		private List<String[]> summary = new ArrayList<>();
	}

	private LocalDate weekStart;
	private LocalDate weekEnd;
	private String player;
	/**
	 * Picks the colour palette, so each week looks different.
	 */
	private int theme;
	private List<Slide> slides = new ArrayList<>();
}
