package com.cruxcoach.android.spraywall

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.db.secure.Spray_ascent
import com.cruxcoach.db.secure.Spray_climb
import com.cruxcoach.db.secure.Spray_wall
import com.cruxcoach.db.secure.Spray_wall_hold
import com.cruxcoach.domain.spraywall.EditableHold
import com.cruxcoach.domain.spraywall.SprayAscent
import com.cruxcoach.domain.spraywall.SprayClimb
import com.cruxcoach.domain.spraywall.SprayClimbHolds
import com.cruxcoach.domain.spraywall.SprayHold
import com.cruxcoach.domain.spraywall.SprayHoldDiff
import com.cruxcoach.domain.spraywall.SprayHoldRole
import com.cruxcoach.domain.spraywall.SprayWall
import com.cruxcoach.domain.spraywall.SprayWallSummary
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
 * Spray walls, their holds, climbs and ascents (FEAT-078), in the encrypted
 * per-identity database. Nothing here is synced, published or shared.
 * [changes] ticks after every write so open screens can reload.
 */
@Singleton
class SprayWallRepository @Inject constructor(
    private val database: SecureDatabase,
    private val photos: SprayWallPhotoStore,
) {
    private val queries get() = database.sprayWallsQueries
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** What saving the hold editor did. [keptForClimbs] holds came off the wall but stay drawn on the climbs that use them. */
    data class HoldSaveResult(val added: Int, val changed: Int, val removed: Int, val keptForClimbs: Int)

    private fun changed() = _changes.update { it + 1 }
    private fun now() = System.currentTimeMillis()

    // ── Walls ───────────────────────────────────────────────────

    suspend fun walls(): List<SprayWallSummary> = io {
        queries.selectWallsWithCounts().executeAsList().map {
            SprayWallSummary(
                wall = SprayWall(it.id, it.name, it.angle.toInt(), it.photo_file, it.photo_width.toInt(), it.photo_height.toInt(), it.created_at, it.updated_at),
                holdCount = it.hold_count.toInt(),
                climbCount = it.climb_count.toInt(),
            )
        }
    }

    suspend fun wall(id: String): SprayWall? = io { queries.selectWall(id).executeAsOneOrNull()?.toModel() }

    suspend fun createWall(name: String, angle: Int, photo: SprayWallPhotoStore.StoredPhoto): String = io {
        val id = UUID.randomUUID().toString()
        val t = now()
        queries.insertWall(id, name.trim(), angle.toLong(), photo.fileName, photo.width.toLong(), photo.height.toLong(), t, t)
        id
    }.also { changed() }

    suspend fun updateWall(id: String, name: String, angle: Int) {
        io { queries.updateWall(name.trim(), angle.toLong(), now(), id) }
        changed()
    }

    /** Deletes the wall with everything on it, including its logbook entries and the photo. */
    suspend fun deleteWall(id: String) {
        val photo = io {
            database.transactionWithResult {
                val wall = queries.selectWall(id).executeAsOneOrNull()
                queries.deleteAscentsForWall(id)
                queries.deleteClimbsForWall(id)
                queries.deleteHoldsForWall(id)
                queries.deleteWall(id)
                wall?.photo_file
            }
        }
        photo?.let(photos::delete)
        changed()
    }

    // ── Holds ───────────────────────────────────────────────────

    /** All holds of a wall, including those that came off it but are still used by a climb. */
    suspend fun holds(wallId: String): List<SprayHold> = io { queries.selectHolds(wallId).executeAsList().map { it.toModel() } }

    /**
     * Writes the editor's holds. New holds get the next free ids; a removed
     * hold that a climb uses is marked as gone instead of deleted, so the
     * climb keeps it and can show it lost a hold.
     */
    suspend fun saveHolds(wallId: String, edited: List<EditableHold>): HoldSaveResult = io {
        database.transactionWithResult {
            val current = queries.selectHolds(wallId).executeAsList().map { it.toModel() }
            val diff = SprayHoldDiff.between(current, edited)
            if (diff.isEmpty) return@transactionWithResult HoldSaveResult(0, 0, 0, 0)
            val t = now()
            var next = queries.maxHoldId(wallId).executeAsOne() + 1
            diff.added.forEach { h ->
                queries.insertHold(wallId, next++, h.x.toDouble(), h.y.toDouble(), h.radius.toDouble(), t)
            }
            diff.changed.forEach { h ->
                queries.updateHold(h.x.toDouble(), h.y.toDouble(), h.radius.toDouble(), wallId, h.id)
            }
            val used = queries.selectClimbs(wallId).executeAsList()
                .flatMapTo(HashSet()) { SprayClimbHolds.decode(it.holds).keys }
            var kept = 0
            diff.removedIds.forEach { id ->
                if (id in used) {
                    queries.markHoldRemoved(t, wallId, id)
                    kept++
                } else {
                    queries.deleteHold(wallId, id)
                }
            }
            queries.touchWall(t, wallId)
            HoldSaveResult(diff.added.size, diff.changed.size, diff.removedIds.size, kept)
        }
    }.also { if (it.added + it.changed + it.removed > 0) changed() }

    // ── Climbs ──────────────────────────────────────────────────

    suspend fun climbs(wallId: String): List<SprayClimb> = io { queries.selectClimbs(wallId).executeAsList().map { it.toModel() } }

    suspend fun climb(uuid: String): SprayClimb? = io { queries.selectClimb(uuid).executeAsOneOrNull()?.toModel() }

    /** Saves a new climb (null [uuid]) or updates one; returns its uuid. */
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
            val existing = uuid?.let { queries.selectClimb(it).executeAsOneOrNull() }
            if (existing != null) {
                queries.updateClimb(name.trim(), description.trim(), difficulty?.toLong(), encoded, t, existing.uuid)
                queries.touchWall(t, wallId)
                existing.uuid
            } else {
                val id = uuid ?: UUID.randomUUID().toString()
                queries.insertClimb(id, wallId, name.trim(), description.trim(), difficulty?.toLong(), encoded, t, t)
                queries.touchWall(t, wallId)
                id
            }
        }
    }.also { changed() }

    /** Deletes a climb with its logbook entries. */
    suspend fun deleteClimb(uuid: String) {
        io {
            database.transaction {
                queries.deleteAscentsForClimb(uuid)
                queries.deleteClimb(uuid)
            }
        }
        changed()
    }

    // ── Ascents ─────────────────────────────────────────────────

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

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val MAX_TRIES = 999
    }
}

private fun Spray_wall.toModel() =
    SprayWall(id, name, angle.toInt(), photo_file, photo_width.toInt(), photo_height.toInt(), created_at, updated_at)

private fun Spray_wall_hold.toModel() =
    SprayHold(wall_id, hold_id, x.toFloat(), y.toFloat(), radius.toFloat(), added_at, removed_at)

private fun Spray_climb.toModel() =
    SprayClimb(uuid, wall_id, name, description, difficulty?.toInt(), SprayClimbHolds.decode(holds), created_at, updated_at)

private fun Spray_ascent.toModel() =
    SprayAscent(uuid, climb_uuid, wall_id, is_send != 0L, tries.toInt(), difficulty?.toInt(), comment, climbed_at)
