# Feature Spec: Nostr Encrypted Backup & Sync (v0.1.3)

> **Status:** Ready for implementation — design decisions resolved (§16), concrete
> classes + test plan + rollout specified (§17–§20).
> **Depends on:** FEAT-001 (Nostr Relay Discovery / NIP-65) — consumes the pool
> contract from FEAT-001 §8 (`writeRelays()`, `readRelays()`, `onRelaysChanged()`).

## 1. Overview

CruxCoach stores all personal climbing data (ascents, bids, sessions, body stats,
training plans, climb lists) in a local SQLCipher-encrypted database. Currently,
data loss on device change or uninstall is permanent. This feature adds encrypted
backup of the secure database using Blossom blob storage and Nostr relay pointers.

### Goals

- Opt-in encrypted backup using the user's Nostr keypair (default: off)
- On/off toggle + interval selector (daily / weekly / manual)
- Onboarding prompt asks once whether to enable backup
- Full-snapshot backup (serialize all data, compress, encrypt, upload)
- Restore during onboarding when importing an existing Nostr key
- Work with Amber (NIP-55 external signer) and local key storage
- No additional accounts, no cloud services beyond Blossom + Nostr relays

### Non-Goals

- Multi-user collaborative editing (single-user sync only)
- Board database sync (already handled by Blossom)
- Real-time or incremental sync (full snapshot per backup cycle)
- Merge/conflict resolution (restore replaces or skips)
- iOS support (Android only for now)

---

## 2. Architecture

```
┌───────────────────────────────────────────────────────┐
│                     CruxCoach App                      │
│                                                        │
│  ┌──────────┐    ┌──────────────┐    ┌──────────────┐  │
│  │ SQLCipher │───→│ JSON + gzip  │───→│  AES-256-GCM │  │
│  │ SecureDB  │    │  Serializer  │    │  (dataKey)   │  │
│  └──────────┘    └──────────────┘    └──────┬───────┘  │
│                                             │          │
│                  ┌──────────────┐    ┌───────▼──────┐  │
│                  │  WorkManager │    │ Amber/Quartz │  │
│                  │  (schedule)  │    │ (key wrap)   │  │
│                  └──────┬───────┘    └──────────────┘  │
│                         │                              │
└─────────────────────────┼──────────────────────────────┘
                          │
             ┌────────────┼────────────┐
             │            │            │
   ┌─────────▼──────┐  ┌─▼──────────────────┐
   │  Blossom Servers │  │   Nostr Relays     │
   │  (encrypted blob) │  │  Kind 30078 Events │
   │  PUT/GET/DELETE   │  │  (pointer + key)   │
   └──────────────────┘  └────────────────────┘
```

### Core Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Blob storage | Blossom (BUD-02) | No size limits, content-addressed, multi-server redundancy. Relay events max 64 KB — our data exceeds this after 1 year of normal use (benchmark: 483 KB JSON → 94 KB gzip for 2-year user) |
| Relay usage | Kind 30078 pointer + key (2 events) | Pointer references Blossom blob by SHA-256. Key event stores NIP-44-wrapped dataKey. Total relay footprint: <1 KB |
| Relay strategy | Fire-and-forget writes, first-valid reads | Write: broadcast to all known relays (errors OK). Read: parallel query, highest `created_at` wins. Only 1 of N relays needs to be online |
| Relay discovery | Delegated to FEAT-001 | FEAT-002 consumes the resolved pool from `NostrRelayPool`. No backup-specific NIP-65 fetch — see FEAT-001 for discovery mechanics, bootstrap relays, and merge policy |
| Blossom discovery | Kind 10063 + hardcoded defaults | Read user's Blossom server list if available, fallback to blossom.primal.net + blossom.nostr.build |
| Privacy | HMAC d-tags, no labels | D-tags derived via HMAC-SHA256(HKDF-derived key, identifier) — opaque to observers. No `com.cruxcoach` labels. Content NIP-44 encrypted |
| Blob encryption | AES-256-GCM (dataKey) | Operates on raw bytes — no String/Base64 conversion needed. Hardware-accelerated on ARM. javax.crypto built-in |
| Key management | Random dataKey wrapped via NIP-44 | O(1) Amber calls regardless of data size. DataKey cached locally as NIP-44-wrapped blob in DataStore-Preferences |
| Compression | gzip | ~5:1 ratio for real data (UUIDs, timestamps, varied strings). `java.util.zip` built-in, zero APK overhead |
| Backup model | Full snapshot (serialize all → upload) | No dirty tracking, no per-row flags, no incremental sync. Estimated ~800 lines Kotlin for production quality |
| Sync trigger | WorkManager periodic (daily/weekly/manual) | Same pattern as board sync (`SyncInterval` enum). Opt-in, default off |
| Restore model | Replace or skip (no merge) | User chooses: overwrite local data with backup, or keep local data. No conflict resolution needed |

### Why Not Relay Events for Data?

Benchmark with realistic varied data (random UUIDs, timestamps, climb names):

| User profile | JSON | gzip | Relay event size | strfry 64 KB limit |
|-------------|------|------|-----------------|-------------------|
| Casual (1 yr) | 162 KB | 34 KB | 61 KB | Barely ✅ |
| Normal (2 yr) | 483 KB | 94 KB | 172 KB | ❌ |
| Power (3 yr) | 1.1 MB | 217 KB | 397 KB | ❌ |
| Extreme (5 yr) | 2.2 MB | 429 KB | 784 KB | ❌ |

Splitting across multiple relay events (monthly, yearly, or by category) solves
the size problem but introduces manifest management, dirty tracking per chunk,
atomicity concerns, and grows from ~41 events for a 3-year user. Blossom
eliminates this entire complexity class — one blob, any size.

### Why Not NIP-44 for Blob Encryption?

NIP-44 via Quartz's `NostrSigner` operates on `String`, not `ByteArray`. Using
it for the Blossom blob would require: `gzip → Base64 → nip44Encrypt(string) →
bytes → upload`. This double-Base64 wastes ~33% bandwidth and routes potentially
500 KB+ through Amber's ContentResolver IPC per backup cycle.

AES-256-GCM with a NIP-44-wrapped dataKey:
- Encrypts raw bytes directly (no String conversion)
- O(1) Amber calls total (one `nip44_encrypt` for key wrapping, cached forever)
- `javax.crypto.Cipher` — built-in, hardware-accelerated, zero dependencies

---

## 3. Event Layout

Only two Kind 30078 events on relays. Total footprint: <1 KB.

### 3.1 Privacy: HMAC-Obfuscated D-Tags

Plaintext d-tags like `cruxcoach/backup/v1` would let any relay operator or
indexer enumerate all CruxCoach users by querying `#d`. Instead, d-tags are
derived deterministically from the user's key:

```
hmacKey = HKDF(IKM=nostr_private_key, salt="cruxcoach-dtag-v1", info="hmac-key")
backup_d_tag = hex(HMAC-SHA256(hmacKey, "cruxcoach/backup/v1"))
key_d_tag    = hex(HMAC-SHA256(hmacKey, "cruxcoach/key/v1"))
```

This produces a deterministic but opaque hex string per user. Only the key
holder can derive it, so the app can always find its own events, but observers
see random-looking d-tags with no link to CruxCoach. A plain SHA-256 hash
is insufficient — anyone suspecting CruxCoach could compute the same hash.
HMAC with a key-derived secret requires secret knowledge to derive.

