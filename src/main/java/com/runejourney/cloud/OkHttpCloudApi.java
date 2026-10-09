package com.runejourney.cloud;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.runejourney.service.PublicAchievements;
import com.runejourney.service.PublicCollectionLog;
import com.runejourney.service.PublicSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The cloud API over the RuneLite HTTP client. Every call is queued on the client's own threads,
 * never the caller's. Keys and signed URLs are never logged.
 */
@Singleton
class OkHttpCloudApi implements CloudApi
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final MediaType BINARY = MediaType.parse("application/octet-stream");
	private static final String DEVICE_HEADER = "X-RuneJourney-Device";
	private static final Pattern XML_CODE = Pattern.compile("<Code>([^<]{1,100})</Code>");
	private static final Pattern XML_MESSAGE = Pattern.compile("<Message>([^<]{1,300})</Message>");

	private final OkHttpClient api;
	/**
	 * For files, which can take a while on a slow connection. Every request goes to the server the
	 * plugin is connected to: nothing is ever sent to the storage, or anywhere else.
	 */
	private final OkHttpClient files;
	private final Gson gson;
	private final SyncLog syncLog;
	private final Set<Call> calls = ConcurrentHashMap.newKeySet();

	@Inject
	OkHttpCloudApi(OkHttpClient client, Gson gson, SyncLog syncLog)
	{
		this.api = client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build();
		this.files = client.newBuilder().callTimeout(2, TimeUnit.MINUTES).build();
		this.gson = gson;
		this.syncLog = syncLog;
	}

	private interface Reader<T>
	{
		T read(ResponseBody body) throws IOException;
	}

	@Override
	public CompletableFuture<Api.Me> me(Api.Session s)
	{
		return json(get(s, "me"), Api.Me.class);
	}

	@Override
	public CompletableFuture<Api.Registered> registerDevice(Api.Session s)
	{
		JsonObject body = new JsonObject();
		body.addProperty("device_id", s.getDeviceId());
		return json(post(s, "devices", body), Api.Registered.class);
	}

	@Override
	public CompletableFuture<Void> disconnect(Api.Session s)
	{
		return empty(request(s, "devices/current").delete().build());
	}

	@Override
	public CompletableFuture<Api.Resolved> resolve(Api.Session s, String fingerprint, boolean create)
	{
		JsonObject body = new JsonObject();
		body.addProperty("fingerprint", fingerprint);
		body.addProperty("create", create);
		return json(post(s, "profiles/resolve", body), Api.Resolved.class);
	}

	@Override
	public CompletableFuture<Api.Changes> changes(Api.Session s, String profileId, long since)
	{
		HttpUrl url = url(s, "profiles/" + profileId + "/changes").newBuilder()
			.addQueryParameter("since", Long.toString(since))
			.build();
		return json(headers(s, new Request.Builder().url(url)).get().build(), Api.Changes.class);
	}

	@Override
	public CompletableFuture<Api.Uploads> uploads(Api.Session s, String profileId, List<Api.FileSpec> files)
	{
		JsonObject body = new JsonObject();
		body.add("files", gson.toJsonTree(files));
		return json(post(s, "profiles/" + profileId + "/uploads", body), Api.Uploads.class);
	}

	@Override
	public CompletableFuture<Void> uploadFile(Api.Session s, String profileId, String uploadId, byte[] body)
	{
		Request r = request(s, "profiles/" + profileId + "/uploads/" + uploadId).put(RequestBody.create(BINARY, body)).build();
		return send(files, r, b -> null);
	}

	@Override
	public CompletableFuture<Api.Committed> commit(Api.Session s, String profileId, String changeId, int seq, List<String> uploadIds)
	{
		JsonObject body = new JsonObject();
		body.addProperty("change_id", changeId);
		body.addProperty("seq", seq);
		body.add("uploads", gson.toJsonTree(uploadIds));
		return json(post(s, "profiles/" + profileId + "/commit", body), Api.Committed.class);
	}

	@Override
	public CompletableFuture<byte[]> downloadFile(Api.Session s, String profileId, long fileId, int maxBytes)
	{
		return send(files, get(s, "profiles/" + profileId + "/objects/" + fileId), body ->
		{
			if (body.contentLength() > maxBytes)
			{
				throw new IOException("File is too large");
			}
			try (InputStream in = body.byteStream())
			{
				java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
				byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) > 0)
				{
					if (out.size() + n > maxBytes)
					{
						throw new IOException("File is too large");
					}
					out.write(buf, 0, n);
				}
				return out.toByteArray();
			}
		});
	}

	@Override
	public CompletableFuture<Api.PublicReply> updatePublic(Api.Session s, String profileId, Boolean enabled, Boolean searchable)
	{
		JsonObject body = new JsonObject();
		if (enabled != null)
		{
			body.addProperty("enabled", enabled);
		}
		if (searchable != null)
		{
			body.addProperty("searchable", searchable);
		}
		Request r = request(s, "profiles/" + profileId + "/public").patch(RequestBody.create(JSON, gson.toJson(body))).build();
		return json(r, Api.PublicReply.class);
	}

	@Override
	public CompletableFuture<Api.Published> publish(Api.Session s, String profileId, PublicSnapshot snapshot)
	{
		Request r = request(s, "profiles/" + profileId + "/public/snapshot").put(RequestBody.create(JSON, gson.toJson(snapshot))).build();
		return json(r, Api.Published.class);
	}

	@Override
	public CompletableFuture<Void> publishCharacter(Api.Session s, String profileId, String look, byte[] model)
	{
		HttpUrl url = url(s, "profiles/" + profileId + "/public/character").newBuilder().addQueryParameter("look", look).build();
		Request r = headers(s, new Request.Builder().url(url)).put(RequestBody.create(BINARY, model)).build();
		return empty(r);
	}

	@Override
	public CompletableFuture<Void> publishCollectionLog(Api.Session s, String profileId, String hash, PublicCollectionLog log)
	{
		JsonObject body = gson.toJsonTree(log).getAsJsonObject();
		body.addProperty("hash", hash);
		Request r = request(s, "profiles/" + profileId + "/public/collection-log").put(RequestBody.create(JSON, gson.toJson(body))).build();
		return empty(r);
	}

	@Override
	public CompletableFuture<Void> publishAchievements(Api.Session s, String profileId, String hash, PublicAchievements achievements)
	{
		JsonObject body = gson.toJsonTree(achievements).getAsJsonObject();
		body.addProperty("hash", hash);
		Request r = request(s, "profiles/" + profileId + "/public/achievements").put(RequestBody.create(JSON, gson.toJson(body))).build();
		return empty(r);
	}

	@Override
	public void cancelAll()
	{
		for (Call c : calls)
		{
			c.cancel();
		}
		calls.clear();
	}

	private HttpUrl url(Api.Session s, String path)
	{
		String server = s.getServer().endsWith("/") ? s.getServer() : s.getServer() + "/";
		HttpUrl base = HttpUrl.parse(server);
		if (base == null)
		{
			throw new IllegalArgumentException("Cloud server address isn't valid");
		}
		return base.resolve("api/v1/" + path);
	}

	private Request.Builder headers(Api.Session s, Request.Builder r)
	{
		r.header("Authorization", "Bearer " + s.getApiKey()).header("Accept", "application/json");
		if (s.getDeviceId() != null)
		{
			r.header(DEVICE_HEADER, s.getDeviceId());
		}
		return r;
	}

	private Request.Builder request(Api.Session s, String path)
	{
		return headers(s, new Request.Builder().url(url(s, path)));
	}

	private Request get(Api.Session s, String path)
	{
		return request(s, path).get().build();
	}

	private Request post(Api.Session s, String path, JsonObject body)
	{
		return request(s, path).post(RequestBody.create(JSON, gson.toJson(body))).build();
	}

	private <T> CompletableFuture<T> json(Request r, Class<T> type)
	{
		return send(api, r, body ->
		{
			try
			{
				T value = gson.fromJson(body.charStream(), type);
				if (value == null)
				{
					throw new IOException("Empty reply from the cloud");
				}
				return value;
			}
			catch (JsonParseException e)
			{
				throw new IOException("Unexpected reply from the cloud", e);
			}
		});
	}

	private CompletableFuture<Void> empty(Request r)
	{
		return send(api, r, body -> null);
	}

	private <T> CompletableFuture<T> send(OkHttpClient client, Request request, Reader<T> reader)
	{
		CompletableFuture<T> future = new CompletableFuture<>();
		String where = where(request);
		long started = System.nanoTime();
		Call call = client.newCall(request);
		calls.add(call);
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				calls.remove(call);
				syncLog.write("%s failed after %d ms: %s", where, millis(started), e.toString());
				future.completeExceptionally(e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				calls.remove(call);
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful())
					{
						String text = body == null ? "" : body.string();
						CloudException failure = failure(response.code(), body == null ? null : body.contentType(), text);
						syncLog.write("%s -> %d %s (%d ms)", where, response.code(), detail(failure, text), millis(started));
						future.completeExceptionally(failure);
						return;
					}
					T value = reader.read(body);
					syncLog.write("%s -> %d (%d ms)", where, response.code(), millis(started));
					future.complete(value);
				}
				catch (IOException | RuntimeException e)
				{
					syncLog.write("%s -> %d, but its reply couldn't be read: %s", where, response.code(), e.toString());
					future.completeExceptionally(e);
				}
			}
		});
		return future;
	}

	/**
	 * A request as the sync log shows it: its path on the server, never the key.
	 */
	private static String where(Request request)
	{
		HttpUrl url = request.url();
		long bytes = -1;
		try
		{
			bytes = request.body() == null ? -1 : request.body().contentLength();
		}
		catch (IOException e)
		{
			// Unknown
		}
		String size = bytes > 0 ? " [" + bytes + " bytes]" : "";
		String path = url.encodedPath();
		int api = path.indexOf("/api/v1/");
		return request.method() + " " + (api >= 0 ? path.substring(api + "/api/v1/".length()) : path) + size;
	}

	private static long millis(long started)
	{
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
	}

	/**
	 * Why a request was turned down: the API's error, or (behind a proxy, say) whatever came back.
	 */
	private static String detail(CloudException failure, String text)
	{
		if (failure.getCode() != null)
		{
			return failure.getCode() + ": " + failure.getMessage();
		}
		Matcher code = XML_CODE.matcher(text);
		Matcher message = XML_MESSAGE.matcher(text);
		if (code.find())
		{
			return code.group(1) + (message.find() ? ": " + message.group(1) : "");
		}
		String flat = text.replaceAll("\\s+", " ").trim();
		return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
	}

	private CloudException failure(int status, MediaType type, String text)
	{
		Api.Error error = null;
		try
		{
			if (type != null && "json".equals(type.subtype()))
			{
				error = gson.fromJson(text, Api.Error.class);
			}
		}
		catch (JsonParseException | IllegalStateException e)
		{
			// Not an API error, e.g. the storage's own XML
		}
		return new CloudException(status, error != null ? error.getError() : null, error != null ? error.getMessage() : null);
	}
}
