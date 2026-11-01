package com.cruxcoach.android.spraywall

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.db.secure.Spray_ascent
import com.cruxcoach.db.secure.Spray_climb
import com.cruxcoach.db.secure.Spray_wall
import com.cruxcoach.db.secure.Spray_wall_hold
import com.cruxcoach.db.secure.Spray_wall_version
import com.cruxcoach.domain.spraywall.EditableHold
import com.cruxcoach.domain.spraywall.SprayAscent
import com.cruxcoach.domain.spraywall.SprayClimb
import com.cruxcoach.domain.spraywall.SprayClimbHolds
import com.cruxcoach.domain.spraywall.SprayGym
import com.cruxcoach.domain.spraywall.SprayHold
import com.cruxcoach.domain.spraywall.SprayHoldDiff
import com.cruxcoach.domain.spraywall.SprayHoldRole
import com.cruxcoach.domain.spraywall.SprayVersionKind
import com.cruxcoach.domain.spraywall.SprayWall
import com.cruxcoach.domain.spraywall.SprayWallHistory
import com.cruxcoach.domain.spraywall.SprayWallSummary
import com.cruxcoach.domain.spraywall.SprayWallVersion
import com.cruxcoach.domain.spraywall.SprayWallVisibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spray walls, their versions, holds, climbs and ascents (FEAT-078), in the
 * encrypted per-identity database. Ascents stay here; public walls and their
 * climbs are published by [SprayWallPublisher]. [changes] ticks after every
 * write so open screens can reload.
 */