The HMAC key is HKDF-derived from the private key rather than using the raw
private key directly. This provides domain separation between Schnorr signing,
NIP-44 ECDH, and d-tag derivation at negligible cost.

**Amber/NIP-55 consideration:** HMAC-SHA256 requires the raw private key,
which is not available when using Amber. NIP-44 encryption is non-deterministic
(random nonce per invocation), so `nip44Encrypt` output cannot be used.

Instead, Amber users derive d-tags via Schnorr signature:

```
1. Construct a fixed Nostr event template:
   kind=0, created_at=0, content="cruxcoach/backup/v1", tags=[]
2. Request Amber to sign it (sign_event permission, already granted)
3. d_tag = hex(SHA-256(signature))
4. Cache the d-tag locally (DataStore-Preferences) — compute once, reuse forever
```

The cached d-tag drives every subsequent write on the same device (step 4).
For cross-device restore (fresh install, imported key, no local cache), we
do NOT rely on Schnorr determinism — Amber implementations vary on
`aux_rand` handling, and detecting determinism adds branching we can avoid.

**Restore for Amber users: unconditional O(N) query-all.** Query relays for
all Kind 30078 events by this pubkey (no d-tag filter), decrypt each with
NIP-44, and identify CruxCoach events by the `version` field in the
decrypted content. Typical N is well under 10 Kind 30078 events per user,
so the cost is negligible. Local-key users continue to use the deterministic
HMAC path; only Amber restore falls back to query-all. This kills the
deterministic-detection branch and makes the restore path uniform across
Amber implementations.

### 3.2 Pointer Event

The pointer is the entry point for discovery and restore. Its content is NIP-44
self-encrypted so relays cannot observe backup metadata.

```json
{
  "kind": 30078,
  "pubkey": "<user-pubkey>",
  "tags": [
    ["d", "<HMAC-derived d-tag>"]
  ],
  "content": "<NIP-44 encrypted JSON>"
}
```

- **No label tags** — `["L", "com.cruxcoach"]` and `["l", ...]` are omitted
  to prevent app-usage fingerprinting.
- **No NIP-70 `["-"]` tag** — relays that don't support NIP-70 silently
  reject events containing it, shrinking the usable relay pool. Since the
  content is NIP-44 encrypted (opaque to observers) and d-tags are HMAC-
  obfuscated, the event is already unlinkable to CruxCoach without the
  private key. The marginal privacy benefit of `["-"]` does not justify
  the relay compatibility risk.

Decrypted content:

```json
{
  "version": 1,
  "schema_version": 3,
  "sha256": "a1b2c3d4e5f6...",
  "size": 94000,
  "servers": [
    "https://blossom.primal.net",
    "https://blossom.nostr.build"
  ],
  "previous_sha256": "f6e5d4c3b2a1...",
  "updated_at": 1744700000,
  "device_id": "a1b2c3d4-...",
  "categories": ["ascents", "bids", "sessions", "body", "training", "config", "lists"]
}
```

The `previous_sha256` field enables blob cleanup: after uploading a new blob,
DELETE the old one from each Blossom server (best-effort, no error if it fails).

> **Versioning note.** The `version: 1` field above is the **pointer format
> version**, distinct from the `version: Int = 2` on `CruxCoachBackup.Backup`
> in §9 (the **payload schema version**). Both evolve independently: the
> pointer envelope can change (new metadata fields) without reserializing
> every user's backup blob, and the payload schema can change (new tables,
> renamed fields) without breaking pointer discovery.

### 3.3 Key Event

The dataKey is a random 32-byte symmetric key, NIP-44-encrypted to the user's
own pubkey. Generated once on first backup, then cached locally.

```json
{
  "kind": 30078,
  "pubkey": "<user-pubkey>",
  "tags": [
    ["d", "<HMAC-derived d-tag>"]
  ],
  "content": "<NIP-44 encrypted hex(dataKey)>"
}
```

---

## 4. Cryptography

### 4.1 Key Wrapping (one-time setup)

```
SETUP (once, on first backup):
  dataKey = SecureRandom(32 bytes)
  wrappedKey = signer.nip44Encrypt(hex(dataKey), signer.pubKey)  // 1 Amber call
  publish(Kind 30078, d="cruxcoach/key/v1", content=wrappedKey)
  cache wrappedKey in DataStore-Preferences (already NIP-44 encrypted, no ESP needed)
```

After setup, the dataKey is cached locally. No further Amber calls needed for
encryption — all AES-256-GCM operations use the local dataKey.

### 4.2 AES-256-GCM Blob Encryption

Uses `javax.crypto.Cipher` — built into Android, hardware-accelerated via ARMv8
Cryptography Extensions, no additional dependencies.

```kotlin
object BackupCrypto {
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        return iv + cipher.doFinal(plaintext)  // IV || ciphertext || auth tag
    }

    fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, data, 0, IV_LEN)
        )
        return cipher.doFinal(data, IV_LEN, data.size - IV_LEN)
    }
}
```

### 4.3 Compression

```kotlin
object BackupCompression {
    fun compress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size / 4)
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    fun decompress(compressed: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
}
```

### 4.4 Full Backup Pipeline

```
JSON (e.g., 483 KB for 2-year user)
  → gzip: ~94 KB
  → AES-256-GCM(dataKey): ~94 KB + 28 bytes (IV + auth tag)
  → Blossom upload: one PUT request
  → SHA-256 hash from server response
  → Update pointer event on relays
```

No Base64 encoding anywhere in the data path. Raw bytes from gzip go directly
into AES-256-GCM, and raw ciphertext goes directly to Blossom. Minimal overhead.

### 4.5 Amber Call Budget

| Operation | Amber calls | When |
|-----------|-------------|------|
| First backup setup | 3 | 1× nip44_encrypt (wrap dataKey) + 1× sign (key event) + 1× sign (pointer event) |
| Subsequent backups | 2 | 1× sign (Blossom auth Kind 24242) + 1× sign (pointer event update) |
| Restore | 2 | 1× nip44_decrypt (unwrap dataKey) + 1× (optional: verify pointer signature) |

After first setup, encryption is local (AES-256-GCM with cached dataKey).
Amber is only needed for Nostr event signing and the initial key unwrapping.

---

## 5. Relay & Blossom Discovery

> **Relay discovery is owned by FEAT-001** (Nostr Relay Discovery / NIP-65).
> FEAT-002 does not fetch Kind 10002 itself — it consumes the resolved pool
> from `NostrRelayPool`. This section describes only the backup-specific
> read/write strategy on top of that pool, plus Blossom server discovery
> (Kind 10063), which remains FEAT-002's concern.

### 5.1 Backup Read/Write Strategy

The resolved pool (from FEAT-001) is the union of the user's NIP-65 relays
and the hardcoded defaults in `NostrConfig.DEFAULT_RELAYS`. Backup operations
treat every relay in the pool as both readable and writable:

**Write strategy: fire-and-forget broadcast.**
Publish backup events to every relay in the pool. Each successful publish
adds redundancy. Failed publishes are ignored — if at least 1 relay accepted
the event, the backup pointer is safe. No relay is a dependency; every relay
is a safety net.

**Read strategy: parallel query, first-valid-wins.**
Query all pool relays simultaneously. Accept the first valid event with the
highest `created_at` timestamp (replaceable event semantics). Only 1 of N
relays needs to be online for a successful read. Timeout after 10 seconds.

