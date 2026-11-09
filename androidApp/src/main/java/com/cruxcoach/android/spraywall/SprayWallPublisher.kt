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
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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

    /** A local key or a configured Amber signer. Never creates one. */
    fun hasIdentity(): Boolean = nostrSigner.getStoredSignerMode() == SignerMode.AMBER || keyStore.hasKey()

    /** The identity's public key, or null without one. */
    fun myPubkey(): String? = if (hasIdentity()) runCatching { nostrSigner.getPublicKeyHex() }.getOrNull() else null

    /** Publishes whatever is pending, in the background (after a save). */
    fun publishSoon() {
        scope.launch { publishPending() }
    }

    /** [askAgainFor]: the climber asked to publish that wall, so the signer is asked again for what it declined there. */
    suspend fun publishPending(askAgainFor: String? = null): Outcome = mutex.withLock {
        askAgainFor?.let(publishing::askAgain)
        val outcome = try {
            publishing.run(::myPubkey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "publish failed", e)
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
        _last.value = outcome
        outcome
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
            is ProfileImageUploader.Result.Failure ->
                if (result.authFailed) throw SprayEventSigningFailed(IllegalStateException(result.message)) else error("photo upload: ${result.message}")
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

    private val publishing = SprayWallPublishing(
        repository = repository,
        withdrawal = withdrawal,
        send = ::send,
        upload = ::uploaded,
        stamp = ::stamp,
        now = ::now,
    )

    /** Withdrawals running per wall (a second try may start before the first ends). */
    private val running = HashMap<String, Int>()
    private val _withdrawing = MutableStateFlow<Set<String>>(emptySet())

    /** The walls being withdrawn right now, for the screens' progress; also after the screen that asked was left. */
    val withdrawing: StateFlow<Set<String>> = _withdrawing.asStateFlow()

    private fun running(wallId: String, by: Int) = synchronized(running) {
        val n = (running[wallId] ?: 0) + by
        if (n > 0) running[wallId] = n else running.remove(wallId)
        _withdrawing.value = running.keys.toSet()
    }

    /**
     * Takes a public wall back, photos included ([SprayWallWithdrawal]); the wall stays public when that fails. It runs
     * in the publisher's own scope: leaving the screen that asked for it does not cut it off. [before] runs first,
     * where no publish run comes between it and the withdrawal's mark (the details saved with it); [whenDone] once the
     * wall is private (deleting it).
     */
    suspend fun withdrawWall(
        wallId: String,
        before: suspend () -> Unit = {},
        whenDone: suspend () -> Unit = {},
    ): SprayWithdrawal {
        running(wallId, +1)
        return scope.async {
            try {
                mutex.withLock {
                    before()
                    val result = withdrawal.run(myPubkey(), wallId)
                    // Declined at the first tombstone: nothing of the wall asks the signer to publish it at once.
                    val wall = repository.wall(wallId)
                    if (result == SprayWithdrawal.SIGNING_FAILED && wall != null && wall.withdrawStarted == null) {
                        publishing.holdWall(wall)
                    }
                    if (result == SprayWithdrawal.DONE) whenDone()
                    result
                }
            } finally {
                running(wallId, -1)
            }
        }.await()
    }

    /**
     * Takes my [climbs] on [wall] back from Nostr ([SprayWallPublishing.takesBack]): a tombstone each and one deletion,
     * waiting for the relays. True when a relay took them, or when none had to go; without an identity, when none of
     * them is known to have gone out.
     */
    suspend fun withdrawClimbs(climbs: List<SprayClimb>, wall: SprayWall?): Boolean {
        val mine = climbs.filter { SprayWallPublishing.takesBack(it, wall) }
        if (mine.isEmpty()) return true
        return scope.async {
            mutex.withLock {
                try {
                    val me = myPubkey() ?: return@withLock mine.none { it.publishedAt != null }
                    val owner = wall?.ownerPubkey ?: me
                    val dTags = mine.map { SprayWallNostr.climbDTag(me, it.uuid) }
                    var taken = 0
                    for ((climb, d) in mine.zip(dTags)) {
                        val address = SprayWallNostr.wallAddress(owner, climb.wallId)
                        taken = maxOf(taken, send(SprayWallNostr.tombstone(stamp(d), d, SprayWallNostr.LABEL_CLIMB, address)))
                    }
                    taken = maxOf(taken, send(SprayWallNostr.deletion(me, now(), dTags)))
                    taken > 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "withdrawing climbs failed", e)
                    false
                }
            }
        }.await()
    }

    /** Withdraws a climb of mine that was deleted here from [wall] ([SprayWallPublishing.takesBack]). */
    fun withdrawClimbSoon(climb: SprayClimb, wall: SprayWall?) {
        if (!SprayWallPublishing.takesBack(climb, wall)) return
        val wallOwner = wall?.ownerPubkey
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

    // Last, so the runs it starts find every property above set.
    init {
        // Every save may leave something to publish; a burst of saves publishes once.
        scope.launch {
            repository.changes.drop(1).debounce(PUBLISH_DELAY_MS).collect { publishPending() }
        }
        // What was still pending when the app closed (versions, a head with new details, climbs) goes out now.
        scope.launch { publishPending() }
    }

    private companion object {
        const val TAG = "SprayWallPublisher"
        const val PUBLISH_DELAY_MS = 1500L
    }
}
