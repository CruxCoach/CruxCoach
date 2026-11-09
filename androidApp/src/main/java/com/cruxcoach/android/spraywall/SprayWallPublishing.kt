package com.cruxcoach.android.spraywall

import android.util.Log
import com.cruxcoach.domain.spraywall.SprayClimb
import com.cruxcoach.domain.spraywall.SprayNostrEvent
import com.cruxcoach.domain.spraywall.SprayWall
import com.cruxcoach.domain.spraywall.SprayWallNostr
import com.cruxcoach.domain.spraywall.SprayWallVersion
import com.cruxcoach.domain.spraywall.SprayWallVisibility
import kotlinx.coroutines.CancellationException

/**
 * One publish run of [SprayWallPublisher] (FEAT-078): the versions of my public
 * walls not on Nostr yet, with their photos and the head, then the heads with
 * new details alone, then my climbs on public walls. A wall the relays took
 * back in part (an unfinished withdrawal) is left out of all three.
 *
 * What the signer declines (Amber declining, a local key that cannot sign, a
 * refused Blossom authorisation) holds up nothing else: the run goes on, and
 * the declined version, head or climb is not asked for again before the next
 * start, until it changes, or until the climber asks for its wall ([askAgain]).
 *
 * Signing, relays and Blossom come in as functions ([SprayWallPublisher]
 * wires them), which keeps this testable on the JVM.
 */
