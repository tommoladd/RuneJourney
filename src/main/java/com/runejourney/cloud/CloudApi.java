package com.runejourney.cloud;

import com.google.inject.ImplementedBy;
import com.runejourney.service.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@ImplementedBy(OkHttpCloudApi.class)
public interface CloudApi
{
	CompletableFuture<Api.Me> me(Api.Session session);

	CompletableFuture<Api.Registered> registerDevice(Api.Session session);

	CompletableFuture<Void> disconnect(Api.Session session);

	CompletableFuture<Api.Resolved> resolve(Api.Session session, String fingerprint, boolean create);

	CompletableFuture<Api.Changes> changes(Api.Session session, String profileId, long since);

	CompletableFuture<Api.Uploads> uploads(Api.Session session, String profileId, List<Api.FileSpec> files);

	CompletableFuture<Void> uploadFile(Api.Session session, String profileId, String uploadId, byte[] body);

	CompletableFuture<Api.Committed> commit(Api.Session session, String profileId, String changeId, int seq, List<String> uploadIds);

	CompletableFuture<byte[]> downloadFile(Api.Session session, String profileId, long fileId, int maxBytes);

	CompletableFuture<Api.PublicReply> updatePublic(Api.Session session, String profileId, Boolean enabled, Boolean searchable);

	CompletableFuture<Api.Published> publish(Api.Session session, String profileId, PublicSnapshot snapshot);

	CompletableFuture<Void> publishCharacter(Api.Session session, String profileId, String look, byte[] model);

	CompletableFuture<Void> publishCollectionLog(Api.Session session, String profileId, String hash, PublicCollectionLog log);

	CompletableFuture<Void> publishAchievements(Api.Session session, String profileId, String hash, PublicAchievements achievements);

	void cancelAll();
}