Per FEAT-001 §6.2, the pool is treated as unified in v1: read/write markers
from Kind 10002 are preserved on each `ResolvedRelay` but not enforced by
pool operations. If a future spec turns on strict NIP-65 routing, backup
writes will target `write`-marked relays only and reads will hit
`read`-marked relays only — no data-model changes required.

### 5.2 Kind 10063 Fetch (Blossom Server List)

```kotlin
suspend fun fetchBlossomServers(pubkey: String): List<String> {
    // Additive union of user's Kind 10063 list and defaults — mirrors
    // FEAT-001 §6 relay merge policy. Never replace, always union.
    // User order first so preferences drive upload priority; defaults
    // guarantee >=2 servers for redundancy even when the user lists just one.
    val serverListEvent = queryFirstValid(
        relays = allWriteRelays,
        filter = Filter(kinds = listOf(10063), authors = listOf(pubkey)),
        timeout = 5_000
    )

    val userServers = serverListEvent?.tags
        ?.filter { it.size >= 2 && it[0] == "server" }
        ?.map { it[1].trimEnd('/') }
        ?: emptyList()

    return (userServers + DEFAULT_BLOSSOM_SERVERS).distinct()
}
```

### 5.3 Configuration Constants

```kotlin
object BackupConfig {
    // Only Blossom defaults are owned by FEAT-002.
    // Relay lists (bootstrap + defaults) live in FEAT-001 / NostrConfig.
    val DEFAULT_BLOSSOM_SERVERS = listOf(
        "https://blossom.nostr.build",
        "https://blossom.primal.net"
    )
}
```

---

## 6. Blossom Integration

### 6.1 Upload Flow

Blossom (BUD-02) accepts arbitrary bytes via authenticated PUT. CruxCoach
already has Blossom download infrastructure for board sync — upload mirrors it.

```kotlin
suspend fun uploadBlob(
    data: ByteArray,
    signer: NostrSigner,
    servers: List<String>
): List<BlossomUploadResult> {
    // 1. Create BUD-02 auth event (Kind 24242)
    val authEvent = createBlossomAuth(
        signer = signer,
        action = "upload",
        sha256 = sha256(data),
        expiration = TimeUtils.fiveMinutesFromNow()
    )

    // 2. Upload to all servers in parallel
    return servers.pmap { server ->
        httpClient.put("$server/upload") {
            header("Authorization", "Nostr ${Base64.encode(authEvent.toJson())}")
            setBody(data)
        }.body<BlossomUploadResult>()
    }
}
```

### 6.2 Download Flow

```kotlin
suspend fun downloadBlob(sha256: String, servers: List<String>): ByteArray {
    for (server in servers) {
        try {
            return httpClient.get("$server/$sha256").body<ByteArray>()
        } catch (e: Exception) {
            Log.w(TAG, "Blossom download failed from $server, trying next", e)
        }
    }
    throw BackupException("Blob $sha256 not found on any server")
}
```

No authentication needed for download — Blossom blobs are public by hash.
The data is encrypted, so public availability is safe.

### 6.3 Blob Cleanup

After a successful upload of a new backup blob, delete the previous one:

```kotlin
suspend fun cleanupPreviousBlob(previousSha256: String?, servers: List<String>, signer: NostrSigner) {
    if (previousSha256 == null) return
    val authEvent = createBlossomAuth(signer, "delete", previousSha256)
    servers.forEach { server ->
        try {
            httpClient.delete("$server/$previousSha256") {
                header("Authorization", "Nostr ${Base64.encode(authEvent.toJson())}")
            }
        } catch (_: Exception) { /* best-effort */ }
    }
}
```

### 6.4 Blob Health Check

During each sync cycle, verify blobs still exist on all configured servers
using HEAD requests (BUD-01, no auth required, no download):

```kotlin
suspend fun healthCheckBlob(sha256: String, servers: List<String>, signer: NostrSigner) {
    servers.forEach { server ->
        try {
            val response = httpClient.head("$server/$sha256")
            if (response.status == HttpStatusCode.NotFound) {
                Log.w(TAG, "Blob $sha256 missing from $server, re-uploading")
                reUploadBlob(sha256, server, signer)
            }
        } catch (_: Exception) { /* server unreachable, skip */ }
    }
}
```

Re-uploading an existing hash is a no-op on compliant Blossom servers, so
periodic re-uploads are cheap insurance against silent blob deletion.

### 6.5 Content-Type Compatibility

Major public Blossom servers (blossom.nostr.build, blossom.band) may restrict
free uploads to media types (images, audio, video). Encrypted backup blobs are
`application/octet-stream` and may be rejected with 415 Unsupported Media Type.

**Runtime preflight (BUD-06), not a ship-blocker.** Before the first upload
to any configured server, send `HEAD /upload` with
`X-Content-Type: application/octet-stream`. Cache the per-server result
(`accepted` / `rejected_octet` / `incompatible`) in DataStore-Preferences.
Servers that reject `application/octet-stream` are retried with
`application/x-cruxcoach-backup` as a Content-Type hint; if that also fails,
the server is marked `incompatible` and upload proceeds on the remaining
servers. A server marked incompatible is re-probed on the next backup cycle
to recover if the server later starts accepting arbitrary types. This moves
the compatibility question from a pre-release matrix to a self-healing
runtime fallback — no Blossom endpoint list is frozen at ship time.

### 6.6 Blossom Server Configuration

Servers are discovered via Kind 10063 (see Section 5.3) with fallback to
hardcoded defaults. User can add/remove servers in Settings. Upload goes to
all configured servers for redundancy. Download tries servers in order until
one succeeds.

### 6.7 SHA-256 Integrity Verification

After download, verify the blob hash matches the pointer event:

```kotlin
fun verifySha256(data: ByteArray, expectedHash: String) {
    val actual = MessageDigest.getInstance("SHA-256")
        .digest(data)
        .joinToString("") { "%02x".format(it) }
    check(actual == expectedHash) {
        "Blob integrity check failed: expected $expectedHash, got $actual"
    }
}
```

---

## 7. Sync Mechanism

### 7.1 Sync Interval (same pattern as Board Sync)

Backup is controlled by two settings in UserPreferences:

1. **`backupEnabled: Boolean`** — feature toggle (default: `false`)
2. **`backupInterval: SyncInterval`** — schedule (default: `DAILY`)

Reuses the existing `SyncInterval` enum from board sync:

```kotlin
// Already in UserPreferences.kt
enum class SyncInterval(val label: String) {
    DAILY("Taeglich"),
    WEEKLY("Woechentlich"),
    MANUAL("Manuell")
}
```

### 7.2 BackupSyncWorker

```kotlin
@HiltWorker
class BackupSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val backupRepository: BackupRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            backupRepository.performFullBackup()
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Backup failed", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "backup_sync_periodic"

        fun schedule(context: Context, enabled: Boolean, interval: SyncInterval) {
            if (!enabled || interval == SyncInterval.MANUAL) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                return
            }

            val repeatInterval = when (interval) {
                SyncInterval.DAILY -> 24L
                SyncInterval.WEEKLY -> 168L
                SyncInterval.MANUAL -> return
            }

            val request = PeriodicWorkRequestBuilder<BackupSyncWorker>(
                repeatInterval, TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
```

### 7.3 Full Backup Flow

Each backup cycle performs a complete snapshot — no dirty tracking needed.

**CRITICAL INVARIANT:** Blob must be uploaded and verified BEFORE the pointer
event is published. Publishing a pointer to a non-existent blob creates a
dangling reference. Since the pointer is a replaceable event, the old pointer
is lost on publish — so a premature pointer publish causes data loss.

