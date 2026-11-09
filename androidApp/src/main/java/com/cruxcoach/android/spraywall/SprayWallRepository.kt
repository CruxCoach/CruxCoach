package com.cruxcoach.android.spraywall

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.db.secure.Spray_ascent
import com.cruxcoach.db.secure.Spray_climb
import com.cruxcoach.db.secure.Spray_wall
import com.cruxcoach.db.secure.Spray_wall_hold
import com.cruxcoach.db.secure.Spray_wall_version
import com.cruxcoach.domain.spraywall.EditableHold
import com.cruxcoach.domain.spraywall.HoldOutline
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
import com.cruxcoach.domain.spraywall.SprayWallNostr
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

    /** [sendHead]: the wall is published and its head goes out again with these details, also after a restart. */
    suspend fun updateWallDetails(
        id: String,
        name: String,
        angle: Int,
        visibility: SprayWallVisibility,
        gym: SprayGym?,
        sendHead: Boolean = false,
    ) {
        io {
            database.transaction {
                queries.updateWallDetails(name.trim(), angle.toLong(), visibility.wire, gym?.id, gym?.name, now(), id)
                // Going public (again) publishes every version and the climbs anew.
                if (visibility == SprayWallVisibility.PRIVATE) {
                    queries.setPublishedVersion(null, id)
                    queries.clearClimbsPublished(id)
                    queries.dropHeadPending(id)
                    // Private is where a withdrawal ends.
                    queries.clearWithdrawStarted(id)
                } else if (sendHead) {
                    queries.markHeadPending(id)
                }
            }
        }
        changed()
    }

    /** My published walls whose head still has to go out with new details. */
    suspend fun wallsWithHeadPending(): List<SprayWall> = io {
        queries.selectWallsWithHeadPending().executeAsList().mapNotNull { it.toModel() }
    }

    /**
     * Withdrawing [id] is about to send its first tombstone: nothing of the wall is published (a waiting head stays
     * back) until the withdrawal is done and the wall is private, or [clearWithdrawStarted] says no relay took anything.
     */
    suspend fun markWithdrawStarted(id: String) {
        io { queries.markWithdrawStarted(now(), id) }
        changed()
    }

    /** No relay took a tombstone of the withdrawal: the relays still show the wall, and it publishes as before. */
    suspend fun clearWithdrawStarted(id: String) {
        io { queries.clearWithdrawStarted(id) }
        changed()
    }

    /** Public walls my climbs may go out on: not one the relays already took back. */
    suspend fun wallsTakingClimbs(): List<SprayWall> = io {
        queries.selectWallsTakingClimbs().executeAsList().mapNotNull { it.toModel() }
    }

    /** A version of [id] reached the relays before its head: the head is to go out on its own if this run fails. */
    suspend fun markHeadPending(id: String) {
        io { queries.markHeadPending(id) }
    }

    /**
     * A relay took the head built while [pending] was the wall's mark; a change since then stays pending. The wall page
     * shows a pending head ("Upload pending"), so it hears of this too.
     */
    suspend fun clearHeadPending(id: String, pending: Long) {
        io { queries.clearHeadPending(id, pending) }
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
            val holds = queries.selectHolds(wallId, version.toLong()).executeAsList().map { it.toModel() }
                .map { EditableHold(it.id, it.x, it.y, it.radius, it.outline) }
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
            database.transactionWithResult<String?> {
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
        val outline = HoldOutline.fit(h.outline)?.let(HoldOutline::encode)
        queries.insertHold(wallId, version.toLong(), h.id, h.x.toDouble(), h.y.toDouble(), h.radius.toDouble(), outline)
    }

    // ── Climbs ──────────────────────────────────────────────────

    suspend fun climbs(wallId: String): List<SprayClimb> = io { queries.selectClimbs(wallId).executeAsList().map { it.toModel() } }

    suspend fun climb(uuid: String): SprayClimb? = io { queries.selectClimb(uuid).executeAsOneOrNull()?.toModel() }

    /**
     * Saves a new climb (null [uuid]) or updates one; returns its uuid. The
     * climb goes onto the wall's current version, or stays on [version] (a
     * climb from before a reset is edited on the photo it was set on).
     */
    suspend fun saveClimb(
        uuid: String?,
        wallId: String,
        name: String,
        description: String,
        difficulty: Int?,
        holds: Map<Long, SprayHoldRole>,
        version: Int? = null,
    ): String = io {
        val t = now()
        val encoded = SprayClimbHolds.encode(holds)
        database.transactionWithResult {
            val version = version?.toLong() ?: queries.selectWall(wallId).executeAsOne().current_version
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

    /** After a withdrawal deleted the photos from Blossom: they are uploaded again if the wall goes public again. */
    suspend fun clearPhotoBlobs(wallId: String) {
        io { queries.clearPhotoBlobs(wallId) }
    }

    /** [wallId] forgets one Blossom photo (deleted from its server, or kept there by another wall); the next publish uploads it anew. */
    suspend fun clearPhotoBlob(wallId: String, sha256: String) {
        io { queries.clearPhotoBlob(wallId, sha256) }
    }

    /** Which of my walls recorded this Blossom photo, and at which URL. */
    suspend fun photoRecords(sha256: String): List<Pair<String, String?>> = io {
        queries.selectPhotoRecords(sha256).executeAsList().map { it.wall_id to it.photo_url }
    }

    /** Whether another public wall of mine still uses this Blossom photo (then it stays on the servers). */
    suspend fun photoUsedElsewhere(sha256: String, wallId: String): Boolean = io {
        queries.countPublishedPhotoUses(sha256, wallId).executeAsOne() > 0
    }

    suspend fun markPublished(wallId: String, version: Int) {
        io { queries.setPublishedVersion(version.toLong(), wallId) }
        changed()
    }

    /** Only if the climb was not edited meanwhile ([updatedAt] still matches). */
    suspend fun markClimbPublished(uuid: String, updatedAt: Long) {
        io { queries.setClimbPublished(now(), uuid, updatedAt) }
    }

    // ── Other people's public walls (SprayWallFetcher) ──────────

    /** A version of someone else's wall as read from Nostr, with its photo stored here. */
    data class RemoteVersion(val parsed: SprayWallNostr.Parsed.Version, val photo: SprayWallPhotoStore.StoredPhoto)

    /**
     * Stores or updates someone else's public wall. Versions never change, so
     * known ones stay as they are. Returns null when the id already belongs
     * to a different wall on this phone.
     */
    suspend fun importRemoteWall(head: SprayWallNostr.Parsed.WallHead, versions: List<RemoteVersion>): String? = io {
        database.transactionWithResult<String?> {
            val existing = queries.selectWall(head.wallId).executeAsOneOrNull()
            if (existing != null && existing.owner_pubkey != head.author) return@transactionWithResult null
            val known = queries.selectVersions(head.wallId).executeAsList().map { it.version.toInt() }.toSet()
            val available = known + versions.map { it.parsed.number }
            val current = head.currentVersion.takeIf { it in available }
                ?: existing?.current_version?.toInt()
                ?: return@transactionWithResult null
            val t = now()
            if (existing == null) {
                queries.insertWall(head.wallId, head.name, head.angle.toLong(), SprayWallVisibility.PUBLIC.wire, head.gym?.id, head.gym?.name,
                    head.author, current.toLong(), current.toLong(), t, t)
            } else {
                queries.updateRemoteWall(head.name, head.angle.toLong(), SprayWallVisibility.PUBLIC.wire, head.gym?.id, head.gym?.name,
                    current.toLong(), current.toLong(), t, head.wallId, head.author)
            }
            for (v in versions) {
                val p = v.parsed
                if (p.number in known) continue
                insertVersion(head.wallId, p.number, p.kind, p.basedOn, v.photo.fileName, v.photo.width, v.photo.height,
                    p.photoSha256, p.photoUrl, p.holds.map { EditableHold(it.id, it.x, it.y, it.radius, it.outline) }, p.createdAt * 1000)
            }
            head.wallId
        }
    }.also { if (it != null) changed() }

    /** A climb someone published on a public wall here; a newer one replaces it. */
    suspend fun upsertRemoteClimb(c: SprayWallNostr.Parsed.Climb) {
        val wrote = io {
            database.transactionWithResult {
                if (queries.selectWall(c.wallId).executeAsOneOrNull() == null) return@transactionWithResult false
                val t = c.createdAt * 1000
                val holds = SprayClimbHolds.encode(c.holds)
                val existing = queries.selectClimb(c.uuid).executeAsOneOrNull()
                when {
                    existing == null -> {
                        queries.insertClimb(c.uuid, c.wallId, c.version.toLong(), c.name, c.description, c.difficulty?.toLong(), holds, c.author, t, t, t)
                        true
                    }
                    existing.author_pubkey == c.author && existing.wall_id == c.wallId && existing.updated_at < t -> {
                        queries.replaceRemoteClimb(c.version.toLong(), c.name, c.description, c.difficulty?.toLong(), holds, t, c.uuid, c.author)
                        true
                    }
                    else -> false
                }
            }
        }
        if (wrote) changed()
    }

    /** Its setter took a climb back. It stays here only while it carries logbook entries of mine. */
    suspend fun removeRemoteClimb(author: String, uuid: String) {
        val removed = io {
            database.transactionWithResult {
                val climb = queries.selectClimb(uuid).executeAsOneOrNull() ?: return@transactionWithResult false
                if (climb.author_pubkey != author) return@transactionWithResult false
                if (queries.selectAscentsForClimb(uuid).executeAsList().isNotEmpty()) return@transactionWithResult false
                queries.deleteRemoteClimb(uuid, author)
                true
            }
        }
        if (removed) changed()
    }

    /** Its owner took a wall back: the copy here stays for the logbook, but is no longer public. */
    suspend fun markRemoteWallWithdrawn(owner: String, wallId: String) {
        io { queries.setRemoteWallVisibility(SprayWallVisibility.PRIVATE.wire, now(), wallId, owner) }
        changed()
    }

    /** A photo already here with this Blossom hash (versions on the same photo share it). */
    suspend fun photoWithHash(sha256: String): SprayWallPhotoStore.StoredPhoto? = io {
        queries.selectPhotoWithHash(sha256).executeAsOneOrNull()
            ?.takeIf { photos.file(it.photo_file) != null }
            ?.let { SprayWallPhotoStore.StoredPhoto(it.photo_file, it.photo_width.toInt(), it.photo_height.toInt()) }
    }

    /** Public walls here, mine and followed ones. */
    suspend fun publicWalls(): List<SprayWall> = io {
        queries.selectPublicWalls().executeAsList().mapNotNull { it.toModel() }
    }

    /** My climbs on [wallId], published or not. */
    suspend fun myClimbs(wallId: String): List<SprayClimb> = io {
        queries.selectClimbs(wallId).executeAsList().map { it.toModel() }.filter { it.isMine }
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
            headPending = head_pending,
            withdrawStarted = withdraw_started,
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
    SprayHold(wall_id, version.toInt(), hold_id, x.toFloat(), y.toFloat(), radius.toFloat(), outline?.let(HoldOutline::decode))

internal fun Spray_climb.toModel() = SprayClimb(
    uuid = uuid, wallId = wall_id, version = version.toInt(), name = name, description = description,
    difficulty = difficulty?.toInt(), holds = SprayClimbHolds.decode(holds), createdAt = created_at, updatedAt = updated_at,
    authorPubkey = author_pubkey, publishedAt = published_at,
)

internal fun Spray_ascent.toModel() =
    SprayAscent(uuid, climb_uuid, wall_id, is_send != 0L, tries.toInt(), difficulty?.toInt(), comment, climbed_at)
