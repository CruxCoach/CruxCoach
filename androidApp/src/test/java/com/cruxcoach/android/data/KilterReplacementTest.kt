package com.cruxcoach.android.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.data.repository.QuickLogBidInput
import com.cruxcoach.data.repository.QuickLogSendInput
import com.cruxcoach.db.secure.SecureDatabase
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Kilter keeps a log as first uploaded (bulk upload is insert-only): changing
 * an entry Kilter holds queues the deletion of its copy and makes the row an
 * upload again. Real file-backed SQLite, same harness as PerBoardLogbookDeletionTest.
 */
class KilterReplacementTest {
    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: SecureDatabase
    private lateinit var repo: PersonalBoardRepositoryImpl

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-kilter-replacement-")
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

    private fun bid(uuid: String, synced: Boolean, bidCount: Long = 1) = repo.insertBid(
        uuid = uuid, climbUuid = "climb-a", angle = 40, isMirror = false, bidCount = bidCount, comment = null,
        climbedAt = "2026-10-07T18:00:00", synced = synced, climbName = "A", difficultyAverage = null,
    )

    private fun ascent(uuid: String, synced: Boolean) = repo.insertAscent(
        uuid = uuid, climbUuid = "climb-a", angle = 40, isMirror = false, attemptId = 0, bidCount = 2, quality = 2,
        difficulty = null, isBenchmark = false, comment = null, climbedAt = "2026-10-07T18:00:00", synced = synced,
        gymUuid = null, wallUuid = null, productLayoutUuid = null, climbName = "A", difficultyAverage = null,
        climbFrames = "", framesCount = 0, boardBrand = "kilter", layoutId = null, externalId = null,
    )

    private fun unsyncedBid(uuid: String) = repo.getUnsyncedBids().firstOrNull { it.uuid == uuid }
    private fun unsyncedAscent(uuid: String) = repo.getUnsyncedAscents().firstOrNull { it.uuid == uuid }

    @Test fun another_try_on_an_uploaded_attempt_replaces_kilters_copy() {
        bid("try", synced = true)
        repo.updateBid("try", 2, null)
        assertEquals(listOf("try"), repo.pendingLogDeletions())
        val row = unsyncedBid("try")!!
        assertEquals(2, row.bidCount)
        assertEquals(1, row.rowVersion, "an edit during an upload must not be marked synced with the old copy")
    }

    @Test fun an_unchanged_save_or_an_attempt_not_yet_uploaded_leaves_kilter_alone() {
        bid("same", synced = true, bidCount = 2)
        repo.updateBid("same", 2, null)
        bid("local", synced = false)
        repo.updateBid("local", 3, null)
        assertTrue(repo.pendingLogDeletions().isEmpty())
        assertEquals(null, unsyncedBid("same"), "unchanged: still as Kilter has it")
        assertEquals(3, unsyncedBid("local")!!.bidCount)
    }

    @Test fun a_quick_log_send_of_an_uploaded_attempt_replaces_it_and_undo_does_too() {
        bid("seq", synced = true)
        repo.promoteQuickBidToSend(QuickLogSendInput(
            uuid = "seq", climbUuid = "climb-a", angle = 40, isMirror = false, bidCount = 2, difficulty = null,
            climbedAt = "2026-10-07T18:05:00", climbName = "A", difficultyAverage = null, climbFrames = "",
            framesCount = 0, boardBrand = "kilter", layoutId = null,
        ))
        assertEquals(listOf("seq"), repo.pendingLogDeletions())
        assertTrue(unsyncedAscent("seq") != null)
        // The deletion went through and the send was uploaded; then the user undoes the send.
        repo.clearLogDeletion("seq")
        assertTrue(repo.markAscentSyncedIfUnchanged("seq", unsyncedAscent("seq")!!.rowVersion))
        repo.restoreQuickBidFromSend(QuickLogBidInput(
            uuid = "seq", climbUuid = "climb-a", angle = 40, isMirror = false, bidCount = 1,
            climbedAt = "2026-10-07T18:00:00", climbName = "A", difficultyAverage = null, boardBrand = "kilter", layoutId = null,
        ))
        assertEquals(listOf("seq"), repo.pendingLogDeletions())
        assertTrue(unsyncedBid("seq") != null)
    }

    @Test fun a_send_of_an_attempt_not_yet_uploaded_queues_nothing() {
        bid("fresh", synced = false)
        repo.promoteQuickBidToSend(QuickLogSendInput(
            uuid = "fresh", climbUuid = "climb-a", angle = 40, isMirror = false, bidCount = 2, difficulty = null,
            climbedAt = "2026-10-07T18:05:00", climbName = "A", difficultyAverage = null, climbFrames = "",
            framesCount = 0, boardBrand = "kilter", layoutId = null,
        ))
        assertTrue(repo.pendingLogDeletions().isEmpty())
    }

    @Test fun an_edited_uploaded_send_is_replaced_but_a_new_rating_alone_is_not() {
        ascent("send", synced = true)
        // Device test 2026-10-08: a new rating first, then more attempts.
        repo.updateAscent("send", bidCount = 2, quality = 4, comment = null)
        assertTrue(repo.pendingLogDeletions().isEmpty(), "the rating is not part of a Kilter log")
        assertEquals(null, unsyncedAscent("send"), "a rating alone leaves the row as uploaded")
        repo.updateAscent("send", bidCount = 4, quality = 4, comment = null)
        assertEquals(listOf("send"), repo.pendingLogDeletions())
        assertEquals(4, unsyncedAscent("send")!!.bidCount)
        assertFalse(unsyncedAscent("send") == null)
    }

    @Test fun imported_entries_are_found_by_their_external_id_prefix() {
        repo.insertBid(
            uuid = "imp", climbUuid = "climb-a", angle = 40, isMirror = false, bidCount = 1, comment = null,
            climbedAt = "2026-10-07T18:00:00", synced = true, climbName = "A", difficultyAverage = null,
            externalId = "aurora-json:bid:1",
        )
        bid("own", synced = true)
        assertEquals(setOf("imp"), repo.getLogUuidsWithExternalIdPrefix("aurora-json:"))
    }
}
