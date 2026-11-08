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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Withdrawing a public wall: off the relays and off Blossom, or nothing changes here. */
class SprayWallWithdrawalTest {
    private lateinit var driver: SqlDriver
    private lateinit var repository: SprayWallRepository
    private val photo = SprayWallPhotoStore.StoredPhoto("0b1c2d3e-photo.jpg", 3000, 2000)
    private val newPhoto = SprayWallPhotoStore.StoredPhoto("1c2d3e4f-photo.jpg", 3000, 2000)
    private val thirdPhoto = SprayWallPhotoStore.StoredPhoto("2d3e4f5a-photo.jpg", 3000, 2000)
    private val twinPhoto = SprayWallPhotoStore.StoredPhoto("3e4f5a6b-photo.jpg", 3000, 2000)

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

    /** A published wall, reset onto a second photo that reached Blossom before the publish failed. */
    private suspend fun partlyPublishedWall(): String {
        val id = publishedWall()
        repository.resetWall(id, newPhoto, listOf(EditableHold(-1, 0.4f, 0.4f, 0.02f)))
        repository.setPhotoBlob(id, newPhoto.fileName, SHA_2, "https://cdn.example.org/$SHA_2")
        return id
    }

    @Test
    fun `withdrawing also takes back what a publish that failed partway left`() = runTest {
        val id = partlyPublishedWall()

        assertEquals(SprayWithdrawal.DONE, withdrawal.run(ME, id))

        // Head and both versions (the second may have reached a relay), then the deletion; both photos.
        assertEquals(listOf(30078, 30078, 30078, 5), sent.map { it.kind })
        assertEquals(setOf(SHA, SHA_2), deleted.map { it.first }.toSet())
        assertTrue(repository.versions(id).all { it.photoSha256 == null })
    }

    @Test
    fun `a failed publish deletes the photos it uploaded and keeps the published ones`() = runTest {
        val id = partlyPublishedWall()

        withdrawal.deleteUnpublishedPhotos(id)

        assertTrue(sent.isEmpty())
        assertEquals(listOf(SHA_2 to listOf("https://cdn.example.org")), deleted)
        val versions = repository.versions(id).associateBy { it.number }
        assertEquals(SHA, versions.getValue(1).photoSha256)
        // The next publish uploads the second photo again.
        assertNull(versions.getValue(2).photoSha256)
        val wall = repository.wall(id)!!
        assertEquals(SprayWallVisibility.PUBLIC, wall.visibility)
        assertEquals(1, wall.publishedVersion)
    }

    @Test
    fun `a photo of a failed publish that could not be deleted is remembered for the withdrawal`() = runTest {
        val id = repository.createWall("Wall", 40, SprayWallVisibility.PUBLIC, null, photo, listOf(EditableHold(-1, 0.5f, 0.5f, 0.02f)))
        repository.setPhotoBlob(id, photo.fileName, SHA, "https://cdn.example.org/$SHA")
        photoResult = SprayWallWithdrawal.PhotoDeletion.LEFT

        withdrawal.deleteUnpublishedPhotos(id)
        assertEquals(SHA, repository.wall(id)!!.current.photoSha256)

        // Never published, yet its photo is on Blossom: going private deletes it.
        photoResult = SprayWallWithdrawal.PhotoDeletion.DELETED
        deleted.clear()
        assertEquals(SprayWithdrawal.DONE, withdrawal.run(ME, id))
        assertEquals(listOf(SHA to listOf("https://cdn.example.org")), deleted)
        val wall = repository.wall(id)!!
        assertEquals(SprayWallVisibility.PRIVATE, wall.visibility)
        assertNull(wall.current.photoSha256)
    }

