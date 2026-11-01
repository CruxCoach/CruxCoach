package com.cruxcoach.android.spraywall

import android.util.Log
import com.cruxcoach.android.nostr.NostrKeyStore
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.nostr.SignerMode
import com.cruxcoach.android.nostr.profile.ProfileImageUploader
import com.cruxcoach.domain.spraywall.SprayClimb
import com.cruxcoach.domain.spraywall.SprayNostrEvent
import com.cruxcoach.domain.spraywall.SprayWall
import com.cruxcoach.domain.spraywall.SprayWallNostr
import com.cruxcoach.domain.spraywall.SprayWallVersion
import com.cruxcoach.domain.spraywall.SprayWallVisibility
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes public spray walls (FEAT-078) over Nostr with the climber's own
 * identity: the photo of every version goes to Blossom, then one event per
 * version (never replaced), the wall head, and the climbs set on public
 * walls. Ascents are never published; private walls never leave the phone.
 *
 * Nothing here creates a Nostr identity: without one (local key or Amber)
 * publishing waits and the wall shows "not published yet".
 */
@OptIn(FlowPreview::class)
@Singleton
class SprayWallPublisher @Inject constructor(
    private val repository: SprayWallRepository,
    private val photos: SprayWallPhotoStore,
    private val nostrSigner: NostrSigner,
    private val keyStore: NostrKeyStore,
    private val pool: NostrRelayPool,
    private val uploader: ProfileImageUploader,
) {
    sealed interface Outcome {
        data class Done(val versions: Int, val climbs: Int) : Outcome
        data object NoIdentity : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** The last run's outcome, for the wall screen's "not published yet" hint and its retry. */
    private val _last = MutableStateFlow<Outcome?>(null)
    val last: StateFlow<Outcome?> = _last.asStateFlow()

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Every save may leave something to publish; a burst of saves publishes once.
        scope.launch {
            repository.changes.drop(1).debounce(PUBLISH_DELAY_MS).collect { publishPending() }
        }
    }

    /** A local key or a configured Amber signer. Never creates one. */
    fun hasIdentity(): Boolean = nostrSigner.getStoredSignerMode() == SignerMode.AMBER || keyStore.hasKey()

    /** The identity's public key, or null without one. */
    fun myPubkey(): String? = if (hasIdentity()) runCatching { nostrSigner.getPublicKeyHex() }.getOrNull() else null

    /** Publishes whatever is pending, in the background (after a save). */
    fun publishSoon() {
        scope.launch { publishPending() }
    }

    suspend fun publishPending(): Outcome = mutex.withLock {
        val outcome = try {
            run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "publish failed", e)
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
        _last.value = outcome
        outcome
    }

    private suspend fun run(): Outcome {
        val walls = repository.wallsNeedingPublish()
        val publicWalls = repository.publicWalls()
        if (walls.isEmpty() && publicWalls.none { repository.unpublishedClimbs(it.id).isNotEmpty() }) return Outcome.Done(0, 0)
        val me = myPubkey() ?: return Outcome.NoIdentity
        var versions = 0
        for (wall in walls) versions += publishWall(me, wall)
        var climbs = 0
        for (wall in repository.publicWalls()) {
            val owner = wall.ownerPubkey ?: me
            for (climb in repository.unpublishedClimbs(wall.id)) {
                val event = SprayWallNostr.climb(me, now(), climb, owner)
                if (send(event) > 0) {
                    repository.markClimbPublished(climb.uuid, climb.updatedAt)
                    climbs++
                }
            }
        }
        return Outcome.Done(versions, climbs)
    }

    /** Every version not on Nostr yet, then the head. Returns the number of versions sent. */
    private suspend fun publishWall(me: String, wall: SprayWall): Int {
        val from = (wall.publishedVersion ?: 0) + 1
        val pending = repository.versions(wall.id).filter { it.number >= from }.sortedBy { it.number }
        for (version in pending) {
            val (sha, url) = uploaded(wall.id, version)
            val event = SprayWallNostr.version(me, now(), version, sha, url, repository.holds(wall.id, version.number))
            check(send(event) > 0) { "no relay took version ${version.number}" }
        }
        val fresh = repository.wall(wall.id) ?: return pending.size
        check(send(SprayWallNostr.wallHead(me, now(), fresh)) > 0) { "no relay took the wall" }
        repository.markPublished(wall.id, fresh.current.number)
        return pending.size
    }

    /** The version's photo on Blossom, uploaded once per photo file. */
    private suspend fun uploaded(wallId: String, version: SprayWallVersion): Pair<String, String> {
        val sha = version.photoSha256
        val url = version.photoUrl
        if (sha != null && url != null) return sha to url
        val bytes = photos.bytes(version.photoFile) ?: error("photo missing")
        when (val result = uploader.upload(bytes, purpose = "CruxCoach spray wall photo upload")) {
            is ProfileImageUploader.Result.Success -> {
                val hash = result.url.substringAfterLast('/')
                repository.setPhotoBlob(wallId, version.photoFile, hash, result.url)
                return hash to result.url
            }
            is ProfileImageUploader.Result.Failure -> error("photo upload: ${result.message}")
        }
    }

    /**
     * Takes a public wall back: every event it published (head, versions,
     * my climbs on it) is replaced by a tombstone and named in a kind-5
     * deletion. The wall turns private on this phone either way; returns
     * false when no relay confirmed.
     */
    suspend fun withdrawWall(wallId: String): Boolean = mutex.withLock {
        val wall = repository.wall(wallId) ?: return false
        val me = myPubkey()
        var accepted = 0
        if (me != null && wall.isMine && wall.publishedVersion != null) {
            val address = SprayWallNostr.wallAddress(me, wallId)
            val dTags = buildList {
                add(SprayWallNostr.wallDTag(me, wallId) to SprayWallNostr.LABEL_WALL)
                repository.versions(wallId).filter { it.number <= wall.publishedVersion!! }.forEach {
                    add(SprayWallNostr.versionDTag(me, wallId, it.number) to SprayWallNostr.LABEL_VERSION)
                }
                repository.publishedClimbs(wallId).forEach { add(SprayWallNostr.climbDTag(me, it.uuid) to SprayWallNostr.LABEL_CLIMB) }
            }
            val t = now()
            for ((d, label) in dTags) accepted = maxOf(accepted, send(SprayWallNostr.tombstone(t, d, label, address)))
            accepted = maxOf(accepted, send(SprayWallNostr.deletion(me, t, dTags.map { it.first })))
        }
        repository.updateWallDetails(wallId, wall.name, wall.angle, SprayWallVisibility.PRIVATE, wall.gym)
        accepted > 0
    }

    /** Withdraws one published climb of mine (it was deleted here). */
    fun withdrawClimbSoon(climb: SprayClimb, wallOwner: String?) {
        if (climb.publishedAt == null || !climb.isMine) return
        scope.launch {
            mutex.withLock {
                runCatching {
                    val me = myPubkey() ?: return@runCatching
                    val d = SprayWallNostr.climbDTag(me, climb.uuid)
                    val t = now()
                    send(SprayWallNostr.tombstone(t, d, SprayWallNostr.LABEL_CLIMB, SprayWallNostr.wallAddress(wallOwner ?: me, climb.wallId)))
                    send(SprayWallNostr.deletion(me, t, listOf(d)))
                }.onFailure { Log.w(TAG, "withdrawing a climb failed", it) }
            }
        }
    }

    private suspend fun send(event: SprayNostrEvent): Int {
        val signed = nostrSigner.signer.sign<Event>(
            createdAt = event.createdAt,
            kind = event.kind,
            tags = event.tags.map { it.toTypedArray() }.toTypedArray(),
            content = event.content,
        )
        val (attempted, accepted) = pool.sendEventWithStats(signed)
        Log.i(TAG, "kind=${event.kind} attempted=$attempted accepted=$accepted")
        return accepted
    }

    private fun now() = System.currentTimeMillis() / 1000

    private companion object {
        const val TAG = "SprayWallPublisher"
        const val PUBLISH_DELAY_MS = 1500L
    }
}