```kotlin
suspend fun performFullBackup() {
    // 1. Serialize all personal data (reuses existing CruxCoachBackup.export)
    val json = CruxCoachBackup.export(
        categories = CruxCoachBackup.Category.entries.toSet(),
        userRepository, bodyStatRepository, workoutRepository,
        climbRepository, planRepository, personalBoardRepo,
        exportedAt = TimeUtils.isoNow(),
        nostrPubkey = signer.pubKey
    )

    // 2. Compress
    val compressed = BackupCompression.compress(json.toByteArray(Charsets.UTF_8))

    // 3. Encrypt with cached dataKey
    val dataKey = getOrCreateDataKey()
    val encrypted = BackupCrypto.encrypt(compressed, dataKey)

    // 4. Discover servers (Kind 10063 + defaults)
    val blossomServers = fetchBlossomServers(signer.pubKey)

    // 5. Upload to ALL Blossom servers
    val results = uploadBlob(encrypted, signer, blossomServers)
    if (results.isEmpty()) throw BackupException("Blob upload failed on all servers")
    val sha256 = results.first().sha256

    // 6. Verify blob exists via HEAD (at least one server must confirm)
    val verified = blossomServers.any { server ->
        try { httpClient.head("$server/$sha256").status.isSuccess() }
        catch (_: Exception) { false }
    }
    if (!verified) throw BackupException("Blob upload not verified on any server")

    // 7. ONLY NOW publish pointer event on ALL write relays (fire-and-forget)
    val writeRelays = fetchWriteRelays(signer.pubKey)
    val previousSha256 = getCurrentPointerSha256()  // reads DataStore-Preferences
    publishPointerEvent(sha256, encrypted.size, blossomServers, writeRelays)
    setCurrentPointerSha256(sha256)                 // atomic, after publish succeeds

    // 8. Cleanup old blob (best-effort)
    cleanupPreviousBlob(previousSha256, blossomServers, signer)

    // 9. Health-check: verify blob on all servers, re-upload where missing
    healthCheckBlob(sha256, blossomServers, signer)

    // 10. Record last backup time
    userPreferences.setLastBackupSync(TimeUtils.now())
}
```

**`getCurrentPointerSha256()` source.** The previous blob's SHA-256 is read
from DataStore-Preferences (key: `PreferenceKeys.BACKUP_CURRENT_POINTER_SHA256`,
a `stringPreferencesKey`), written atomically in step 7 after each successful
pointer publish. This gives cleanup zero extra round-trips — no relay fetch,
no Blossom HEAD walk. On fresh install (cache miss), the function returns
`null` and step 8 is a no-op; the single orphaned blob left behind on the
old server is reconciled at its server's retention policy or by the next
health-check cycle if the user adds that server back. The cache is the
authority because it always reflects *this device's* last write.

### 7.4 Manual Sync

"Jetzt sichern" button triggers an immediate one-shot backup:

```kotlin
fun syncNow(context: Context) {
    val request = OneTimeWorkRequestBuilder<BackupSyncWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .build()
    WorkManager.getInstance(context).enqueue(request)
}
```

---

## 8. Restore Flow

### 8.1 Two-Step Discovery

On fresh install, the app doesn't know the user's relays yet. Restore is a
two-step sequential process:

```
Step 1: Relay pool resolution  (owned by FEAT-001)
  ├── Bootstrap Kind 10002 discovery for the imported pubkey
  ├── Merge user's NIP-65 relays with hardcoded defaults
  └── NostrRelayPool exposes the resolved pool

Step 2: Fetch backup events  (FEAT-002)
  ├── Compute HMAC d-tags from private key
  ├── Query resolved pool in parallel
  ├── Find pointer + key events (highest created_at wins)
  ├── Fetch Kind 10063 for Blossom server discovery
  └── Timeout: 10 seconds → "no backup found"
```

Step 1 must complete before Step 2 — the backup query needs the resolved
pool. If FEAT-001's bootstrap fallback fires (both discovery relays down,
see FEAT-001 §6.4), Step 2 proceeds with defaults-only.

### 8.2 Detection

Triggers during onboarding when the user imports a Nostr key (via Amber or
manual entry).

```kotlin
suspend fun checkForBackup(signer: NostrSigner): BackupInfo? {
    // Step 1: Discover relays
    val writeRelays = fetchWriteRelays(signer.pubKey)

    // Step 2: Compute HMAC d-tags
    val backupDTag = deriveHmacDTag(signer, "cruxcoach/backup/v1")
    val keyDTag = deriveHmacDTag(signer, "cruxcoach/key/v1")

    // Step 3: Query all relays in parallel, first-valid-wins
    val events = queryAllRelays(
        relays = writeRelays,
        filter = Filter(
            kind = 30078,
            authors = listOf(signer.pubKey),
            dTags = listOf(backupDTag, keyDTag)
        ),
        timeout = 10_000
    )

    val pointerEvent = events.find { it.dTag == backupDTag } ?: return null
    val keyEvent = events.find { it.dTag == keyDTag } ?: return null

    // Step 4: Decrypt pointer to get metadata
    val pointerJson = signer.nip44Decrypt(pointerEvent.content, signer.pubKey)
    val pointer = Json.decodeFromString<BackupPointer>(pointerJson)

    return BackupInfo(
        lastUpdated = pointer.updatedAt,
        blobSize = pointer.size,
        categories = pointer.categories
    )
}
```

### 8.3 Restore Dialog

```
┌─────────────────────────────────────────┐
│         Backup gefunden                 │
│                                         │
│  Stand: 12.04.2026, 18:30 Uhr          │
│  Groesse: 94 KB                         │
│                                         │
│  Enthaltene Daten:                      │
│    - Board-Sends & Versuche             │
│    - Board-Sessions                     │
│    - Koerperdaten                       │
│    - Trainingsplaene                    │
│    - Climb-Listen                       │
│                                         │
│  [Wiederherstellen]                     │
│  [Ueberspringen]                       │
└─────────────────────────────────────────┘
```

No partial restore, no merge. User either restores the full backup (replacing
any local data) or skips. This is intentionally simple — CruxCoach is a
single-user app with snapshot-based backups.

### 8.4 Restore Implementation

```kotlin
suspend fun restore(backupInfo: BackupInfo) {
    // Events already fetched during checkForBackup — reuse them

    // 1. Unwrap dataKey (1 Amber call)
    val dataKeyHex = signer.nip44Decrypt(backupInfo.keyEvent.content, signer.pubKey)
    val dataKey = dataKeyHex.hexToByteArray()

    // 2. Decrypt pointer for Blossom URLs
    val pointer = backupInfo.pointer

    // 3. Discover Blossom servers (Kind 10063 + pointer.servers + defaults)
    val blossomServers = (
        fetchBlossomServers(signer.pubKey) + pointer.servers
    ).distinct()

    // 4. Download blob from Blossom (tries servers in order)
    val encrypted = downloadBlob(pointer.sha256, blossomServers)
    verifySha256(encrypted, pointer.sha256)

    // 5. Decrypt + decompress
    val compressed = BackupCrypto.decrypt(encrypted, dataKey)
    val json = BackupCompression.decompress(compressed).toString(Charsets.UTF_8)

    // 6. Import into local DB (reuses existing CruxCoachBackup.import)
    CruxCoachBackup.import(
        jsonString = json,
        selectedCategories = CruxCoachBackup.Category.entries.toSet(),
        userRepository, bodyStatRepository, workoutRepository,
        climbRepository, planRepository, personalBoardRepo,
        transactionRunner
    )

    // 7. Cache dataKey locally for future backups
    cacheDataKey(dataKey)
}
```

