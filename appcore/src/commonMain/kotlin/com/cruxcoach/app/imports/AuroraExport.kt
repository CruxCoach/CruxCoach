package com.cruxcoach.app.imports

import kotlinx.serialization.Serializable

/**
 * Mirror of the Aurora email-export JSON, ported from Android
 * `AuroraExportSchema`. snake_case property names so the implicit keys line up
 * with Aurora's wire format without an annotation on every field.
 *
 * Top-level keys the importer does not consume (`follows`, `walls`, `blocks`,
 * `beta_links`, `agreements`) are dropped at parse time.
 */
@Serializable
class AuroraExportData(
    val user: AuroraUser,
    val ascents: List<AuroraAscent> = emptyList(),
    val attempts: List<AuroraAttempt> = emptyList(),
    val circuits: List<AuroraCircuit> = emptyList(),
    val climbs: List<AuroraClimb> = emptyList(),
)

@Serializable
class AuroraUser(
    val username: String,
    val email_address: String? = null,
    val created_at: String? = null,
)

@Serializable
class AuroraAscent(
    val climb: String,
    val angle: Int,
    /** `1` = flash, `>1` = redpoint. */
    val count: Int,
    /** Already on the 5-point scale in the email export. */
    val stars: Int,
    val grade: String,
    /** ISO 8601 *or* `YYYY-MM-DD HH:MM:SS`; both appear in real exports. */
    val climbed_at: String,
    val created_at: String,
)

@Serializable
class AuroraAttempt(
    val climb: String,
    val angle: Int,
    val count: Int,
    val climbed_at: String,
    val created_at: String,
)

@Serializable
class AuroraCircuit(
    val name: String,
    /** Hex colour without the leading `#`. Stored verbatim on `climb_lists.color`. */
    val color: String,
    val created_at: String,
    val description: String? = null,
    val is_private: Boolean = false,
    val climbs: List<String> = emptyList(),
)

@Serializable
class AuroraClimb(
    val name: String,
    /** Layout display name, e.g. `"Kilter Board Original"`. */
    val layout: String,
    val created_at: String,
    val is_draft: Boolean? = null,
    val holds: List<AuroraHold> = emptyList(),
    val description: String? = null,
)

@Serializable
class AuroraHold(
    val x: Int,
    val y: Int,
    /** `start` | `middle` | `finish` | `foot`. */
    val role: String,
)
