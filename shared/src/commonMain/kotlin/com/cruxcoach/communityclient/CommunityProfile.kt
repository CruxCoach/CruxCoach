package com.cruxcoach.communityclient

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

/** Filter for the profiles of [pubkeys]. */
fun CommunityFilters.profiles(pubkeys: Collection<String>): RelayFilter =
    RelayFilter(kinds = listOf(CommunityKinds.PROFILE), authors = pubkeys.toList(), limit = pubkeys.size)
