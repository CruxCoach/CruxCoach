package com.cruxcoach.android.updater

import android.util.Log
import com.cruxcoach.android.BuildConfig
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verifyId
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import java.util.Collections
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Resolves CruxCoach releases from Zapstore's signed NIP-82 events.
 *
 * The relay and CDN are untrusted transports. An asset is exposed only after
 * its event id and Schnorr signature have been verified against the pinned
 * maintainer pubkey, and all package/version/hash/certificate tags agree with
 * this installed app. The selected CDN URL is content-addressed and must be
 * exactly `<configured CDN>/<x-tag>`.
 */
@Singleton
class ZapstoreReleaseClient @Inject constructor(
    @param:Named("updater") private val okHttpClient: OkHttpClient,
    private val pinStore: UpdaterPinStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchReleases(
        packageId: String = BuildConfig.APPLICATION_ID,
        publisherPubkey: String = BuildConfig.MAINTAINER_PUBKEY,
        relayUrl: String = BuildConfig.ZAPSTORE_RELAY_URL,
        cdnBaseUrl: String = BuildConfig.ZAPSTORE_CDN_BASE_URL,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result {
        if (!HEX_64.matches(publisherPubkey)) return Result.Error("invalid_publisher_pubkey")
        val pinnedCert = runCatching { pinStore.getOrTofu().certSha256Hex.lowercase() }
            .getOrElse {
                Log.w(TAG, "Could not read installed signer pin", it)
                return Result.Error("installed_signer_unavailable")
            }

        val events = fetchVerifiedEvents(
            relayUrl = relayUrl,
            packageId = packageId,
            publisherPubkey = publisherPubkey,
            timeoutMs = timeoutMs,
        ) ?: return Result.Error("relay_unavailable")

        val releases = ZapstoreEventParser.parse(
            events = events,
            packageId = packageId,
            pinnedCertSha256 = pinnedCert,
            cdnBaseUrl = cdnBaseUrl,
        )
        return Result.Success(releases)
    }

    private suspend fun fetchVerifiedEvents(
        relayUrl: String,
        packageId: String,
        publisherPubkey: String,
        timeoutMs: Long,
    ): List<VerifiedZapstoreEvent>? {
        val result = CompletableDeferred<List<VerifiedZapstoreEvent>?>()
        val events = Collections.synchronizedList(mutableListOf<VerifiedZapstoreEvent>())
        val subId = "zapstore-${System.nanoTime().toString(16)}"
        val requestMessage = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    put("kinds", buildJsonArray {
                        add(KIND_RELEASE)
                        add(KIND_ASSET)
                    })
                    put("authors", buildJsonArray { add(publisherPubkey) })
                    put("#i", buildJsonArray { add(packageId) })
                    put("limit", MAX_EVENTS)
                },
            )
        }.toString()

        fun snapshot(): List<VerifiedZapstoreEvent> = synchronized(events) { events.toList() }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(requestMessage)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val arr = json.parseToJsonElement(text).jsonArray
                    val type = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return
                    val responseSubId = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull
                    if (responseSubId != subId) return
                    when (type) {
                        "EVENT" -> {
                            val eventJson = arr.getOrNull(2)?.toString() ?: return
                            val event = runCatching { Event.fromJson(eventJson) }.getOrNull() ?: return
                            if (event.pubKey != publisherPubkey ||
                                event.kind !in setOf(KIND_RELEASE, KIND_ASSET) ||
                                !event.verifyId() ||
                                !event.verifySignature()
                            ) {
                                Log.w(TAG, "Rejected forged Zapstore event from relay")
                                return
                            }
                            if (events.size >= MAX_EVENTS) return
                            events += VerifiedZapstoreEvent(
                                id = event.id,
                                kind = event.kind,
                                pubkey = event.pubKey,
                                createdAt = event.createdAt,
                                tags = event.tags.map { it.toList() },
                                content = event.content,
                            )
                        }
                        "EOSE" -> {
                            webSocket.send(buildJsonArray { add("CLOSE"); add(subId) }.toString())
                            webSocket.close(1000, "complete")
                            result.complete(snapshot())
                        }
                        "CLOSED" -> result.complete(null)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not parse Zapstore relay message", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Zapstore relay request failed", t)
                if (!result.isCompleted) result.complete(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!result.isCompleted) result.complete(null)
            }
        }

        val webSocket = try {
            okHttpClient.newWebSocket(Request.Builder().url(relayUrl).build(), listener)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open Zapstore relay", e)
            return null
        }
        return try {
            withTimeout(timeoutMs) { result.await() }
        } catch (_: TimeoutCancellationException) {
            // Only EOSE proves that the relay returned a complete result set.
            // A partial set cannot safely establish "no newer release".
            null
        } finally {
            runCatching { webSocket.cancel() }
        }
    }

    sealed interface Result {
        data class Success(val releases: List<ZapstoreRelease>) : Result
        data class Error(val message: String) : Result
    }

    companion object {
        private const val TAG = "ZapstoreReleaseClient"
        private const val KIND_RELEASE = 30063
        private const val KIND_ASSET = 3063
        private const val MAX_EVENTS = 100
        const val DEFAULT_TIMEOUT_MS = 6_000L
        private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")
    }
}

