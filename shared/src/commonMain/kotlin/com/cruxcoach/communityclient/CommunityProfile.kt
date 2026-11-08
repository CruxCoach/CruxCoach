package com.cruxcoach.communityclient

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The parts of a kind:0 profile the client shows. */
data class CommunityProfile(
    val pubkey: String,
    val displayName: String?,
    val name: String?,
    val picture: String?,
    val updatedAt: Long,
) {
    /** What to call this person: display name, then name, else null (the UI shows a short key). */
    val label: String? get() = displayName?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }

    companion object {
        fun fromEvent(event: CommunityEvent): CommunityProfile? {
            if (event.kind != CommunityKinds.PROFILE) return null
            val obj = runCatching { CommunityJson.parseToJsonElement(event.content).jsonObject }.getOrNull() ?: return null
            fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.take(100)
            return CommunityProfile(
                pubkey = event.pubkey,
                displayName = str("display_name"),
                name = str("name"),
                picture = (obj["picture"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.startsWith("https://") },
                updatedAt = event.createdAt,
            )
        }
    }
}

/** The longest display name the client writes. */
const val MAX_DISPLAY_NAME_LENGTH = 50

/** A kind:0 to build the next one on: its JSON content, its tags, and when it was signed. */
data class ProfileBase(val content: String, val tags: List<List<String>>, val createdAt: Long) {
    companion object {
        fun of(event: CommunityEvent): ProfileBase = ProfileBase(event.content, event.tags, event.createdAt)
    }
}

/**
 * The newest kind:0 of [me] in [events]. The session checks only signatures, so a relay
 * could hand back another member's profile; signing that would take over their data.
 */
fun ownNewestProfile(events: List<CommunityEvent>, me: String): CommunityEvent? =
    events.filter { it.pubkey == me && it.kind == CommunityKinds.PROFILE }.maxByOrNull { it.createdAt }

/**
 * The kind:0 that sets [displayName] on top of [base]: the user's newest profile on this
 * community, or for the first write their public profile, so the event loses nothing if
 * it reaches public relays. Every other field and every tag is kept, as the Buzz apps do
 * (an agent key keeps its auth tag); a blank name removes `display_name` and `name`.
 * [createdAt] is moved past [base] so the new one wins.
 */
fun CommunityEventTemplates.displayName(base: ProfileBase?, displayName: String, createdAt: Long): UnsignedEvent {
    val fields = base?.let { runCatching { CommunityJson.parseToJsonElement(it.content).jsonObject }.getOrNull() }
        .orEmpty()
        .toMutableMap()
    val name = displayName.trim().take(MAX_DISPLAY_NAME_LENGTH)
    if (name.isEmpty()) {
        fields.remove("display_name")
        fields.remove("name")
    } else {
        fields["display_name"] = JsonPrimitive(name)
    }
    return UnsignedEvent(
        createdAt = maxOf(createdAt, (base?.createdAt ?: 0) + 1),
        kind = CommunityKinds.PROFILE,
        tags = base?.tags.orEmpty(),
        content = JsonObject(fields).toString(),
    )
}

/** Filter for the profiles of [pubkeys]. */
fun CommunityFilters.profiles(pubkeys: Collection<String>): RelayFilter =
    RelayFilter(kinds = listOf(CommunityKinds.PROFILE), authors = pubkeys.toList(), limit = pubkeys.size)
