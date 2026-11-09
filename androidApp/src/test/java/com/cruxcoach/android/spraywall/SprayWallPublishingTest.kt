package com.cruxcoach.android.spraywall

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.spraywall.EditableHold
import com.cruxcoach.domain.spraywall.SprayHoldRole
import com.cruxcoach.domain.spraywall.SprayNostrEvent
import com.cruxcoach.domain.spraywall.SprayWallNostr
import com.cruxcoach.domain.spraywall.SprayWallVisibility
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A publish run when the signer declines: the rest goes out, and the declined one is not asked for again at once. */
class SprayWallPublishingTest {
    private lateinit var driver: SqlDriver
    private lateinit var repository: SprayWallRepository
    private val photo = SprayWallPhotoStore.StoredPhoto("0b1c2d3e-photo.jpg", 3000, 2000)

    private val sent = mutableListOf<SprayNostrEvent>()
    /** The d-tag of every event the signer was asked to sign. */
    private val asked = mutableListOf<String>()
    private var declines: (SprayNostrEvent) -> Boolean = { false }
    private var uploadDeclined: (String) -> Boolean = { false }
    private var clock = 1_800_000_000L
    private lateinit var publishing: SprayWallPublishing

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SecureDatabase.Schema.create(driver)
        repository = SprayWallRepository(SecureDatabase(driver), mockk(relaxed = true))
        publishing = newPublishing()
    }

    @AfterTest
    fun tearDown() = driver.close()

    /** Also what the next start of the app makes. */
    private fun newPublishing(): SprayWallPublishing {
        val send: suspend (SprayNostrEvent) -> Int = { event ->
            asked += event.d
            if (declines(event)) throw SprayEventSigningFailed(IllegalStateException("declined"))
            sent += event
            1
        }
        val withdrawal = SprayWallWithdrawal(
            repository = repository,
            send = send,
            deletePhoto = { _, _ -> SprayWallWithdrawal.PhotoDeletion.DELETED },
            stamp = { clock++ },
            now = { clock++ },
            defaultServers = listOf("https://default.example.org"),
        )
        return SprayWallPublishing(
            repository = repository,
            withdrawal = withdrawal,
            send = send,
            upload = { wallId, _ ->
                if (uploadDeclined(wallId)) throw SprayEventSigningFailed(IllegalStateException("upload declined"))
                SHA to "https://cdn.example.org/$SHA"
            },
            stamp = { clock++ },
            now = { clock++ },
        )
    }

    private suspend fun run() = publishing.run { ME }

    /** A public wall not on Nostr yet. */
    private suspend fun newPublicWall(name: String): String =
        repository.createWall(name, 40, SprayWallVisibility.PUBLIC, null, photo, listOf(EditableHold(-1, 0.5f, 0.5f, 0.02f)))

    /** A public wall whose version 1 is on Nostr. */
    private suspend fun publishedWall(name: String): String = newPublicWall(name).also {
        repository.setPhotoBlob(it, photo.fileName, SHA, "https://cdn.example.org/$SHA")
        repository.markPublished(it, 1)
    }

    /** A rename or any other save: the wall's state changes (its update time moves on). */
    private suspend fun rename(id: String, name: String, sendHead: Boolean = true) {
        Thread.sleep(2)
        repository.updateWallDetails(id, name, 40, SprayWallVisibility.PUBLIC, null, sendHead)
    }

    private suspend fun climbOn(wallId: String, name: String): String =
        repository.saveClimb(null, wallId, name, "", 20, mapOf(1L to SprayHoldRole.START))

    private val SprayNostrEvent.d: String get() = tags.first { it.first() == "d" }[1]
    private fun headOf(wallId: String) = SprayWallNostr.wallDTag(ME, wallId)
    private fun versionsOf(wallId: String): (String) -> Boolean = { it.startsWith(headOf(wallId) + ":v:") }
    private fun askedAbout(wallId: String) = asked.any { it == headOf(wallId) || versionsOf(wallId)(it) }

    @Test
    fun `a declined version holds up neither the other walls nor the climbs, and waits for a change`() = runTest {
        val declined = newPublicWall("A")
        val other = newPublicWall("B")
        val published = publishedWall("C")
        val climb = climbOn(published, "Line")
        declines = { versionsOf(declined)(it.d) }

        assertIs<SprayWallPublisher.Outcome.Failed>(run())
        assertNull(repository.wall(declined)!!.publishedVersion)
        assertEquals(1, repository.wall(other)!!.publishedVersion)
        assertTrue(repository.unpublishedClimbs(published).none { it.uuid == climb })

        // Not asked again on the next change elsewhere.
        asked.clear()
        rename(other, "B2")
        assertIs<SprayWallPublisher.Outcome.Failed>(run())
        assertTrue(!askedAbout(declined))
        assertTrue(headOf(other) in asked)

        // Its own change asks again.
        rename(declined, "A2", sendHead = false)
        declines = { false }
        assertIs<SprayWallPublisher.Outcome.Done>(run())
        assertEquals(1, repository.wall(declined)!!.publishedVersion)
    }

    @Test
    fun `a declined photo upload holds up nothing else and keeps the wall to publish`() = runTest {
        val declined = newPublicWall("A")
        val other = newPublicWall("B")
        uploadDeclined = { it == declined }

        assertIs<SprayWallPublisher.Outcome.Failed>(run())
        assertTrue(repository.wall(declined)!!.needsPublish)
        assertEquals(1, repository.wall(other)!!.publishedVersion)
    }

    @Test
    fun `a declined head holds up neither the climbs nor other heads, and is asked again after a start or a rename`() = runTest {
        val declined = publishedWall("A")
        val other = publishedWall("B")
        val withClimb = publishedWall("C")
        val climb = climbOn(withClimb, "Line")
        rename(declined, "A2")
        rename(other, "B2")
        declines = { it.d == headOf(declined) }

        assertIs<SprayWallPublisher.Outcome.Failed>(run())
        assertEquals(listOf(declined), repository.wallsWithHeadPending().map { it.id })
        assertTrue(repository.unpublishedClimbs(withClimb).none { it.uuid == climb })

        asked.clear()
        run()
        assertTrue(headOf(declined) !in asked)

        // The next start asks again.
        publishing = newPublishing()
        run()
        assertTrue(headOf(declined) in asked)

        // So does a new rename.
        asked.clear()
        rename(declined, "A3")
        declines = { false }
        assertIs<SprayWallPublisher.Outcome.Done>(run())
        assertTrue(headOf(declined) in asked)
        assertTrue(repository.wallsWithHeadPending().isEmpty())
    }

    @Test
    fun `a declined climb holds up nothing else, and an edit asks again`() = runTest {
        val wall = publishedWall("A")
        val declined = climbOn(wall, "Line")
        val other = climbOn(wall, "Other")
        declines = { it.d == SprayWallNostr.climbDTag(ME, declined) }

        assertIs<SprayWallPublisher.Outcome.Failed>(run())
        assertEquals(listOf(declined), repository.unpublishedClimbs(wall).map { it.uuid })
        assertTrue(other !in repository.unpublishedClimbs(wall).map { it.uuid })

        asked.clear()
        run()
        assertTrue(SprayWallNostr.climbDTag(ME, declined) !in asked)

        Thread.sleep(2)
        repository.saveClimb(declined, wall, "Line 2", "", 20, mapOf(1L to SprayHoldRole.START))
        declines = { false }
        assertIs<SprayWallPublisher.Outcome.Done>(run())
        assertTrue(repository.unpublishedClimbs(wall).isEmpty())
    }

    @Test
    fun `asking again for a wall asks the signer for what it declined there, not elsewhere`() = runTest {
        val wall = publishedWall("A")
        val other = publishedWall("B")
        rename(wall, "A2")
        rename(other, "B2")
        declines = { true }
        assertIs<SprayWallPublisher.Outcome.Failed>(run())

        asked.clear()
        publishing.askAgain(wall)
        declines = { false }
        assertIs<SprayWallPublisher.Outcome.Failed>(run())
        assertEquals(listOf(headOf(wall)), asked)
        assertEquals(listOf(other), repository.wallsWithHeadPending().map { it.id })
    }

    @Test
    fun `a wall held after a declined withdrawal sends nothing until the next start or a change`() = runTest {
        // Its head, a version still to go and its climbs would all ask the signer to publish the wall.
        val wall = publishedWall("A")
        rename(wall, "A2")
        climbOn(wall, "Line")
        repository.saveHolds(wall, listOf(EditableHold(1, 0.5f, 0.5f, 0.02f), EditableHold(-1, 0.3f, 0.3f, 0.02f)))
        publishing.holdWall(repository.wall(wall)!!)

        assertIs<SprayWallPublisher.Outcome.Failed>(run())
        assertTrue(!askedAbout(wall))
        assertTrue(asked.none { it.startsWith("cruxcoach:sprayclimb:") })

        // The next start sends it all.
        publishing = newPublishing()
        assertIs<SprayWallPublisher.Outcome.Done>(run())
        assertTrue(headOf(wall) in asked)
        assertTrue(asked.any { it.startsWith("cruxcoach:sprayclimb:") })
        assertEquals(2, repository.wall(wall)!!.publishedVersion)
    }

    @Test
    fun `a wall held after a declined withdrawal goes out after a rename`() = runTest {
        val wall = publishedWall("A")
        rename(wall, "A2")
        publishing.holdWall(repository.wall(wall)!!)
        run()
        assertTrue(headOf(wall) !in asked)
        assertEquals(listOf(wall), repository.wallsWithHeadPending().map { it.id })

        rename(wall, "A3")
        assertIs<SprayWallPublisher.Outcome.Done>(run())
        assertTrue(headOf(wall) in asked)
    }

    @Test
    fun `nothing of a wall being withdrawn goes out`() = runTest {
        val wall = publishedWall("A")
        rename(wall, "A2")
        climbOn(wall, "Line")
        repository.saveHolds(wall, listOf(EditableHold(1, 0.5f, 0.5f, 0.02f), EditableHold(-1, 0.3f, 0.3f, 0.02f)))
        repository.markWithdrawStarted(wall)

        assertIs<SprayWallPublisher.Outcome.Done>(run())
        assertTrue(!askedAbout(wall))
        assertTrue(asked.none { it.startsWith("cruxcoach:sprayclimb:") })
    }

    private companion object {
        val ME = "a1".repeat(32)
        val SHA = "5e".repeat(32)
    }
}
