package com.cruxcoach.app.profile

import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.db.secure.SecureDatabase

/** One Nostr kind-0 profile, as the app keeps it. */
class NostrProfileData(
    val pubkey: String,
    val displayName: String? = null,
    val about: String? = null,
    val pictureUrl: String? = null,
    val bannerUrl: String? = null,
    val nip05: String? = null,
    val website: String? = null,
    val lightningAddress: String? = null,
)

/**
 * The `nostr_profiles` cache, ported from Android `NostrProfileManager`.
 *
 * The two rules that matter are both here rather than in the presenter: an
 * older kind-0 may never overwrite a newer one, and a profile this device owns
 * (`local_primary`) is never overwritten by a relay copy at all — a relay that
 * still serves a stale metadata event must not undo what the user just saved.
 */
class NostrProfileStore(
    private val database: SecureDatabase,
    private val clock: WallClock,
) {
    private val queries get() = database.nostrProfilesQueries

    fun cached(pubkey: String): NostrProfileData? {
        val row = queries.getByPubkey(pubkey).executeAsOneOrNull() ?: return null
        return NostrProfileData(
            pubkey = row.pubkey,
            displayName = row.display_name,
            about = row.about,
            pictureUrl = row.picture_url,
            bannerUrl = row.banner_url,
            nip05 = row.nip05,
            website = row.website,
            lightningAddress = row.lightning_address,
        )
    }

    fun isLocalPrimary(pubkey: String): Boolean =
        queries.isLocalPrimary(pubkey).executeAsOneOrNull() == 1L

    /** Epoch seconds since this row was last written, or null when there is none. */
    fun cacheAgeSeconds(pubkey: String): Long? =
        queries.getCacheUpdatedAt(pubkey).executeAsOneOrNull()?.let { clock.epochSeconds() - it }

    /**
     * Stores the user's own edits without signing or sending anything. The
     * last published kind-0 timestamp is preserved so a later publish can
     * still replace the relay copy.
     */
    fun saveLocal(profile: NostrProfileData) {
        val lastPublishedAt = queries.getLastEventCreatedAt(profile.pubkey)
            .executeAsOneOrNull()?.last_event_created_at
        write(profile, lastPublishedAt, localPrimary = true)
    }

    /**
     * Caches a profile seen on a relay. Returns false when the guard rejected
     * it, so a caller can tell "nothing newer" from "stored".
     */
    fun cacheIfNewer(
        profile: NostrProfileData,
        eventCreatedAt: Long,
        localPrimary: Boolean,
    ): Boolean {
        var wrote = false
        queries.transaction {
            val existingIsLocalPrimary = isLocalPrimary(profile.pubkey)
            if (existingIsLocalPrimary && !localPrimary) return@transaction
            val existing = queries.getLastEventCreatedAt(profile.pubkey)
                .executeAsOneOrNull()?.last_event_created_at
            if (existing != null && eventCreatedAt <= existing) return@transaction
            write(profile, eventCreatedAt, localPrimary || existingIsLocalPrimary)
            wrote = true
        }
        return wrote
    }

    private fun write(profile: NostrProfileData, eventCreatedAt: Long?, localPrimary: Boolean) {
        queries.upsert(
            pubkey = profile.pubkey,
            display_name = profile.displayName?.takeIf { it.isNotBlank() },
            lightning_address = profile.lightningAddress?.takeIf { it.isNotBlank() },
            picture_url = profile.pictureUrl?.takeIf { it.isNotBlank() },
            updated_at = clock.epochSeconds(),
            banner_url = profile.bannerUrl?.takeIf { it.isNotBlank() },
            nip05 = profile.nip05?.takeIf { it.isNotBlank() },
            website = profile.website?.takeIf { it.isNotBlank() },
            about = profile.about?.takeIf { it.isNotBlank() },
            local_primary = if (localPrimary) 1L else 0L,
            last_event_created_at = eventCreatedAt,
        )
    }
}
