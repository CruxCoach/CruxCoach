package com.cruxcoach.communityclient

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A signed Nostr event (NIP-01) as the community relay sends and stores it.
 *
 * Parsing never trusts the signature; the transport verifies it before an event
 * reaches the store (see [EventVerifier]).
 */
@Serializable
data class CommunityEvent(
    val id: String,
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    /** First value of the first tag named [name], or null. */
    fun tagValue(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    /** Every value at position 1 of the tags named [name], in order. */
    fun tagValues(name: String): List<String> = tags.filter { it.size >= 2 && it[0] == name }.map { it[1] }

    fun hasTag(name: String): Boolean = tags.any { it.isNotEmpty() && it[0] == name }

    /** The channel (`h` tag) this event belongs to. */
    val channelId: String? get() = tagValue("h")

    fun toJson(): String = CommunityJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): CommunityEvent? =
            runCatching { CommunityJson.decodeFromString(serializer(), json) }.getOrNull()?.takeIf { it.isWellFormed() }

        fun fromJson(element: JsonElement): CommunityEvent? =
            runCatching { CommunityJson.decodeFromJsonElement(serializer(), element) }.getOrNull()?.takeIf { it.isWellFormed() }
    }

    /** Shape checks only: hex lengths and bounded tags. Not a signature check. */
    private fun isWellFormed(): Boolean =
        id.isHex(64) && pubkey.isHex(64) && sig.isHex(128) && createdAt >= 0 &&
            tags.size <= MAX_TAGS && tags.all { it.size <= MAX_TAG_FIELDS }
}

/**
 * An event before signing. The signer fills in `pubkey`, `id` and `sig`.
 */
data class UnsignedEvent(
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
)

/** Event kinds the community client reads or writes. Values from the Buzz relay at the pinned version. */
object CommunityKinds {
    const val PROFILE = 0
    const val DELETION = 5
    const val REACTION = 7
    const val STREAM_MESSAGE = 9
    const val GROUP_ADD_USER = 9000
    const val GROUP_DELETE_EVENT = 9005
    const val GROUP_CREATE = 9007
    const val GROUP_JOIN_REQUEST = 9021
    const val GROUP_LEAVE_REQUEST = 9022
    const val AUTH = 22242
    const val HTTP_AUTH = 27235
    const val GROUP_METADATA = 39000
    const val GROUP_ADMINS = 39001
    const val GROUP_MEMBERS = 39002
    const val THREAD_SUMMARY = 39005
    const val WINDOW_BOUNDS = 39006
    const val STREAM_MESSAGE_V2 = 40002
    const val STREAM_MESSAGE_EDIT = 40003
    const val SYSTEM_MESSAGE = 40099
    const val MEMBER_ADDED = 44100
    const val MEMBER_REMOVED = 44101
    const val FORUM_POST = 45001
    const val FORUM_VOTE = 45002
    const val FORUM_COMMENT = 45003

    /** Kinds shown as rows in a stream room. */
    val STREAM_CONTENT = listOf(STREAM_MESSAGE, STREAM_MESSAGE_V2, SYSTEM_MESSAGE)

    /** Kinds that change rows already shown: reactions, deletions, edits. */
    val AUX = listOf(DELETION, REACTION, GROUP_DELETE_EVENT, STREAM_MESSAGE_EDIT)

    /**
     * Kinds the client accepts only from the relay's key. Room notices (40099) are
     * among them: from any other key, a notice could fake "X removed Y".
     */
    val RELAY_SIGNED = setOf(GROUP_METADATA, GROUP_ADMINS, GROUP_MEMBERS, THREAD_SUMMARY, WINDOW_BOUNDS, SYSTEM_MESSAGE, MEMBER_ADDED, MEMBER_REMOVED)
}

internal val CommunityJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

internal const val MAX_TAGS = 4096
internal const val MAX_TAG_FIELDS = 256

internal fun String.isHex(length: Int): Boolean =
    this.length == length && all { it in '0'..'9' || it in 'a'..'f' }
