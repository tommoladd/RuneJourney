package com.runejourney.cloud;

import lombok.Data;

/**
 * This RuneLite install's connection to the player's RuneJourney cloud, saved in
 * cloud/credentials.json (not RuneLite's settings, which RuneLite can sync to its own servers).
 */
@Data
public class CloudCredentials
{
	/**
	 * Random, made the first time this install connects. Never the computer's name.
	 */
	private String deviceId;
	private String deviceName;
	/**
	 * The server the key belongs to.
	 */
	private String server;
	private String apiKey;
	private String userUuid;
	private String userName;
	private int dataKeyId;
	/**
	 * The key everything uploaded is encrypted with, base64.
	 */
	private String dataKey;
	/**
	 * This install has been registered with the server under its device ID.
	 */
	private boolean registered;
	/**
	 * The server said the key no longer works (revoked, or the account deleted).
	 */
	private boolean keyRejected;

	public boolean isConnected()
	{
		return apiKey != null && dataKey != null && userUuid != null;
	}
}
