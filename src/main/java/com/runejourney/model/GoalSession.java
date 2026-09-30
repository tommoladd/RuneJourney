package com.runejourney.model;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;

/**
 * A player-started training session associated with a goal.
 */
@Data
public class GoalSession
{
	private String goalId;
	private long startedAt;
	private long activeMillis;
	private Map<String, Long> startXp = new HashMap<>();
	private double startProgress;
}