@Singleton
class SprayWallRepository @Inject constructor(
    private val database: SecureDatabase,
    private val photos: SprayWallPhotoStore,
) {
    private val queries get() = database.sprayWallsQueries
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** What saving the hold editor did. [newVersion] = a version was added (otherwise the current one was corrected). */
    data class HoldSaveResult(val version: Int, val newVersion: Boolean, val added: Int, val changed: Int, val removed: Int) {
        val changedAnything: Boolean get() = added + changed + removed > 0
    }

    private fun changed() = _changes.update { it + 1 }
    private fun now() = System.currentTimeMillis()

    // ── Walls ───────────────────────────────────────────────────

    suspend fun walls(): List<SprayWallSummary> = io {
        val climbs = queries.countClimbsPerWall().executeAsList().associate { it.wall_id to it.climbs.toInt() }
        queries.selectWalls().executeAsList().mapNotNull { row ->
            val wall = row.toModel() ?: return@mapNotNull null
            SprayWallSummary(wall, wall.current.holdCount, climbs[wall.id] ?: 0)
        }
    }

    suspend fun wall(id: String): SprayWall? = io { queries.selectWall(id).executeAsOneOrNull()?.toModel() }

    /** A new wall on this phone, with its first version: the photo and the holds marked so far. */
    suspend fun createWall(
        name: String,
        angle: Int,
        visibility: SprayWallVisibility,
        gym: SprayGym?,
        photo: SprayWallPhotoStore.StoredPhoto,
        holds: List<EditableHold>,
    ): String = io {
        val id = UUID.randomUUID().toString()
        val t = now()
        database.transaction {
            queries.insertWall(id, name.trim(), angle.toLong(), visibility.wire, gym?.id, gym?.name, null, 1, null, t, t)
            insertVersion(id, 1, SprayVersionKind.CREATED, null, photo.fileName, photo.width, photo.height, null, null, allNew(holds, 1), t)
        }
        id
    }.also { changed() }

    suspend fun updateWallDetails(id: String, name: String, angle: Int, visibility: SprayWallVisibility, gym: SprayGym?) {
        io {
            database.transaction {
                queries.updateWallDetails(name.trim(), angle.toLong(), visibility.wire, gym?.id, gym?.name, now(), id)
                // Going public (again) publishes the current version and the climbs anew.
                if (visibility == SprayWallVisibility.PRIVATE) queries.setPublishedVersion(null, id)
            }
        }
        changed()
    }

    /** Deletes the wall with everything on it, including its logbook entries, and photos no other wall uses. */
    suspend fun deleteWall(id: String) {
        val files = io {
            database.transactionWithResult {
                val files = queries.selectPhotoFiles(id).executeAsList()
                queries.deleteAscentsForWall(id)
                queries.deleteClimbsForWall(id)
                queries.deleteHoldsForWall(id)
                queries.deleteVersionsForWall(id)
                queries.deleteWall(id)
                files.filter { queries.countPhotoFileUses(it).executeAsOne() == 0L }
            }
        }
        files.forEach(photos::delete)
        changed()
    }

    // ── Versions and holds ──────────────────────────────────────

    suspend fun versions(wallId: String): List<SprayWallVersion> = io {
        queries.selectVersions(wallId).executeAsList().map { it.toModel() }
    }

    suspend fun holds(wallId: String, version: Int): List<SprayHold> = io {
        queries.selectHolds(wallId, version.toLong()).executeAsList().map { it.toModel() }
    }

    /** Every hold of every version, e.g. to draw a hold that came off at its last known place. */
    suspend fun allHolds(wallId: String): List<SprayHold> = io {
        queries.selectAllHolds(wallId).executeAsList().map { it.toModel() }
    }

    /**
     * Writes the editor's holds as the wall's next version on the same photo.
     * While the current version has no climbs and was never published (the
     * first marking of a wall), it is corrected in place instead.
     */
    suspend fun saveHolds(wallId: String, edited: List<EditableHold>): HoldSaveResult = io {
        database.transactionWithResult {
            val wall = queries.selectWall(wallId).executeAsOne()
            val currentNumber = wall.current_version.toInt()
            val current = queries.selectVersion(wallId, wall.current_version).executeAsOne().toModel()
            val before = queries.selectHolds(wallId, wall.current_version).executeAsList().map { it.toModel() }
            val diff = SprayHoldDiff.between(before, edited)
            if (diff.isEmpty) return@transactionWithResult HoldSaveResult(currentNumber, false, 0, 0, 0)
            val climbs = queries.selectClimbs(wallId).executeAsList().map { it.toModel() }
            val amend = SprayWallHistory.mayAmend(current, wall.published_version?.toInt(), climbs)
            val withIds = SprayWallHistory.assignIds(edited, queries.maxHoldId(wallId).executeAsOne() + 1)
            val t = now()
            val number = if (amend) {
                queries.deleteHoldsOfVersion(wallId, wall.current_version)
                withIds.forEach { insertHold(wallId, currentNumber, it) }
                queries.setVersionHoldCount(withIds.size.toLong(), wallId, wall.current_version)
                currentNumber
            } else {
                val next = latestVersionNumber(wallId) + 1
                insertVersion(wallId, next, SprayVersionKind.HOLDS, null, current.photoFile, current.photoWidth, current.photoHeight,
                    current.photoSha256, current.photoUrl, withIds, t)
                queries.setCurrentVersion(next.toLong(), t, wallId)
                next
            }
            queries.touchWall(t, wallId)
            HoldSaveResult(number, !amend, diff.added.size, diff.changed.size, diff.removedIds.size)
        }
    }.also { if (it.changedAnything) changed() }

    /**
     * A reset ("Umschrauben"): a new photo with its own holds becomes the
     * current version. Climbs set before stay with the earlier versions.
     */
    suspend fun resetWall(wallId: String, photo: SprayWallPhotoStore.StoredPhoto, holds: List<EditableHold>): Int = io {
        database.transactionWithResult {
            val t = now()
            val next = latestVersionNumber(wallId) + 1
            // Every hold on a new photo is a new hold.
            val withIds = allNew(holds, queries.maxHoldId(wallId).executeAsOne() + 1)
            insertVersion(wallId, next, SprayVersionKind.RESET, null, photo.fileName, photo.width, photo.height, null, null, withIds, t)
            queries.setCurrentVersion(next.toLong(), t, wallId)
            next
        }
    }.also { changed() }

    /** Makes an earlier version current again, as a new version that copies it. */
    suspend fun restoreVersion(wallId: String, version: Int): Int = io {
        database.transactionWithResult {
            val source = queries.selectVersion(wallId, version.toLong()).executeAsOne().toModel()
            val holds = queries.selectHolds(wallId, version.toLong()).executeAsList().map { EditableHold(it.hold_id, it.x.toFloat(), it.y.toFloat(), it.radius.toFloat()) }
            val t = now()
            val next = latestVersionNumber(wallId) + 1
            insertVersion(wallId, next, SprayVersionKind.RESTORED, version, source.photoFile, source.photoWidth, source.photoHeight,
                source.photoSha256, source.photoUrl, holds, t)
            queries.setCurrentVersion(next.toLong(), t, wallId)
            next
        }
    }.also { changed() }

    /**
     * Drops the current version when nothing depends on it yet (no climbs,
     * never published), e.g. a reset that was not finished. The previous
     * version becomes current again. Returns false when it cannot be dropped.
     */
    suspend fun discardCurrentVersion(wallId: String): Boolean {
        val dropped = io {
            database.transactionWithResult {
                val wall = queries.selectWall(wallId).executeAsOneOrNull() ?: return@transactionWithResult null
                val current = queries.selectVersion(wallId, wall.current_version).executeAsOne().toModel()
                val climbs = queries.selectClimbs(wallId).executeAsList().map { it.toModel() }
                val previous = queries.selectVersions(wallId).executeAsList().map { it.version.toInt() }.filter { it < current.number }.maxOrNull()
                if (previous == null || !SprayWallHistory.mayAmend(current, wall.published_version?.toInt(), climbs)) {
                    return@transactionWithResult null
                }
                queries.deleteHoldsOfVersion(wallId, wall.current_version)
                queries.deleteVersion(wallId, wall.current_version)
                queries.setCurrentVersion(previous.toLong(), now(), wallId)
                current.photoFile.takeIf { queries.countPhotoFileUses(it).executeAsOne() == 0L }.orEmpty()
            }
        } ?: return false
        if (dropped.isNotEmpty()) photos.delete(dropped)
        changed()
        return true
    }

    private fun allNew(holds: List<EditableHold>, firstId: Long): List<EditableHold> {
        var next = firstId
        return holds.map { it.copy(id = next++) }
    }

    private fun latestVersionNumber(wallId: String): Int =
        queries.selectVersions(wallId).executeAsList().maxOfOrNull { it.version.toInt() } ?: 0

    private fun insertVersion(
        wallId: String, number: Int, kind: SprayVersionKind, basedOn: Int?,
        photoFile: String, width: Int, height: Int, sha256: String?, url: String?,
        holds: List<EditableHold>, createdAt: Long,
    ) {
        queries.insertVersion(wallId, number.toLong(), kind.wire, basedOn?.toLong(), photoFile, width.toLong(), height.toLong(),
            sha256, url, holds.size.toLong(), createdAt)
        holds.forEach { insertHold(wallId, number, it) }
    }

    private fun insertHold(wallId: String, version: Int, h: EditableHold) {
        require(h.id >= 0) { "hold without a stored id" }
        queries.insertHold(wallId, version.toLong(), h.id, h.x.toDouble(), h.y.toDouble(), h.radius.toDouble())
    }

    // ── Climbs ──────────────────────────────────────────────────

    suspend fun climbs(wallId: String): List<SprayClimb> = io { queries.selectClimbs(wallId).executeAsList().map { it.toModel() } }

    suspend fun climb(uuid: String): SprayClimb? = io { queries.selectClimb(uuid).executeAsOneOrNull()?.toModel() }

    /** Saves a new climb (null [uuid]) or updates one, on the wall's current version; returns its uuid. */
    suspend fun saveClimb(
        uuid: String?,
        wallId: String,
        name: String,
        description: String,
        difficulty: Int?,
        holds: Map<Long, SprayHoldRole>,
    ): String = io {
        val t = now()
        val encoded = SprayClimbHolds.encode(holds)
        database.transactionWithResult {
            val version = queries.selectWall(wallId).executeAsOne().current_version
            val existing = uuid?.let { queries.selectClimb(it).executeAsOneOrNull() }
            if (existing != null) {
                queries.updateClimb(version, name.trim(), description.trim(), difficulty?.toLong(), encoded, t, existing.uuid)
                queries.touchWall(t, wallId)
                existing.uuid
            } else {
                val id = uuid ?: UUID.randomUUID().toString()
                queries.insertClimb(id, wallId, version, name.trim(), description.trim(), difficulty?.toLong(), encoded, null, null, t, t)
                queries.touchWall(t, wallId)
                id
            }
        }
    }.also { changed() }

    /** Deletes a climb with its logbook entries. Returns the deleted climb (so a published one can be withdrawn). */
    suspend fun deleteClimb(uuid: String): SprayClimb? = io {
        database.transactionWithResult {
            val climb = queries.selectClimb(uuid).executeAsOneOrNull()?.toModel()
            queries.deleteAscentsForClimb(uuid)
            queries.deleteClimb(uuid)
            climb
        }
    }.also { changed() }

    // ── Ascents (private) ───────────────────────────────────────

    suspend fun ascents(climbUuid: String): List<SprayAscent> = io {
        queries.selectAscentsForClimb(climbUuid).executeAsList().map { it.toModel() }
    }

    suspend fun sendCounts(wallId: String): Map<String, Int> = io {
        queries.sendCountsForWall(wallId).executeAsList().associate { it.climb_uuid to it.sends.toInt() }
    }

    suspend fun logAscent(
        climb: SprayClimb,
        isSend: Boolean,
        tries: Int,
        difficulty: Int?,
        comment: String?,
        climbedAt: LocalDateTime = LocalDateTime.now(),
    ): String = io {
        val id = UUID.randomUUID().toString()
        queries.insertAscent(
            id, climb.uuid, climb.wallId, if (isSend) 1L else 0L, tries.coerceIn(1, MAX_TRIES).toLong(),
            difficulty?.toLong(), comment?.trim()?.takeIf { it.isNotEmpty() },
            climbedAt.truncatedTo(ChronoUnit.SECONDS).toString(),
        )
        id
    }.also { changed() }

    suspend fun deleteAscent(uuid: String) {
        io { queries.deleteAscent(uuid) }
        changed()
    }

    // ── Publishing state (used by SprayWallPublisher) ───────────

    suspend fun wallsNeedingPublish(): List<SprayWall> = io {
        queries.selectWallsNeedingPublish().executeAsList().mapNotNull { it.toModel() }
    }

    suspend fun unpublishedClimbs(wallId: String): List<SprayClimb> = io {
        queries.selectUnpublishedClimbs(wallId).executeAsList().map { it.toModel() }
    }

    suspend fun setPhotoBlob(wallId: String, photoFile: String, sha256: String, url: String) {
        io { queries.setPhotoBlob(sha256, url, wallId, photoFile) }
    }

    suspend fun markPublished(wallId: String, version: Int) {
        io { queries.setPublishedVersion(version.toLong(), wallId) }
        changed()
    }

    /** Only if the climb was not edited meanwhile ([updatedAt] still matches). */
    suspend fun markClimbPublished(uuid: String, updatedAt: Long) {
        io { queries.setClimbPublished(now(), uuid, updatedAt) }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun Spray_wall.toModel(): SprayWall? {
        val version = queries.selectVersion(id, current_version).executeAsOneOrNull()?.toModel() ?: return null
        return SprayWall(
            id = id, name = name, angle = angle.toInt(),
            visibility = SprayWallVisibility.fromWire(visibility),
            gym = gym_id?.let { SprayGym(it, gym_name.orEmpty()) },
            ownerPubkey = owner_pubkey,
            current = version,
            publishedVersion = published_version?.toInt(),
            createdAt = created_at, updatedAt = updated_at,
        )
    }

    companion object {
        const val MAX_TRIES = 999
    }
}

internal fun Spray_wall_version.toModel() = SprayWallVersion(
    wallId = wall_id, number = version.toInt(), kind = SprayVersionKind.fromWire(kind), basedOn = based_on?.toInt(),
    photoFile = photo_file, photoWidth = photo_width.toInt(), photoHeight = photo_height.toInt(),
    photoSha256 = photo_sha256, photoUrl = photo_url, holdCount = hold_count.toInt(), createdAt = created_at,
)

internal fun Spray_wall_hold.toModel() =
    SprayHold(wall_id, version.toInt(), hold_id, x.toFloat(), y.toFloat(), radius.toFloat())

internal fun Spray_climb.toModel() = SprayClimb(
    uuid = uuid, wallId = wall_id, version = version.toInt(), name = name, description = description,
    difficulty = difficulty?.toInt(), holds = SprayClimbHolds.decode(holds), createdAt = created_at, updatedAt = updated_at,
    authorPubkey = author_pubkey, publishedAt = published_at,
)

internal fun Spray_ascent.toModel() =
    SprayAscent(uuid, climb_uuid, wall_id, is_send != 0L, tries.toInt(), difficulty?.toInt(), comment, climbed_at)