### 8.5 Progress Reporting

```kotlin
sealed class RestorePhase {
    object FetchingMetadata : RestorePhase()
    object DecryptingKey : RestorePhase()
    data class Downloading(val bytesReceived: Long, val totalBytes: Long) : RestorePhase()
    object Decrypting : RestorePhase()
    object Importing : RestorePhase()
    object Complete : RestorePhase()
    data class Error(val message: String, val retryable: Boolean) : RestorePhase()
}
```

---

## 9. Backup Data Format

Reuses the existing `CruxCoachBackup.Backup` data class from
`shared/.../CruxCoachBackup.kt`. No new serialization format needed.

```kotlin
@Serializable
data class Backup(
    val version: Int = 2,
    val app: String = "CruxCoach",
    val exportedAt: String,
    val nostrPubkey: String? = null,
    val profile: UserProfile? = null,
    val assessments: List<Assessment> = emptyList(),
    val bodyStats: List<BodyStat> = emptyList(),
    val workoutLogs: List<WorkoutLog> = emptyList(),
    val climbLogs: List<ClimbLog> = emptyList(),
    val trainingPlans: List<PlanWithSessions> = emptyList(),
    val boardAscents: List<AscentExport> = emptyList(),
    val boardBids: List<BidExport> = emptyList(),
    val boardSessions: List<SessionExport> = emptyList(),
    val climbLists: List<ClimbListExport> = emptyList()
)
```

### Schema version — no bump for FEAT-002

`Backup.version` stays at `2`, matching the format already used by the
existing JSON export/import. FEAT-002 does not change the serialization
shape — it only adds a new *transport* (Blossom blob + Nostr pointer event)
for the same JSON. The two versioning numbers in this spec are independent:

| Field | Location | Current value | Semantics |
|-------|----------|---------------|-----------|
| `Backup.version` | `CruxCoachBackup.Backup` (payload) | `2` | Bumps when schema fields change |
| Pointer `"version"` | §3.2 decrypted pointer envelope | `1` | Bumps when pointer metadata fields change |

Both can evolve independently — a new pointer metadata field (e.g., multi-blob
support) is a pointer-version bump with `Backup.version` unchanged. Adding
a new table to the backup is a payload-version bump with pointer-version
unchanged.

### Tables backed up (12 of 16 secure DB tables)

| Table | Export class | Typical row size | Backed up? |
|-------|-------------|-----------------|-----------|
| UserProfile | `UserProfile` | ~400 bytes | Yes |
| Assessment | `Assessment` | ~600 bytes | Yes |
| body_stat | `BodyStat` | ~82 bytes | Yes |
| TrainingPlan + TrainingSession | `PlanWithSessions` | ~1500 bytes | Yes |
| WorkoutLog | `WorkoutLog` | ~677 bytes | Yes |
| ClimbLog | `ClimbLog` | ~273 bytes | Yes |
| aurora_ascent | `AscentExport` | ~409 bytes | Yes |
| aurora_bid | `BidExport` | ~261 bytes | Yes |
| board_session | `SessionExport` | ~166 bytes | Yes |
| climb_list + climb_list_entry | `ClimbListExport` | ~37 bytes/entry | Yes |
| NostrMessage | — | — | No (on relays) |
| NostrProfile | — | — | No (reconstructable) |
| PaymentEvent | — | — | No (on relays) |
| Announcement | — | — | No (from server) |

---

## 10. Amber / NIP-55 Integration

### 10.1 Permission Request

Request permissions for signing (Blossom auth + pointer event) and key
wrapping (one-time NIP-44 encrypt/decrypt):

```kotlin
fun requestAmberPermissions(launcher: ActivityResultLauncher<Intent>) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
    intent.`package` = "com.greenart7c3.nostrsigner"
    intent.putExtra("type", "get_public_key")
    intent.putExtra("permissions", """[
        {"type":"sign_event","kind":30078},
        {"type":"sign_event","kind":24242},
        {"type":"nip44_encrypt"},
        {"type":"nip44_decrypt"}
    ]""")
    launcher.launch(intent)
}
```

Permissions requested on first backup enable (not during onboarding — only
when the user actually enables backup). The user must grant permissions via
Amber's foreground Intent dialog ("remember my choice") BEFORE any background
WorkManager task is scheduled.

**Background worker constraint:** Amber's ContentResolver IPC only works after
the user has granted permissions via the foreground Intent flow. WorkManager
tasks cannot trigger Intent dialogs. If Amber's process is killed by OEM
battery optimization, `contentResolver.query()` returns `null`. The worker
must handle this gracefully:
- If Amber is unreachable: log warning, return `Result.retry()`
- If permission expired: skip backup, show notification prompting user to
  open the app (which re-triggers the foreground permission flow)

### 10.2 Fallback: Local Key

When Amber is not installed, all operations use Quartz's `NostrSignerInternal`
directly — no IPC overhead, no foreground permission constraints.

---

## 11. Schema Changes

### 11.1 No Per-Row Sync Metadata

Full-snapshot backups eliminate the need for per-row dirty tracking. No
`is_dirty`, `sync_version`, `modified_at`, or `device_id` columns on existing
tables. The backup serializes everything as-is.

### 11.2 DataKey Cache

The NIP-44-wrapped dataKey (ciphertext) is cached in DataStore-Preferences
(same instance as `UserPreferences`, under `PreferenceKeys.BACKUP_WRAPPED_DATA_KEY`).
The encryption is already handled by NIP-44 — wrapping it again in
EncryptedSharedPreferences (ESP) would add a redundant layer with known
corruption bugs (Samsung S24 / Android 14 Tink keyset corruption causing
`KeyStoreException` crash loops). DataStore-Preferences storing an
already-encrypted blob is simpler and more reliable, and matches the existing
project convention (`UserPreferences` lives in the same store).

On cache miss or corruption, the app re-fetches the key event from relays
and unwraps via `signer.nip44Decrypt` (1 Amber call). This makes the local
cache a performance optimization, not a single point of failure.

The d-tags (HMAC-derived or Schnorr-derived) are also cached in DataStore-Preferences
alongside the wrapped dataKey for Amber users where derivation requires a
sign_event call (§3.1).

**Scope of this rule — only applies to self-encrypted ciphertext blobs.**
This "no ESP" decision is specific to data that is *already* encrypted by an
external mechanism (NIP-44, AES-GCM with a well-protected key, etc.). It does
NOT apply to plaintext credentials such as OAuth bearer tokens. `KilterTokenStore`
correctly uses ESP because its stored data — Kilter access/refresh tokens —
is plaintext and needs ESP as the at-rest encryption boundary. Likewise,
`NostrKeyStore` uses ESP for the user's private key (plaintext nsec).
Different threat model, different storage choice. A future refactor must
not harmonize the paths under a single "SecureStorage" abstraction — the
distinction is load-bearing:

| | Backup dataKey (here) | Kilter tokens / Nostr nsec |
|---|---|---|
| Stored data | NIP-44 ciphertext | Plaintext credential |
| Self-protection | Yes, inside the blob | None |
| ESP role if added | Redundant 2nd layer | **The** encryption boundary |
| Corruption recovery | Re-fetch from relays | User re-login / key regen |
| ESP risk/benefit | Risk > benefit → avoid | Risk < benefit → use |

---

## 12. UI / UX

