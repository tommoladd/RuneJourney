package com.runejourney.cloud;

import lombok.Data;

@Data
public class CloudCredentials
{
	private String deviceId;
	private String deviceName;
	private String server;
	private String apiKey;
	private String userUuid;
	private String userName;
	private int dataKeyId;
	private String dataKey;
	private boolean registered;
	private boolean keyRejected;

	public boolean isConnected()
	{
		return apiKey != null && dataKey != null && userUuid != null;
	}
}
