package com.cruxcoach.android.spraywall

import android.util.Log
import com.cruxcoach.domain.spraywall.SprayNostrEvent
import com.cruxcoach.domain.spraywall.SprayWallNostr
import com.cruxcoach.domain.spraywall.SprayWallVersion
import com.cruxcoach.domain.spraywall.SprayWallVisibility
import kotlinx.coroutines.CancellationException

/** How taking a public wall back went. Anything but [DONE] changed nothing on this phone. */
enum class SprayWithdrawal {
    DONE,
    /** No identity to sign the deletions with. */
    NO_IDENTITY,
    /** The signer (local key or Amber) failed or the request was declined. */
    SIGNING_FAILED,
    /** No relay confirmed the tombstones. */
    NOT_CONFIRMED,
    /** The relays took the tombstones, but a photo could not be deleted from its Blossom server. */
    PHOTOS_LEFT,
}

/** Signing an event failed: kept apart from network errors, so the screen can say what to do. */
class SprayEventSigningFailed(cause: Throwable) : Exception(cause.message, cause)

/**
 * Takes one of my public walls back (FEAT-078): every event it may have
 * published (head, every version, my climbs on it) is replaced by a tombstone
 * and named in a kind-5 deletion, then every photo it uploaded is deleted from
 * the Blossom server that took it. That includes what a publish that failed
 * partway left behind: versions past the published one and their photos. Only
 * when all of that worked does the wall turn private here; otherwise it stays
 * public, so the climber can try again, and nothing is deleted. Once a relay
 * took a tombstone, the wall is marked as being withdrawn: nothing of it is
 * published until a later try finishes. Never throws,
 * also not when the signer fails.
 *
 * Signing, relays and Blossom come in as functions ([SprayWallPublisher]
 * wires them), which keeps this testable on the JVM.
 */
