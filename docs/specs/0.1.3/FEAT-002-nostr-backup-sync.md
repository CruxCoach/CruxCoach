# Feature Spec: Nostr Encrypted Backup & Sync (v0.1.3)

> **Status:** Ready for implementation — all design decisions resolved (§16).
> **Depends on:** FEAT-001 (Nostr Relay Discovery / NIP-65) for the resolved relay pool.

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
| Key management | Random dataKey wrapped via NIP-44 | O(1) Amber calls regardless of data size. DataKey cached locally as NIP-44-wrapped blob in SharedPreferences |
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
4. Cache the d-tag locally (SharedPreferences) — compute once, reuse forever
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
  cache wrappedKey in SharedPreferences (already NIP-44 encrypted, no ESP needed)
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
            Timber.w(e, "Blossom download failed from $server, trying next")
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
                Timber.w("Blob $sha256 missing from $server, re-uploading")
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
(`accepted` / `rejected_octet` / `incompatible`) in SharedPreferences.
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
            Timber.w(e, "Backup failed")
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
    val previousSha256 = getCurrentPointerSha256()  // reads local SharedPreferences
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
from local SharedPreferences (key: `backup_current_pointer_sha256`), written
atomically in step 7 after each successful pointer publish. This gives cleanup
zero extra round-trips — no relay fetch, no Blossom HEAD walk. On fresh install
(cache miss), the function returns `null` and step 8 is a no-op; the single
orphaned blob left behind on the old server is reconciled at its server's
retention policy or by the next health-check cycle if the user adds that
server back. The cache is the authority because it always reflects *this
device's* last write.

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

The NIP-44-wrapped dataKey (ciphertext) is cached in regular SharedPreferences.
The encryption is already handled by NIP-44 — wrapping it again in
EncryptedSharedPreferences (ESP) would add a redundant layer with known
corruption bugs (Samsung S24 / Android 14 Tink keyset corruption causing
`KeyStoreException` crash loops). Plain SharedPreferences storing an already-
encrypted blob is simpler and more reliable.

On cache miss or corruption, the app re-fetches the key event from relays
and unwraps via `signer.nip44Decrypt` (1 Amber call). This makes the local
cache a performance optimization, not a single point of failure.

The d-tags (HMAC-derived) are also cached in SharedPreferences alongside
the wrapped dataKey for Amber users where derivation requires a sign_event
call.

**Scope of this rule — only applies to self-encrypted ciphertext blobs.**
This "no ESP" decision is specific to data that is *already* encrypted by an
external mechanism (NIP-44, AES-GCM with a well-protected key, etc.). It does
NOT apply to plaintext credentials such as OAuth bearer tokens. `KilterTokenStore`
(FEAT-003 §12.1) correctly uses ESP because its stored data — Kilter
access/refresh tokens — is plaintext and needs ESP as the at-rest encryption
boundary. Different threat model, different storage choice. A future refactor
must not harmonize the two paths under a single "SecureStorage" abstraction —
the distinction is load-bearing:

| | Backup dataKey (here) | Kilter tokens (FEAT-003) |
|---|---|---|
| Stored data | NIP-44 ciphertext | Plaintext OAuth tokens |
| Self-protection | Yes, inside the blob | None |
| ESP role if added | Redundant 2nd layer | **The** encryption boundary |
| Corruption recovery | Re-fetch from relays | User re-login |
| ESP risk/benefit | Risk > benefit → avoid | Risk < benefit → use |

---

## 12. UI / UX

### 12.1 Onboarding: Backup Opt-In

During onboarding (after Nostr key setup), a single screen asks whether to
enable backup. Default: **off**.

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
1. **Local cache** in plain SharedPreferences (NIP-44-wrapped ciphertext,
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
| EncryptedSharedPreferences | Not used | ESP is deprecated (security-crypto 1.1.0-alpha07), has known Tink keyset corruption bugs on Samsung S24/Android 14. NIP-44-wrapped key blob in plain SharedPreferences is simpler and more reliable |
| Multi-device backup | Not supported in v0.1.3 | Single-device only. Two devices backing up simultaneously create orphaned blobs. Future: per-device d-tags (`cruxcoach/backup/{device-uuid}`) |
| DataKey rotation | Not in v0.1.3 | Mathematically safe for this volume (NIST 2^64-block limit). Optional annual rotation can be added later |
| Blossom content-type | Runtime BUD-06 preflight, cached per server, self-healing | Dev-time compatibility matrix is fragile (servers change policies). On first upload per server, HEAD-probe `/upload` with `X-Content-Type: application/octet-stream`; cache result. On 415, retry with `application/x-cruxcoach-backup`; if still rejected, mark `incompatible` and skip. Re-probe once per backup cycle to recover. No Blossom endpoint list is frozen at ship time |
| Previous blob SHA-256 source | Local SharedPreferences, written atomically after each successful pointer publish | Zero extra round-trips during cleanup — no relay fetch, no HEAD walk. On fresh install (cache miss), cleanup is a no-op; the one orphaned blob is reconciled by server retention or next health-check. The cache is authoritative because it always reflects *this* device's last write |
