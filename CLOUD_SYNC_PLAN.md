# Cloud sync and storage (Discord sign-in, API key, S3)

## Context
RuneJourney keeps everything in `.runelite/plugin-data/runejourney/<profileKey>/`: `profile.json`, `days/*.json` and `screenshots/*.png`. A player who uses RuneLite on two computers ends up with two histories that drift apart, and a dead PC loses everything. "Cloud storage" is first on the roadmap.

This replaces the earlier no-account design (pairing codes and recovery codes). The sync and merge design from that version is kept as it was (sections 4 and 5). Identity, storage and media are new.

Your requirements:
- **Opt-in, signed in with Discord.** To turn on cloud storage you sign in on the website with Discord, create an API key, and paste it into the plugin. No password, no email.
- **One Discord user, any number of RS accounts.** Every RuneScape account you sync is stored under your user and reached with your key.
- **Private S3 storage per user** at `storage/<user uuid>/<profile id>/`. The plugin never holds bucket credentials. Every read and write uses a short-lived signed URL that the website issues after checking the API key.
- **No player can reach another player's data.**
- **Sync must never break or double-count.** This includes offline devices, crashes, lost network replies, and two devices on different plugin versions.
- **100 MB of free media storage per user.** When it's full, screenshots stop uploading until the user frees space. Nothing is ever deleted automatically. Paid storage is out of the MVP.
- **The cloud copy shouldn't identify the RuneScape account.** We know the Discord user by design. The plugin still never sends the RSN or the account hash, the bucket holds only random IDs and encrypted files, and our database can't be matched against known account hashes. Exact XP totals could be matched against the public hiscores, which is another reason everything is encrypted.

What the code exploration found, and why the design is shaped like this:
- **Daily records are running totals.** Two devices both adding to the same day can't just overwrite each other.
- **Logging in counts "gained while away" XP** (`setBaseline`): anything above `profile.lastXp` is added to today. A device with stale data would double-count XP another device already recorded.
- **The profile stays loaded at the login screen**, so you can edit goals and notes on one device while the other plays.
- **No delete leaves a trace.** Deleting a goal or event just removes it. Events have no IDs, and goal items are referenced by list position.
- **Some changes are meant to happen once** (goal completed, week rolled over, "affordable", records), but each device would run them independently.
- **The save code clears the "needs saving" flags before writing, and only logs failed writes** (`collectWrites`). It can't double as an upload queue.
- **Screenshots are large PNGs.** The three on this PC are 1.5–2.9 MB each (1207×900 to 2256×1377). As JPEG at quality 0.85 the same images are 210–505 KB. 100 MB holds about 40 of the PNGs or roughly 250 JPEGs, so cloud copies are JPEG. The local PNG is untouched.
- **Journey data is tiny.** Every day file for the main account so far adds up to 16 KB. The media limit is the only one users will notice.
- **Two RuneLite windows can hold the same account.** A logged-out window keeps the profile loaded, and `loadProfile` returns early when the same account logs back in (`RuneJourneyPlugin.java:310`). A stale window then overwrites what the other one saved. Sync would spread that to every PC (section 6).
- **The website has accounts switched off.** Fortify is installed but its routes are ignored (`FortifyServiceProvider::register`). There's no Sanctum, Socialite or `routes/api.php` yet, and `users.email` and `users.password` are required columns.

## Approach

### 1. Identity: Discord sign-in and API keys
- **Website:** "Sign in with Discord" through Laravel Socialite and the SocialiteProviders Discord driver, scope `identify` only (no email). The first sign-in creates a user with:
  - a random `uuid`, used in storage paths. Never the Discord ID, which is public.
  - a random 256-bit **data key**, stored with Laravel's encrypted cast. It encrypts everything the plugin uploads (section 3).
  - `media_quota_bytes` set to 100 MB.
