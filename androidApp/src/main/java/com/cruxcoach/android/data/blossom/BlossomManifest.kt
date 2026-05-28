package com.cruxcoach.android.data.blossom

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BlossomManifest(
    val v: Int,
    val board: String,
    /**
     * Aurora product ID — Kilter-only. The MoonBoard catalogue manifest
     * has no Aurora product, so this field is absent there: nullable so
     * the same parser serves both `cruxcoach/board-db` and
     * `cruxcoach/moonboard-db` manifests.
     */
    @SerialName("product_id") val productId: Int? = null,
    @SerialName("created_at") val createdAt: Long,
    val compression: String,
    val chunks: List<BlossomChunk>
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