### 12.1 Onboarding: Backup Opt-In

Existing onboarding is a linear state machine in `OnboardingViewModel` with
three steps (`OnboardingStep.WELCOME`, `PRIVACY`, `BOARD_SETUP`). FEAT-002
adds one step at the end:

```kotlin
enum class OnboardingStep { WELCOME, PRIVACY, BOARD_SETUP, NOSTR_BACKUP }
```

`NOSTR_BACKUP` is inserted after `BOARD_SETUP` because the Nostr key is
materialized lazily by `NostrKeyStore.getOrCreateKeyPair()`. When the user
reaches this step with the toggle set to **On**, the ViewModel:

1. Triggers `NostrKeyStore.getOrCreateKeyPair()` — generates the key if
   needed (local key path). For `SignerMode.AMBER`, the Amber permission
   intent (see §10.1) is launched inline; the user cannot proceed past this
   step until Amber responds (grant or deny). On deny, the toggle reverts
   to **Off** and the user can continue.
2. Writes `backupEnabled = true` + `backupInterval = DAILY` to
   `UserPreferences`.
3. Schedules the periodic `BackupSyncWorker` (§7.2) — first run happens
   opportunistically when `NetworkType.CONNECTED` is satisfied.

When the toggle is **Off** (default), no Nostr key is generated by this
step; the key is still generated lazily on the first Nostr-dependent feature
use (DMs, announcements, etc.). This keeps the default install footprint
unchanged for users who never opt into backup.

Restore flow interception: if the user imports an existing Nostr key via
Amber or manual entry on a fresh install (happens *before* onboarding or
from the `KeyImportScreen` later), the `checkForBackup` query from §8.1
runs and — if a backup is found — the restore dialog (§8.3) pre-empts the
`NOSTR_BACKUP` onboarding step. A successful restore sets
`backupEnabled = true` implicitly (the user clearly wants backups since
they just restored one); a dismissed restore dialog proceeds to the
normal opt-in step.

```
┌─────────────────────────────────────────┐
│         Nostr Backup                    │
│                                         │
│  CruxCoach kann deine Kletterdaten      │
│  verschlüsselt auf Blossom-Servern      │
│  sichern. Nur du kannst sie             │
│  entschlüsseln.                         │
│                                         │
│  Backup aktivieren?                     │
│                                         │
│  [━━━━━━━○] Aus                         │
│                                         │
│  [Weiter]                               │
└─────────────────────────────────────────┘
```

If the user enables backup, the interval defaults to "Taeglich".

If the user dismisses with backup disabled, the opt-in is not re-prompted
automatically. They can enable it later in Settings (§12.2). The
`backup_onboarding_seen` flag in DataStore-Preferences prevents
re-showing the step on subsequent app launches.

### 12.2 Settings Screen

```
Einstellungen
└── Backup & Sync
    ├── Nostr Backup: [━━━●━━━] Ein
    ├── Intervall: [Taeglich ▾]        ← Taeglich / Woechentlich / Manuell
    ├── Status: ✅ Letzte Sicherung: 12.04.2026, 18:30 (94 KB)
    ├── Blossom-Server (2 konfiguriert)
    │   ├── blossom.primal.net ✅
    │   └── blossom.nostr.build ✅
    └── Jetzt sichern [Button]
```

When toggle = off: interval, status, servers, and button are hidden.
When interval = "Manuell": no automatic schedule, only "Jetzt sichern" button.

### 12.3 Status Banner

Only shown when:
- Backup is actively running (progress bar with KB uploaded)
- Backup failed and needs attention (error, dismissable)

Never shown during normal operation.

---

## 13. Error Handling

### 13.1 Failure Modes

| Failure | Impact | Recovery |
|---------|--------|----------|
| Blossom upload fails (all servers) | Backup not created | WorkManager retries with exponential backoff |
| Blossom upload partial (1 of 2 servers) | Reduced redundancy | Re-upload to failed server on next cycle |
| Relay publish fails | Pointer not updated | Retry on next cycle; old pointer still valid |
| Blob download fails (restore) | Cannot restore | Try next Blossom server; show error if all fail |
| SHA-256 mismatch | Corrupt blob | Try downloading from different server |
| NIP-44 decrypt failure (key) | Cannot unwrap dataKey | Key event corrupt — unrecoverable without local cache |
| Blossom server removed blob | Blob lost | Download from other server; re-upload if needed |

### 13.2 Key Loss Protection

Two layers:
1. **Local cache** in DataStore-Preferences (NIP-44-wrapped ciphertext,
   self-protecting — see §11.2; survives app updates, not uninstall)
2. **Multi-relay redundancy** (key event on 3+ relays)

If both fail, backups are irrecoverable. Local SQLCipher DB is unaffected.
Users should back up their nsec (standard Nostr guidance).

### 13.3 Blob Retention

Blossom servers have no SLA — same as Nostr relays. Multi-server upload (2-3
servers) provides redundancy. Weekly health check: verify blob exists on all
configured servers, re-upload if any server has lost it.

---

## 14. Migration from JSON Export

Existing JSON export/import is the SAME format used for Nostr backup. The
`CruxCoachBackup.export()` / `CruxCoachBackup.import()` methods are reused
directly. Users with existing JSON backups can simply enable Nostr backup — the
next cycle will upload their data.

---

## 15. Dependencies

```kotlin
// No new dependencies for 0.1.3
dependencies {
    // Already in use:
    // net.zetetic:android-database-sqlcipher (local encryption)
    // androidx.work:work-runtime-ktx (background sync)
    // com.vitorpamplona.quartz:quartz-android:1.05.1 (NIP-44 key wrapping, signing)
    // com.squareup.okhttp3:okhttp (Blossom HTTP, already used for board sync)
    // javax.crypto (AES-256-GCM, built-in)
    // java.util.zip (gzip, built-in)
}
```

APK size impact: Zero. All dependencies are already bundled.

---

## 16. Resolved Design Decisions