    @Test
    fun `a declined Blossom authorisation stops deleting the photos of a failed publish`() = runTest {
        val id = partlyPublishedWall()
        repository.resetWall(id, thirdPhoto, listOf(EditableHold(-1, 0.3f, 0.3f, 0.02f)))
        repository.setPhotoBlob(id, thirdPhoto.fileName, SHA_3, "https://cdn.example.org/$SHA_3")
        photoResult = SprayWallWithdrawal.PhotoDeletion.NOT_AUTHORISED

        withdrawal.deleteUnpublishedPhotos(id)

        assertEquals(1, deleted.size)
        assertTrue(repository.versions(id).all { it.photoSha256 != null })
    }

    /** A second wall on the same photo, public and uploaded but not on Nostr yet (its publish is still to come). */
    private suspend fun unpublishedWallWithPhoto(sha: String, server: String = "https://cdn.example.org"): String {
        val id = repository.createWall("Twin", 40, SprayWallVisibility.PUBLIC, null, twinPhoto, listOf(EditableHold(-1, 0.5f, 0.5f, 0.02f)))
        repository.setPhotoBlob(id, twinPhoto.fileName, sha, "$server/$sha")
        return id
    }

    @Test
    fun `a wall with the same photo on another server keeps it`() = runTest {
        val id = publishedWall()
        val twin = unpublishedWallWithPhoto(SHA, server = "https://other.example.org")

        assertEquals(SprayWithdrawal.DONE, withdrawal.run(ME, id))

        assertEquals(listOf(SHA to listOf("https://cdn.example.org")), deleted)
        assertEquals("https://other.example.org/$SHA", repository.wall(twin)!!.current.photoUrl)
    }

    /** A rename that has not reached a relay yet: its head waits. */
    private suspend fun renamedOffline(id: String) =
        repository.updateWallDetails(id, "Wall B", 40, SprayWallVisibility.PUBLIC, null, sendHead = true)

    @Test
    fun `once a relay took a tombstone a waiting head is dropped, also when a photo stays`() = runTest {
        val id = publishedWall()
        renamedOffline(id)
        photoResult = SprayWallWithdrawal.PhotoDeletion.LEFT

        assertEquals(SprayWithdrawal.PHOTOS_LEFT, withdrawal.run(ME, id))
        // It would replace the tombstone at the next publish and bring the wall back.
        assertTrue(repository.wallsWithHeadPending().isEmpty())
    }

    @Test
    fun `a waiting head stays when no relay took a tombstone`() = runTest {
        val id = publishedWall()
        renamedOffline(id)
        relaysTaking = 0

        assertEquals(SprayWithdrawal.NOT_CONFIRMED, withdrawal.run(ME, id))
        assertEquals(listOf(id), repository.wallsWithHeadPending().map { it.id })
    }

    @Test
    fun `a withdrawal the relays took part of marks the wall, finishing it clears the mark`() = runTest {
        val id = publishedWall()
        photoResult = SprayWallWithdrawal.PhotoDeletion.LEFT

        assertEquals(SprayWithdrawal.PHOTOS_LEFT, withdrawal.run(ME, id))
        assertNotNull(repository.wall(id)!!.withdrawStarted)

        photoResult = SprayWallWithdrawal.PhotoDeletion.DELETED
        assertEquals(SprayWithdrawal.DONE, withdrawal.run(ME, id))
        val wall = repository.wall(id)!!
        assertEquals(SprayWallVisibility.PRIVATE, wall.visibility)
        assertNull(wall.withdrawStarted)
    }

    @Test
    fun `a photo deletion declined after the tombstones went out marks the wall`() = runTest {
        val id = publishedWall()
        photoResult = SprayWallWithdrawal.PhotoDeletion.NOT_AUTHORISED

        assertEquals(SprayWithdrawal.SIGNING_FAILED, withdrawal.run(ME, id))
        assertNotNull(repository.wall(id)!!.withdrawStarted)
    }

    @Test
    fun `a signer declining the first tombstone leaves the wall as it was`() = runTest {
        val id = publishedWall()
        signing = { throw IllegalStateException("declined") }

        assertEquals(SprayWithdrawal.SIGNING_FAILED, withdrawal.run(ME, id))
        assertNull(repository.wall(id)!!.withdrawStarted)
    }

