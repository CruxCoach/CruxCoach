package com.cruxcoach.app.backup

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.testing.JvmAead
import com.cruxcoach.app.testing.JvmGzip
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.FramesBinaryCodec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The payload store against real repositories on a real (in-memory) SecureDB,
 * driven through the same encrypt/compress layers the pipeline uses.
 */
class CruxCoachBackupPayloadStoreTest {
    private val secret = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa".hexToBytesOrNull()!!
    private val pubkey = NostrKeys.publicKeyHex(secret)!!
    private val stranger = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"

    /** One device: its own SecureDB, its own board DB, its own store. */
    private class Device {
        private val secureDriver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val boardDriver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
            override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
            override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
        }
        val secure: SecureDatabase
        val personal: PersonalBoardRepositoryImpl
        val store: CruxCoachBackupPayloadStore

        init {
            SecureDatabase.Schema.create(secureDriver)
            BoardDatabase.Schema.create(boardDriver)
            secure = SecureDatabase(secureDriver)
            personal = PersonalBoardRepositoryImpl(secure)
            val board = BoardDatabase(boardDriver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
            store = CruxCoachBackupPayloadStore(secure, BoardRepositoryImpl(board), personal)
        }

        fun close() {
            secureDriver.close()
            boardDriver.close()
        }
    }

    private val source = Device()
    private val target = Device()

    @AfterTest
    fun tearDown() {
        source.close()
        target.close()
    }

    private val ascentUuid = "11111111-1111-1111-1111-111111111111"
    private val climbUuid = "22222222-2222-2222-2222-222222222222"
    private val favouriteUuid = "33333333-3333-3333-3333-333333333333"

    private fun seed(device: Device) {
        device.personal.insertAscent(
            uuid = ascentUuid,
            climbUuid = climbUuid,
            angle = 40,
            isMirror = false,
            attemptId = 1,
            bidCount = 3,
            quality = 4,
            difficulty = 17,
            isBenchmark = true,
            comment = "crux at the third move",
            climbedAt = "2026-09-01 18:30:00",
            synced = false,
            climbName = "Test Problem",
            difficultyAverage = 17.4,
            climbFrames = "p1100r12p1101r14",
            framesCount = 2,
        )
        device.personal.addClimbToList(device.personal.ensureFavoritesListExists(), favouriteUuid)
    }

    @Test
    fun `a backup exported on one device restores on another through the real crypto`() {
        seed(source)
        val json = assertNotNull(source.store.exportJson("2026-09-20T08:00:00Z", pubkey))
        assertTrue(json.contains(ascentUuid))

        // Through the same gzip + AES-256-GCM the pipeline uses.
        val dataKey = ByteArray(32) { (it * 5 + 3).toByte() }
        val blob = assertNotNull(
            BackupCrypto.encrypt(JvmAead, JvmHashing, assertNotNull(JvmGzip.compress(json.encodeToByteArray())), dataKey),
        )
        val restoredJson = assertNotNull(
            JvmGzip.decompress(assertNotNull(BackupCrypto.decrypt(JvmAead, blob, dataKey)), 64L * 1024 * 1024),
        ).decodeToString()
        assertEquals(json, restoredJson)

        val summary = assertNotNull(target.store.importJson(restoredJson, pubkey))
        assertEquals(1, summary.ascentsInBackup)
        assertEquals(0, summary.bidsInBackup)
        assertTrue(summary.listsInBackup >= 1)
        assertTrue(summary.rowsImported >= 2, "ascent and list came back: ${summary.rowsImported}")

        // The rows are really in the target database.
        assertEquals(1, target.personal.countUserLogbook())
        val ascent = target.personal.getUserAscentsAll().single()
        assertEquals(climbUuid, ascent.climbUuid)
        assertEquals(3L, ascent.bidCount)
        assertEquals("crux at the third move", ascent.comment)
        assertTrue(target.personal.isClimbFavorited(favouriteUuid))

        // Re-importing the same payload is idempotent, not a duplicate logbook.
        val again = assertNotNull(target.store.importJson(restoredJson, pubkey))
        assertEquals(1, target.personal.countUserLogbook(), "the ascent is not doubled")
        assertEquals(0, again.ascentsInBackup - summary.ascentsInBackup)
        assertTrue(again.skippedDuplicates >= 1, "the duplicate ascent is reported as skipped")
    }

    @Test
    fun `a payload from another identity is refused before any write`() {
        seed(source)
        val json = assertNotNull(source.store.exportJson("2026-09-20T08:00:00Z", stranger))
        assertNull(target.store.importJson(json, pubkey), "the identity binding refuses it")
        assertEquals(0, target.personal.countUserLogbook())
        assertEquals(emptySet(), target.personal.getUserSentClimbUuids())

        // The very same payload under its own identity imports.
        assertNotNull(target.store.importJson(json, stranger))
        assertEquals(1, target.personal.countUserLogbook())
    }

    @Test
    fun `malformed payloads are a null result, never an exception`() {
        assertNull(target.store.importJson("", pubkey))
        assertNull(target.store.importJson("{}", pubkey))
        assertNull(target.store.importJson("""{"version":99,"app":"CruxCoach","exportedAt":"2026-09-20T08:00:00Z"}""", pubkey))
        assertNull(target.store.importJson("""{"version":3,"app":"CruxCoach"}""", pubkey))
        assertEquals(0, target.personal.countUserLogbook())
    }

    @Test
    fun `an empty account still exports a payload that carries its identity`() {
        val json = assertNotNull(source.store.exportJson("2026-09-20T08:00:00Z", pubkey))
        assertTrue(json.contains(pubkey))
        val summary = assertNotNull(target.store.importJson(json, pubkey))
        assertEquals(0, summary.ascentsInBackup)
        assertEquals(0, target.personal.countUserLogbook())
    }
}