class SprayWallPublishing(
    private val repository: SprayWallRepository,
    private val withdrawal: SprayWallWithdrawal,
    /** Signs and sends; the number of relays that took it. Throws [SprayEventSigningFailed]. */
    private val send: suspend (SprayNostrEvent) -> Int,
    /** The version's photo on Blossom (sha256, URL). Throws [SprayEventSigningFailed] when its authorisation failed. */
    private val upload: suspend (wallId: String, version: SprayWallVersion) -> Pair<String, String>,
    private val stamp: (dTag: String) -> Long,
    private val now: () -> Long,
) {
    /**
     * Something the signer declined (or that waits, [holdWall]): [what] of [wallId] as it was then. [at] is the wall's
     * update time (a rename, a hold edit, a climb or any save moves it on), or a climb's edit time.
     */
    private data class Declined(val what: String, val wallId: String, val item: String, val at: Long)

    /** What the signer declined since the app started. */
    private val declined = HashSet<Declined>()

    /** The climber asked to publish [wallId] now: the signer is asked again for what it declined on that wall. */
    fun askAgain(wallId: String) = synchronized(declined) { declined.removeAll { it.wallId == wallId } }

    /**
     * The signer declined the first tombstone of withdrawing [wall], so nothing left the phone. Nothing of the wall
     * (versions, head, climbs) asks the signer to publish the wall the climber meant to make private: it waits for the
     * next start or a new change of the wall.
     */
    fun holdWall(wall: SprayWall) {
        decline(versionsKey(wall))
        decline(headKey(wall))
        decline(climbsKey(wall))
    }

    /** [identity]: my public key, or null without an identity (asked only when something is to go out). */
    suspend fun run(identity: () -> String?): SprayWallPublisher.Outcome {
        // A wall without holds yet (a reset still being marked) waits: published, it could no longer be corrected in place.
        val walls = repository.wallsNeedingPublish().filter { it.current.holdCount > 0 }
        val heads = repository.wallsWithHeadPending()
        // A wall the relays already took part of (an unfinished withdrawal) is left out of all three.
        val climbWalls = repository.wallsTakingClimbs()
        if (walls.isEmpty() && heads.isEmpty() && climbWalls.none { repository.unpublishedClimbs(it.id).isNotEmpty() }) {
            return SprayWallPublisher.Outcome.Done(0, 0)
        }
        val me = identity() ?: return SprayWallPublisher.Outcome.NoIdentity
        var left = 0
        var versions = 0
        for (wall in walls) {
            val key = versionsKey(wall)
            if (isDeclined(key)) {
                left++
                continue
            }
            versions += try {
                publishWall(me, wall)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SprayEventSigningFailed) {
                // Deleting the photos would ask the signer again, and every later try would upload them anew. They
                // stay recorded for the next try. The head, pending once a version went out, waits with them.
                Log.w(TAG, "signing a wall version failed", e)
                decline(key)
                decline(headKey(wall))
                left++
                continue
            } catch (e: Exception) {
                // Photos uploaded before the failure do not stay on Blossom unused; the next try uploads them again.
                withdrawal.deleteUnpublishedPhotos(wall.id)
                throw e
            }
        }
        // Nothing new on these walls but their details: the head alone. A wall with versions still to go (a reset
        // being marked) sends it with them; the walls published above already did.
        for (wall in repository.wallsWithHeadPending()) {
            val pending = wall.headPending ?: continue
            if (wall.needsPublish) continue
            if (wall.visibility == SprayWallVisibility.PUBLIC && wall.publishedVersion != null) {
                // A head no relay took, or one the signer declined, holds up neither the other walls nor the climbs.
                val key = headKey(wall)
                val taken = if (isDeclined(key)) 0 else signed(key, "a wall head") {
                    send(SprayWallNostr.wallHead(me, stamp(SprayWallNostr.wallDTag(me, wall.id)), wall))
                }
                if (taken == 0) {
                    left++
                    continue
                }
            }
            repository.clearHeadPending(wall.id, pending)
        }
        var climbs = 0
        for (wall in repository.wallsTakingClimbs()) {
            val owner = wall.ownerPubkey ?: me
            val unpublished = repository.unpublishedClimbs(wall.id)
            if (unpublished.isNotEmpty() && isDeclined(climbsKey(wall))) {
                left += unpublished.size
                continue
            }
            for (climb in unpublished) {
                val key = climbKey(climb)
                if (isDeclined(key)) {
                    left++
                    continue
                }
                val taken = signed(key, "a climb") {
                    send(SprayWallNostr.climb(me, stamp(SprayWallNostr.climbDTag(me, climb.uuid)), climb, owner))
                }
                when {
                    taken > 0 -> {
                        repository.markClimbPublished(climb.uuid, climb.updatedAt)
                        climbs++
                    }
                    // One no relay took goes out with the next run, as before.
                    isDeclined(key) -> left++
                }
            }
        }
        return if (left > 0) SprayWallPublisher.Outcome.Failed("$left left: declined or not taken") else SprayWallPublisher.Outcome.Done(versions, climbs)
    }

    /** Every version not on Nostr yet, then the head. Returns the number of versions sent. */
    private suspend fun publishWall(me: String, wall: SprayWall): Int {
        val from = (wall.publishedVersion ?: 0) + 1
        val pending = repository.versions(wall.id).filter { it.number >= from }.sortedBy { it.number }
        // Versions on the same photo share one upload.
        val blobs = HashMap<String, Pair<String, String>>()
        for ((i, version) in pending.withIndex()) {
            val (sha, url) = blobs.getOrPut(version.photoFile) { upload(wall.id, version) }
            val event = SprayWallNostr.version(me, now(), version, sha, url, repository.holds(wall.id, version.number))
            check(send(event) > 0) { "no relay took version ${version.number}" }
            // On the relays from here, even if the rest fails: a withdrawal takes it back, its photo stays, and it is
            // no longer corrected in place. The head waits as pending until a relay takes it.
            if (i == 0) repository.markHeadPending(wall.id)
            repository.markPublished(wall.id, version.number)
        }
        val fresh = repository.wall(wall.id) ?: return pending.size
        // A reset started meanwhile (a new version without holds yet): the head would name a version not on the
        // relays. It stays pending and goes out with that version once its holds are marked.
        if (pending.isNotEmpty() && fresh.current.number != pending.last().number) return pending.size
        check(send(SprayWallNostr.wallHead(me, stamp(SprayWallNostr.wallDTag(me, wall.id)), fresh)) > 0) { "no relay took the wall" }
        // The head carried the wall's details as they were read: a change since then stays pending.
        fresh.headPending?.let { repository.clearHeadPending(wall.id, it) }
        return pending.size
    }

    /**
     * Takes my [climbs] on [wall] back from Nostr: a tombstone each for those that may be out ([takesBack]) and one
     * deletion naming them all. [SprayWithdrawal.DONE] when the deletion or every tombstone was taken by a relay, or
     * when none had to go. Run under the publisher's lock, with [climbs] read there.
     *
     * A climb that never went out can't be told apart from one a relay may have kept without confirming it in time,
     * or from an edited one (an edit clears the mark), so it is taken back too: offline, it waits for a connection.
     */
    suspend fun takeBackClimbs(identity: () -> String?, climbs: List<SprayClimb>, wall: SprayWall?): SprayWithdrawal {
        val mine = climbs.filter { takesBack(it, wall) }
        if (mine.isEmpty()) return SprayWithdrawal.DONE
        // Nothing goes out without an identity; one marked as published went out with an earlier one and stays.
        val me = identity() ?: return if (mine.none { it.publishedAt != null }) SprayWithdrawal.DONE else SprayWithdrawal.NO_IDENTITY
        val owner = wall?.ownerPubkey ?: me
        val dTags = mine.map { SprayWallNostr.climbDTag(me, it.uuid) }
        // A tombstone a relay took has replaced its climb there already, also when the deletion then fails.
        var tombstones = 0
        return try {
            for ((climb, d) in mine.zip(dTags)) {
                val address = SprayWallNostr.wallAddress(owner, climb.wallId)
                if (send(SprayWallNostr.tombstone(stamp(d), d, SprayWallNostr.LABEL_CLIMB, address)) > 0) tombstones++
            }
            val deleted = send(SprayWallNostr.deletion(me, now(), dTags)) > 0
            if (deleted || tombstones == mine.size) SprayWithdrawal.DONE else SprayWithdrawal.NOT_CONFIRMED
        } catch (e: CancellationException) {
            throw e
        } catch (e: SprayEventSigningFailed) {
            Log.w(TAG, "signing a climb's take-back failed", e)
            if (tombstones == mine.size) SprayWithdrawal.DONE else SprayWithdrawal.SIGNING_FAILED
        } catch (e: Exception) {
            Log.w(TAG, "taking climbs back failed", e)
            if (tombstones == mine.size) SprayWithdrawal.DONE else SprayWithdrawal.NOT_CONFIRMED
        }
    }

    /**
     * Deletes my climb [uuid] here once it is taken back ([takeBackClimbs]). Otherwise it stays: deleted here, nothing
     * would know of its event on the relays, and a withdrawal of the wall would miss it.
     */
    suspend fun deleteClimb(identity: () -> String?, uuid: String): SprayWithdrawal {
        val climb = repository.climb(uuid) ?: return SprayWithdrawal.DONE
        val result = takeBackClimbs(identity, listOf(climb), repository.wall(climb.wallId))
        if (result == SprayWithdrawal.DONE) repository.deleteClimb(uuid)
        return result
    }

    /** Removes someone else's wall [wallId] from this phone once my climbs on it are taken back ([takeBackClimbs]). */
    suspend fun removeFollowed(identity: () -> String?, wallId: String): SprayWithdrawal {
        val wall = repository.wall(wallId) ?: return SprayWithdrawal.DONE
        val result = takeBackClimbs(identity, repository.myClimbs(wallId), wall)
        if (result == SprayWithdrawal.DONE) repository.deleteWall(wallId)
        return result
    }

    /** Sends with [block]; a declined signature is remembered under [key] and counts as taken by no relay. */
    private suspend fun signed(key: Declined, what: String, block: suspend () -> Int): Int = try {
        block()
    } catch (e: SprayEventSigningFailed) {
        Log.w(TAG, "signing $what failed", e)
        decline(key)
        0
    }

    private fun decline(key: Declined) = synchronized(declined) { declined += key }
    private fun isDeclined(key: Declined) = synchronized(declined) { key in declined }

    private fun versionsKey(wall: SprayWall) = Declined("versions", wall.id, "", wall.updatedAt)
    private fun headKey(wall: SprayWall) = Declined("head", wall.id, "", wall.updatedAt)
    /** All of the wall's climbs, held with the wall ([holdWall]). */
    private fun climbsKey(wall: SprayWall) = Declined("climbs", wall.id, "", wall.updatedAt)
    private fun climbKey(climb: SprayClimb) = Declined("climb", climb.wallId, climb.uuid, climb.updatedAt)

    companion object {
        private const val TAG = "SprayWallPublishing"

        /**
         * A climb of mine on [wall] that may be on the relays or about to go out: it went out, or it is on a public or
         * followed wall even when it is not marked as published (an edit clears that mark until it goes out again, and
         * its earlier event is still on the relays, also when the owner withdrew the wall since). A climb on my private
         * wall is neither.
         */
        fun takesBack(climb: SprayClimb, wall: SprayWall?): Boolean =
            climb.isMine && (climb.publishedAt != null || wall == null || !wall.isMine || wall.visibility == SprayWallVisibility.PUBLIC)
    }
}