| Question | Decision | Rationale |
|----------|----------|-----------|
| Blossom server discovery | Additive union of user Kind 10063 + defaults | Mirrors FEAT-001 §6 relay merge policy (never replace, always union). User order first so preferences drive upload priority; defaults guarantee >=2 servers for redundancy even if the user lists just one. Kills the former `>=2 threshold` branch |
| Citrine integration | No | Local relay has same SPOF as local DB — device loss kills both. Extra app install for zero safety gain |
| Backup size warning | No | 500 KB gzip = 5-year power user = ~1s upload. Warning makes sense at 5-10 MB (10+ years extreme use). Build when needed, not proactively |
| D-tag privacy (local key) | HMAC-SHA256 over HKDF-SHA256(nsec)-derived 32-byte key | Plaintext d-tags enable app-usage fingerprinting. RFC 5869 HKDF with SHA-256 and 32-byte output matches the Nostr ecosystem standard (NIP-44 v2); provides domain separation from signing/ECDH at negligible cost. `javax.crypto.Mac` with `HmacSHA256` — no new deps |
| D-tag privacy (Amber) | Cached Schnorr-sig-derived d-tag for writes; unconditional O(N) query-all on restore | Amber cannot expose nsec, so HMAC path is unavailable. Schnorr-over-fixed-template is computed once on first backup setup and cached for all subsequent writes on this device. Restore on a fresh install deliberately does NOT rely on Schnorr determinism — we query all Kind 30078 events for the pubkey (typically <10) and identify CruxCoach events by decrypted content. Uniform across Amber implementations; no deterministic-detection branch |
| Label tags | Removed entirely | `com.cruxcoach` labels leak app identity to relay operators and indexers |
| Relay discovery | Delegated to FEAT-001 | Single source of truth across all Nostr features. FEAT-002 consumes the resolved pool; no backup-specific bootstrap logic |
| NIP-70 `["-"]` tag | Not used | Relays without NIP-70 support silently reject events containing it. Content is already NIP-44 encrypted and d-tags are HMAC-obfuscated — marginal privacy benefit doesn't justify relay compatibility risk |
| EncryptedSharedPreferences | Not used | ESP is deprecated (security-crypto 1.1.0-alpha07), has known Tink keyset corruption bugs on Samsung S24/Android 14. NIP-44-wrapped key blob in DataStore-Preferences (same store as `UserPreferences`) is simpler and more reliable. See §11.2 for the scope-boundary of this rule |
| Multi-device backup | Not supported in v0.1.3 | Single-device only. Two devices backing up simultaneously create orphaned blobs. Future: per-device d-tags (`cruxcoach/backup/{device-uuid}`) |
| DataKey rotation | Not in v0.1.3 | Mathematically safe for this volume (NIST 2^64-block limit). Optional annual rotation can be added later |
| Blossom content-type | Runtime BUD-06 preflight, cached per server, self-healing | Dev-time compatibility matrix is fragile (servers change policies). On first upload per server, HEAD-probe `/upload` with `X-Content-Type: application/octet-stream`; cache result. On 415, retry with `application/x-cruxcoach-backup`; if still rejected, mark `incompatible` and skip. Re-probe once per backup cycle to recover. No Blossom endpoint list is frozen at ship time |
| Previous blob SHA-256 source | DataStore-Preferences, written atomically after each successful pointer publish | Zero extra round-trips during cleanup — no relay fetch, no HEAD walk. On fresh install (cache miss), cleanup is a no-op; the one orphaned blob is reconciled by server retention or next health-check. The cache is authoritative because it always reflects *this* device's last write |

---

## 17. Test Plan

All tests live under
`androidApp/src/test/java/com/cruxcoach/android/nostr/backup/`. JUnit4 +
`kotlinx-coroutines-test` + MockK, matching the style of
`UserPreferencesAnnouncementCategoryTest.kt`.

### 17.1 Pure-function unit tests

| Test class | Coverage |
|------------|----------|
| `BackupCryptoTest` | AES-256-GCM round-trip; `decrypt(encrypt(x)) == x`; different IV per `encrypt` call; tampered ciphertext throws; tampered IV throws; wrong key throws; empty-input handling |
| `BackupCompressionTest` | gzip round-trip on small / medium / realistic payloads; decompress rejects non-gzip bytes; compression ratio sanity check (JSON → <50% size) |
| `HkdfSha256Test` | RFC 5869 test vectors (Test Case 1: SHA-256 basic; Test Case 2: longer inputs; Test Case 3: zero-length salt); 32-byte output length; domain-separation via different `info` |
| `DTagDeriverTest` | HMAC path is deterministic for same nsec; different nsec → different d-tag; output is 64 hex chars; Schnorr-path returns cached value on second call (via fake signer); restore path returns null if no cache + Amber |
| `BackupPointerSerializationTest` | `BackupPointer` JSON round-trip; unknown future fields tolerated; malformed JSON throws with clear message |

### 17.2 Integration tests (Fakes)

`FakeNostrSigner`, `FakeBlossomServer` (using OkHttp `MockWebServer`),
`FakeNostrRelayPool`. No real network, no real DataStore — in-memory fakes
throughout.

| Test class | Scenario |
|------------|----------|
| `BackupPipelineTest` | End-to-end `performFullBackup` with 3 Blossom servers; all accept → sha256 correctly recorded; 2 accept + 1 reject (415) → pipeline continues; all reject → throws `BackupException`, pointer NOT published, previous pointer sha unchanged |
| `PointerOrderingInvariantTest` | **Critical**: simulate Blossom-verify failure after successful upload → assert pointer event is never published; previous pointer sha remains the stored value from prior backup |
| `RestoreQueryAllAmberTest` | Amber user, fresh install, no local d-tag cache → restore queries all Kind 30078 by pubkey (no d-tag filter); correctly identifies CruxCoach events by decrypted `version` field; handles ≤10 noise events |
| `RestoreHmacLocalKeyTest` | Local-key user, HMAC path → `checkForBackup` filters on exact d-tag, finds pointer + key, decrypts pointer metadata |
| `RestoreSha256MismatchTest` | Blossom serves wrong bytes → `verifySha256` throws; next server in list attempted; final failure surfaces `BackupException` |
| `BlossomContentTypePreflightTest` | Server responds 415 on first upload → retry with `application/x-cruxcoach-backup` content-type; server now accepts → cached as `rejected_octet`; second cycle same server → skip preflight |
| `BlobCleanupTest` | After successful pointer publish, DELETE is issued for previous sha to all configured servers; DELETE failure is silent (not retried); missing `previous_sha256` → no DELETE issued |
| `KeyCacheCorruptionTest` | Corrupt wrapped dataKey in DataStore → `getOrCreateDataKey()` re-fetches key event from relays, unwraps, overwrites cache; no user-visible error |

### 17.3 BackupSyncWorker behavior

`BackupSyncWorkerTest` using `WorkManagerTestInitHelper`:

- `Result.success` on clean run
- `Result.retry` on `BackupException`
- `Result.retry` on `NetworkException`
- `Result.failure` only when dataKey is unrecoverable AND user opted out
  (never during normal operation — retry is always safer)
- Exponential backoff respected (30 min initial)

### 17.4 Opt-out flow

`BackupOptOutTest`:

- Flip `backupEnabled` to `false` → WorkManager cancels `backup_sync_periodic`
- Opt-out with "delete remote data" flag → Kind 5 deletion events published
  for pointer + key d-tags; Blossom DELETE issued for last-known sha
- Opt-out without deletion flag → local cache cleared, remote data untouched
- Re-enable after passive opt-out → next cycle reuses existing dataKey if
  local cache survived, otherwise unwraps from surviving key event

### 17.5 Out of scope for unit tests

- Real Amber IPC — covered only by manual device testing during release QA
- Real Blossom servers — manual smoke test against `blossom.primal.net` +
  `blossom.nostr.build` during release QA
- Actual WorkManager scheduling over real system alarms (covered only by
  instrumented tests, optional for this release)

---

## 18. Telemetry

Matching FEAT-001's style: no analytics library, structured `Log` events
with a stable tag and key/value payload.

Tag: `TAG = "BackupSync"`. Shape:

```
BackupSync: event=<event-name> key1=<val1> key2=<val2>
```

### 18.1 Events