    @Test
    fun `nothing of a wall the relays took back in part is published, also after a restart`() = runTest {
        val id = publishedWall()
        photoResult = SprayWallWithdrawal.PhotoDeletion.LEFT
        assertEquals(SprayWithdrawal.PHOTOS_LEFT, withdrawal.run(ME, id))

        // A rename, a new climb and a hold edit (a new version, as a climb is set on version 1).
        renamedOffline(id)
        repository.saveClimb(null, id, "Line", "", 20, mapOf(1L to SprayHoldRole.START))
        val holds = repository.saveHolds(id, listOf(EditableHold(1, 0.5f, 0.5f, 0.02f), EditableHold(-1, 0.3f, 0.3f, 0.02f)))
        assertTrue(holds.newVersion)

        // What the publisher sends: versions, heads, climbs.
        suspend fun assertNothingToSend(repo: SprayWallRepository) {
            assertTrue(repo.wallsNeedingPublish().none { it.id == id })
            assertTrue(repo.wallsWithHeadPending().none { it.id == id })
            assertTrue(repo.wallsTakingClimbs().none { it.id == id })
            assertTrue(!repo.wall(id)!!.needsPublish)
        }
        assertNothingToSend(repository)
        // A restart: a new repository on the same database.
        assertNothingToSend(SprayWallRepository(SecureDatabase(driver), mockk(relaxed = true)))
    }

    @Test
    fun `a climb edited since it went out is taken back too`() = runTest {
        val id = publishedWall()
        // Edited climbs are not marked published until they went out again; their old event is on the relays.
        val climb = repository.saveClimb(null, id, "Line", "", 20, mapOf(1L to SprayHoldRole.START))

        assertEquals(SprayWithdrawal.DONE, withdrawal.run(ME, id))

        val climbD = SprayWallNostr.climbDTag(ME, climb)
        assertTrue(sent.any { e -> e.kind == 30078 && e.tags.any { it == listOf("d", climbD) } })
        assertTrue(sent.single { it.kind == 5 }.tags.any { climbD in it.joinToString(":") })
    }

    @Test
    fun `a photo deleted after a failed publish is forgotten by my other walls too`() = runTest {
        val id = partlyPublishedWall()
        val twin = unpublishedWallWithPhoto(SHA_2)

        withdrawal.deleteUnpublishedPhotos(id)

        assertEquals(listOf(SHA_2 to listOf("https://cdn.example.org")), deleted)
        // Its publish uploads the photo anew instead of linking to the deleted one.
        assertNull(repository.wall(twin)!!.current.photoSha256)
        assertNull(repository.wall(twin)!!.current.photoUrl)
    }

    @Test
    fun `a photo deleted by a withdrawal is forgotten by my other walls too`() = runTest {
        val id = publishedWall()
        val twin = unpublishedWallWithPhoto(SHA)

        assertEquals(SprayWithdrawal.DONE, withdrawal.run(ME, id))

        assertEquals(listOf(SHA to listOf("https://cdn.example.org")), deleted)
        assertNull(repository.wall(twin)!!.current.photoSha256)
        assertEquals(SprayWallVisibility.PUBLIC, repository.wall(twin)!!.visibility)
    }

    @Test
    fun `a photo left on its server stays recorded in my other walls`() = runTest {
        val id = partlyPublishedWall()
        val twin = unpublishedWallWithPhoto(SHA_2)
        photoResult = SprayWallWithdrawal.PhotoDeletion.LEFT

        withdrawal.deleteUnpublishedPhotos(id)
        assertEquals(SprayWithdrawal.PHOTOS_LEFT, withdrawal.run(ME, id))

        assertEquals(SHA_2, repository.wall(twin)!!.current.photoSha256)
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
        val SHA_2 = "6f".repeat(32)
        val SHA_3 = "7a".repeat(32)
    }
}
