package com.cruxcoach.android.spraywall

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.spraywall.EditableHold
import com.cruxcoach.domain.spraywall.SprayNostrEvent
import com.cruxcoach.domain.spraywall.SprayWallVisibility
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Withdrawing a public wall: off the relays and off Blossom, or nothing changes here. */
class SprayWallWithdrawalTest {
    private lateinit var driver: SqlDriver
    private lateinit var repository: SprayWallRepository
    private val photo = SprayWallPhotoStore.StoredPhoto("0b1c2d3e-photo.jpg", 3000, 2000)

    private val sent = mutableListOf<SprayNostrEvent>()
    private val deleted = mutableListOf<Pair<String, List<String>>>()
    private var relaysTaking = 1
    private var signing: (() -> Unit)? = null
    private var photoResult = SprayWallWithdrawal.PhotoDeletion.DELETED
    private lateinit var withdrawal: SprayWallWithdrawal

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SecureDatabase.Schema.create(driver)
        repository = SprayWallRepository(SecureDatabase(driver), mockk(relaxed = true))
        var clock = 1_800_000_000L
        withdrawal = SprayWallWithdrawal(
            repository = repository,
            send = { event ->
                signing?.let { fail -> try { fail() } catch (e: Exception) { throw SprayEventSigningFailed(e) } }
                sent += event
                relaysTaking
            },
            deletePhoto = { sha, servers -> deleted += sha to servers; photoResult },
            stamp = { clock++ },
            now = { clock++ },
            defaultServers = listOf("https://default.example.org"),
        )
    }

    @AfterTest
    fun tearDown() = driver.close()

    /** A public wall whose version 1 and photo are on Nostr and Blossom. */
    private suspend fun publishedWall(): String {
        val id = repository.createWall("Wall", 40, SprayWallVisibility.PUBLIC, null, photo, listOf(EditableHold(-1, 0.5f, 0.5f, 0.02f)))
        repository.setPhotoBlob(id, photo.fileName, SHA, "https://cdn.example.org/$SHA")
        repository.markPublished(id, 1)
        return id
    }

    private suspend fun assertStillPublic(id: String) {
        val wall = repository.wall(id)!!
        assertEquals(SprayWallVisibility.PUBLIC, wall.visibility)
        assertEquals(1, wall.publishedVersion)
        assertNotNull(wall.current.photoSha256)
    }

    @Test
    fun `withdrawing sends tombstones, deletes the photo on its server and turns the wall private`() = runTest {
        val id = publishedWall()

        assertEquals(SprayWithdrawal.DONE, withdrawal.run(ME, id))

        // Head and version tombstones, then the kind-5 deletion naming both.
        assertEquals(listOf(30078, 30078, 5), sent.map { it.kind })
        assertEquals(listOf(SHA to listOf("https://cdn.example.org")), deleted)
        val wall = repository.wall(id)!!
        assertEquals(SprayWallVisibility.PRIVATE, wall.visibility)
        assertNull(wall.publishedVersion)
        // Publishing it again uploads the photo anew.
        assertNull(wall.current.photoSha256)
        assertNull(wall.current.photoUrl)
    }

    @Test
    fun `no relay confirming keeps the wall public and its photo`() = runTest {
        val id = publishedWall()
        relaysTaking = 0

        assertEquals(SprayWithdrawal.NOT_CONFIRMED, withdrawal.run(ME, id))

        assertTrue(deleted.isEmpty())
        assertStillPublic(id)
    }

    @Test
    fun `a photo left on its server keeps the wall public`() = runTest {
        val id = publishedWall()
        photoResult = SprayWallWithdrawal.PhotoDeletion.LEFT

        assertEquals(SprayWithdrawal.PHOTOS_LEFT, withdrawal.run(ME, id))
        assertStillPublic(id)
    }

    @Test
    fun `a failing or declining signer is reported, not thrown`() = runTest {
        val id = publishedWall()
        signing = { throw IllegalStateException("No activity to launch from.") }

        assertEquals(SprayWithdrawal.SIGNING_FAILED, withdrawal.run(ME, id))
        assertStillPublic(id)
    }

    @Test
    fun `a declined Blossom authorisation is a signing failure`() = runTest {
        val id = publishedWall()
        photoResult = SprayWallWithdrawal.PhotoDeletion.NOT_AUTHORISED

        assertEquals(SprayWithdrawal.SIGNING_FAILED, withdrawal.run(ME, id))
        assertStillPublic(id)
    }

    @Test
    fun `a network error is reported, not thrown`() = runTest {
        val id = publishedWall()
        withdrawal = SprayWallWithdrawal(repository, { error("socket closed") }, { _, _ -> photoResult }, { 1L }, { 1L }, emptyList())

        assertEquals(SprayWithdrawal.NOT_CONFIRMED, withdrawal.run(ME, id))
        assertStillPublic(id)
    }

    @Test
    fun `without an identity nothing is sent and the wall stays public`() = runTest {
        val id = publishedWall()

        assertEquals(SprayWithdrawal.NO_IDENTITY, withdrawal.run(null, id))
        assertTrue(sent.isEmpty())
        assertStillPublic(id)
    }

    @Test
    fun `a photo another public wall still shows stays on Blossom`() = runTest {
        val id = publishedWall()
        publishedWall()

        assertEquals(SprayWithdrawal.DONE, withdrawal.run(ME, id))
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `a photo without a known server is deleted on the default servers`() = runTest {
        val id = publishedWall()
        repository.setPhotoBlob(id, photo.fileName, SHA, "not-a-url")

        assertEquals(SprayWithdrawal.DONE, withdrawal.run(ME, id))
        assertEquals(listOf(SHA to listOf("https://default.example.org")), deleted)
    }

    @Test
    fun `a wall never published turns private without asking anyone`() = runTest {
        val id = repository.createWall("Wall", 40, SprayWallVisibility.PUBLIC, null, photo, emptyList())

        assertEquals(SprayWithdrawal.DONE, withdrawal.run(null, id))
        assertTrue(sent.isEmpty())
        assertEquals(SprayWallVisibility.PRIVATE, repository.wall(id)!!.visibility)
    }

    private companion object {
        val ME = "a1".repeat(32)
        val SHA = "5e".repeat(32)
    }
}