| Event | Level | Fields | Emitted by |
|-------|-------|--------|------------|
| `backup_scheduled` | `Log.d` | `interval={DAILY|WEEKLY}` | `BackupSyncWorker.schedule` |
| `backup_cancelled` | `Log.d` | `reason={disabled|manual}` | `BackupSyncWorker.schedule` |
| `backup_start` | `Log.d` | `trigger={periodic|manual}`, `signerMode={LOCAL|AMBER}` | `performFullBackup` entry |
| `backup_upload_ok` | `Log.d` | `serversOk`, `serversTotal`, `sizeKb`, `durationMs` | after Blossom upload step |
| `backup_upload_partial` | `Log.w` | `serversOk`, `serversTotal`, `sizeKb` | if >=1 but not all servers accepted |
| `backup_upload_failed` | `Log.w` | `serversTotal`, `lastError` | all servers rejected → `Result.retry` |
| `backup_verify_failed` | `Log.w` | `serversTotal` | HEAD-verify found blob nowhere → `Result.retry` |
| `backup_pointer_published` | `Log.d` | `writeRelayCount` | after pointer event publish |
| `backup_cleanup` | `Log.d` | `previousShaPresent`, `serversCleaned` | after blob cleanup step |
| `backup_healthcheck` | `Log.d` | `serversMissing`, `reuploaded` | after health check |
| `backup_done` | `Log.d` | `totalDurationMs` | pipeline success |
| `restore_check_start` | `Log.d` | `signerMode` | `checkForBackup` entry |
| `restore_check_hit` | `Log.d` | `sizeKb`, `ageHours` | backup found |
| `restore_check_miss` | `Log.d` | `reason={no-pointer|no-key|timeout}` | nothing to restore |
| `restore_download_ok` | `Log.d` | `sha256Prefix`, `durationMs` | blob downloaded + verified |
| `restore_download_failed` | `Log.w` | `serversTried`, `lastError` | restore aborts |
| `restore_done` | `Log.d` | `rowsImported`, `durationMs` | restore pipeline success |
| `content_type_probe` | `Log.d` | `server`, `result={accepted|rejected_octet|incompatible}` | BUD-06 preflight |
| `key_cache_miss` | `Log.w` | `reason={empty|corrupt}` | dataKey unwrap fallback |
| `killswitch_off` | `Log.i` | — | logged once per process if flag is false |

### 18.2 PII stance

No event payload contains backup content, full SHA-256 values, or relay
URLs. Sizes are rounded to the nearest KB. `sha256Prefix` is the first
8 hex chars — enough to correlate events for the same backup cycle without
identifying the user across logs.

### 18.3 Derived measurements

From logs alone:
- Backup success rate: `backup_done / backup_start`
- Upload partial rate: `backup_upload_partial / backup_upload_ok+partial`
- Restore success rate: `restore_done / restore_check_hit`
- Amber vs local key split (from `signerMode` field)
- Median upload duration (from `backup_upload_ok.durationMs`)

---

## 19. Rollout & Kill-Switch

### 19.1 Feature flag

```kotlin
// In PreferenceKeys:
val BACKUP_FEATURE_ENABLED = booleanPreferencesKey("backup_feature_enabled")
```

Default: `true`. Exposed via `UserPreferences.backupFeatureEnabled: Flow<Boolean>`
with setter `setBackupFeatureEnabled(enabled: Boolean)`. Distinct from the
user-facing `backupEnabled` toggle (§7.1) — that is the user's choice, this
is the dev-side kill-switch.

When `backupFeatureEnabled = false`:
- `BackupSyncWorker.schedule` short-circuits and cancels any existing work
- `NOSTR_BACKUP` onboarding step is skipped (treated as already-seen)
- Settings section (§12.2) is hidden entirely
- In-flight `performFullBackup` calls throw `BackupFeatureDisabled` and
  return `Result.failure` (no retry — the feature is gone)

Re-enabling the flag does not automatically resume backups — the user's
`backupEnabled` toggle is authoritative for scheduling.

### 19.2 Rollout sequence

- **0.1.3-dev.\*** CI prereleases: flag `true`, internal testing across
  Amber and local-key paths
- **0.1.3 stable**: flag `true`; monitored via telemetry (§18)
- Metrics-to-watch during first week: backup success rate, Amber-path
  failure rate, pointer-vs-blob ordering invariant holds (if `backup_verify_failed`
  ever co-occurs with `backup_pointer_published` in the same cycle, that's
  a priority-1 bug)

No staged percentage rollout (Zapstore doesn't offer rings; user base is small).

### 19.3 Kill-switch triggers

Conditions that warrant flipping the flag off via a 0.1.3.x patch:
- NIP-44 decryption failure rate climbs due to a Quartz regression
- Blossom upload consistently fails for >50% of users (server-side change)
- SHA-256 mismatch appears in real user logs (indicates blob integrity bug)
- Pipeline ordering invariant violated (pointer published without verified
  blob — see §17.2)

Mitigation paths:
1. Ship 0.1.3.x with flag default `false` — quickest rollback
2. Developer DM users the "turn off backup" guidance via DevContact
3. Ship 0.1.3.y with the problem fix and flag back to `true`

---

## 20. Opt-Out Lifecycle

When the user flips `backupEnabled` from **on** to **off** in Settings
(§12.2), FEAT-002 handles two distinct scenarios:

### 20.1 Passive opt-out (default)

Settings toggle flip alone, no further confirmation:

1. `BackupSyncWorker.schedule(enabled = false)` cancels `backup_sync_periodic`
2. `backupEnabled = false` persisted to DataStore
3. Local caches (wrapped dataKey, d-tag, previous-blob-sha) are **kept**
4. Remote data (blob + pointer + key events) is **kept**
5. Emits `backup_cancelled reason=disabled`

Rationale: re-enabling is a single toggle and must be seamless. Users often
disable backups temporarily (low data plan, travel, etc.) and expect to
resume without losing history. Preserving the remote data makes re-enable
a no-op plus one fresh cycle.

### 20.2 Active opt-out — "Delete remote backups"

Settings offers a destructive second action, hidden behind a confirmation
dialog: *"Alle Remote-Backups löschen"*. Flow:

```kotlin
suspend fun deleteRemoteBackups() {
    // 1. Publish Kind 5 deletion events for pointer + key d-tags
    val backupDTag = getCachedBackupDTag() ?: return   // nothing to delete
    val keyDTag = getCachedKeyDTag() ?: return

    val deletion = Event(
        kind = 5,
        tags = listOf(
            listOf("e", lastPointerEventId ?: ""),
            listOf("e", lastKeyEventId ?: ""),
            listOf("k", "30078"),
        ),
        content = "backup opt-out",
    )
    pool.writeRelays().forEach { pool.sendEvent(deletion.signedBy(signer)) }

    // 2. DELETE blob from all configured Blossom servers
    val previousSha = getCurrentPointerSha256()
    if (previousSha != null) {
        cleanupPreviousBlob(previousSha, fetchBlossomServers(signer.pubKey), signer)
    }

    // 3. Clear local caches
    clearWrappedDataKey()
    clearDTagCache()
    clearPreviousPointerSha()

    // 4. Disable feature (same as passive opt-out)
    userPreferences.setBackupEnabled(false)
}
```

Caveats the confirmation dialog must surface:
- Kind 5 deletion is a *request*, not a guarantee — relays that don't honor
  NIP-09 keep the events until their own retention policy expires
- Blossom `DELETE` is best-effort; blobs the user uploaded to servers not in
  the current list (e.g. after server-list changes) are unreachable here
- Once deleted, restore on a new device is impossible — the user must have
  already exported a local JSON backup if they want a safety net

Emits `backup_cancelled reason=manual` plus one `backup_cleanup` event per
server-and-kind touched.

### 20.3 Re-enable after opt-out

After passive opt-out, toggle-on restores scheduling. The next worker run
reuses the cached wrapped dataKey, derives the same d-tags, and publishes
a fresh pointer + blob. Restore history is preserved.

After active opt-out, toggle-on requires a fresh setup: new dataKey, new
wrap, new first upload. The previous pointer event (if not actually deleted
by relays) still exists but has no referenced blob; the next cycle
overwrites it via the replaceable-event semantics of Kind 30078.
