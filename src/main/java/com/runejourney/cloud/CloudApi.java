package com.runejourney.cloud;

import com.google.inject.ImplementedBy;
import com.runejourney.service.PublicAchievements;
import com.runejourney.service.PublicCollectionLog;
import com.runejourney.service.PublicSnapshot;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The RuneJourney cloud API. Every call is asynchronous; a turned-down request fails with a
 * {@link CloudException}, a network problem with an {@link java.io.IOException}.
 */
@ImplementedBy(OkHttpCloudApi.class)
public interface CloudApi
{
	/**
	 * Checks a key: whose it is, the key their data is encrypted with, and their screenshot storage.
	 */
	CompletableFuture<Api.Me> me(Api.Session session);

	/**
	 * Registers this RuneLite install, named after the key it connects with. Safe to repeat; it
	 * also reconnects it.
	 */
	CompletableFuture<Api.Registered> registerDevice(Api.Session session);

	/**
	 * "Disconnect this PC".
	 */
	CompletableFuture<Void> disconnect(Api.Session session);

	CompletableFuture<Api.Resolved> resolve(Api.Session session, String fingerprint, boolean create);

	CompletableFuture<Api.Changes> changes(Api.Session session, String profileId, long since);

	CompletableFuture<Api.Uploads> uploads(Api.Session session, String profileId, List<Api.FileSpec> files);

	/**
	 * Sends one file reserved by {@link #uploads}, to the server, which checks and stores it. The
	 * plugin never talks to the storage itself.
	 */
	CompletableFuture<Void> uploadFile(Api.Session session, String profileId, String uploadId, byte[] body);

	CompletableFuture<Api.Committed> commit(Api.Session session, String profileId, String changeId, int seq, List<String> uploadIds);

	/**
	 * Fetches one of the account's files, by its ID in {@link #changes}, from the server.
	 */
	CompletableFuture<byte[]> downloadFile(Api.Session session, String profileId, long fileId, int maxBytes);

	/**
	 * The IDs of one screenshot's files.
	 */
	CompletableFuture<Api.MediaFiles> media(Api.Session session, String profileId, String mediaId);

	/**
	 * "Remove from cloud". The copy on the PC is kept.
	 */
	CompletableFuture<Void> deleteMedia(Api.Session session, String profileId, String mediaId);

	/**
	 * Changes an account's public page settings. Null leaves a setting as it is.
	 */
	CompletableFuture<Api.PublicReply> updatePublic(Api.Session session, String profileId, Boolean enabled, Boolean searchable);

	/**
	 * Publishes what a public account's page shows.
	 */
	CompletableFuture<Api.Published> publish(Api.Session session, String profileId, PublicSnapshot snapshot);

	/**
	 * Publishes the character model a public account's page shows (see {@link CharacterModel}).
	 *
	 * @param look the look it shows, so the server can say which is on the page
	 */
	CompletableFuture<Void> publishCharacter(Api.Session session, String profileId, String look, byte[] model);

	/**
	 * Publishes a public account's whole collection log.
	 *
	 * @param hash identifies this version, so the server can say which it shows
	 */
	CompletableFuture<Void> publishCollectionLog(Api.Session session, String profileId, String hash, PublicCollectionLog log);

	/**
	 * Publishes a public account's quests and combat tasks.
	 *
	 * @param hash identifies this version, so the server can say which it shows
	 */
	CompletableFuture<Void> publishAchievements(Api.Session session, String profileId, String hash, PublicAchievements achievements);

	/**
	 * Cancels every call still running.
	 */
	void cancelAll();
}
