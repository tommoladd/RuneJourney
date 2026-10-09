package com.runejourney.cloud;

import java.util.*;
import lombok.Data;

@Data
public class SyncState
{
	private String userUuid;
	private Boolean consent;
	private String profileId;
	private String deviceId;
	private boolean linked;
	private boolean choosing;
	private boolean restoring;
	private long cursor;
	private int seq;
	private long clock;
	private Map<String, String> sent = new HashMap<>();
	private PendingCommit pending;
	private long lastSync;
	private Api.PublicSettings publicSettings;
	private String publishedHash;
	private long lastPublished;
	private String publicProblem;
	private Hiscores.Entry hiscores;
	private long hiscoresTriedAt;

	@Data
	public static class PendingCommit
	{
		private String changeId;
		private int seq;
		private List<String> uploadIds = new ArrayList<>();
		private Map<String, String> docs = new HashMap<>();
		private long sentAt;
	}
}
