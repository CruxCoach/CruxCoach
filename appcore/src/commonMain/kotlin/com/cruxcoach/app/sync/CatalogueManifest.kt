package com.cruxcoach.app.sync

import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrEvents
import com.cruxcoach.app.platform.Hashing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Signed kind-30078 catalogue manifest; same wire shape as Android's `BlossomManifest`. */
@Serializable
data class CatalogueManifest(
    val v: Int,
    val board: String,
    val source: String? = null,
    @SerialName("media_schema") val mediaSchema: Int? = null,
    @SerialName("product_id") val productId: Int? = null,
    @SerialName("created_at") val createdAt: Long,
    val compression: String,
    val chunks: List<CatalogueChunk>,
    /** Envelope metadata, not part of the signed content JSON; orders relay answers (NIP-01). */
    @Transient val eventCreatedAt: Long = 0,
    @Transient val eventId: String = "",
)

@Serializable
data class CatalogueChunk(
    val name: String,
    val type: String = "unknown",
    val sha256: String,
    val size: Long,
    val urls: List<String>,
)

enum class ManifestRejection {
    MALFORMED_EVENT, WRONG_KIND, WRONG_PUBKEY, BAD_SIGNATURE, WRONG_D_TAG,
    MALFORMED_CONTENT, INVALID_CHUNK, FUTURE_TIMESTAMP,
}

sealed class ManifestVerdict {
    class Accepted(val manifest: CatalogueManifest) : ManifestVerdict()
    class Rejected(val reason: ManifestRejection) : ManifestVerdict()
}

object CatalogueTrust {
    const val MANIFEST_PUBKEY = "70b2740bff77cf65743a7d6ffa5465b3a27105ae26123458cf5450eafb1bd68d"
    const val MANIFEST_KIND = 30078
    const val MAX_FUTURE_SKEW_SECONDS: Long = 60L * 60L

    const val KILTER_D_TAG = "cruxcoach/board-db"
    const val MOONBOARD_D_TAG = "cruxcoach/moonboard-db"
    const val MOONBOARD_BETA_D_TAG = "cruxcoach/moonboard-beta-links"
    const val QUANTUM_D_TAG = "cruxcoach/quantum-db"

    const val KILTER_PREFS = "blossom_sync"
    const val MOONBOARD_PREFS = "blossom_sync_moonboard"
    const val MOONBOARD_BETA_PREFS = "blossom_sync_moonboard_beta"
    const val QUANTUM_PREFS = "blossom_sync_quantum_v2"

    const val BETA_IMPORT_VERSION = "beta_links_v1"

    /** Same list and order as Android's `NostrConfig.MANIFEST_RELAYS`. */
    val MANIFEST_RELAYS: List<String> = listOf(
        "wss://relay.primal.net",
        "wss://relay.damus.io",
        "wss://nostr-pub.wellorder.net",
        "wss://nos.lol",
        "wss://nostr.oxtr.dev",
        "wss://blossom.cruxcoach.org/nostr",
    )

    // Chunk names become file names; the allowlist keeps "../x" out of the work directory.
    private val CHUNK_NAME_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val json = Json { ignoreUnknownKeys = true }

    fun auroraDTag(wireValue: String) = "cruxcoach/$wireValue-db"
    fun auroraPrefs(wireValue: String) = "blossom_sync_$wireValue"

    fun isCreatedAtAcceptable(createdAtSeconds: Long, nowSeconds: Long): Boolean =
        createdAtSeconds <= nowSeconds + MAX_FUTURE_SKEW_SECONDS

    fun effectiveTimestamp(manifest: CatalogueManifest): Long =
        manifest.eventCreatedAt.takeIf { it > 0 } ?: manifest.createdAt

    fun hasAcceptableTimestamps(manifest: CatalogueManifest, nowSeconds: Long): Boolean =
        isCreatedAtAcceptable(effectiveTimestamp(manifest), nowSeconds) &&
            isCreatedAtAcceptable(manifest.createdAt, nowSeconds)

    /** Rollback decision; a null watermark means this track has never synced. */
    fun isManifestAcceptable(manifest: CatalogueManifest, lastAccepted: Long?, nowSeconds: Long): Boolean =
        hasAcceptableTimestamps(manifest, nowSeconds) &&
            (lastAccepted == null || effectiveTimestamp(manifest) >= lastAccepted)

    /** NIP-01 replaceable ordering: newest `created_at`, then the lower event id. */
    fun selectPreferred(manifests: Iterable<CatalogueManifest>): CatalogueManifest? =
        manifests.reduceOrNull { selected, candidate ->
            val selectedAt = effectiveTimestamp(selected)
            val candidateAt = effectiveTimestamp(candidate)
            val candidateWins = candidateAt > selectedAt ||
                (candidateAt == selectedAt && candidate.eventId.isNotBlank() &&
                    (selected.eventId.isBlank() || candidate.eventId < selected.eventId))
            if (candidateWins) candidate else selected
        }

    fun isValidChunk(chunk: CatalogueChunk): Boolean =
        CHUNK_NAME_REGEX.matches(chunk.name) && chunk.urls.any { it.startsWith("https://") }

    fun isBetaChunk(chunk: CatalogueChunk): Boolean =
        if (chunk.type.isEmpty() || chunk.type == "unknown") chunk.name.startsWith("beta") else chunk.type == "beta"

    /** v1 manifests carry no chunk type; fall back to the name. */
    fun resolvedType(chunk: CatalogueChunk): String =
        chunk.type.takeIf { it != "unknown" && it.isNotEmpty() } ?: when {
            chunk.name == "meta" -> "meta"
            chunk.name.startsWith("climbs") -> "climbs"
            chunk.name.startsWith("stats") -> "stats"
            chunk.name.startsWith("locations") -> "locations"
            chunk.name.startsWith("beta") -> "beta"
            else -> "unknown"
        }

    fun parseContent(content: String): CatalogueManifest? = try {
        json.decodeFromString(CatalogueManifest.serializer(), content)
    } catch (e: Exception) {
        null
    }

    /**
     * The relay's filter is never trusted: author, signature + id binding,
     * d-tag, content shape, chunk names/URLs and the future bound are all
     * re-checked here before anything from the event is used.
     */
    fun acceptEvent(hashing: Hashing, eventJson: JsonElement, dTag: String, nowSeconds: Long): ManifestVerdict {
        val event = NostrEvent.fromJson(eventJson) ?: return reject(ManifestRejection.MALFORMED_EVENT)
        if (event.pubkey != MANIFEST_PUBKEY) return reject(ManifestRejection.WRONG_PUBKEY)
        if (event.kind != MANIFEST_KIND) return reject(ManifestRejection.WRONG_KIND)
        if (!NostrEvents.verify(hashing, event)) return reject(ManifestRejection.BAD_SIGNATURE)
        if (event.firstTagValue("d") != dTag) return reject(ManifestRejection.WRONG_D_TAG)
        val parsed = parseContent(event.content) ?: return reject(ManifestRejection.MALFORMED_CONTENT)
        if (!parsed.chunks.all(::isValidChunk)) return reject(ManifestRejection.INVALID_CHUNK)
        val candidate = parsed.copy(eventCreatedAt = event.createdAt, eventId = event.id)
        if (!hasAcceptableTimestamps(candidate, nowSeconds)) return reject(ManifestRejection.FUTURE_TIMESTAMP)
        return ManifestVerdict.Accepted(candidate)
    }

    private fun reject(reason: ManifestRejection) = ManifestVerdict.Rejected(reason)
}