internal data class VerifiedZapstoreEvent(
    val id: String,
    val kind: Int,
    val pubkey: String,
    val createdAt: Long,
    val tags: List<List<String>>,
    val content: String,
)

data class ZapstoreRelease(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val apkSha256: String,
    val apkSizeBytes: Long,
    val apkCertificateSha256: String,
    val releaseNotesMarkdown: String,
    val publishedAtEpochSeconds: Long,
)

internal object ZapstoreEventParser {
    private const val KIND_RELEASE = 30063
    private const val KIND_ASSET = 3063
    private const val MAX_APK_BYTES = 512L * 1024L * 1024L
    private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")

    fun parse(
        events: List<VerifiedZapstoreEvent>,
        packageId: String,
        pinnedCertSha256: String,
        cdnBaseUrl: String,
    ): List<ZapstoreRelease> {
        val normalizedCdn = cdnBaseUrl.trimEnd('/') + "/"
        val notesByAssetId = events.asSequence()
            .filter { it.kind == KIND_RELEASE && it.tag("i") == packageId }
            .mapNotNull { event ->
                val version = SemVer.parseOrNull(event.tag("version") ?: return@mapNotNull null)
                    ?: return@mapNotNull null
                val assetId = event.tag("e") ?: return@mapNotNull null
                Triple(assetId, version.toString(), event)
            }
            .groupBy { it.first }
            .mapValues { (_, candidates) -> candidates.maxByOrNull { it.third.createdAt }?.third }

        return events.asSequence()
            .filter { it.kind == KIND_ASSET && it.tag("i") == packageId }
            .mapNotNull { event ->
                val version = SemVer.parseOrNull(event.tag("version") ?: return@mapNotNull null)
                    ?: return@mapNotNull null
                val sha = event.tag("x")?.lowercase()?.takeIf(HEX_64::matches)
                    ?: return@mapNotNull null
                val cert = event.tag("apk_certificate_hash")?.lowercase()?.takeIf(HEX_64::matches)
                    ?: return@mapNotNull null
                if (cert != pinnedCertSha256.lowercase()) return@mapNotNull null
                if (event.tag("m") != "application/vnd.android.package-archive") return@mapNotNull null
                if (event.tags.none { it.size >= 2 && it[0] == "f" && it[1] == "android-arm64-v8a" }) {
                    return@mapNotNull null
                }
                val size = event.tag("size")?.toLongOrNull()
                    ?.takeIf { it in 1..MAX_APK_BYTES }
                    ?: return@mapNotNull null
                val versionCode = event.tag("version_code")?.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?: return@mapNotNull null
                val expectedUrl = normalizedCdn + sha
                if (event.tags.none { it.size >= 2 && it[0] == "url" && it[1] == expectedUrl }) {
                    return@mapNotNull null
                }
                val notes = notesByAssetId[event.id]
                    ?.takeIf { SemVer.parseOrNull(it.tag("version").orEmpty()) == version }
                ZapstoreRelease(
                    versionName = version.toString(),
                    versionCode = versionCode,
                    apkUrl = expectedUrl,
                    apkSha256 = sha,
                    apkSizeBytes = size,
                    apkCertificateSha256 = cert,
                    releaseNotesMarkdown = notes?.content.orEmpty(),
                    publishedAtEpochSeconds = maxOf(event.createdAt, notes?.createdAt ?: 0L),
                )
            }
            .groupBy { it.versionName }
            .mapNotNull { (_, candidates) -> candidates.maxByOrNull { it.publishedAtEpochSeconds } }
            .sortedByDescending { SemVer.parseOrNull(it.versionName) }
            .toList()
    }

    private fun VerifiedZapstoreEvent.tag(name: String): String? =
        tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
}
