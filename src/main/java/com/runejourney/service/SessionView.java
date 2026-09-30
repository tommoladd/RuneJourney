package com.runejourney.service;

import lombok.Value;

@Value
public class SessionView
{
	String goalId;
	String goalName;
	long activeMillis;
	long xpGained;
	double xpPerHour;
	double startProgress;
	double currentProgress;
	long weekTarget;
	long weekAchieved;
}
