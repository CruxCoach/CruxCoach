package com.cruxcoach.android.aurora

import kotlinx.serialization.Serializable

/**
 * kotlinx-serialization mirror of the Aurora email-export JSON shape.
 *
 * Field set is intentionally limited to the Aurora email-export keys consumed
 * by [AuroraExportParser]. Unknown top-level keys remain forward-compatible.
 *
 * Top-level keys not consumed by the importer (`follows`, `walls`,
 * `blocks`, `beta_links`, `agreements`) are silently dropped at parse
 * time via `Json { ignoreUnknownKeys = true }` in [AuroraExportParser].
 *
 * Naming: snake_case property names so the implicit JSON keys line up
 * with Aurora's wire format without `@SerialName` annotations on every
 * field. Kotlin convention dictates camelCase but readability of the
 * data class as a literal mirror of the JSON wins here.
 */
@Serializable
data class AuroraExportData(
    val user: AuroraUser,
    val ascents: List<AuroraAscent> = emptyList(),
    val attempts: List<AuroraAttempt> = emptyList(),
    val circuits: List<AuroraCircuit> = emptyList(),
    val climbs: List<AuroraClimb> = emptyList(),
    val likes: List<AuroraLike> = emptyList(),
)

@Serializable
data class AuroraUser(
    val username: String,
    val email_address: String? = null,
    val created_at: String? = null,
)

@Serializable
data class AuroraAscent(
    /** Climb display name. Resolved against the local board DB at
     *  import time — Aurora email exports do not carry climb UUIDs. */
    val climb: String,
    val angle: Int,
    /** Attempt count: `1` = flash, `>1` = redpoint. */
    val count: Int,
    /** User rating 1–5. The email export is already on the 5-point
     *  scale; only Aurora's *live API* returned 1–3. No conversion
     *  needed here. */
    val stars: Int,
    /** Font/V grade as plain text (`"6A"`, `"6A/V3"`). Resolved to a
     *  numeric difficulty ID at import time via the existing
     *  KilterGradeMapper / FontGradeParser. */
    val grade: String,
    /** ISO 8601 *or* `YYYY-MM-DD HH:MM:SS`. Both forms appear in
     *  empirical Aurora exports — see [AuroraTimestamp.normalize]. */
    val climbed_at: String,
    val created_at: String,
)

@Serializable
data class AuroraAttempt(
    val climb: String,
    val angle: Int,
    val count: Int,
    val climbed_at: String,
    val created_at: String,
)

@Serializable
data class AuroraCircuit(
    val name: String,
    /** Hex color without leading `#`, e.g. `"FF0000"`. Stored verbatim
     *  on `climb_lists.color`; the list-detail UI can pick it up later. */
    val color: String,
    val created_at: String,
    val description: String? = null,
    val is_private: Boolean = false,
    /** Climb names; resolved via the same name→uuid map ascents use. */
    val climbs: List<String> = emptyList(),
)

@Serializable
data class AuroraClimb(
    val name: String,
    /** Layout display name e.g. `"Kilter Board Original"`. Resolved
     *  against the board DB's `product_layouts.product_name` at import
     *  time. */
    val layout: String,
    val created_at: String,
    val is_draft: Boolean? = null,
    val holds: List<AuroraHold> = emptyList(),
    val description: String? = null,
)

@Serializable
data class AuroraHold(
    val x: Int,
    val y: Int,
    /** One of `start`, `middle`, `finish`, `foot`. Maps to the existing
     *  CruxCoach role IDs (12/13/14/15 — see HoldRole). */
    val role: String,
)

@Serializable
data class AuroraLike(
    val climb: String,
    val created_at: String,
)
