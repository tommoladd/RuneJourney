package com.runejourney.service;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.Value;

/**
 * Pre-formatted content for the in-game overlay. Built on the game tick, drawn every frame.
 */
@Data
public class OverlayData
{
	@Value
	public static class Row
	{
		String label;
		String value;
		double progress;
	}

	private String goalName;
	/**
	 * 0-1, or negative for goals without a progress bar.
	 */
	private double goalPercent = -1;
	private String goalDetail;
	private List<Row> week = new ArrayList<>();
	private String today;
	private String session;
	private String streak;
	private String netWorth;

	public boolean isEmpty()
	{
		return goalName == null && week.isEmpty() && today == null && session == null && streak == null && netWorth == null;
	}
}
