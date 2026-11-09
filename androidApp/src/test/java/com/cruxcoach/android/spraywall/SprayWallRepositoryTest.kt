package com.cruxcoach.android.spraywall

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.spraywall.EditableHold
import com.cruxcoach.domain.spraywall.SprayClimbRules
import com.cruxcoach.domain.spraywall.SprayGym
import com.cruxcoach.domain.spraywall.SprayHold
import com.cruxcoach.domain.spraywall.SprayHoldRole
import com.cruxcoach.domain.spraywall.SprayVersionKind
import com.cruxcoach.domain.spraywall.SprayWallHistory
import com.cruxcoach.domain.spraywall.SprayWallNostr
import com.cruxcoach.domain.spraywall.SprayWallVisibility
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SprayWallRepositoryTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: SecureDatabase
    private val photos = mockk<SprayWallPhotoStore>(relaxed = true)
    private lateinit var repository: SprayWallRepository
    private val photo = SprayWallPhotoStore.StoredPhoto("0b1c2d3e-photo.jpg", 3000, 2000)
    private val newPhoto = SprayWallPhotoStore.StoredPhoto("4f5a6b7c-photo.jpg", 2000, 3000)

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SecureDatabase.Schema.create(driver)
        database = SecureDatabase(driver)
        repository = SprayWallRepository(database, photos)
    }

    @AfterTest
    fun tearDown() = driver.close()

    private fun hold(id: Long, x: Float) = EditableHold(id, x, 0.5f, 0.02f)

    private suspend fun newWall(holds: List<EditableHold> = emptyList(), name: String = "Wall") =
        repository.createWall(name, 40, SprayWallVisibility.PRIVATE, null, photo, holds)

    private suspend fun line(wall: String, vararg holds: Pair<Long, SprayHoldRole>) =
        repository.saveClimb(null, wall, "Line", "", 20, holds.toMap())

    @Test
    fun `a new wall starts with version 1 and is listed with its counts`() = runTest {
        val id = repository.createWall("  Garage  ", 35, SprayWallVisibility.PRIVATE, SprayGym("v1", "Boulderhalle"), photo, listOf(hold(-1, 0.1f), hold(-2, 0.2f), hold(-3, 0.3f)))

        val summary = repository.walls().single()
        assertEquals("Garage", summary.wall.name)
        assertEquals(35, summary.wall.angle)
        assertEquals(SprayGym("v1", "Boulderhalle"), summary.wall.gym)
        assertEquals(3000, summary.wall.photoWidth)
        assertEquals(3, summary.holdCount)
        assertEquals(0, summary.climbCount)
        val current = summary.wall.current
        assertEquals(1, current.number)
        assertEquals(SprayVersionKind.CREATED, current.kind)
        assertEquals(listOf(1L, 2L, 3L), repository.holds(id, 1).map { it.id })
    }

    @Test
    fun `the first marking is saved in place until a climb is set on it`() = runTest {
        val wall = newWall()
        val first = repository.saveHolds(wall, listOf(hold(-1, 0.1f), hold(-2, 0.2f)))
        val again = repository.saveHolds(wall, listOf(hold(1, 0.1f), hold(2, 0.25f), hold(-1, 0.3f)))
        assertFalse(first.newVersion)
        assertFalse(again.newVersion)
        assertEquals(listOf(1), repository.versions(wall).map { it.number })
        assertEquals(3, repository.wall(wall)!!.current.holdCount)

        line(wall, 1L to SprayHoldRole.START, 2L to SprayHoldRole.FINISH)
        val edit = repository.saveHolds(wall, listOf(hold(1, 0.1f), hold(2, 0.25f)))
        assertTrue(edit.newVersion)
        assertEquals(2, edit.version)
        assertEquals(listOf(2, 1), repository.versions(wall).map { it.number })
        // Version 1 is untouched.
        assertEquals(3, repository.holds(wall, 1).size)
    }

    @Test
    fun `a hold that comes off stays with the climbs that use it`() = runTest {
        val wall = newWall(listOf(hold(-1, 0.1f), hold(-2, 0.2f), hold(-3, 0.3f), hold(-4, 0.4f)))
        val climbId = line(wall, 1L to SprayHoldRole.START, 2L to SprayHoldRole.HAND, 3L to SprayHoldRole.FINISH)

        // Hold 2 (used) and hold 4 (unused) come off, hold 1 moves, one hold is added.
        val result = repository.saveHolds(wall, listOf(EditableHold(1, 0.15f, 0.5f, 0.02f), hold(3, 0.3f), hold(-1, 0.9f)))

        assertEquals(1, result.added)
        assertEquals(1, result.changed)
        assertEquals(2, result.removed)
        val current = repository.holds(wall, result.version).associateBy { it.id }
        assertEquals(setOf(1L, 3L, 5L), current.keys, "new ids continue after the highest id ever used")
        assertEquals(0.15f, current[1L]?.x)
        val climb = repository.climb(climbId)!!
        assertEquals(1, climb.version, "the climb remembers the version it was set on")
        assertEquals(1, SprayClimbRules.lostHoldCount(climb, current.keys))
        // The gone hold is still drawn where it was last seen.
        assertEquals(0.2f, SprayWallHistory.lastKnown(repository.allHolds(wall))[2L]?.x)
    }

    @Test
    fun `saving unchanged holds writes nothing`() = runTest {
        val wall = newWall(listOf(hold(-1, 0.1f)))
        val before = repository.changes.value
        val result = repository.saveHolds(wall, listOf(hold(1, 0.1f)))
        assertEquals(SprayWallRepository.HoldSaveResult(1, false, 0, 0, 0), result)
        assertEquals(before, repository.changes.value)
    }

    @Test
    fun `a reset adds a version on the new photo and earlier climbs stay behind`() = runTest {
        val wall = newWall(listOf(hold(-1, 0.1f), hold(-2, 0.2f)))
        line(wall, 1L to SprayHoldRole.START, 2L to SprayHoldRole.FINISH)

        val number = repository.resetWall(wall, newPhoto, listOf(hold(-1, 0.5f)))

        val current = repository.wall(wall)!!.current
        assertEquals(2, number)
        assertEquals(SprayVersionKind.RESET, current.kind)
        assertEquals(newPhoto.fileName, current.photoFile)
        assertEquals(listOf(3L), repository.holds(wall, 2).map { it.id }, "every hold on a new photo is a new hold")
        assertEquals(setOf(1), SprayWallHistory.earlierVersions(repository.versions(wall), current))
    }

    @Test
    fun `restoring an old version makes it current again as a new version`() = runTest {
        val wall = newWall(listOf(hold(-1, 0.1f), hold(-2, 0.2f)))
        line(wall, 1L to SprayHoldRole.START, 2L to SprayHoldRole.FINISH)
        repository.resetWall(wall, newPhoto, listOf(hold(-1, 0.5f)))

        val number = repository.restoreVersion(wall, 1)

        val current = repository.wall(wall)!!.current
        assertEquals(3, number)
        assertEquals(SprayVersionKind.RESTORED, current.kind)
        assertEquals(1, current.basedOn)
        assertEquals(photo.fileName, current.photoFile)
        assertEquals(listOf(1L, 2L), repository.holds(wall, 3).map { it.id })
        // The climbs of the first photo are current again, the reset's version is the earlier one.
        assertEquals(setOf(2), SprayWallHistory.earlierVersions(repository.versions(wall), current))
        assertEquals(listOf(3, 2, 1), repository.versions(wall).map { it.number })
    }

    @Test
    fun `an unfinished reset can be discarded, a used version cannot`() = runTest {
        val wall = newWall(listOf(hold(-1, 0.1f), hold(-2, 0.2f)))
        line(wall, 1L to SprayHoldRole.START, 2L to SprayHoldRole.FINISH)
        repository.resetWall(wall, newPhoto, emptyList())

        assertTrue(repository.discardCurrentVersion(wall))
        assertEquals(1, repository.wall(wall)!!.current.number)
        assertEquals(listOf(1), repository.versions(wall).map { it.number })
        verify { photos.delete(newPhoto.fileName) }
        // Version 1 carries a climb: it stays.
        assertFalse(repository.discardCurrentVersion(wall))
    }

    @Test
    fun `a climb from before a reset keeps its version when edited`() = runTest {
        val wall = newWall(listOf(hold(-1, 0.1f), hold(-2, 0.2f)))
        val id = line(wall, 1L to SprayHoldRole.START, 2L to SprayHoldRole.FINISH)
        repository.resetWall(wall, newPhoto, listOf(hold(-1, 0.5f)))

        repository.saveClimb(id, wall, "Old line", "", 21, mapOf(1L to SprayHoldRole.START, 2L to SprayHoldRole.FINISH), version = 1)
        assertEquals(1, repository.climb(id)!!.version)
        // Without a version an edit moves the climb onto the current one.
        repository.saveClimb(id, wall, "Old line", "", 21, mapOf(1L to SprayHoldRole.START, 2L to SprayHoldRole.FINISH))
        assertEquals(2, repository.climb(id)!!.version)
    }

    @Test
    fun `a climb is updated in place and deleting it removes its logbook`() = runTest {
        val wall = newWall(listOf(hold(-1, 0.1f), hold(-2, 0.2f)))
        val holds = mapOf(1L to SprayHoldRole.START, 2L to SprayHoldRole.FINISH)
        val id = repository.saveClimb(null, wall, "First", "", null, holds)
        assertEquals(id, repository.saveClimb(id, wall, "Renamed", "Crimpy", 22, holds))

        val climb = repository.climb(id)!!
        assertEquals("Renamed", climb.name)
        assertEquals("Crimpy", climb.description)
        assertEquals(22, climb.difficulty)
        assertEquals(holds, climb.holds)

        repository.logAscent(climb, isSend = true, tries = 3, difficulty = 21, comment = "  nice  ")
        repository.logAscent(climb, isSend = false, tries = 5, difficulty = null, comment = " ")
        assertEquals(mapOf(id to 1), repository.sendCounts(wall))
        val ascents = repository.ascents(id)
        assertEquals(2, ascents.size)
        assertEquals("nice", ascents.first { it.isSend }.comment)
        assertNull(ascents.first { !it.isSend }.comment)

        assertEquals(id, repository.deleteClimb(id)?.uuid)
        assertNull(repository.climb(id))
        assertTrue(repository.ascents(id).isEmpty())
    }

    @Test
    fun `deleting a wall removes every version, climb and logbook entry and its photos`() = runTest {
        val wall = newWall(listOf(hold(-1, 0.1f), hold(-2, 0.2f)))
        val other = repository.createWall("Other", 20, SprayWallVisibility.PRIVATE, null, photo.copy(fileName = "other.jpg"), listOf(hold(-1, 0.1f)))
        val id = line(wall, 1L to SprayHoldRole.START, 2L to SprayHoldRole.FINISH)
        repository.logAscent(repository.climb(id)!!, true, 1, null, null)
        repository.resetWall(wall, newPhoto, listOf(hold(-1, 0.4f)))

        repository.deleteWall(wall)

        assertNull(repository.wall(wall))
        assertTrue(repository.versions(wall).isEmpty())
        assertTrue(repository.allHolds(wall).isEmpty())
        assertTrue(repository.climbs(wall).isEmpty())
        assertTrue(repository.ascents(id).isEmpty())
        assertEquals(listOf("Other"), repository.walls().map { it.wall.name })
        assertEquals(1, repository.holds(other, 1).size)
        verify { photos.delete(photo.fileName) }
        verify { photos.delete(newPhoto.fileName) }
    }

    @Test
    fun `going private again forgets what was published`() = runTest {
        val wall = repository.createWall("Wall", 40, SprayWallVisibility.PUBLIC, null, photo, listOf(hold(-1, 0.1f)))
        assertTrue(repository.wall(wall)!!.needsPublish)
        repository.markPublished(wall, 1)
        assertFalse(repository.wall(wall)!!.needsPublish)
        repository.updateWallDetails(wall, "Wall", 40, SprayWallVisibility.PRIVATE, null)
        assertNull(repository.wall(wall)!!.publishedVersion)
        assertFalse(repository.wall(wall)!!.needsPublish)
    }

    // ── Other people's public walls ─────────────────────────────

    private val owner = "b".repeat(64)
    private val remoteId = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"

    private fun head(version: Int, name: String = "Gym wall") =
        SprayWallNostr.Parsed.WallHead(owner, 1_000, SprayWallNostr.wallDTag(owner, remoteId), remoteId, name, 30, version, SprayGym("475123,93456", "Halle"))

    private fun remoteVersion(n: Int, file: String) = SprayWallRepository.RemoteVersion(
        SprayWallNostr.Parsed.Version(
            owner, 1_000L + n, SprayWallNostr.versionDTag(owner, remoteId, n), remoteId, n, SprayVersionKind.HOLDS, null,
            "c".repeat(64), "https://cdn.example/" + "c".repeat(64), 3000, 2000,
            listOf(SprayHold(remoteId, n, 1, 0.1f, 0.2f, 0.02f), SprayHold(remoteId, n, 2, 0.3f, 0.2f, 0.02f)),
        ),
        SprayWallPhotoStore.StoredPhoto(file, 3000, 2000),
    )

    private fun remoteClimb(uuid: String, author: String, createdAt: Long, name: String = "Their line") = SprayWallNostr.Parsed.Climb(
        author, createdAt, SprayWallNostr.climbDTag(author, uuid), uuid, owner, remoteId, 1, name, "", 20,
        mapOf(1L to SprayHoldRole.START, 2L to SprayHoldRole.FINISH),
    )

    @Test
    fun `someone else's wall is stored read-only with its versions`() = runTest {
        assertEquals(remoteId, repository.importRemoteWall(head(1), listOf(remoteVersion(1, "r1.jpg"))))
        val wall = repository.wall(remoteId)!!
        assertFalse(wall.isMine)
        assertEquals(owner, wall.ownerPubkey)
        assertEquals(SprayWallVisibility.PUBLIC, wall.visibility)
        assertFalse(wall.needsPublish, "someone else's wall is never published from here")
        assertEquals(listOf(1L, 2L), repository.holds(remoteId, 1).map { it.id })
        assertEquals("r1.jpg", repository.photoWithHash("c".repeat(64))?.fileName)

        // A newer head with a new version; the known version is kept as it is.
        repository.importRemoteWall(head(2, name = "Renamed"), listOf(remoteVersion(1, "other.jpg"), remoteVersion(2, "r1.jpg")))
        val updated = repository.wall(remoteId)!!
        assertEquals("Renamed", updated.name)
        assertEquals(2, updated.current.number)
        assertEquals("r1.jpg", repository.versions(remoteId).first { it.number == 1 }.photoFile)
        assertEquals(listOf(remoteId), repository.publicWalls().map { it.id })
    }

    @Test
    fun `a wall id that is already taken here is refused`() = runTest {
        repository.createWall("Mine", 40, SprayWallVisibility.PRIVATE, null, photo, emptyList())
        val mine = repository.walls().single().wall.id
        val clash = head(1).copy(wallId = mine, dTag = SprayWallNostr.wallDTag(owner, mine))
        assertNull(repository.importRemoteWall(clash, listOf(remoteVersion(1, "r1.jpg"))))
        assertTrue(repository.wall(mine)!!.isMine)
    }

    @Test
    fun `climbs others publish are added, replaced only by newer ones and removed unless logged`() = runTest {
        repository.importRemoteWall(head(1), listOf(remoteVersion(1, "r1.jpg")))
        val uuid = "1a2b3c4d-5e6f-7081-92a3-b4c5d6e7f809"
        val setter = "d".repeat(64)
        repository.upsertRemoteClimb(remoteClimb(uuid, setter, 2_000))
        assertEquals(setter, repository.climb(uuid)?.authorPubkey)

        repository.upsertRemoteClimb(remoteClimb(uuid, setter, 1_500, name = "Older"))
        assertEquals("Their line", repository.climb(uuid)?.name)
        repository.upsertRemoteClimb(remoteClimb(uuid, setter, 3_000, name = "Newer"))
        assertEquals("Newer", repository.climb(uuid)?.name)
        // Someone else cannot take over the climb.
        repository.upsertRemoteClimb(remoteClimb(uuid, "e".repeat(64), 4_000, name = "Hijack"))
        assertEquals("Newer", repository.climb(uuid)?.name)

        // Logged here: it stays when its setter takes it back.
        repository.logAscent(repository.climb(uuid)!!, true, 1, null, null)
        repository.removeRemoteClimb(setter, uuid)
        assertEquals("Newer", repository.climb(uuid)?.name)
        repository.deleteAscent(repository.ascents(uuid).single().uuid)
        repository.removeRemoteClimb(setter, uuid)
        assertNull(repository.climb(uuid))
    }

    @Test
    fun `a withdrawn wall stays here as a private copy`() = runTest {
        repository.importRemoteWall(head(1), listOf(remoteVersion(1, "r1.jpg")))
        repository.markRemoteWallWithdrawn(owner, remoteId)
        val wall = repository.wall(remoteId)!!
        assertEquals(SprayWallVisibility.PRIVATE, wall.visibility)
        assertTrue(repository.publicWalls().isEmpty())
    }

    @Test
    fun `migration 16 adds the spray wall tables to an existing secure database`() = runTest {
        listOf("spray_ascent", "spray_climb", "spray_wall_hold", "spray_wall_version", "spray_wall").forEach {
            driver.execute(null, "DROP TABLE $it", 0)
        }
        // Only the spray-wall steps: the database was created at the newest
        // version, so a replay over earlier migrations would fail on existing tables.
        SecureDatabase.Schema.migrate(driver, 16, 18)

        val wall = newWall(listOf(hold(-1, 0.1f)), name = "After upgrade")
        assertEquals(listOf("After upgrade"), repository.walls().map { it.wall.name })
        assertEquals(1, repository.holds(wall, 1).size)
    }

    @Test
    fun `migration 17 keeps the walls and adds the pending head and the unfinished withdrawal`() = runTest {
        listOf("spray_ascent", "spray_climb", "spray_wall_hold", "spray_wall_version", "spray_wall").forEach {
            driver.execute(null, "DROP TABLE $it", 0)
        }
        SecureDatabase.Schema.migrate(driver, 16, 17)
        driver.execute(
            null,
            "INSERT INTO spray_wall(id, name, angle, visibility, current_version, published_version, created_at, updated_at) " +
                "VALUES ('w1', 'Before upgrade', 40, 'public', 1, 1, 1, 1)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO spray_wall_version(wall_id, version, kind, photo_file, photo_width, photo_height, hold_count, created_at) " +
                "VALUES ('w1', 1, 'created', '${photo.fileName}', 3000, 2000, 0, 1)",
            0,
        )

        SecureDatabase.Schema.migrate(driver, 17, 18)

        val wall = repository.wall("w1")!!
        assertEquals("Before upgrade", wall.name)
        assertNull(wall.headPending)
        assertNull(wall.withdrawStarted)
        repository.updateWallDetails("w1", "After upgrade", 40, SprayWallVisibility.PUBLIC, null, sendHead = true)
        assertEquals(listOf("After upgrade"), repository.wallsWithHeadPending().map { it.name })
    }

    @Test
    fun `a pending head is cleared only for the change it carried`() = runTest {
        val id = repository.createWall("Wall", 40, SprayWallVisibility.PUBLIC, null, photo, listOf(hold(-1, 0.1f)))
        repository.markPublished(id, 1)
        assertTrue(repository.wallsWithHeadPending().isEmpty())

        repository.updateWallDetails(id, "Wall B", 40, SprayWallVisibility.PUBLIC, null, sendHead = true)
        val sent = repository.wall(id)!!.headPending!!
        // Renamed again while that head was on its way.
        repository.updateWallDetails(id, "Wall C", 40, SprayWallVisibility.PUBLIC, null, sendHead = true)
        repository.clearHeadPending(id, sent)
        assertEquals(listOf("Wall C"), repository.wallsWithHeadPending().map { it.name })

        repository.clearHeadPending(id, repository.wall(id)!!.headPending!!)
        assertTrue(repository.wallsWithHeadPending().isEmpty())
    }

    @Test
    fun `a head that went out tells the screens, so the pending hint goes`() = runTest {
        val id = repository.createWall("Wall", 40, SprayWallVisibility.PUBLIC, null, photo, listOf(hold(-1, 0.1f)))
        repository.markPublished(id, 1)
        repository.updateWallDetails(id, "Wall B", 40, SprayWallVisibility.PUBLIC, null, sendHead = true)
        assertTrue(repository.wall(id)!!.publishPending)
        val before = repository.changes.value

        repository.clearHeadPending(id, repository.wall(id)!!.headPending!!)
        assertTrue(repository.changes.value > before)
        assertTrue(!repository.wall(id)!!.publishPending)
    }

    @Test
    fun `going private drops the pending head`() = runTest {
        val id = repository.createWall("Wall", 40, SprayWallVisibility.PUBLIC, null, photo, listOf(hold(-1, 0.1f)))
        repository.markPublished(id, 1)
        repository.updateWallDetails(id, "Wall B", 40, SprayWallVisibility.PUBLIC, null, sendHead = true)

        repository.updateWallDetails(id, "Wall B", 40, SprayWallVisibility.PRIVATE, null)

        assertTrue(repository.wallsWithHeadPending().isEmpty())
        assertNull(repository.wall(id)!!.headPending)
    }
}