class SprayWallWithdrawal(
    private val repository: SprayWallRepository,
    /** Signs and sends; the number of relays that took it. Throws [SprayEventSigningFailed]. */
    private val send: suspend (SprayNostrEvent) -> Int,
    private val deletePhoto: suspend (sha256: String, servers: List<String>) -> PhotoDeletion,
    private val stamp: (dTag: String) -> Long,
    private val now: () -> Long,
    /** Where a photo goes when its upload server is unknown. */
    private val defaultServers: List<String>,
) {
    enum class PhotoDeletion { DELETED, LEFT, NOT_AUTHORISED }

    suspend fun run(me: String?, wallId: String): SprayWithdrawal {
        val wall = repository.wall(wallId) ?: return SprayWithdrawal.DONE
        val published = wall.publishedVersion
        if (!wall.isMine || (published == null && uploads(wallId).isEmpty())) {
            // Never on Nostr or Blossom: nothing to take back.
            repository.updateWallDetails(wallId, wall.name, wall.angle, SprayWallVisibility.PRIVATE, wall.gym)
            return SprayWithdrawal.DONE
        }
        if (me == null) return SprayWithdrawal.NO_IDENTITY
        val outcome = try {
            withdraw(me, wallId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SprayEventSigningFailed) {
            Log.w(TAG, "withdrawing a wall: signing failed", e)
            SprayWithdrawal.SIGNING_FAILED
        } catch (e: Exception) {
            Log.w(TAG, "withdrawing a wall failed", e)
            SprayWithdrawal.NOT_CONFIRMED
        }
        if (outcome == SprayWithdrawal.DONE) {
            repository.clearPhotoBlobs(wallId)
            repository.updateWallDetails(wallId, wall.name, wall.angle, SprayWallVisibility.PRIVATE, wall.gym)
        }
        return outcome
    }

    /**
     * After a publish of [wallId] failed partway: deletes the photos it uploaded
     * that no published version shows, and forgets them in all my walls once
     * they are gone. A photo that could not be deleted stays recorded, so a later withdrawal
     * deletes it (or the next publish uses it). Never throws.
     */
    suspend fun deleteUnpublishedPhotos(wallId: String) {
        try {
            val wall = repository.wall(wallId) ?: return
            val published = wall.publishedVersion ?: 0
            val versions = repository.versions(wallId)
            val shown = versions.filter { it.number <= published }.mapNotNull { it.photoSha256 }.toSet()
            for ((sha, url) in uploads(wallId, versions)) {
                if (sha in shown) continue
                if (repository.photoUsedElsewhere(sha, wallId)) {
                    repository.clearPhotoBlob(wallId, sha)
                    continue
                }
                val servers = servers(url)
                when (deletePhoto(sha, servers)) {
                    PhotoDeletion.DELETED -> forgetDeleted(sha, servers)
                    PhotoDeletion.LEFT -> Unit
                    // The signer declined: asking again for every photo would not help.
                    PhotoDeletion.NOT_AUTHORISED -> return
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "deleting the photos of a failed publish failed", e)
        }
    }

    private suspend fun withdraw(me: String, wallId: String): SprayWithdrawal {
        val versions = repository.versions(wallId)
        val address = SprayWallNostr.wallAddress(me, wallId)
        val dTags = buildList {
            add(SprayWallNostr.wallDTag(me, wallId) to SprayWallNostr.LABEL_WALL)
            versions.forEach { add(SprayWallNostr.versionDTag(me, wallId, it.number) to SprayWallNostr.LABEL_VERSION) }
            // Every climb of mine, also one edited since it went out (its old event is still on the relays).
            repository.myClimbs(wallId).forEach { add(SprayWallNostr.climbDTag(me, it.uuid) to SprayWallNostr.LABEL_CLIMB) }
        }
        var accepted = 0
        suspend fun sent(taken: Int) {
            // From the first tombstone a relay took, the wall is off Nostr, even if the rest fails: nothing of it
            // is published until a later try finishes (a waiting head, a climb or a version would bring it back).
            if (accepted == 0 && taken > 0) repository.markWithdrawStarted(wallId)
            accepted = maxOf(accepted, taken)
        }
        for ((d, label) in dTags) sent(send(SprayWallNostr.tombstone(stamp(d), d, label, address)))
        sent(send(SprayWallNostr.deletion(me, now(), dTags.map { it.first })))
        if (accepted == 0) return SprayWithdrawal.NOT_CONFIRMED
        // One deletion per photo, on the server that took the upload; a photo another public wall of mine shows stays.
        var photosLeft = false
        for ((sha, url) in uploads(wallId, versions)) {
            if (repository.photoUsedElsewhere(sha, wallId)) continue
            val servers = servers(url)
            when (deletePhoto(sha, servers)) {
                PhotoDeletion.DELETED -> forgetDeleted(sha, servers)
                PhotoDeletion.LEFT -> photosLeft = true
                PhotoDeletion.NOT_AUTHORISED -> throw SprayEventSigningFailed(IllegalStateException("Blossom delete authorisation"))
            }
        }
        return if (photosLeft) SprayWithdrawal.PHOTOS_LEFT else SprayWithdrawal.DONE
    }

    /**
     * The photo is gone from [servers]: every wall of mine that recorded it there (or on a server its URL does not
     * name) uploads it anew. A wall that has the same photo on another server keeps it.
     */
    private suspend fun forgetDeleted(sha: String, servers: List<String>) {
        for ((wallId, url) in repository.photoRecords(sha)) {
            val server = url?.let(::blobServer)
            if (server == null || server in servers) repository.clearPhotoBlob(wallId, sha)
        }
    }

    /** Every photo of the wall that went to Blossom, once each: sha256 to its URL. */
    private suspend fun uploads(wallId: String, versions: List<SprayWallVersion>? = null): List<Pair<String, String?>> =
        (versions ?: repository.versions(wallId))
            .mapNotNull { v -> v.photoSha256?.let { sha -> sha to v.photoUrl } }
            .distinctBy { it.first }

    private fun servers(url: String?): List<String> = listOfNotNull(url?.let { blobServer(it) }).ifEmpty { defaultServers }

    /** `https://server/<sha256>` → `https://server`, the server that took the upload. */
    private fun blobServer(url: String): String? =
        url.substringBeforeLast('/', "").takeIf { it.startsWith("https://") }

    private companion object {
        const val TAG = "SprayWallWithdrawal"
    }
}
