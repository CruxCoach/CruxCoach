package com.cruxcoach.android.spraywall

import android.util.Log
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.backup.BlossomUploader
import com.cruxcoach.domain.spraywall.SprayWallNostr
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verifyId
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import com.cruxcoach.domain.spraywall.SprayWallVisibility
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads public spray walls from Nostr (FEAT-078), only when asked: the
 * walls at a gym for the map, adding one of them to "my walls", and
 * refreshing a public wall's climbs when it is opened. No background
 * traffic.
 *
 * Every event is checked like the community climbs: size cap, id and
 * signature, the d-tag must belong to its author, ids are plain, counts and
 * coordinates are in range, and tombstones win. Photos come from Blossom by
 * their hash and are checked against it.
 */
@Singleton
class SprayWallFetcher @Inject constructor(
    private val pool: NostrRelayPool,
    private val repository: SprayWallRepository,
    private val photos: SprayWallPhotoStore,
    private val blossom: BlossomUploader,
    private val publisher: SprayWallPublisher,
) {
    /** A public wall at a gym, as listed on the map. */
    data class Listing(val owner: String, val wallId: String, val name: String, val angle: Int, val gymName: String, val alreadyHere: Boolean)

    suspend fun wallsAt(venueKey: String): List<Listing> {
        if (!SprayWallNostr.validVenueKey(venueKey)) return emptyList()
        val filter = filter(tags = mapOf("#L" to SprayWallNostr.NAMESPACE, "#l" to venueKey), limit = LISTING_LIMIT)
        val heads = newestPerDTag(fetch(filter)).filterIsInstance<SprayWallNostr.Parsed.WallHead>()
            .filter { it.gym?.id == venueKey }
        val local = repository.walls().map { it.wall.id }.toSet()
        return heads.map { Listing(it.author, it.wallId, it.name, it.angle, it.gym?.name.orEmpty(), it.wallId in local) }
            .sortedBy { it.name.lowercase() }
    }

    sealed interface FollowResult {
        data class Added(val wallId: String) : FollowResult
        data object Gone : FollowResult
        data object Conflict : FollowResult
        data class Failed(val reason: String) : FollowResult
    }

    /** Adds someone else's public wall to this phone (read-only holds), with its photo and climbs. */
    suspend fun follow(owner: String, wallId: String): FollowResult = try {
        sync(owner, wallId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "follow failed", e)
        FollowResult.Failed(e.message ?: e.javaClass.simpleName)
    }

    /** Brings a public wall up to date: new versions, climbs by others, withdrawals. */
    suspend fun refresh(wallId: String) {
        val wall = repository.wall(wallId) ?: return
        val owner = wall.ownerPubkey ?: publisher.myPubkey() ?: return
        if (wall.visibility != SprayWallVisibility.PUBLIC) return
        runCatching { sync(owner, wallId) }.onFailure { Log.w(TAG, "refresh failed", it) }
    }

    private suspend fun sync(owner: String, wallId: String): FollowResult {
        val me = publisher.myPubkey()
        val mine = owner == me
        val headDTag = SprayWallNostr.wallDTag(owner, wallId)
        val address = SprayWallNostr.wallAddress(owner, wallId)
        val headFilter = filter(author = owner, tags = mapOf("#d" to headDTag))
        val linkedFilter = filter(tags = mapOf("#a" to address), limit = LINKED_LIMIT)
        // The owner's versions on their own: what anyone else tags the wall with cannot crowd them out of a fetch.
        val ownFilter = filter(author = owner, tags = mapOf("#a" to address), limit = LINKED_LIMIT)
        // The owner's own head only, also from a relay that ignores the authors filter.
        val head = newestPerDTag(fetch(headFilter)).firstOrNull { it.dTag == headDTag && it.author == owner }
        if (!mine) {
            when (head) {
                is SprayWallNostr.Parsed.Tombstone -> {
                    repository.markRemoteWallWithdrawn(owner, wallId)
                    return FollowResult.Gone
                }
                !is SprayWallNostr.Parsed.WallHead -> return FollowResult.Gone
                else -> Unit
            }
        }
        val linked = coroutineScope {
            val own = async { fetch(ownFilter) }
            val others = async { fetch(linkedFilter) }
            newestPerDTag(own.await() + others.await())
        }
        val climbs = linked.filterIsInstance<SprayWallNostr.Parsed.Climb>().filter { it.wallOwner == owner && it.wallId == wallId && it.author != me }
        val removed = linked.filterIsInstance<SprayWallNostr.Parsed.Tombstone>().mapNotNull { t -> t.climbUuid?.let { t.author to it } }

        if (!mine && head is SprayWallNostr.Parsed.WallHead) {
            val versions = linked.filterIsInstance<SprayWallNostr.Parsed.Version>().filter { it.author == owner && it.wallId == wallId }
            // The current version and every version a climb is set on.
            val needed = (climbs.map { it.version } + head.currentVersion).toSet()
            val known = repository.versions(wallId).map { it.number }.toSet()
            val toStore = versions.filter { it.number in needed && it.number !in known }
            val stored = toStore.map { v -> SprayWallRepository.RemoteVersion(v, photoFor(v)) }
            if (head.currentVersion !in known && stored.none { it.parsed.number == head.currentVersion }) return FollowResult.Failed("current version not found")
            if (repository.importRemoteWall(head, stored) == null) return FollowResult.Conflict
        }
        // A climb only comes in with the version it was set on, so it can be drawn on its own photo.
        val present = repository.versions(wallId).map { it.number }.toSet()
        for (c in climbs) if (c.version in present) repository.upsertRemoteClimb(c)
        for ((author, uuid) in removed) repository.removeRemoteClimb(author, uuid)
        return FollowResult.Added(wallId)
    }

    /** The version's photo here, downloaded once per hash and checked against it. */
    private suspend fun photoFor(v: SprayWallNostr.Parsed.Version): SprayWallPhotoStore.StoredPhoto {
        repository.photoWithHash(v.photoSha256)?.let { return it }
        val origin = "https://" + v.photoUrl.removePrefix("https://").substringBefore('/')
        val servers = (listOf(origin) + BlossomUploader.DEFAULT_SERVERS).distinct()
        val bytes = blossom.download(v.photoSha256, servers, MAX_PHOTO_BYTES)
        return photos.storeDownloaded(bytes)
    }

    private fun filter(author: String? = null, tags: Map<String, String>, limit: Int? = null): String = buildJsonObject {
        putJsonArray("kinds") { add(SprayWallNostr.KIND) }
        author?.let { putJsonArray("authors") { add(it) } }
        for ((key, value) in tags) putJsonArray(key) { add(value) }
        limit?.let { put("limit", it) }
    }.toString()

    private suspend fun fetch(filter: String): List<SprayWallNostr.Parsed> {
        // A slow relay must not cost the answers of the others: keep what came in by the timeout. Anyone can tag a
        // public wall, so what is kept is bounded: no oversized event, each copy once, and no more than a cap in all.
        val raw = LinkedHashSet<String>()
        var bytes = 0L
        withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            pool.fetchStored(filter)
                .filter { it.length <= MAX_EVENT_JSON }
                .takeWhile { bytes < MAX_FETCH_BYTES }
                .collect { if (raw.add(it)) bytes += it.length }
        }
        val now = System.currentTimeMillis() / 1000
        return raw.mapNotNull { json ->
            val event = runCatching { Event.fromJson(json) }.getOrNull() ?: return@mapNotNull null
            if (!runCatching { event.verifyId() && event.verifySignature() }.getOrDefault(false)) return@mapNotNull null
            SprayWallNostr.parse(event.pubKey, event.createdAt, event.kind, event.tags.map { it.toList() }, event.content, now)
        }
    }

    private fun newestPerDTag(all: List<SprayWallNostr.Parsed>): List<SprayWallNostr.Parsed> =
        all.groupBy { it.author to it.dTag }.values.map { same -> same.maxBy { it.createdAt } }

    private companion object {
        const val TAG = "SprayWallFetcher"
        const val FETCH_TIMEOUT_MS = 10_000L
        const val LISTING_LIMIT = 100
        const val LINKED_LIMIT = 2000
        const val MAX_PHOTO_BYTES = 16L * 1024 * 1024
        /** An event's JSON: the event itself plus its id, key and signature. */
        const val MAX_EVENT_JSON = SprayWallNostr.MAX_EVENT_BYTES + 4096
        /** All events of one fetch, in characters: far above a busy wall (2000 climbs of a few KB), far below a phone's memory. */
        const val MAX_FETCH_BYTES = 24L * 1024 * 1024
    }
}
