package com.runejourney.model;

import java.util.*;
import lombok.Data;

@Data
public class GoalSession
{
	private String goalId;
	private long startedAt;
	private long activeMillis;
	private Map<String, Long> startXp = new HashMap<>();
	private double startProgress;
}
