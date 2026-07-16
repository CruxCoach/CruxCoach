package com.cruxcoach.android.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Race simulation for Fix #145 — KilterSync mark-synced TOCTOU protection.
 *
 * Realistic scenario: the upload thread reads a RawAscent snapshot (capturing
 * `row_version`), sends it to Kilter, and later stamps `synced = 1`. If the
 * user edits the row during the upload window the naive UPDATE would mask
 * that edit — the row would be flagged synced even though its new values
 * never reached the server. `row_version = row_version + 1` on every edit
 * plus `UPDATE … WHERE row_version = :snapshot` turns the stamp into a CAS
 * that refuses stale marks and leaves the row for the next sync.
 *
 * The tests use a real file-backed SQLite DB (not the Fake) so the assertions
 * exercise the actual SQL, `changes()` semantics, and transaction isolation.
 */
class KilterSyncRaceTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: SecureDatabase
    private lateinit var repo: PersonalBoardRepositoryImpl

    @BeforeTest
    fun setUp() {
        // File-backed in-temp-dir DB: each JdbcDriver connection must see the
        // same store. `jdbc:sqlite:` in-memory would give each connection its
        // own empty DB, which breaks transaction isolation checks across
        // threads.
        val tmp = Files.createTempDirectory("cruxcoach-kilter-sync-race-")
        dbFile = tmp.resolve("secure.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        SecureDatabase.Schema.create(driver)
        db = SecureDatabase(driver)
        repo = PersonalBoardRepositoryImpl(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun insertUnsyncedAscent(
        uuid: String = "ascent-1",
        comment: String? = "initial"
    ) {
        repo.insertAscent(
            uuid = uuid,
            climbUuid = "climb-xyz",
            angle = 40L,
            isMirror = false,
            attemptId = 1L,
            bidCount = 0L,
            quality = null,
            difficulty = null,
            isBenchmark = false,
            comment = comment,
            climbedAt = "2026-04-20T10:00:00Z",
            synced = false,
            gymUuid = null,
            wallUuid = null,
            productLayoutUuid = null,
            climbName = "Test Climb",
            difficultyAverage = null,
            climbFrames = "",
            framesCount = 1L
        )
    }

    private fun insertBid(
        uuid: String = "bid-1",
        synced: Boolean = false,
        comment: String? = "initial",
    ) {
        repo.insertBid(
            uuid = uuid,
            climbUuid = "climb-xyz",
            angle = 40L,
            isMirror = false,
            bidCount = 3L,
            comment = comment,
            climbedAt = "2026-04-20T10:00:00Z",
            synced = synced,
            gymUuid = null,
            wallUuid = null,
            productLayoutUuid = null,
            climbName = "Test Climb",
            difficultyAverage = null,
            boardBrand = "kilter",
            layoutId = 1L,
            externalId = null,
        )
    }

    // ── Happy path ───────────────────────────────────────────────

    @Test
    fun `fresh insert starts at row_version 0`() {
        insertUnsyncedAscent()
        val snapshot = repo.getUnsyncedAscents().single()
        assertEquals(0L, snapshot.rowVersion)
    }

    @Test
    fun `mark-synced applies when row_version is still current`() {
        insertUnsyncedAscent()
        val snapshot = repo.getUnsyncedAscents().single()

        val applied = repo.markAscentSyncedIfUnchanged(snapshot.uuid, snapshot.rowVersion)

        assertTrue(applied, "no concurrent edit → mark-synced must apply")
        assertEquals(0, repo.getUnsyncedAscents().size,
            "ascent must disappear from the unsynced set")
    }

    @Test
    fun `updateAscent increments row_version`() {
        insertUnsyncedAscent()
        val before = repo.getUnsyncedAscents().single()

        repo.updateAscent(before.uuid, bidCount = 2L, quality = 4L, comment = "edited")

        val after = repo.getUnsyncedAscents().single()
        assertEquals(before.rowVersion + 1, after.rowVersion)
    }

    @Test
    fun `updateBid increments row_version and rejects stale mark-synced`() {
        insertBid()
        val snapshot = repo.getUnsyncedBids().single()

        repo.updateBid(snapshot.uuid, bidCount = 7L, comment = "edited")

        assertFalse(repo.markBidSyncedIfUnchanged(snapshot.uuid, snapshot.rowVersion))
        val edited = repo.getUnsyncedBids().single()
        assertEquals(snapshot.rowVersion + 1L, edited.rowVersion)
        assertEquals(7L, edited.bidCount)
        assertEquals("edited", edited.comment)
    }

    @Test
    fun `editing a synced bid requeues it`() {
        insertBid(synced = true)
        assertTrue(repo.getUnsyncedBids().isEmpty())

        repo.updateBid("bid-1", bidCount = 8L, comment = "changed after upload")

        val queued = repo.getUnsyncedBids().single()
        assertEquals(8L, queued.bidCount)
        assertEquals(1L, queued.rowVersion)
    }

    // ── Sequential race (read → edit → markIfUnchanged) ──────────

    @Test
    fun `single-thread race - edit between read and mark is detected`() {
        insertUnsyncedAscent()
        val snapshot = repo.getUnsyncedAscents().single()

        // Simulated concurrent edit: bumps row_version past the snapshot.
        repo.updateAscent(snapshot.uuid, bidCount = 3L, quality = 5L, comment = "edited")

        val applied = repo.markAscentSyncedIfUnchanged(snapshot.uuid, snapshot.rowVersion)

        assertFalse(applied, "stale row_version → mark-synced must refuse to apply")
        val remaining = repo.getUnsyncedAscents().single()
        assertEquals(snapshot.uuid, remaining.uuid,
            "ascent must stay unsynced so the next sync re-uploads the edit")
        assertTrue(remaining.rowVersion > snapshot.rowVersion,
            "row_version must have been bumped by the edit")
        assertEquals("edited", remaining.comment,
            "the edited comment must not be clobbered by a stale mark-synced")
    }

    @Test
    fun `two back-to-back edits then stale mark still rejected`() {
        insertUnsyncedAscent()
        val snapshot = repo.getUnsyncedAscents().single()

        repo.updateAscent(snapshot.uuid, bidCount = 1L, quality = null, comment = "first")
        repo.updateAscent(snapshot.uuid, bidCount = 2L, quality = 3L, comment = "second")

        assertFalse(repo.markAscentSyncedIfUnchanged(snapshot.uuid, snapshot.rowVersion),
            "first edit already invalidated the snapshot — second must not re-validate it")
        assertEquals(1, repo.getUnsyncedAscents().size)
    }

    @Test
    fun `re-read after failed mark and re-mark with fresh row_version succeeds`() {
        insertUnsyncedAscent()
        val stale = repo.getUnsyncedAscents().single()
        repo.updateAscent(stale.uuid, bidCount = 1L, quality = null, comment = "edit")

        // First attempt uses the now-stale snapshot → must fail.
        assertFalse(repo.markAscentSyncedIfUnchanged(stale.uuid, stale.rowVersion))

        // Next sync cycle re-reads and succeeds.
        val fresh = repo.getUnsyncedAscents().single()
        assertTrue(repo.markAscentSyncedIfUnchanged(fresh.uuid, fresh.rowVersion),
            "after re-read the snapshot is current → mark-synced must apply")
        assertTrue(repo.getUnsyncedAscents().isEmpty())
    }

    // ── True two-thread race ─────────────────────────────────────

    /**
     * Interleaves read → edit → markIfUnchanged across two threads with
     * latches as rendezvous points. The sync thread holds its stale
     * snapshot while the edit thread commits an update; the subsequent
     * mark-synced must observe the bumped row_version and refuse.
     */
    @Test
    fun `two-thread race - edit committed between read and mark is rejected`() {
        insertUnsyncedAscent()

        val snapshotTaken = CountDownLatch(1)   // T_sync  → "snapshot captured, go edit"
        val editCommitted = CountDownLatch(1)   // T_edit  → "update committed, go mark"

        val pool = Executors.newFixedThreadPool(2)
        try {
            val syncResult = pool.submit<Boolean> {
                val snapshot = repo.getUnsyncedAscents().single()
                snapshotTaken.countDown()
                check(editCommitted.await(5, TimeUnit.SECONDS)) {
                    "edit thread did not commit in time"
                }
                repo.markAscentSyncedIfUnchanged(snapshot.uuid, snapshot.rowVersion)
            }

            val editFuture = pool.submit {
                check(snapshotTaken.await(5, TimeUnit.SECONDS)) {
                    "sync thread did not snapshot in time"
                }
                repo.updateAscent("ascent-1",
                    bidCount = 7L, quality = 4L, comment = "raced-edit")
                editCommitted.countDown()
            }

            val applied = syncResult.get(5, TimeUnit.SECONDS)
            editFuture.get(5, TimeUnit.SECONDS)

            assertFalse(applied,
                "stale snapshot across the read→edit→mark race must be rejected")
            val remaining = repo.getUnsyncedAscents().singleOrNull()
            assertNotNull(remaining, "ascent must still be unsynced for re-upload")
            assertEquals("raced-edit", remaining.comment,
                "the concurrent edit must be preserved, not masked by the sync")
        } finally {
            pool.shutdownNow()
            check(pool.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    /**
     * Control case: if no edit happens between read and mark, the two-thread
     * path must still succeed — proves the race harness itself isn't a false
     * positive for the previous test.
     */
    @Test
    fun `two-thread race - mark succeeds when no edit interleaves`() {
        insertUnsyncedAscent()

        val snapshotTaken = CountDownLatch(1)
        val markReleased = CountDownLatch(1)

        val pool = Executors.newFixedThreadPool(2)
        try {
            val syncResult = pool.submit<Boolean> {
                val snapshot = repo.getUnsyncedAscents().single()
                snapshotTaken.countDown()
                check(markReleased.await(5, TimeUnit.SECONDS))
                repo.markAscentSyncedIfUnchanged(snapshot.uuid, snapshot.rowVersion)
            }
            val releaser = pool.submit {
                // No edit — just acts as the second rendezvous so the sync
                // thread genuinely spans two scheduler slices.
                check(snapshotTaken.await(5, TimeUnit.SECONDS))
                markReleased.countDown()
            }

            assertTrue(syncResult.get(5, TimeUnit.SECONDS),
                "without concurrent edit the mark must apply")
            releaser.get(5, TimeUnit.SECONDS)
            assertTrue(repo.getUnsyncedAscents().isEmpty())
        } finally {
            pool.shutdownNow()
            check(pool.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
