package com.cruxcoach.android.data.blossom

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.nostr.NostrEventPolicy
import com.cruxcoach.android.util.ZstdNative
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verifyId
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * Downloads board database chunks from Nostr Blossom servers.
 *
 * Flow:
 * 1. Fetch Kind 30078 manifest event from Nostr relays (direct WebSocket, not [NostrRelayPool])
 * 2. Compare chunk SHA-256 hashes against stored hashes (incremental updates)
 * 3. Download changed chunks via HTTP GET (public, no auth)
 * 4. Verify SHA-256, decompress zstd, produce temp SQLite files
 */
class BlossomSyncManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    /**
     * Kind-30078 d-tag this instance fetches. Defaults to the Kilter
     * board-DB manifest; FEAT-027 constructs a second instance with
     * [MOONBOARD_D_TAG] for the MoonBoard catalogue. The fetch /
     * download / verify / decompress logic is otherwise board-agnostic.
     */
    private val manifestDTag: String = MANIFEST_D_TAG,
    /**
     * SharedPreferences file backing this instance's chunk-hash state.
     * Per-board so a MoonBoard sync / [clearStoredHashes] can never wipe
     * the Kilter chunk hashes. Kilter keeps the original file name
     * ([DEFAULT_PREFS_NAME]) so existing installs retain their
     * incremental-sync state across the upgrade.
     */
    prefsName: String = DEFAULT_PREFS_NAME,
    /** Injectable wall clock for future-skew trust-boundary tests. */
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetches the manifest from Nostr relays. Uses dedicated short-lived
     * WebSockets to avoid coupling with the app's relay pool lifecycle.
     *
     * Queries all relays in parallel and returns the NIP-01-preferred manifest:
     * highest event `created_at`, then lowest event id on an exact timestamp
     * tie. Kind 30078 is a parameterized-replaceable event, so
     * different relays may serve different versions (e.g. a relay that was
     * offline during the last publish still holds yesterday's manifest).
     * Picking first-success would deterministically pin us to the slowest-
     * updating relay and defeat every fresh publish.
     */
    suspend fun fetchManifest(): BlossomManifest = withContext(Dispatchers.IO) {
        fetchManifestWithRetry(manifestDTag) { relayUrl -> fetchManifestFromRelay(relayUrl) }
    }


    private suspend fun fetchManifestFromRelay(relayUrl: String): BlossomManifest? {
        return withTimeout(RELAY_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val filter = """{"kinds":[30078],"authors":["$MANIFEST_PUBKEY"],"#d":["$manifestDTag"],"limit":1}"""
                val reqMsg = """["REQ","blossom-manifest",$filter]"""

                val request = Request.Builder().url(relayUrl).build()
                val ws = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                    private var result: BlossomManifest? = null

                    override fun onOpen(ws: WebSocket, response: Response) {
                        ws.send(reqMsg)
                    }

                    override fun onMessage(ws: WebSocket, text: String) {
                        try {
                            val arr = json.parseToJsonElement(text).jsonArray
                            when (arr[0].jsonPrimitive.content) {
                                "EVENT" -> {
                                    // Do not trust the relay's filter: re-verify
                                    // pubkey, Schnorr signature, and d-tag on the
                                    // event before parsing its content as a manifest.
                                    val event = Event.fromJson(arr[2].toString())
                                    if (event.pubKey != MANIFEST_PUBKEY) {
                                        Log.w(TAG, "Manifest pubkey mismatch from $relayUrl: ${event.pubKey}")
                                        return
                                    }
                                    if (!event.verifySignature()) {
                                        Log.w(TAG, "Manifest signature invalid from $relayUrl")
                                        return
                                    }
                                    // Bind the signature to the body: verifyId
                                    // recomputes the event id from its serialized
                                    // content, so a relay cannot substitute the
                                    // manifest body under a validly-signed id.
                                    if (!event.verifyId()) {
                                        Log.w(TAG, "Manifest id/content mismatch from $relayUrl")
                                        return
                                    }
                                    // A non-compliant relay could return any
                                    // Kind-30078 from MANIFEST_PUBKEY — e.g. the
                                    // sibling manifest of the other board
                                    // (Kilter <-> MoonBoard share the signing
                                    // key, only the d-tag differs). Reject any
                                    // event whose d-tag is not the one this
                                    // instance asked for, rather than relying on
                                    // the incidental shape of BlossomManifest to
                                    // fail the parse.
                                    val dTag = Companion.extractDTag(event.tags)
                                    if (dTag != manifestDTag) {
                                        Log.w(TAG, "Manifest d-tag mismatch from $relayUrl: $dTag")
                                        return
                                    }
                                    val parsed = json.decodeFromString<BlossomManifest>(event.content)
                                    val candidate = Companion.validateManifest(parsed).copy(
                                        eventCreatedAt = event.createdAt,
                                        eventId = event.id,
                                    )
                                    if (!Companion.hasAcceptableTimestamps(candidate, nowSeconds())) {
                                        Log.w(
                                            TAG,
                                            "Manifest timestamp too far in the future from $relayUrl: " +
                                                "event=${candidate.eventCreatedAt} content=${candidate.createdAt}",
                                        )
                                        return
                                    }
                                    result = candidate
                                }
                                "EOSE" -> {
                                    ws.close(1000, "done")
                                    if (cont.isActive) {
                                        cont.resume(result)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error parsing relay message from $relayUrl", e)
                            ws.close(1000, "parse-error")
                            if (cont.isActive) {
                                cont.resumeWithException(e)
                            }
                        }
                    }

                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                BlossomSyncException("WebSocket failure for $relayUrl: ${t.message}", t)
                            )
                        }
                    }

                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        if (cont.isActive) {
                            cont.resume(result)
                        }
                    }
                })

                cont.invokeOnCancellation {
                    ws.cancel()
                }
            }
        }
    }

    /**
     * Returns chunk names that have changed compared to stored hashes.
     * On first run, all chunks are returned.
     *
     * A stale signed manifest is a normal no-update result: keep the verified
     * catalogue already on disk and do not expose any of its rollback hashes
     * to callers. Callers also check [canApplyManifest] before auxiliary
     * side-effects, but this guard keeps a future call site fail-safe.
     */
    fun getChangedChunks(manifest: BlossomManifest): List<BlossomChunk> {
        if (!canApplyManifest(manifest)) return emptyList()
        return manifest.chunks.filter { chunk ->
            val storedHash = prefs.getString("chunk_sha256_${chunk.name}", null)
            storedHash != chunk.sha256
        }
    }

    /**
     * Returns whether [manifest] is at least as new as the last manifest this
     * per-board sync track completely applied. Equal timestamps remain valid so
     * an interrupted import can resume from the same signed event.
     */
    fun canApplyManifest(manifest: BlossomManifest): Boolean {
        val lastAccepted = lastAcceptedManifestTimestamp()
        val currentTime = nowSeconds()
        val acceptable = isManifestAcceptable(manifest, lastAccepted, currentTime)
        if (!acceptable) {
            if (!hasAcceptableTimestamps(manifest, currentTime)) {
                Log.w(
                    TAG,
                    "[$manifestDTag] rejected future-dated manifest: " +
                        "event=${manifest.eventCreatedAt} content=${manifest.createdAt}",
                )
            } else {
                Log.w(
                    TAG,
                    "[$manifestDTag] rejected stale manifest: " +
                        "${effectiveTimestamp(manifest)} < accepted $lastAccepted",
                )
            }
        }
        return acceptable
    }

    /**
     * Advances this track's rollback watermark after a complete successful
     * application (or after confirming that every chunk already matches).
     * Never lowers an existing mark, even if a caller accidentally presents a
     * stale manifest after the fail-safe check.
     */
    fun saveAcceptedManifestTimestamp(manifest: BlossomManifest) {
        saveCompletedManifest(manifest, emptyList())
    }

    /**
     * Atomically persists every successfully imported chunk hash together with
     * the completed manifest watermark. Call this only after the importer has
     * applied every changed chunk; partial Kilter runs intentionally continue
     * to use [saveChunkHash] without advancing the watermark.
     */
    fun saveCompletedManifest(
        manifest: BlossomManifest,
        importedChunks: Iterable<BlossomChunk>,
    ) {
        val incoming = effectiveTimestamp(manifest)
        val lastAccepted = lastAcceptedManifestTimestamp()
        if (!hasAcceptableTimestamps(manifest, nowSeconds())) {
            Log.w(
                TAG,
                "[$manifestDTag] refused future-dated completed manifest: " +
                    "event=${manifest.eventCreatedAt} content=${manifest.createdAt}",
            )
        } else if (lastAccepted == null || incoming >= lastAccepted) {
            val editor = prefs.edit()
            importedChunks.forEach { chunk ->
                editor.putString("chunk_sha256_${chunk.name}", chunk.sha256)
            }
            editor.putLong(KEY_LAST_MANIFEST_CREATED_AT, incoming).apply()
        } else {
            Log.w(
                TAG,
                "[$manifestDTag] refused to lower manifest watermark: " +
                    "$incoming < accepted $lastAccepted"
            )
        }
    }

    internal fun lastAcceptedManifestTimestamp(): Long? {
        if (!prefs.contains(KEY_LAST_MANIFEST_CREATED_AT)) return null
        val stored = prefs.getLong(KEY_LAST_MANIFEST_CREATED_AT, 0L)
        if (NostrEventPolicy.isCreatedAtAcceptable(stored, nowSeconds())) return stored

        // Upgrade repair for clients that accepted an unbounded future
        // envelope before 0.2.2. Remove only the poisoned ordering floor;
        // retained chunk hashes still prevent unnecessary downloads, and the
        // next safe signed manifest can establish a corrected watermark.
        Log.w(TAG, "[$manifestDTag] removing implausibly future stored manifest watermark: $stored")
        prefs.edit().remove(KEY_LAST_MANIFEST_CREATED_AT).apply()
        return null
    }

    /**
     * Downloads a chunk, verifies SHA-256, decompresses zstd, and writes the
     * resulting raw SQLite data to [outputFile].
     *
     * @param onProgress called with (bytesDownloaded, totalBytes)
     */
    suspend fun downloadAndDecompressChunk(
        chunk: BlossomChunk,
        outputFile: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Unit = withContext(Dispatchers.IO) {
        val compressedFile = File(context.cacheDir, "blossom_${chunk.name}.zst")
        try {
            downloadAndVerifyChunk(chunk, compressedFile, onProgress)
            decompressZstd(compressedFile, outputFile)
        } finally {
            compressedFile.delete()
        }
    }

    /**
     * Downloads a chunk and verifies its SHA-256 against the manifest's
     * declared hash. Iterates the mirror list up to [DOWNLOAD_PASSES] times
     * with backoff; a corrupted response from one mirror fails over to the
     * next instead of poisoning the chunk.
     *
     * Hash verification lives inside the loop on purpose: a truncated or
     * malformed body counts as a mirror failure, not a chunk-level error.
     */
    private suspend fun downloadAndVerifyChunk(
        chunk: BlossomChunk,
        targetFile: File,
        onProgress: ((Long, Long) -> Unit)?
    ) {
        // https-only: refuse cleartext URLs so a hostile manifest cannot
        // downgrade chunk transport to MITM-able http://.
        val httpsUrls = chunk.urls.filter { it.startsWith("https://") }
        if (httpsUrls.isEmpty()) {
            throw BlossomSyncException("No https:// URL for chunk ${chunk.name}")
        }

        // Two passes through the mirror list. Most transient failures
        // (5xx blip, brief socket reset, rate-limit window flip) clear
        // within a second, so retrying once across all mirrors recovers
        // the common case without ever surfacing the error to the user.
        var lastError: Throwable? = null
        for (pass in 0 until DOWNLOAD_PASSES) {
            for ((mirrorIdx, url) in httpsUrls.withIndex()) {
                try {
                    downloadChunkFromUrl(chunk, url, targetFile, onProgress)
                    verifyHash(targetFile, chunk.sha256)
                    return
                } catch (e: Exception) {
                    lastError = e
                    Log.w(
                        TAG,
                        "Chunk ${chunk.name} mirror ${mirrorIdx + 1}/${httpsUrls.size} " +
                            "(pass ${pass + 1}/$DOWNLOAD_PASSES) failed: ${e.message}"
                    )
                }
            }
            if (pass < DOWNLOAD_PASSES - 1) {
                // Linear backoff with jitter — enough to clear a transient
                // server-side rate-limit window without dragging the sync.
                val backoffMs = DOWNLOAD_BACKOFF_BASE_MS +
                    Random.nextLong(DOWNLOAD_BACKOFF_JITTER_MS)
                delay(backoffMs)
            }
        }
        throw BlossomSyncException(
            "All ${httpsUrls.size} mirror(s) failed for chunk ${chunk.name}",
            lastError
        )
    }

    private fun downloadChunkFromUrl(
        chunk: BlossomChunk,
        url: String,
        targetFile: File,
        onProgress: ((Long, Long) -> Unit)?
    ) {
        val request = Request.Builder().url(url).build()
        val response = okHttpClient.newCall(request).execute()
        response.use { r ->
            if (!r.isSuccessful) {
                throw BlossomSyncException("HTTP ${r.code} downloading chunk ${chunk.name}")
            }

            val body = r.body
                ?: throw BlossomSyncException("Empty response body for chunk ${chunk.name}")

            val totalBytes = chunk.size
            // Hard ceiling = declared size + small margin for protocol framing.
            // SHA-256 verification only rejects the stored file; it does not
            // stop an over-long stream from filling cacheDir first. Abort as
            // soon as the declared size is exceeded so a hostile CDN can't
            // disk-fill.
            val maxAllowedBytes = totalBytes + CHUNK_SIZE_OVERRUN_MARGIN
            var bytesRead = 0L
            // Throttle progress emissions: an unfiltered callback fires
            // hundreds of times per second per chunk and floods the
            // StateFlow / UI recomposition pipeline. The cumulative
            // counter in BoardSyncManager keeps the UI fresh across
            // chunk boundaries even if the last per-chunk emit is
            // dropped, so missing the final tick is harmless.
            var lastEmitMs = 0L

            body.byteStream().use { input ->
                BufferedOutputStream(FileOutputStream(targetFile), DOWNLOAD_BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        bytesRead += read
                        if (bytesRead > maxAllowedBytes) {
                            throw BlossomSyncException(
                                "Chunk ${chunk.name} exceeded declared size " +
                                    "($bytesRead > $totalBytes + margin)"
                            )
                        }
                        output.write(buffer, 0, read)
                        if (onProgress != null) {
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= PROGRESS_THROTTLE_MS) {
                                lastEmitMs = now
                                onProgress(bytesRead, totalBytes)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun verifyHash(file: File, expectedSha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualHash != expectedSha256) {
            throw BlossomSyncException(
                "SHA-256 mismatch for ${file.name}: expected $expectedSha256, got $actualHash"
            )
        }
    }

    private fun decompressZstd(compressedFile: File, outputFile: File) {
        // Cap the decompressed output so a maliciously-crafted chunk (zstd bomb)
        // cannot fill the disk. 512MB is ~5x the full legitimate board DB and
        // well above any plausible future growth.
        ZstdNative.decompressFile(compressedFile, outputFile, MAX_DECOMPRESSED_CHUNK_BYTES)
    }

    /** Saves chunk hash after successful import so future syncs can skip unchanged chunks. */
    fun saveChunkHash(chunkName: String, sha256: String) {
        prefs.edit().putString("chunk_sha256_$chunkName", sha256).apply()
    }

    /**
     * Clears chunk hashes and the coupled manifest watermark, forcing a full
     * re-download and deliberately re-arming first-run manifest acceptance.
     */
    fun clearStoredHashes() {
        prefs.edit().clear().apply()
    }

    /** Returns true if any Blossom sync has been performed before. */
    fun hasEverSynced(): Boolean {
        return prefs.contains("chunk_sha256_climbs")
    }

    companion object {

        /** One relay's answer to a manifest query: a hit, a miss, or a failure. */
        private data class RelayOutcome(
            val relayUrl: String,
            val manifest: BlossomManifest?,
            val error: Throwable?,
        )

        /**
         * Queries the relay set with bounded retries and returns the freshest
         * manifest found.
         *
         * A single pass was not enough in practice. A fresh install syncs seven
         * catalogues back to back, each doing its own manifest query, and a
         * catalogue whose one query happened to land in a bad window failed for
         * the whole run while its siblings succeeded — the "some boards fail on
         * first download" report. Two things make that window likelier than it
         * looks: public relays return transient 503s and connect timeouts (both
         * observed on relay.damus.io and nostr-pub.wellorder.net), and a publish
         * that only reached 2 of 3 relays leaves the third legitimately empty, so
         * a single failure among the remaining two is already enough.
         *
         * The chunk-download path has retried across mirrors since day one
         * ([DOWNLOAD_PASSES]); this closes the same gap on the metadata path.
         *
         * Split out from [fetchManifest] and parameterised so the retry behaviour
         * is unit-testable without a live relay.
         */
        internal suspend fun fetchManifestWithRetry(
            dTag: String,
            relayUrls: List<String> = NostrConfig.MANIFEST_RELAYS,
            passes: Int = MANIFEST_FETCH_PASSES,
            fetchOne: suspend (String) -> BlossomManifest?,
        ): BlossomManifest {
            var lastError: Throwable? = null

            for (pass in 0 until passes) {
                val outcomes = coroutineScope {
                    relayUrls.map { relayUrl ->
                        async {
                            try {
                                RelayOutcome(relayUrl, fetchOne(relayUrl), null)
                            } catch (e: TimeoutCancellationException) {
                                // withTimeout's own signal — this relay was slow,
                                // not the whole sync being cancelled.
                                RelayOutcome(relayUrl, null, e)
                            } catch (e: CancellationException) {
                                // Real cancellation (user left the screen) must
                                // propagate; swallowing it would keep the sync
                                // running after its scope is gone.
                                throw e
                            } catch (e: Exception) {
                                RelayOutcome(relayUrl, null, e)
                            }
                        }
                    }.awaitAll()
                }

                // Log hit / miss / error separately: a relay that answers with no
                // event is a replication gap, one that throws is an availability
                // problem. Collapsing both into "failed" made field reports
                // impossible to tell apart.
                for (outcome in outcomes) {
                    when {
                        outcome.error != null -> {
                            lastError = outcome.error
                            Log.w(
                                TAG,
                                "[$dTag] relay ${outcome.relayUrl} failed " +
                                    "(pass ${pass + 1}/$passes): ${outcome.error.message}"
                            )
                        }
                        outcome.manifest == null ->
                            Log.d(TAG, "[$dTag] relay ${outcome.relayUrl} has no manifest event")
                        else ->
                            Log.d(
                                TAG,
                                "[$dTag] relay ${outcome.relayUrl}: " +
                                    "createdAt=${outcome.manifest.createdAt} chunks=${outcome.manifest.chunks.size}"
                            )
                    }
                }

                val newest = selectPreferredManifest(outcomes.mapNotNull { it.manifest })
                if (newest != null) {
                    Log.d(
                        TAG,
                        "[$dTag] selected manifest: eventCreatedAt=${newest.eventCreatedAt} " +
                            "eventId=${newest.eventId.take(12)} createdAt=${newest.createdAt} " +
                            "chunks=${newest.chunks.size} (pass ${pass + 1}/$passes)"
                    )
                    return newest
                }

                if (pass < passes - 1) {
                    // Escalating linear backoff with jitter. Jitter matters here
                    // beyond the usual reason: the catalogues sync one after
                    // another, so without it every board would retry on the same
                    // cadence and hit the same rate-limit window together.
                    val backoffMs = MANIFEST_BACKOFF_BASE_MS * (pass + 1) +
                        Random.nextLong(MANIFEST_BACKOFF_JITTER_MS)
                    delay(backoffMs)
                }
            }

            throw BlossomSyncException(
                "Failed to fetch manifest '$dTag' from any of " +
                    "${relayUrls.size} relay(s) after $passes pass(es)",
                lastError
            )
        }
        private const val TAG = "BlossomSyncManager"
        private const val RELAY_TIMEOUT_MS = 15_000L
        // Slack above the manifest-declared chunk size for HTTP/zstd framing
        // overhead. Generous enough that legitimate chunks are never rejected,
        // tight enough that a hostile server can't stream gigabytes.
        private const val CHUNK_SIZE_OVERRUN_MARGIN = 64L * 1024
        // Absolute ceiling on decompressed chunk size (zstd-bomb guard).
        // Board DB is ~85MB total across all chunks today; 512MB covers
        // years of growth while still refusing any plausible bomb payload.
        private const val MAX_DECOMPRESSED_CHUNK_BYTES = 512L * 1024 * 1024
        // 64 KB matches OkHttp's internal Okio segment chaining well, cuts
        // syscall count ~8x vs. the previous 8 KB buffer, and is small
        // enough to keep peak heap flat under PARALLEL_DOWNLOADS workers.
        private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
        // Min interval between progress emissions per chunk. ~5 Hz keeps
        // the progress bar visibly smooth without thrashing StateFlow
        // under N parallel downloads.
        private const val PROGRESS_THROTTLE_MS = 200L
        // Number of full passes through the mirror list before giving up.
        private const val DOWNLOAD_PASSES = 2

        /**
         * Full passes over the relay set before a manifest fetch is declared
         * failed. Three attempts cost at most ~2s of added backoff on a run
         * that is going to fail anyway, and rescue the far more common case
         * where one pass was simply unlucky.
         */
        internal const val MANIFEST_FETCH_PASSES = 3
        private const val MANIFEST_BACKOFF_BASE_MS = 500L
        private const val MANIFEST_BACKOFF_JITTER_MS = 500L
        // Backoff between retry passes. Linear + jitter is sufficient for
        // the typical "transient 5xx clears within a second" failure mode.
        private const val DOWNLOAD_BACKOFF_BASE_MS = 750L
        private const val DOWNLOAD_BACKOFF_JITTER_MS = 500L
        private const val KEY_LAST_MANIFEST_CREATED_AT = "last_manifest_created_at"
        // Chunk names are joined into filesystem paths; restrict to a strict
        // allowlist so values like "../x" or "a/b" cannot escape cacheDir.
        private val CHUNK_NAME_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

        const val MANIFEST_PUBKEY =
            "70b2740bff77cf65743a7d6ffa5465b3a27105ae26123458cf5450eafb1bd68d"
        const val MANIFEST_D_TAG = "cruxcoach/board-db"
        const val MOONBOARD_D_TAG = "cruxcoach/moonboard-db"
        const val MOONBOARD_BETA_D_TAG = "cruxcoach/moonboard-beta-links"
        const val QUANTUM_D_TAG = "cruxcoach/quantum-db"
        // Per-board SharedPreferences files for chunk-hash state. Kilter
        // keeps the historical "blossom_sync" name so existing installs
        // do not lose their incremental-sync state on upgrade.
        const val DEFAULT_PREFS_NAME = "blossom_sync"
        const val MOONBOARD_PREFS_NAME = "blossom_sync_moonboard"
        const val MOONBOARD_BETA_PREFS_NAME = "blossom_sync_moonboard_beta"

        /**
         * Applies the NIP-01 ordering rule for parameterized-replaceable events.
         * `eventCreatedAt == 0` is retained as a test/backward-compatible
         * fallback for manifests constructed without an envelope.
         */
        internal fun selectPreferredManifest(
            manifests: Iterable<BlossomManifest>,
        ): BlossomManifest? = manifests.reduceOrNull { selected, candidate ->
            val selectedAt = effectiveTimestamp(selected)
            val candidateAt = effectiveTimestamp(candidate)
            val candidateWins = candidateAt > selectedAt ||
                (candidateAt == selectedAt && candidate.eventId.isNotBlank() &&
                    (selected.eventId.isBlank() || candidate.eventId < selected.eventId))
            if (candidateWins) candidate else selected
        }

        /** Timestamp semantics shared by relay selection and rollback defence. */
        internal fun effectiveTimestamp(manifest: BlossomManifest): Long =
            manifest.eventCreatedAt.takeIf { it > 0 } ?: manifest.createdAt

        /** Pure rollback decision seam; `null` means this track has never synced. */
        internal fun isManifestAcceptable(
            manifest: BlossomManifest,
            lastAcceptedCreatedAt: Long?,
            nowSeconds: Long,
        ): Boolean = hasAcceptableTimestamps(manifest, nowSeconds) &&
            (lastAcceptedCreatedAt == null ||
                effectiveTimestamp(manifest) >= lastAcceptedCreatedAt)

        /**
         * Both timestamps cross persistence trust boundaries: the signed-event
         * time orders rollback watermarks, while the manifest-content time
         * seeds the community-climb cursor. A publisher clock mistake must not
         * permanently pin either monotonic value in the future.
         */
        internal fun hasAcceptableTimestamps(
            manifest: BlossomManifest,
            nowSeconds: Long,
        ): Boolean = NostrEventPolicy.isCreatedAtAcceptable(
            effectiveTimestamp(manifest),
            nowSeconds,
        ) && NostrEventPolicy.isCreatedAtAcceptable(
            manifest.createdAt,
            nowSeconds,
        )

        /**
         * Validates chunk names and URL schemes after the manifest is parsed.
         * Rejects anything that could write outside the cache dir or be fetched
         * over cleartext. `internal` for direct unit testing.
         */
        internal fun validateManifest(manifest: BlossomManifest): BlossomManifest {
            manifest.chunks.forEach { chunk ->
                require(CHUNK_NAME_REGEX.matches(chunk.name)) {
                    "Chunk name rejected (path-traversal guard): ${chunk.name}"
                }
                require(chunk.urls.any { it.startsWith("https://") }) {
                    "Chunk ${chunk.name} has no https:// URL"
                }
            }
            return manifest
        }

        /**
         * Extracts the `d` tag value from a Nostr event's tag array, or
         * null if absent / malformed. Used to re-verify the manifest
         * event's d-tag client-side rather than trusting the relay
         * honoured the REQ `#d` filter. `internal` for direct unit testing.
         */
        internal fun extractDTag(tags: Array<Array<String>>): String? =
            tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
    }
}

class BlossomSyncException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
