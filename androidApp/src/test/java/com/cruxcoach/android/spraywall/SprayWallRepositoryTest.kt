package com.cruxcoach.android.spraywall

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.spraywall.EditableHold
import com.cruxcoach.domain.spraywall.SprayClimbRules
import com.cruxcoach.domain.spraywall.SprayGym
import com.cruxcoach.domain.spraywall.SprayHoldRole
import com.cruxcoach.domain.spraywall.SprayVersionKind
import com.cruxcoach.domain.spraywall.SprayWallHistory
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

    @Test
    fun `migration 16 adds the spray wall tables to an existing secure database`() = runTest {
        listOf("spray_ascent", "spray_climb", "spray_wall_hold", "spray_wall_version", "spray_wall").forEach {
            driver.execute(null, "DROP TABLE $it", 0)
        }
        // One step only: the database was created at the newest version, so a
        // replay over later migrations would fail on existing tables.
        SecureDatabase.Schema.migrate(driver, 16, 17)

        val wall = newWall(listOf(hold(-1, 0.1f)), name = "After upgrade")
        assertEquals(listOf("After upgrade"), repository.walls().map { it.wall.name })
        assertEquals(1, repository.holds(wall, 1).size)
    }
}
