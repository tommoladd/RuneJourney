package com.runejourney.cloud;

import lombok.*;

@Value
@Builder(toBuilder = true)
public class CloudStatus
{
	public enum Connection
	{
		OFF,
		NOT_CONNECTED,
		CONNECTING,
		CONNECTED,
		KEY_REJECTED
	}

	public enum Character
	{
		WAITING,
		SENDING,
		SHOWN
	}

	public enum Prompt
	{
		NONE,
		CONSENT,
		CHOOSE
	}

	Connection connection;
	String userName;
	String deviceName;
	String problem;
	boolean updateNeeded;

	String account;
	Prompt prompt;
	boolean savedElsewhere;
	Boolean consent;
	boolean linked;
	boolean readOnly;
	boolean syncing;
	long lastSync;
	int waiting;

	boolean publicEnabled;
	boolean publicSearchable;
	boolean publicBlocked;
	String publicUrl;
	String publicProblem;
	Character publicCharacter;
}
