package com.runejourney.service;

import java.util.*;
import lombok.*;

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
