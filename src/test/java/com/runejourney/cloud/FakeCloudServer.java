package com.runejourney.cloud;

import com.google.gson.Gson;
import com.runejourney.service.PublicAchievements;
import com.runejourney.service.PublicCollectionLog;
import com.runejourney.service.PublicSnapshot;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * An in-memory RuneJourney cloud that behaves like the real API: signed URLs, two-step uploads,
 * commits checked by sequence number and replayed by change ID, a screenshot quota. It can also
 * misbehave: lose a commit's reply, or revoke the key.
 */
class FakeCloudServer implements CloudApi
{
	static final String KEY = "rjk_test_key";
	static final String USER = "6c1d2a6e-0000-4000-8000-000000000001";
	/**
	 * The name the key was given on the website, which names the PC.
	 */
	static final String KEY_NAME = "Desktop";

	final String dataKey;
	long quota = 100L * 1024 * 1024;
	long used;
	boolean revoked;
	/**
	 * The next commits are applied, but their replies are lost.
	 */
	int loseReplies;
	/**
	 * Changes returned per page, so paging gets exercised.
	 */
	int pageSize = 3;
	/**
	 * The next change listings fail, as if the network dropped.
	 */
	int failChanges;
	/**
	 * Another player already has a public page for the name.
	 */
	boolean nameTaken;
	int publishAttempts;
	int characterUploads;
	int logUploads;
	int achievementUploads;
	/**
	 * The next character models are turned down as invalid.
	 */
	boolean refuseCharacter;
	private final Gson gson = new Gson();

	final Set<String> devices = new HashSet<>();
	final Map<String, Profile> profiles = new HashMap<>();
	private final Map<String, Upload> uploads = new HashMap<>();
	private final Map<String, Api.Committed> commits = new HashMap<>();
	private int nextId;
	private long nextObjectId;
	/**
	 * Screenshots can be uploaded; off, as on the website.
	 */
	boolean screenshots;

	static class Profile
	{
		final Api.PublicSettings pub = new Api.PublicSettings();
		PublicSnapshot page;
		byte[] character;
		PublicCollectionLog log;
		PublicAchievements achievements;

		Profile()
		{
			pub.getSections().addAll(java.util.Arrays.asList("character", "skills", "kills", "collection", "timeline", "goals", "records"));
		}

		String id;
		long cursor;
		final Map<String, Stored> objects = new HashMap<>();
		final Map<String, Integer> seq = new HashMap<>();
	}

	static class Stored
	{
		String kind;
		String docKey;
		String device;
		long cursor;
		byte[] bytes;
		String sha256;
		String schema;
		boolean deleted;
		/**
		 * What the file is fetched by.
		 */
		long id;
	}

	private static class Upload
	{
		String id;
		Profile profile;
		String device;
		String kind;
		String docKey;
		long size;
		String sha256;
		String schema;
		byte[] bytes;
	}

	FakeCloudServer()
	{
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		dataKey = Base64.getEncoder().encodeToString(key);
	}

	private static <T> CompletableFuture<T> fail(int status, String code)
	{
		Api.Error e = new Api.Error();
		e.setError(code);
		e.setMessage(code);
		return fail(new CloudException(status, code, code, e));
	}

	private static <T> CompletableFuture<T> fail(Exception e)
	{
		CompletableFuture<T> f = new CompletableFuture<>();
		f.completeExceptionally(e);
		return f;
	}

	private boolean authorized(Api.Session s)
	{
		return KEY.equals(s.getApiKey()) && !revoked;
	}

	@Override
	public CompletableFuture<Api.Me> me(Api.Session s)
	{
		if (!authorized(s))
		{
			return fail(401, null);
		}
		Api.Me me = new Api.Me();
		Api.User user = new Api.User();
		user.setUuid(USER);
		user.setName("Tester");
		me.setUser(user);
		Api.DataKey key = new Api.DataKey();
		key.setId(1);
		key.setKey(dataKey);
		me.setDataKey(key);
		me.setMedia(usage());
		return CompletableFuture.completedFuture(me);
	}

	private Api.Media usage()
	{
		Api.Media m = new Api.Media();
		m.setQuotaBytes(quota);
		m.setUsedBytes(used);
		return m;
	}

	@Override
	public CompletableFuture<Api.Registered> registerDevice(Api.Session s)
	{
		if (!authorized(s))
		{
			return fail(401, null);
		}
		devices.add(s.getDeviceId());
		Api.Registered r = new Api.Registered();
		r.setDevice(new Api.Device());
		r.getDevice().setDeviceId(s.getDeviceId());
		r.getDevice().setName(KEY_NAME);
		return CompletableFuture.completedFuture(r);
	}

