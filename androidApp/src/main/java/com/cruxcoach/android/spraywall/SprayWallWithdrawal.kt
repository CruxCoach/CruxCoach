package com.cruxcoach.android.spraywall

import android.util.Log
import com.cruxcoach.domain.spraywall.SprayNostrEvent
import com.cruxcoach.domain.spraywall.SprayWallNostr
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
 * Takes one of my public walls back (FEAT-078): every event it published
 * (head, versions, my climbs on it) is replaced by a tombstone and named in a
 * kind-5 deletion, then its photos are deleted from the Blossom servers that
 * took them. Only when all of that worked does the wall turn private here;
 * otherwise it stays public, so the climber can try again, and nothing is
 * deleted. Never throws, also not when the signer fails.
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
        if (!wall.isMine || published == null) {
            // Never on Nostr: nothing to take back.
            repository.updateWallDetails(wallId, wall.name, wall.angle, SprayWallVisibility.PRIVATE, wall.gym)
            return SprayWithdrawal.DONE
        }
        if (me == null) return SprayWithdrawal.NO_IDENTITY
        val outcome = try {
            withdraw(me, wallId, published)
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

    private suspend fun withdraw(me: String, wallId: String, published: Int): SprayWithdrawal {
        val versions = repository.versions(wallId).filter { it.number <= published }
        val address = SprayWallNostr.wallAddress(me, wallId)
        val dTags = buildList {
            add(SprayWallNostr.wallDTag(me, wallId) to SprayWallNostr.LABEL_WALL)
            versions.forEach { add(SprayWallNostr.versionDTag(me, wallId, it.number) to SprayWallNostr.LABEL_VERSION) }
            repository.publishedClimbs(wallId).forEach { add(SprayWallNostr.climbDTag(me, it.uuid) to SprayWallNostr.LABEL_CLIMB) }
        }
        var accepted = 0
        for ((d, label) in dTags) accepted = maxOf(accepted, send(SprayWallNostr.tombstone(stamp(d), d, label, address)))
        accepted = maxOf(accepted, send(SprayWallNostr.deletion(me, now(), dTags.map { it.first })))
        if (accepted == 0) return SprayWithdrawal.NOT_CONFIRMED
        // One deletion per photo, on the server that took the upload; a photo another public wall of mine shows stays.
        val blobs = versions.mapNotNull { v -> v.photoSha256?.let { sha -> sha to v.photoUrl } }.distinctBy { it.first }
        var photosLeft = false
        for ((sha, url) in blobs) {
            if (repository.photoUsedElsewhere(sha, wallId)) continue
            val servers = listOfNotNull(url?.let { blobServer(it) }).ifEmpty { defaultServers }
            when (deletePhoto(sha, servers)) {
                PhotoDeletion.DELETED -> Unit
                PhotoDeletion.LEFT -> photosLeft = true
                PhotoDeletion.NOT_AUTHORISED -> throw SprayEventSigningFailed(IllegalStateException("Blossom delete authorisation"))
            }
        }
        return if (photosLeft) SprayWithdrawal.PHOTOS_LEFT else SprayWithdrawal.DONE
    }

    /** `https://server/<sha256>` → `https://server`, the server that took the upload. */
    private fun blobServer(url: String): String? =
        url.substringBeforeLast('/', "").takeIf { it.startsWith("https://") }

    private companion object {
        const val TAG = "SprayWallWithdrawal"
    }
}
