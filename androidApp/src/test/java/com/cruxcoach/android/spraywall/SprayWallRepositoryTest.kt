package com.cruxcoach.android.spraywall

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.spraywall.EditableHold
import com.cruxcoach.domain.spraywall.SprayClimbRules
import com.cruxcoach.domain.spraywall.SprayHoldRole
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SprayWallRepositoryTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: SecureDatabase
    private val photos = mockk<SprayWallPhotoStore>(relaxed = true)
    private lateinit var repository: SprayWallRepository
    private val photo = SprayWallPhotoStore.StoredPhoto("0b1c2d3e-photo.jpg", 3000, 2000)

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

    @Test
    fun `a new wall is listed with its counts`() = runTest {
        val id = repository.createWall("  Garage  ", 35, photo)
        repository.saveHolds(id, listOf(hold(-1, 0.1f), hold(-2, 0.2f), hold(-3, 0.3f)))

        val summary = repository.walls().single()
        assertEquals("Garage", summary.wall.name)
        assertEquals(35, summary.wall.angle)
        assertEquals(3000, summary.wall.photoWidth)
        assertEquals(3, summary.holdCount)
        assertEquals(0, summary.climbCount)
        assertEquals(listOf(1L, 2L, 3L), repository.holds(id).map { it.id })
    }

    @Test
    fun `a removed hold that a climb uses stays as gone, an unused one is deleted`() = runTest {
        val wall = repository.createWall("Wall", 40, photo)
        repository.saveHolds(wall, listOf(hold(-1, 0.1f), hold(-2, 0.2f), hold(-3, 0.3f), hold(-4, 0.4f)))
        val climbId = repository.saveClimb(
            null, wall, "Line", "", 20,
            mapOf(1L to SprayHoldRole.START, 2L to SprayHoldRole.HAND, 3L to SprayHoldRole.FINISH),
        )

        // Hold 2 (used) and hold 4 (unused) come off, hold 1 moves, one hold is added.
        val result = repository.saveHolds(wall, listOf(EditableHold(1, 0.15f, 0.5f, 0.02f), hold(3, 0.3f), hold(-1, 0.9f)))

        assertEquals(1, result.added)
        assertEquals(1, result.changed)
        assertEquals(2, result.removed)
        assertEquals(1, result.keptForClimbs)
        val holds = repository.holds(wall).associateBy { it.id }
        assertNull(holds[4L], "an unused hold is deleted")
        assertNotNull(holds[2L]?.removedAt, "a used hold stays, marked as gone")
        assertEquals(0.15f, holds[1L]?.x)
        // New ids continue after the highest id ever used, never reusing a gone hold's id.
        assertEquals(5L, holds.keys.max())

        val onWall = holds.values.filter { it.isOnWall }.map { it.id }.toSet()
        assertEquals(1, SprayClimbRules.lostHoldCount(repository.climb(climbId)!!, onWall))
        assertEquals(3, repository.walls().single().holdCount)
    }

    @Test
    fun `saving unchanged holds writes nothing`() = runTest {
        val wall = repository.createWall("Wall", 40, photo)
        repository.saveHolds(wall, listOf(hold(-1, 0.1f)))
        val before = repository.changes.value
        val result = repository.saveHolds(wall, listOf(hold(1, 0.1f)))
        assertEquals(SprayWallRepository.HoldSaveResult(0, 0, 0, 0), result)
        assertEquals(before, repository.changes.value)
    }

    @Test
    fun `a climb is updated in place and deleting it removes its logbook`() = runTest {
        val wall = repository.createWall("Wall", 40, photo)
        repository.saveHolds(wall, listOf(hold(-1, 0.1f), hold(-2, 0.2f)))
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

        repository.deleteClimb(id)
        assertNull(repository.climb(id))
        assertTrue(repository.ascents(id).isEmpty())
    }

    @Test
    fun `deleting a wall removes everything on it and its photo`() = runTest {
        val wall = repository.createWall("Wall", 40, photo)
        val other = repository.createWall("Other", 20, photo.copy(fileName = "other.jpg"))
        repository.saveHolds(wall, listOf(hold(-1, 0.1f), hold(-2, 0.2f)))
        repository.saveHolds(other, listOf(hold(-1, 0.1f)))
        val id = repository.saveClimb(null, wall, "Line", "", 20, mapOf(1L to SprayHoldRole.START, 2L to SprayHoldRole.FINISH))
        repository.logAscent(repository.climb(id)!!, true, 1, null, null)

        repository.deleteWall(wall)

        assertNull(repository.wall(wall))
        assertTrue(repository.holds(wall).isEmpty())
        assertTrue(repository.climbs(wall).isEmpty())
        assertTrue(repository.ascents(id).isEmpty())
        assertEquals(listOf("Other"), repository.walls().map { it.wall.name })
        assertEquals(1, repository.holds(other).size)
        verify { photos.delete(photo.fileName) }
    }

    @Test
    fun `migration 16 adds the spray wall tables to an existing secure database`() = runTest {
        listOf("spray_ascent", "spray_climb", "spray_wall_hold", "spray_wall").forEach {
            driver.execute(null, "DROP TABLE $it", 0)
        }
        // One step only: the database was created at the newest version, so a
        // replay over later migrations would fail on existing tables.
        SecureDatabase.Schema.migrate(driver, 16, 17)

        val wall = repository.createWall("After upgrade", 30, photo)
        assertEquals(listOf("After upgrade"), repository.walls().map { it.wall.name })
        assertEquals(wall, repository.wall(wall)?.id)
    }
}