	@Override
	public CompletableFuture<Void> disconnect(Api.Session s)
	{
		devices.remove(s.getDeviceId());
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<Api.Resolved> resolve(Api.Session s, String fingerprint, boolean create)
	{
		if (!authorized(s))
		{
			return fail(401, null);
		}
		if (!devices.contains(s.getDeviceId()))
		{
			return fail(409, "device_not_registered");
		}
		Profile p = profiles.get(fingerprint);
		boolean created = false;
		if (p == null)
		{
			if (!create)
			{
				return fail(404, "profile_not_found");
			}
			p = new Profile();
			p.id = "p" + (++nextId);
			profiles.put(fingerprint, p);
			created = true;
		}
		Api.Resolved r = new Api.Resolved();
		r.setProfile(api(p));
		r.setSeq(p.seq.getOrDefault(s.getDeviceId(), 0));
		r.setCreated(created);
		return CompletableFuture.completedFuture(r);
	}

	private Api.Profile api(Profile p)
	{
		Api.Profile a = new Api.Profile();
		a.setId(p.id);
		a.setLabel("Account 1");
		a.setCursor(p.cursor);
		a.setPublicSettings(settings(p));
		return a;
	}

	/**
	 * A copy, as a reply would be.
	 */
	private Api.PublicSettings settings(Profile p)
	{
		return gson.fromJson(gson.toJson(p.pub), Api.PublicSettings.class);
	}

	@Override
	public CompletableFuture<Api.PublicReply> updatePublic(Api.Session s, String profileId, Boolean enabled, Boolean searchable)
	{
		Profile p = byId(profileId);
		if (p == null)
		{
			return fail(404, "profile_not_found");
		}
		if (enabled != null)
		{
			p.pub.setEnabled(enabled);
			if (!enabled)
			{
				p.page = null;
				p.pub.setUrl(null);
			}
		}
		if (searchable != null)
		{
			p.pub.setSearchable(searchable);
		}
		Api.PublicReply r = new Api.PublicReply();
		r.setPublicSettings(settings(p));
		return CompletableFuture.completedFuture(r);
	}

	@Override
	public CompletableFuture<Api.Published> publish(Api.Session s, String profileId, PublicSnapshot snapshot)
	{
		Profile p = byId(profileId);
		if (p == null)
		{
			return fail(404, "profile_not_found");
		}
		publishAttempts++;
		if (!p.pub.isEnabled())
		{
			return fail(409, "not_public");
		}
		if (nameTaken)
		{
			return fail(409, "name_taken");
		}
		p.page = gson.fromJson(gson.toJson(snapshot), PublicSnapshot.class);
		p.pub.setUrl("https://cloud.test/journeys/" + snapshot.getName().toLowerCase().replace(' ', '-'));
		Api.Published r = new Api.Published();
		r.setUrl(p.pub.getUrl());
		return CompletableFuture.completedFuture(r);
	}

	@Override
	public CompletableFuture<Void> publishCharacter(Api.Session s, String profileId, String look, byte[] model)
	{
		Profile p = byId(profileId);
		if (p == null)
		{
			return fail(404, "profile_not_found");
		}
		characterUploads++;
		if (!p.pub.isEnabled())
		{
			return fail(409, "not_public");
		}
		if (p.page == null)
		{
			return fail(409, "not_published");
		}
		if (refuseCharacter)
		{
			return fail(422, "invalid_character");
		}
		p.character = model;
		p.pub.setCharacter(look);
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<Void> publishCollectionLog(Api.Session s, String profileId, String hash, PublicCollectionLog log)
	{
		Profile p = byId(profileId);
		if (p == null)
		{
			return fail(404, "profile_not_found");
		}
		logUploads++;
		if (!p.pub.isEnabled())
		{
			return fail(409, "not_public");
		}
		if (p.page == null)
		{
			return fail(409, "not_published");
		}
		p.log = gson.fromJson(gson.toJson(log), PublicCollectionLog.class);
		p.pub.setCollectionLog(hash);
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<Void> publishAchievements(Api.Session s, String profileId, String hash, PublicAchievements achievements)
	{
		Profile p = byId(profileId);
		if (p == null)
		{
			return fail(404, "profile_not_found");
		}
		achievementUploads++;
		if (!p.pub.isEnabled())
		{
			return fail(409, "not_public");
		}
		if (p.page == null)
		{
			return fail(409, "not_published");
		}
		p.achievements = gson.fromJson(gson.toJson(achievements), PublicAchievements.class);
		p.pub.setAchievements(hash);
		return CompletableFuture.completedFuture(null);
	}

	/**
	 * The account's public page settings, as if changed on the website.
	 */
	Api.PublicSettings pub()
	{
		return profiles.values().iterator().next().pub;
	}

	/**
	 * What the account's public page shows, or null if there isn't one.
	 */
	PublicSnapshot page()
	{
		return profiles.values().iterator().next().page;
	}

	private Profile byId(String id)
	{
		return profiles.values().stream().filter(p -> p.id.equals(id)).findFirst().orElse(null);
	}

	@Override
	public CompletableFuture<Api.Changes> changes(Api.Session s, String profileId, long since)
	{
		if (!authorized(s))
		{
			return fail(401, null);
		}
		Profile p = byId(profileId);
		if (p == null)
		{
			return fail(404, "profile_not_found");
		}
		if (failChanges > 0)
		{
			failChanges--;
			return fail(new IOException("Timed out"));
		}
		List<Map.Entry<String, Stored>> newer = p.objects.entrySet().stream()
			.filter(e -> e.getValue().cursor > since)
			.sorted(Comparator.comparingLong(e -> e.getValue().cursor))
			.collect(Collectors.toList());
		Api.Changes changes = new Api.Changes();
		for (Map.Entry<String, Stored> e : newer.subList(0, Math.min(pageSize, newer.size())))
		{
			Stored o = e.getValue();
			Api.Change c = new Api.Change();
			c.setKind(o.kind);
			c.setDocKey(o.docKey);
			c.setDeviceId(o.device);
			c.setCursor(o.cursor);
			c.setSize(o.bytes == null ? 0 : o.bytes.length);
			c.setSha256(o.sha256);
			c.setSchema(o.schema);
			c.setDeleted(o.deleted);
			c.setId(o.id);
			changes.getChanges().add(c);
		}
		changes.setMore(newer.size() > pageSize);
		changes.setCursor(changes.getChanges().isEmpty() ? since : changes.getChanges().get(changes.getChanges().size() - 1).getCursor());
		changes.setProfile(api(p));
		return CompletableFuture.completedFuture(changes);
	}

	@Override
	public CompletableFuture<Api.Uploads> uploads(Api.Session s, String profileId, List<Api.FileSpec> files)
	{
		if (!authorized(s))
		{
			return fail(401, null);
		}
		Profile p = byId(profileId);
		if (p == null)
		{
			return fail(404, "profile_not_found");
		}
		long media = files.stream().filter(f -> !"journey".equals(f.getKind())).mapToLong(Api.FileSpec::getSize).sum();
		if (media > 0 && !screenshots)
		{
			return fail(409, "screenshots_disabled");
		}
		if (media > 0 && used + media > quota)
		{
			Api.Error e = new Api.Error();
			e.setError("media_quota_exceeded");
			e.setMedia(usage());
			return fail(new CloudException(409, "media_quota_exceeded", "full", e));
		}
		Api.Uploads out = new Api.Uploads();
		for (Api.FileSpec f : files)
		{
			Upload u = new Upload();
			u.id = String.format("%026d", ++nextId);
			u.profile = p;
			u.device = s.getDeviceId();
			u.kind = f.getKind();
			u.docKey = f.getDocKey();
			u.size = f.getSize();
			u.sha256 = f.getSha256();
			u.schema = f.getSchema();
			uploads.put(u.id, u);
			Api.Upload a = new Api.Upload();
			a.setUploadId(u.id);
			a.setKind(u.kind);
			a.setDocKey(u.docKey);
			out.getUploads().add(a);
		}
		return CompletableFuture.completedFuture(out);
	}

	@Override
	public CompletableFuture<Void> uploadFile(Api.Session s, String profileId, String uploadId, byte[] body)
	{
		if (!authorized(s))
		{
			return fail(401, null);
		}
		Upload u = uploads.get(uploadId);
		if (u == null || !u.profile.id.equals(profileId) || !u.device.equals(s.getDeviceId()))
		{
			return fail(404, "unknown_upload");
		}
		if (body.length != u.size)
		{
			return fail(422, "upload_size_mismatch");
		}
		if (!CloudCrypto.sha256(body).equals(u.sha256))
		{
			return fail(422, "upload_checksum_mismatch");
		}
		u.bytes = body;
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<Api.Committed> commit(Api.Session s, String profileId, String changeId, int seq, List<String> uploadIds)
	{
		if (!authorized(s))
		{
			return fail(401, null);
		}
		Api.Committed previous = commits.get(s.getDeviceId() + "|" + changeId);
		if (previous != null)
		{
			return CompletableFuture.completedFuture(previous);
		}
		Profile p = byId(profileId);
		if (p == null)
		{
			return fail(404, "profile_not_found");
		}
		List<Upload> pending = new ArrayList<>();
		for (String id : uploadIds)
		{
			Upload u = uploads.get(id);
			if (u == null || u.profile != p || !u.device.equals(s.getDeviceId()))
			{
				return fail(422, "unknown_upload");
			}
			if (u.bytes == null)
			{
				return fail(422, "upload_missing");
			}
			pending.add(u);
		}
		int current = p.seq.getOrDefault(s.getDeviceId(), 0);
		if (current != seq)
		{
			return fail(409, "sequence_mismatch");
		}
		for (Upload u : pending)
		{
			String key = u.kind + "|" + u.docKey + ("journey".equals(u.kind) ? "|" + u.device : "");
			Stored o = p.objects.computeIfAbsent(key, k ->
			{
				Stored created = new Stored();
				created.id = ++nextObjectId;
				return created;
			});
			if (!"journey".equals(u.kind) && o.bytes != null && !o.deleted)
			{
				used -= o.bytes.length;
			}
			o.kind = u.kind;
			o.docKey = u.docKey;
			o.device = u.device;
			o.bytes = u.bytes;
			o.sha256 = u.sha256;
			o.schema = u.schema;
			o.deleted = false;
			o.cursor = ++p.cursor;
			if (!"journey".equals(u.kind))
			{
				used += u.bytes.length;
			}
			uploads.remove(u.id);
		}
		p.seq.put(s.getDeviceId(), seq + 1);
		Api.Committed result = new Api.Committed();
		result.setCursor(p.cursor);
		result.setSeq(seq + 1);
		commits.put(s.getDeviceId() + "|" + changeId, result);
		if (loseReplies > 0)
		{
			loseReplies--;
			return fail(new IOException("Connection reset"));
		}
		return CompletableFuture.completedFuture(result);
	}

	@Override
	public CompletableFuture<byte[]> downloadFile(Api.Session s, String profileId, long fileId, int maxBytes)
	{
		Profile p = byId(profileId);
		Stored o = p == null ? null : p.objects.values().stream().filter(x -> x.id == fileId).findFirst().orElse(null);
		if (o == null || o.deleted)
		{
			return fail(404, "file_not_found");
		}
		return CompletableFuture.completedFuture(o.bytes.clone());
	}

	@Override
	public CompletableFuture<Api.MediaFiles> media(Api.Session s, String profileId, String mediaId)
	{
		Profile p = byId(profileId);
		Stored media = p.objects.get("media|media:" + mediaId);
		Stored thumb = p.objects.get("thumb|media:" + mediaId);
		if (media == null || media.deleted)
		{
			return fail(404, "media_not_found");
		}
		Api.MediaFiles found = new Api.MediaFiles();
		found.setMedia(media.id);
		found.setThumb(thumb == null || thumb.deleted ? null : thumb.id);
		return CompletableFuture.completedFuture(found);
	}
	@Override
	public CompletableFuture<Void> deleteMedia(Api.Session s, String profileId, String mediaId)
	{
		Profile p = byId(profileId);
		for (String kind : new String[]{"media", "thumb"})
		{
			Stored o = p.objects.get(kind + "|media:" + mediaId);
			if (o != null && !o.deleted)
			{
				used -= o.bytes.length;
				o.deleted = true;
				o.cursor = ++p.cursor;
			}
		}
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public void cancelAll()
	{
	}

	/**
	 * Every stored file's bytes, for checking nothing readable is uploaded.
	 */
	List<byte[]> storedBytes()
	{
		List<byte[]> out = new ArrayList<>();
		profiles.values().forEach(p -> p.objects.values().forEach(o -> out.add(o.bytes)));
		return out;
	}

	boolean hasDoc(String docKey)
	{
		return profiles.values().stream().flatMap(p -> p.objects.values().stream())
			.anyMatch(o -> docKey.equals(o.docKey) && !o.deleted);
	}

	long count(String kind)
	{
		return profiles.values().stream().flatMap(p -> p.objects.values().stream())
			.filter(o -> o.kind.equals(kind) && !o.deleted).count();
	}
}
