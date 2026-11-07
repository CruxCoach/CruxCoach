package com.cruxcoach.android.spraywall

import android.util.Log
import com.cruxcoach.android.nostr.NostrKeyStore
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.nostr.SignerMode
import com.cruxcoach.android.nostr.backup.BlossomUploader
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
    private val blossom: BlossomUploader,
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

    /**
     * Published walls whose name, angle or gym changed since their head went
     * out, until a relay takes the new head. Only in memory: after a restart
     * the change goes out with the wall's next version.
     */
    private val headsPending = HashSet<String>()

    /** The name, angle or gym of a published wall changed: its head goes out again. */
    fun publishHeadSoon(wallId: String) {
        scope.launch {
            mutex.withLock { headsPending += wallId }
            publishPending()
        }
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
        // A wall without holds yet (a reset still being marked) waits: published, it could no longer be corrected in place.
        val walls = repository.wallsNeedingPublish().filter { it.current.holdCount > 0 }
        val publicWalls = repository.publicWalls()
        if (walls.isEmpty() && headsPending.isEmpty() && publicWalls.none { repository.unpublishedClimbs(it.id).isNotEmpty() }) return Outcome.Done(0, 0)
        val me = myPubkey() ?: return Outcome.NoIdentity
        var versions = 0
        for (wall in walls) {
            versions += try {
                publishWall(me, wall)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Photos uploaded before the failure do not stay on Blossom unused; the next try uploads them again.
                withdrawal.deleteUnpublishedPhotos(wall.id)
                throw e
            }
            // Its head went out with the versions.
            headsPending -= wall.id
        }
        // Nothing new on these walls but their details: the head alone (a wall with versions still to go waits for them).
        for (id in headsPending.toList()) {
            val wall = repository.wall(id)
            if (wall != null && wall.isMine && wall.visibility == SprayWallVisibility.PUBLIC && wall.publishedVersion == wall.current.number) {
                check(send(SprayWallNostr.wallHead(me, stamp(SprayWallNostr.wallDTag(me, id)), wall)) > 0) { "no relay took the wall" }
            }
            headsPending -= id
        }
        var climbs = 0
        for (wall in repository.publicWalls()) {
            val owner = wall.ownerPubkey ?: me
            for (climb in repository.unpublishedClimbs(wall.id)) {
                val event = SprayWallNostr.climb(me, stamp(SprayWallNostr.climbDTag(me, climb.uuid)), climb, owner)
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
        // Versions on the same photo share one upload.
        val blobs = HashMap<String, Pair<String, String>>()
        for (version in pending) {
            val (sha, url) = blobs.getOrPut(version.photoFile) { uploaded(wall.id, version) }
            val event = SprayWallNostr.version(me, now(), version, sha, url, repository.holds(wall.id, version.number))
            check(send(event) > 0) { "no relay took version ${version.number}" }
        }
        val fresh = repository.wall(wall.id) ?: return pending.size
        check(send(SprayWallNostr.wallHead(me, stamp(SprayWallNostr.wallDTag(me, wall.id)), fresh)) > 0) { "no relay took the wall" }
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

    private val withdrawal = SprayWallWithdrawal(
        repository = repository,
        send = ::send,
        deletePhoto = { sha, servers ->
            val result = blossom.delete(sha, servers)
            when {
                result.authFailed -> SprayWallWithdrawal.PhotoDeletion.NOT_AUTHORISED
                result.fullySucceeded() -> SprayWallWithdrawal.PhotoDeletion.DELETED
                else -> SprayWallWithdrawal.PhotoDeletion.LEFT
            }
        },
        stamp = ::stamp,
        now = ::now,
        defaultServers = BlossomUploader.DEFAULT_SERVERS,
    )

    /** Takes a public wall back, photos included ([SprayWallWithdrawal]); the wall stays public when that fails. */
    suspend fun withdrawWall(wallId: String): SprayWithdrawal = mutex.withLock { withdrawal.run(myPubkey(), wallId) }

    /** Withdraws one published climb of mine (it was deleted here). */
    fun withdrawClimbSoon(climb: SprayClimb, wallOwner: String?) {
        if (climb.publishedAt == null || !climb.isMine) return
        scope.launch {
            mutex.withLock {
                runCatching {
                    val me = myPubkey() ?: return@runCatching
                    val d = SprayWallNostr.climbDTag(me, climb.uuid)
                    send(SprayWallNostr.tombstone(stamp(d), d, SprayWallNostr.LABEL_CLIMB, SprayWallNostr.wallAddress(wallOwner ?: me, climb.wallId)))
                    send(SprayWallNostr.deletion(me, now(), listOf(d)))
                }.onFailure { Log.w(TAG, "withdrawing a climb failed", it) }
            }
        }
    }

    private suspend fun send(event: SprayNostrEvent): Int {
        val signed = try {
            nostrSigner.signer.sign<Event>(
                createdAt = event.createdAt,
                kind = event.kind,
                tags = event.tags.map { it.toTypedArray() }.toTypedArray(),
                content = event.content,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Amber declined, timed out or could not be reached; a local key that cannot sign.
            throw SprayEventSigningFailed(e)
        }
        val (attempted, accepted) = pool.sendEventWithStats(signed)
        Log.i(TAG, "kind=${event.kind} attempted=$attempted accepted=$accepted")
        return accepted
    }

    private fun now() = System.currentTimeMillis() / 1000

    /** Replaceable events at one d-tag get strictly rising times, so a quick second edit still replaces the first. */
    private val stamps = HashMap<String, Long>()
    private fun stamp(dTag: String): Long = synchronized(stamps) {
        maxOf(now(), (stamps[dTag] ?: 0L) + 1).also { stamps[dTag] = it }
    }

    private companion object {
        const val TAG = "SprayWallPublisher"
        const val PUBLISH_DELAY_MS = 1500L
    }
}
