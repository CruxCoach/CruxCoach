package com.cruxcoach.android.data.blossom

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class BlossomManifest(
    val v: Int,
    val board: String,
    /** Optional provenance marker used by independently licensed catalogues. */
    val source: String? = null,
    /**
     * Aurora product ID — Kilter-only. The MoonBoard catalogue manifest
     * has no Aurora product, so this field is absent there: nullable so
     * the same parser serves both `cruxcoach/board-db` and
     * `cruxcoach/moonboard-db` manifests.
     */
    @SerialName("product_id") val productId: Int? = null,
    @SerialName("created_at") val createdAt: Long,
    val compression: String,
    val chunks: List<BlossomChunk>,
    /**
     * Nostr envelope metadata used only while selecting between relay answers.
     * It is deliberately excluded from the signed manifest JSON: NIP-01 orders
     * parameterized-replaceable events by the envelope's `created_at`, then by
     * the lexicographically lower event id when timestamps tie.
     */
    @Transient val eventCreatedAt: Long = 0,
    @Transient val eventId: String = "",
)

@Serializable
data class BlossomChunk(
    val name: String,
    /** Chunk type: "meta", "climbs", or "stats". */
    val type: String = "unknown",
    val sha256: String,
    val size: Long,
    val urls: List<String>
)