- **API keys:** the Cloud page creates named keys ("Desktop", "Laptop") as Sanctum personal access tokens with only the `sync` ability.
  - A key is shown once, prefixed `rjk_` (Sanctum's `token_prefix`) so secret scanners recognise it, and stored only as a hash.
  - The page lists keys with when each was last used, and revokes them.
  - One key per PC is recommended, so a lost laptop can be revoked on its own. Using one key on several PCs also works.
- **Plugin:**
  - **Cloud sync** in settings: off by default, with the warning text the Plugin Hub requires.
  - The panel's Cloud section has **Get a key** (opens the website with `LinkBrowser`), a masked field to paste the key, and **Connect**. Connect calls `GET me` to check the key and shows "Connected as <Discord name>".
  - The key, the data key and this install's device ID are saved in `plugin-data/runejourney/cloud/credentials.json` through `Filepath`. Not in RuneLite's config, which RuneLite can sync to its own servers.
  - Each install creates a random **device ID** when it first connects, and names itself "PC 1" (editable). Never the computer name or Windows username.
- **What a key can and can't do:** a key reads and writes its user's journey data and media, and can remove single screenshots from the cloud. Deleting an RS account's cloud data, deleting everything, revoking keys and deleting the user are website-only, behind Discord sign-in. Someone sitting at a shared PC can't wipe your cloud from the plugin.

### 2. RS accounts become cloud profiles
- Each `profileKey` (main, alts, and the Seasonal, Deadman and Fresh Start variants) becomes a **cloud profile** with a random ID from the server. That ID is the `runescape_profile_id` in the storage path.
- **Finding the same account on another PC:**
  - The plugin sends `fingerprint = sha256(userUuid + ":" + profileKey)`.
  - The server stores only `HMAC-SHA256(pepper, fingerprint)`. The pepper is in the server's env, not the database.
  - The same account on any PC reaches the same profile, and a different account can never reach it. A leaked database alone can't be matched against known account hashes.
- **Consent per account:** the first time an account logs in on a connected PC, the panel asks "Save <RSN> to <Discord name>'s cloud?" with **Save** and **Not on this PC**. The answer is kept in `<profileKey>/sync/state.json`. A sibling's account on the same RuneLite is never uploaded silently.
- **Label:** the server stores a nickname ("Account 1", editable), never the RSN. The plugin shows the RSN next to it, locally.
- **A second PC needs one key, not a code per account.** Paste the key once, and each account finds its existing profile the first time it logs in there.

### 3. Storage layout, signed URLs and encryption
```
storage/{user uuid}/{profile id}/
  journey/{device id}/profile.{version}.bin
  journey/{device id}/days/2026-10-02.{version}.bin
  media/{media id}.bin          screenshot (JPEG inside)
  media/{media id}.thumb.bin    320 px thumbnail
```
- **Storage is a DigitalOcean Spaces bucket**, reached through Laravel's `s3` driver with the regional endpoint (`https://<region>.digitaloceanspaces.com`).
  - **The bucket is private:** file listing restricted, every object private, and no CDN. Signed URLs use the regional endpoint, never a CDN one.
  - **Laravel holds the only app key:** a per-bucket access key with Read/Write/Delete on this bucket alone. Per-bucket keys don't work with bucket policies, so the bucket has no policy; the private settings above do that job.
  - Spaces supports SigV4 presigned URLs and the `x-amz-checksum-sha256` header on PUT.
- **The server builds every object key** from the signed-in user's uuid, a profile that belongs to that user, and a checked document key (`^(profile|day:\d{4}-\d{2}-\d{2}|media:[0-9a-f]{32})$`). The plugin never sends a path, so a signed URL can't point outside the user's folder.
- **Uploads take two steps:**
  1. `POST uploads` lists each file's kind, document key, size and SHA-256. The server checks the limits and the quota, reserves the bytes, and returns presigned PUT URLs (`temporaryUploadUrl`, 10 minutes) that sign the Content-Length and the SHA-256 checksum.
  2. The plugin PUTs each file straight to the bucket, then calls `POST commit` with a `change_id`. The server HEADs each object to confirm it exists at the expected size. This check is what counts, in case a provider doesn't enforce signed headers. Then, in one transaction, it bumps the profile's change counter, points the index at the new objects and releases the reservation.
- **Objects are never overwritten.** Each upload gets a new `{version}` key, and the replaced version is deleted after the commit. A reader never sees a half-written file, and a lost commit reply is safe to resend with the same `change_id`.
- **Downloads:** `GET changes?since=` returns the changed documents with presigned GET URLs (10 minutes). Pages hold 200 entries, so URLs don't expire before they're used.
- **Encrypted before upload:**
  - JSON is gzipped, then every file is encrypted with AES-256-GCM using the user's data key.
  - The object key is the associated data, so a file moved to another path fails to decrypt. GCM also catches any change to the bytes.
  - The envelope carries a format version and a key ID, so keys can be rotated.
  - A bucket made public by mistake, or a leaked signed URL, exposes only ciphertext. A full server compromise still exposes everything, as in any design where the server can restore your data.
- **Never logged:** API keys, signed URLs (each works for anyone holding it for 10 minutes), and request bodies.

### 4. Each device publishes only its own data; everyone combines on read
- **One owner per document.** Each device publishes **its own documents**: `profile@deviceId` and `day:YYYY-MM-DD@deviceId`, under each cloud profile.
  - Only that device ever writes them, so they never conflict on the server. When several RuneLite windows on one PC share a device ID, the account lock (section 6) keeps it to one writer.
  - A repeated or reordered upload is harmless, because each upload is the full current state of that device's own contribution.
- **Combining days:**
  - Running totals are **added up** across devices.
  - Events are combined by **event ID**.
  - Note edits use "newest wins", ordered by a **logical clock**, which is safe if computers' clocks disagree.
  - Deletions are **tombstones**: small "deleted" markers, kept for good, and a delete beats an edit.
- **Combining the profile:** every field gets a fixed rule, and the result is the same whatever order the devices' copies arrive in.
  - **Highest value:** lastXp, KCs, clue counts, best days, bestWealth.
  - **Once true, stays true:** combatTasksFromGame, bankValueKnown.
  - **Newest by logical clock:** holdings, GE slots, CA points, observed rates, kill times, active session, preferences.
  - **Goals by ID:**
    - Weekly-plan fields are one group, keyed by the week they belong to.
    - completedAt and obtainedAt take the earliest value that's set.
    - Goal items are keyed by item ID, not list position.
- **"Happens once" events get fixed IDs** (`goalDone|<id>`, `week|<id>|<weekStart>`, `level|ATTACK|80`, `wealth|1000000000`…; the full list is under Phase 0), so duplicates from two devices collapse into one.
- **What's stored locally:**
  - `days` in memory stays the combined view, so **existing features and UI don't change**.
  - Other devices' copies are stored inside the same day/profile file under a `sync` field, so they're saved together in one atomic write.
  - This device's own copy is worked out only when uploading: **view minus the other devices' copies**.
  - Upload only when this device's own copy has changed since the last acknowledged upload.

### 5. XP "while away" without double counting
- **Wait for sync before counting XP at login.** On **every** login (not just profile load; logging back in at the login screen doesn't reload the profile):
  - Take a snapshot of current XP on the first tick, and hold off setting the baseline until the pull finishes or times out (~8 s).
  - Then set the baseline from that snapshot, and replay any XP gained since through `onXp`.
  - GE offers already wait for the baseline (`pendingOffers`).
- **Online XP is recorded as ranges** per skill per day (e.g. Agility 12,000,000→12,250,000). "While away" XP is recorded as a separate, provisional range.
- **When combining, overlaps are trimmed.** Any part of an away range already covered by any device's online range is dropped, even if the other device's upload was late. Away levels and milestones collapse via the fixed event IDs.

### 6. Multiple RS accounts and multiple RuneLite windows
- **Switching accounts:** each login looks up that account's profile and holds the XP baseline until its pull finishes (section 5). Requests are tagged with the profile key and a generation number, so a slow pull for the main can't land in the alt. This follows the existing `installIfCurrent` pattern.
- **One window per account at a time:**
  - A lock file, `<profileKey>/sync/lock`, is taken with `Filepath.openFileChannel().tryLock()`.
  - Only the window holding the lock saves files or syncs that account. Another window showing the same account is read-only: "Open in another RuneLite window".
  - When an account logs back in and its files changed on disk, the plugin reloads them instead of keeping the copy in memory. This also fixes the local overwrite bug noted above.
- **Pending uploads for every account:** each profile has its own outbox, and the sync manager sends all of them in the background, not just the logged-in account's.
- **Shared quota:** media for all your RS accounts counts towards your one 100 MB.
- **Several Windows users on one PC** each have their own `.runelite` folder, so each pastes a key. A different person uses their own Discord.

### 7. Media storage and the 100 MB limit
- **What uploads:** screenshots RuneJourney takes, while **Back up screenshots** is on (on by default once Cloud sync is on). Each upload is a JPEG at quality 0.85 plus a 320 px thumbnail of about 15 KB, both encrypted. Local PNGs stay as they are.
- **Existing screenshots at first connect:** a one-time prompt, "Back up your existing screenshots (N, about X MB)?". They upload newest first.
- **Accounting:** per Discord user, across every RS account. Committed bytes plus open reservations are checked whenever uploads are requested. Thumbnails count. Each image is capped at 5 MB.
- **When the 100 MB is full:**
  - The server answers `409 media_quota_exceeded` with the usage.
  - The plugin pauses media uploads; journey sync carries on. The panel shows "Cloud screenshots full: 100 MB used. New screenshots are still saved on this PC." with **Manage**.
  - The gallery marks each screenshot: on this PC, in the cloud, or waiting (cloud full).
  - **Remove from cloud** keeps the local file. **Delete** removes it everywhere. Uploads resume on the next sync once there's room.
  - Nothing is ever deleted automatically to make room.
- **Other PCs:** the gallery shows the cloud thumbnails, downloads the full image when it's opened, and caches it locally as `.jpg`. Removing from the cloud is a tombstone in the change feed, so other PCs drop it too. Events that pointed to it keep their text.
- **Ready for paid storage later:** the quota is a column on the user, so a paid tier only changes that number. It's not in the MVP; check the Plugin Hub and Jagex rules on paid features before offering one.
- **Hub review note:** screenshots can show other players' names in chat. They stay private to their owner (no share links in the MVP). Say so in the Hub submission.

### 8. Server
- **Website routes (session, Discord):**
  - `login/discord`, `login/discord/callback`, `logout`
  - a `cloud` page following the existing `pages::` conventions: keys, devices, RS profiles with size and last sync, media usage bar, delete one profile's data, delete everything, delete account
  - Fortify stays off.
- **API routes** (`/api/v1`, all JSON, `auth:sanctum` plus `abilities:sync`):
  - `GET me`: user uuid, Discord name, data key, quota and usage, limits
  - `POST devices`: register this install (safe to repeat for the same device ID)
  - `POST devices/fork`: for a copied `.runelite` folder (section 9)
  - `DELETE devices/current`: "Disconnect this PC"
  - `POST profiles/resolve {fingerprint, create, label}`
  - `GET profiles/{profile}/changes?since=&limit=`
  - `POST profiles/{profile}/uploads`
  - `POST profiles/{profile}/commit`
  - `DELETE profiles/{profile}/media/{media}`
- **Every profile is looked up through the signed-in user** (`$request->user()->cloudProfiles()`), so another user's profile ID is a 404.
- **Limits:** 100 MB of media per user; 512 KB per journey document; 25 MB of journey data per profile; 20 profiles, 10 keys and 10 devices per user. Request size is capped in both nginx and PHP.
- **Rate limits:**
  - Discord sign-in: 10/min/IP.
  - New users: 3/day/IP, and the Discord account must be at least 7 days old (read from the Discord ID, no extra scope).
  - Sync: 60/min per key. Upload requests: 30/min per key.
- **Scheduled cleanup:**
  - Every 15 minutes: delete objects from uploads that were never committed, and release their reservations.
  - Replaced versions are queued for deletion at commit, with a daily sweep for any that were missed.
  - Users with no sync and no sign-in for 12 months have all their cloud data deleted. The privacy page will state this. It's measured per user, so an idle alt is kept while any of your accounts syncs.
- **Bucket versioning** keeps deleted or replaced objects for 7 days as a safety net against a sync bug. The privacy page says deleted data is fully gone within 7 days.

### 9. Robustness details from the design review
- **Corrupt profile file:**
  - JourneyStore moves it aside to `*.corrupt-<ts>`, and the plugin restores it from this device's cloud copy.
  - A blank profile has logical clock 0, so it can never win a combine.
- **Account switched mid-sync:** covered in section 6.
- **Version skew:**
  - Each document carries a schema version as `major.minor`.
  - A newer minor version is combined normally, ignoring fields this version doesn't know.
  - A newer major version stops sync and shows "Update RuneJourney to keep syncing".
  - Per-device documents mean an older plugin can never erase a newer one's fields.
- **Clones** (a copied `.runelite` folder sharing one device ID): the server sees that the device's sequence number for that profile doesn't match, and the plugin calls `devices/fork` to become a new device.
- **Key revoked or user deleted:** the API answers 401. The plugin shows "This key no longer works", keeps everything local, and stops syncing until a new key is pasted.
- **Bucket errors:** an expired signed URL (403 from the bucket) gets one fresh URL, then the outbox backs off. 5xx errors back off the same way.
- **Threads:**
  - All network calls, including bucket PUTs and GETs, use the injected `OkHttpClient` with `enqueue`.
  - Encryption, JPEG encoding, parsing and combining happen on the plugin executor. Results are applied to the service under its lock in batches, never holding the lock during IO.
  - Calls are tagged so `shutDown` can cancel them, and shutdown never blocks.
- **When this PC and the cloud both already have history for an account:**
  - **"Use the cloud journey"** is the default; this PC's local journey is backed up to `backup-<ts>/` first.
  - **"Combine both"** is the alternative. It works through event IDs and range trimming, and warns that "while away" XP from before linking may be counted twice.
- **Upload timing:** pull and push every 2 minutes while playing, every 5 minutes or when the panel opens at the login screen, on logout, and best-effort at client exit. A persistent outbox retries anything that didn't make it.

## Phases and files

**Phase 0: plugin model groundwork (no network; ships safely on its own). Done.**
- `model/JourneyEvent`: `id`.
  - Old events get a deterministic ID (a name-based UUID) from date, time, type, title, detail and their position among identical events, so the same history gets the same IDs on every device.
  - Assigned once at load, after `cleanEventText`, and saved. New events get a random UUID.
- `sync/EventIds`: random, fixed and old-event IDs.
- "Happens once" events have fixed IDs, and an event whose ID is already recorded isn't recorded again:
  - `level|SKILL|n` (live, and found at login), `total|n`, `xp|SKILL|n`, `totalxp|n`, `wealth|n`, `streak|n`
  - `goalDone|goal`, `week|goal|weekStart` (the week that ended), `afford|goal`, `record|xp/loot/kills|date`
  - Everything else is only ever seen by one device and keeps a random ID.
- `model/DayRecord`: `xpRanges` (online), `offlineRanges` (found at login, gained earlier the same day, already in that day's XP) and `awayRanges` (found at login, from a gap that began on an earlier day). Each is skill → `[from, to]` XP totals, with a range extended when the next XP carries on from where it ended. Mirrors `offlineSkillXp` and `awaySkillXp`, so combining knows which away XP was counted in the day's totals.
- `deleteEvent` and `setEventNote` work by event ID; `setItemObtained` by item ID (name for items without one). Callers in `ui/Views` and `ui/GoalsTab` updated; `addMemories` gives memories their IDs.
- `copyDay` and `copy(event)` copy the new fields.
- `JourneyStore`:
  - A file that isn't valid JSON (or is empty) is moved aside to `*.corrupt-<ts>` and left out. A file that can't be read fails the whole load instead, so a blank profile is never saved over one that's still there.
  - Remembers each data file's size and modified time as last loaded or saved, and `changedOnDisk` compares.
- `RuneJourneyPlugin`: when an account logs back in (not on area loads, which also report `LOGGED_IN`), tracking waits while the files are checked. If another window changed them, they're reloaded. Unsaved edits made at the login screen in the stale window are dropped in that case.
- **Moved to the phases that use them:** `holdBaseline` / `releaseBaseline`, `applyRemote` and `exportOwn` need the combining library and the sync manager; the `sync` fields and `SyncMeta` take their shape from Phase 1; the IO for `cloud/credentials.json`, `sync/state.json`, `sync/outbox.json`, `sync/media.json`, `sync/lock` and backups lands with Phase 3.

**Phase 1: combining library, `com.runejourney.sync` (pure and unit-tested)**
- `Hlc` (logical clock), `XpRanges`, `DaySlices` (add, subtract, combine; own = view − remotes), `ProfileJoin` (field rule table), `GoalJoin`, `Envelope` (schema major.minor).
- `model/SyncMeta` and the `sync` fields on `DayRecord` (other devices' copies, edit clocks, tombstones) and `ProfileData` (logical clock, field clocks, tombstones, last-uploaded copy).
- `JourneyService`: `applyRemote(key, gen, docs)` and `exportOwn()`.
- **Built (2026-10-08).** Tests: `sync/*Test` (ranges, clock skew, every field has a rule, own part round trips, trimming, events, goals) and `service/CloudSyncTest` (several PCs in random turns converge, each XP drop counted once). Where it differs from the list above:
  - **Each day stores this PC's own part, not just the other PCs'** (`model/DaySync`: `own` plus `remotes`, each a `DaySlice`). Trimming XP counted twice changes the trimmed part itself, so "view − remotes" alone couldn't give back the own part. Working out the own part is still view − remotes for counts, and anything changed in the view since the parts were last combined for everything else.
  - **Trimming is applied by every PC to every part**, the same way, so each PC can trim a late part before its owner does; the owner uploads the same trimmed part once it sees the overlap.
  - **Shared events have one owner:** when two PCs recorded an event with the same fixed ID, the earliest copy (then the lowest device ID) is shown, and only that PC keeps it in its part.
  - **The profile is combined field by field as JSON** (`ProfileJoin.RULES`), with clocks per field or per map key (`"holdings.bank"`). Goals split into parts (`GoalJoin`): settings, this week's plan, each item's obtained state, completion, "can afford", and purchases (added up).
  - **No `SyncMeta` or saved "needs upload" flag:** at login, any day that isn't exactly its parts combined is worked out again (`checkUnsynced`), which covers RuneLite closing before an upload.
  - **The RSN (`playerName`) and the old wealth fields never leave the PC** (rule `LOCAL`).
  - `AwayXp.migrate` now skips days with offline XP ranges: they were saved after away XP was kept aside.

**Phase 2: Laravel API** (`C:\Users\hello\Herd\runejourney-web`)
- Packages: `laravel/socialite`, `socialiteproviders/discord`, `league/flysystem-aws-s3-v3`. Run `php artisan install:api` for Sanctum and `routes/api.php`.
- Migrations:
  - `users`: add `uuid` unique, `discord_id` unique, `discord_username`, `data_key` (encrypted), `media_quota_bytes`, `media_bytes_used`, `last_active_at`; make `email` and `password` nullable
  - `cloud_devices`: ulid, user FK cascade, token FK null on delete, device_id, name, last_seen_at; unique (user, device_id)
  - `cloud_profiles`: ulid, user FK cascade, fingerprint_hmac, label, cursor, journey_bytes, last_synced_at; unique (user, fingerprint_hmac)
  - `cloud_device_profiles`: device, profile, seq (for the clone check)
  - `cloud_objects`: profile FK cascade, device FK, kind, doc_key, s3_key, size, sha256, schema, cursor, deleted_at; index (profile, cursor)
  - `cloud_uploads`: ulid, user, profile, kind, doc_key, s3_key, size, sha256, expires_at, committed_at
  - `cloud_commits`: device, change_id unique, result; pruned after 7 days
- Models, `Prunable` where they expire. `CloudPaths` builds every object key. `MediaQuota` reserves, commits and releases bytes under a row lock.
- Under `Api/V1`: `MeController`, `DeviceController`, `ProfileController`, `ChangesController`, `UploadController`, `CommitController`, `MediaController`, plus FormRequests.
- `Auth/DiscordController` and the `pages::cloud` page.
- `config/filesystems.php`: a `cloud` disk for Spaces (`s3` driver, regional `endpoint`, `use_path_style_endpoint` false). Set the AWS SDK's `request_checksum_calculation` and `response_checksum_validation` to `when_required`: newer SDKs add checksum headers by default that S3-compatible providers can reject. Check this against a real Spaces bucket on staging. `config/runejourney.php`: limits, quota, pepper, URL lifetimes.
- `bootstrap/app.php`: add `api:` routing and `trustProxies`. `AppServiceProvider`: rate limiters. `routes/console.php`: schedules.
- **Built (2026-10-08), with 78 Pest tests in `tests/Feature/Cloud`.** Where it differs from the list above:
  - **The plugin's API contract:**
    - Every request after `POST devices` sends the plugin's device ID in an `X-RuneJourney-Device` header (`cloud.device` middleware).
    - Commits send `seq`, the device's own count of commits to that profile. A mismatch answers `409 sequence_mismatch` with the server's count.
    - Errors are `{"error": code, "message": ...}`.
  - **No `devices/fork`:** a copied folder just registers a new device ID with `POST devices`, which is safe to repeat and also renames and reconnects.
  - **Added `GET profiles/{profile}/media/{media}`:** fresh URLs for opening a screenshot after the changes feed's have expired.
  - **Disconnecting keeps the device row:** its documents are still part of each account's history.
  - **`cloud_uploads` rows are deleted at commit**, so there's no `committed_at`.
  - **`cloud_profiles.media_bytes`**, so deleting one account frees the right amount of the shared quota.
  - **New `cloud_garbage` table:** replaced and removed files are written there inside the commit's transaction, and deleted by `cloud:sweep` once their signed URLs have expired. Deleted folders are deleted once immediately and again after that, to catch an upload that lands late.
  - **Uploads can't be held to their declared size in the bucket.** The AWS SDK never signs `Content-Length` into presigned URLs, so the size check at commit is the only enforcement (as section 3 anticipated). A wrong size is deleted at once, and uploads that are never committed are swept 20 minutes after their URL was issued. If that window turns out to be abused, check on staging whether Spaces honours presigned POST policies with `content-length-range`.
  - **Sanctum only accepts the plugin's API keys** (`sanctum.guard` and `stateful` are empty), never a website session.
  - **No remember-me cookie on sign-in**, so the cookie list on the privacy page stays accurate.
  - **`trustProxies` waits for Phase 6:** trusting forwarded IPs without a proxy in front would let anyone dodge the IP rate limits.

**Phase 3: plugin transport and UI**
- `RuneJourneyConfig`:
  - `cloudSync`: off by default, with the exact Plugin Hub warning text.
  - `cloudScreenshots`: "Back up screenshots", on.
  - A hidden `cloudServer` URL, for pointing at the local or staging server during development.
- `com.runejourney.cloud`:
  - `CloudApi` (an interface, so a fake server can stand in during tests) and `OkHttpCloudApi`: injected client, `callTimeout(8s)`, `enqueue`, cancellable calls. Bucket PUTs and GETs use the same client.
  - `CloudCrypto`: gzip and the AES-GCM envelope.
  - `CloudCredentials`: `cloud/credentials.json`.
  - `ProfileLock`.
  - `SyncManager`: looking up profiles, the consent prompt, pull, push, outboxes, first-link choice, clone fork.
- `JourneyStore`: IO for `cloud/credentials.json` and, per profile, `sync/state.json`, `sync/outbox.json`, `sync/media.json`, `sync/lock` and backups, all through `Filepath`.
- `JourneyService`: `holdBaseline` / `releaseBaseline(snapshot)`, so login waits for the pull (section 5). The relog check from Phase 0 already holds tracking the same way.
- `ui/CloudSection` in `RuneJourneyPanel`: connect, status ("Synced 2 min ago"), consent prompt, media usage bar, Manage, Disconnect. The website's home page shows a mockup of it (`components/marketing/mockups/cloud.blade.php`): connected as, synced time, this account / this PC / also on, screenshot usage, and the "Save <RSN> to <name>'s cloud?" prompt.
- Wire it into `RuneJourneyPlugin`: `onGameStateChanged`, `onGameTick` and `loadProfile`.
- The Wealth config section's description, which currently says "Stays on your computer", needs rewording.
- **Built (2026-10-08), with `cloud/SyncManagerTest` running PCs against a fake server (real encryption, two-step uploads, sequence numbers, lost replies, a revoked key, a copied folder, the quota) and `cloud/CloudCryptoTest`.** Where it differs:
  - **The server is fixed** at `https://runejourney.org` (`SyncManager.SERVER`). Only in RuneLite's developer mode (`./gradlew run`) does the cloud card show a field for another server (the hidden `cloudServer` setting); outside developer mode that setting is ignored, even if it's saved.
  - **The cloud UI** is a "Cloud sync: ..." line under the panel's title that opens the cloud card. Questions (save this account?, which journey?, back up old screenshots?) and warnings (key stopped working, update needed, open in another window, screenshots full) show above the tabs. Turning sync on from the card shows the Hub warning first.
  - **No separate `ProfileLock`:** `JourneyStore.tryLock(key, holder)` holds `sync/lock`, counting holds by what holds it: playing the account (released after the logout upload, or when switching accounts), a sync, or a save at the login screen. The file lock is released once nothing in the window holds it. Saving at the login screen skips the save if another window changed the files.
  - **Every account flow runs in one queue** (login, consent, the journey choice, timed syncs, logout), under the account's lock. Whenever a window newly takes an account's lock, it reads that account's sync files again, and its credentials before forking, so two RuneLite windows on one PC taking turns never mistake each other for a copied folder.
  - **Restoring is remembered** (`SyncState.restoring`): until a pull that fetches this PC's own copy back has finished, nothing is uploaded, so a failed restore can't upload a blank profile over it.
  - **Encryption binding** (the AES-GCM associated data) is `runejourney|user uuid|profile id|kind|doc key|device id`, the device left out for screenshots. The plugin can't know the server's object key, which includes the upload version.
  - **Commits:** a commit is saved as pending before it's sent and resent with the same change ID until answered. `sequence_mismatch`, or a resolve showing more commits than this PC made, means a copied folder: the PC takes a new device ID and its parts so far become the old ID's. Accounts not loaded at that moment fork when they're next loaded.
  - **Deleted on the website:** if an account's profile is gone (404), sync stops and asks again before saving it.
  - Uploads of accounts that aren't loaded drain in the background, each under its own lock.
- **The privacy page promises the RSN and account ID are never uploaded**, so uploaded documents must leave them out: drop `ProfileData.playerName` (each PC knows the name locally once logged in), and check nothing else carries the RSN or account hash. A test should fail if an exported document contains either.

**Phase 4: media**
- `cloud/MediaSync`: queue from `ScreenshotService`, JPEG and thumbnail encoding on the executor, pause and resume on quota, download cache.
- `JourneyStore`: accept `.jpg` names for cloud copies.
- `ui/ScreenshotWindow`: cloud badges, Remove from cloud, and screenshots taken on other PCs.
- The first-connect backlog prompt.
- **Built (2026-10-08), in `SyncManager` and `MediaCodec`.** Where it differs:
  - **Media ID** = the first 32 hex digits of SHA-256("media:" + file name), so any PC can find a Journey event's screenshot in the cloud.
  - **Each screenshot and thumbnail starts with a small header** (file name, title, time), so other PCs can list and title screenshots that aren't in any Journey event.
  - **Downloaded copies** are saved as `screenshots/<name>.jpg` beside where the PNG would be; `JourneyStore.readScreenshot` falls back to them. Thumbnails of other PCs' screenshots are kept in `sync/thumbs/`.
  - **Screenshots go 5 to a commit**, newest first, journey documents first. A full quota pauses screenshots only; the next sync with room resumes them.

**Phase 5: website and launch.** The website parts are done (2026-10-08): the privacy page has Cloud storage and Deleting sections, the FAQ is updated, cloud storage is a feature on the home page with a mockup, "Cloud" is in the menu and footer, and it's off the roadmap. Still to do: `KNOWN_BUGS.md`, the README and the Plugin Hub description. Don't put the site live before the plugin update ships, or people will sign in and make keys the plugin can't use yet.
- `resources/views/privacy.blade.php`: rewrite the plugin section.
  - We store your Discord ID and username, no email. What's uploaded (no RSN or account ID), encryption before upload, the 100 MB media limit, the 12-month inactivity cleanup, full removal within 7 days of a delete, how to delete, and that our server and DigitalOcean (the storage provider) see your IP address.
  - The "Future features" promise to update this page before launch is fulfilled by this.
- FAQ:
  - "Does it work with multiple accounts?": one key covers all of them, and each account asks once.
  - "What happens when my 100 MB is full?"
  - "What if I lose my Discord account?": local data is unaffected, but the cloud copy can't be reached.
  - Move "Cloud storage" to available on the roadmap.
- `KNOWN_BUGS.md` limitations, README, and the Plugin Hub description.

**Public journeys (website built 2026-10-08; plugin side to do with Phase 3)**
Any saved account can have a public page at `/journeys/<name>` (Seasonal, Deadman and Fresh Start at `/journeys/<name>/seasonal` and so on), and `/journeys` searches the ones whose owners chose "Show in search". It's the one place the RSN is uploaded, and only for accounts made public.
- **Settings live on the server** (`cloud_profiles.public_*`). Every profile in the API includes `public: {enabled, searchable, sections, blocked, url}`. They can be changed on the cloud page or with `PATCH profiles/{profile}/public {enabled?, searchable?, sections?}`.
- **Sections:** `skills`, `kills`, `collection`, `timeline`, `notes`, `goals`, `records`, `wealth`. `notes` (event notes and memories) and `wealth` are off by default.
- **The plugin publishes** `PUT profiles/{profile}/public/snapshot` after a sync while `enabled` is true:
  - It only builds the sections that are on. The server strips the rest anyway, and strips a section as soon as it's switched off.
  - Answers: `409 not_public`, `409 name_taken` (another user's page has that name), `422` validation.
  - Switching a page off deletes the summary on the server.
  ```
  {schema: "1.0", name: RSN, world: main|seasonal|deadman|fresh-start, generated_at,
   skills: {ATTACK: xp, ...},
   kills: {bosses: {name: kc}, clues: {tier: count}},
   collection: {quest_points, collection_log, collection_log_total, combat_tasks, combat_points},
   timeline: [{id, date, time, type, title, detail?, skill?, value?, highlight?, note?, memory?}]   // max 300, newest first
   goals: [{name, type, progress 0-1, target_date?, completed_at?}],                              // max 100
   records: [{title, value, date?}],                                                               // max 40
   wealth: {net_worth, as_of?}}
  ```
- **Plugin changes this needs:**
  - `JourneyEvent` needs a `memory` flag for memories added by hand, so they're hidden along with notes.
  - The Phase 3 rule against uploading the RSN has this one exception: the `name` here, for public accounts only.
  - The panel should show the page's address and let the player change the settings.
- **Plugin side built (2026-10-08)**, tested in `cloud/SyncManagerTest` and `service/PublicPageTest`, and a sample page from the plugin passed the website's validation:
  - `JourneyService.publicSnapshot` builds the page from the combined journey on this PC (`service/PublicSnapshot`), with only the sections that are on; the timeline leaves out memories and notes unless `notes` is on. Names the website can't take (bosses with odd characters, an RSN that isn't valid) are left out rather than failing the whole page; the timeline is shortened if the page would pass 240 KB.
  - `SyncManager` keeps the settings from every profile reply (`SyncState.publicSettings`) and publishes after a sync when the page changed (hashed without `generated_at`), at most every 10 minutes while playing and always at logout. Switching the page on or changing its sections publishes again. `name_taken` and `422` are shown in the cloud card and only retried once the page changes; `not_public` switches it off here.
  - The cloud card shows the page's address, "Make public" (with a confirmation saying what's shown), "Make private", "Show in the website's search", and "Choose what it shows" (the website).
  - `JourneyEvent.memory` is set for memories added from now on. Memories added before are found when loaded by their time: the player types it to the minute, while recorded events have milliseconds.
  - The collection log total is read from the game (`COLLECTION_COUNT_MAX`) for `collection_log_total`.
- **Screenshots on the website (2026-10-08):**
  - The cloud page has a gallery of each account's screenshots, and no longer lists PCs. `ScreenshotController` decrypts each one as it's viewed (`PluginEncryption`, `Screenshot`), for its owner only, and only ever serves it as a JPEG. Nothing decrypted is stored. A test decrypts a real thumbnail the plugin encrypted (`tests/Fixtures/plugin-thumbnail.b64`).
  - Public pages have a `screenshots` section, off by default (screenshots can show other players' names in chat). With it on, the plugin sends each timeline event's `screenshot` (its media ID) if that screenshot is in the cloud, and the page shows it. Only screenshots the page's timeline uses can be fetched, and only while the section is on.
- **Character model (2026-10-08):** public pages have a `character` section, on by default (it's what anyone nearby sees in game). The plugin copies the player's lit model (`cloud/CharacterModel`) while they stand still, unanimated and untransformed, each time their look (worn items, kit colours, gender) is new to the page. It's sent after the page as gzipped "RJM1" binary to `PUT profiles/{id}/public/character?look=<sha256 of the look>`. The profile reply's `public.character` says which look the page shows, so another PC's newer outfit is noticed. The website checks the model (counts, size, every face's vertices exist), keeps it in `public_characters` (deleted with the page, or when the section is hidden) and serves it at `/journeys/<name>/character/<look>`, where `resources/js/character.js` (three.js) shows it turning, with a pause button. A test reads a model made by the plugin's encoder (`tests/Fixtures/plugin-character.b64`).
- **The plugin only talks to the website (2026-10-08).** Signed storage URLs are gone: they failed with `SignatureDoesNotMatch` from Spaces, and meant the plugin contacted DigitalOcean directly. Uploads are now reserve (`POST profiles/{id}/uploads`, as before but with no URLs), then each file's raw bytes to `PUT profiles/{id}/uploads/{upload}` (checked against the reserved size and SHA-256, then stored by the server), then commit as before. Downloads are `GET profiles/{id}/objects/{id}`, by the `id` each change now carries; `media/{id}` returns the screenshot's file IDs. File routes have their own limit (`cloud-files`, 600/min) and request size (the largest file a kind allows). `OkHttpCloudApi` builds every request from the connected server, and `cloud/sync.log` records each one.
- **Screenshot backup is off for now.** `SyncManager.SCREENSHOTS` and the website's `runejourney.cloud.screenshots` (env `RUNEJOURNEY_CLOUD_SCREENSHOTS`, default false) switch it; while off the plugin uploads journeys only (the setting is hidden), the server turns media uploads down with `screenshots_disabled`, and the cloud page, the public page's "Screenshots" choice and the privacy page leave screenshots out. The code and its tests stay (tests switch it on).
- **Hiscores and the whole collection log (2026-10-08):**
  - Before publishing a page that shows kills or the collection log, `SyncManager` looks the account up through RuneLite's `HiscoreClient` (the world's own hiscores; at most every 3 hours, or 5 minutes after "Sync now"; a failure never stops the page). `Hiscores.merge` takes the higher count for each boss and clue tier (names matched whatever their punctuation, "The" ignored), adds minigame scores as `kills.activities`, and raises `collection_log` to "Collections logged".
  - `CollectionLogSync` adds "RuneJourney" to the collection log's burger menu (built by the game's `collection_create_dropdown`, proc 7814; added after script 7797 draws the log, once). Choosing it opens the log's search, which runs script 4100 for every obtained item (args: item ID, quantity), then script 2240 sets the log up again. The tabs and pages come from the cache (enum 2102; params 682/683 for a tab's name and pages, 689/690 for a page's name and items; enum 3721 swaps listed IDs for drawn ones). `JourneyService.onCollectionLog` stores every page (with quantities) and the tab layout in the profile, which also ticks off item goals.
  - The log goes to `PUT profiles/{id}/public/collection-log` as JSON (tabs → pages → [item ID, quantity], item names once), with a hash the profile reply echoes as `public.collection_log`, so it's only sent when it changes. The website keeps it in `public_collection_logs` (deleted with the page or when the section's hidden) and shows it with tabs, pages and item icons, fetched once from RuneLite's icon host and served from the site (`/items/{id}.png`).
  - "Sync now" publishes the page straight away (scheduled syncs still wait 10 minutes while playing), and runs after a sync that's already going.
- **Screenshots after the cloud data is deleted:** `media.json` remembers which cloud profile its "in the cloud" states were checked against. When that differs (the account was saved again after its data was deleted, or a database was reset), the next pull starts from the beginning and notes which screenshots the cloud has; the rest go up again if they're on the PC, and are forgotten if they aren't. The website also leaves out any timeline screenshot the cloud doesn't have, rather than showing it broken.
- **PCs are named after their key**: `POST devices` no longer takes a name; the server names the device after the key it registers with, and the plugin shows that name. Registering again with a new key renames it.
- **Moderation:** every page has a Report form (5 an hour per IP, stored with a hashed IP). `journeys:reports` lists reports. `journeys:takedown <name> [world]` deletes a page and stops its owner making it public again, and `journeys:restore <profile id>` lifts that.

**Phase 6: server setup (Forge + VPS)**
- Ubuntu LTS, SSH keys only, a firewall allowing only 22/80/443, unattended upgrades and fail2ban.
- Let's Encrypt with HSTS.
- MySQL or Postgres bound to localhost, with a least-privilege DB user and daily encrypted off-site backups, with test restores.
- Spaces bucket (one for staging, one for production), in a region close to most players:
  - File listing restricted, no CDN, no bucket policy.
  - The app's key: per-bucket, Read/Write/Delete on that bucket only.
  - Versioning can only be turned on through the API (`aws s3api put-bucket-versioning` against the Spaces endpoint), not the control panel.
  - Lifecycle rules (old versions expire after 7 days, incomplete multipart uploads aborted after 1 day) need a full-access key. The privacy page promises deleted data is gone within 7 days, so this rule must be in place before launch. Use a separate admin key once for setup, and keep it off the server.
- A Discord application with production and staging redirect URLs.
- Secrets in env: Discord client secret, the Spaces key, fingerprint pepper.
- `APP_DEBUG=false`. **Back up `APP_KEY` safely: losing it makes every user's data key unreadable, and with it every file in the bucket.**
- Zero-downtime deploys and a scheduler cron.
- Uptime and error monitoring with request bodies and signed URLs scrubbed.
- A staging site before production.

**Later:** one-click Connect (approve the PC in the browser instead of pasting a key); paid media tiers; a web dashboard, where the browser decrypts with the data key; share links.

## Verification
- **Plugin unit tests (JUnit):**
  - Seeded random checks that combining gives identical results in any order, with any grouping, and with repeats.
  - Own copy = view − remotes, worked through and back.
  - XP range trimming.
  - The logical clock with ±1 day of clock skew.
  - Old event IDs, including duplicate memories.
  - Rejection of a newer major schema.
  - A field-coverage test: serialize fully populated `DayRecord` and `ProfileData` with Gson and fail if any field has no combining rule.
  - Envelope round trip; decrypting under a different object key fails.
  - JPEG and thumbnail encoding stays under the size cap.
- **Two-device integration tests:**
  - Setup: two `JourneyService` instances with separate temp folders and an in-memory `FakeCloudServer` covering uploads, PUT, commit and changes.
  - The fake server can: complete a PUT but never receive the commit; apply a commit but drop the reply; expire a signed URL; revoke the key mid-sync; simulate a crash between writes.
  - Scenarios:
    - two sessions on the same day
    - B offline while A plays, then B syncs
    - a goal edited on A at the login screen while B completes it
    - delete vs note on the same event
    - a GE replay with stale slots
    - the week rolling over on both devices
    - corrupt profile restore
    - a cloned folder
    - an account switch mid-pull
    - an old-schema client
    - a main and an alt on both PCs
    - the same account in two windows on one PC
    - the media quota filling up, then being freed
  - After each scenario, check that each skill's ranges add up to its real XP gain, that no event ID appears twice, and that both devices end up with identical views.
- **Laravel Pest tests:**
  - Every API endpoint tested against two users: user A's key can't look up, read, upload to, commit to or delete anything of user B's. Every signed URL is under the caller's own `storage/<uuid>/`.
  - A key without the `sync` ability gets 403. A revoked key gets 401.
  - Quota: reserving past 100 MB gets 409, deleting frees space, and simultaneous requests can't overshoot.
  - Commit: a missing object or wrong size is rejected; a resent `change_id` is harmless; a clone's sequence mismatch is caught.
  - Uncommitted uploads are swept, replaced versions are deleted, and delete-all removes the user's whole folder (`Storage::fake`).
  - The Discord callback creates the user once and signs them back in on return (Socialite mocked). Discord accounts under 7 days old are refused.
  - Fingerprints are stored only as HMACs, and the database holds no RSN or account hash.
  - An oversized request gets 413.
- **Manual end-to-end (you have to run this in game):** two PCs against the staging site, with the staging quota lowered to 5 MB.
  1. Sign in with Discord, create two keys, and connect both PCs.
  2. On PC A, log into the main, save it, play and log out. Log in on PC B and compare today's totals and Journey.
  3. Log into an alt on PC A, choose "Not on this PC", and check nothing was uploaded for it.
  4. Take screenshots until the quota is full and check the banner. Remove some from the cloud and check uploads resume.
  5. Open the same account in two RuneLite windows on PC A and check the second is read-only.
  6. Pull the network on B mid-session, then reconnect.
  7. Revoke PC B's key on the website and check B stops syncing and keeps its local data.
  8. Delete everything on the website and check the bucket folder is empty.
