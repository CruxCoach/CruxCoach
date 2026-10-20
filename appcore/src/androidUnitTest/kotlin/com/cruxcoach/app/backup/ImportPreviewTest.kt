package com.cruxcoach.app.backup

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.data.CruxCoachBackup.Category
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.FramesBinaryCodec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Android's manual-import rules (DataExchangeViewModel.kt) and the selective store on real databases. */
class ImportPreviewTest {

    // ── Ported from Android's DataExchangeCategoriesTest ────────────────

    @Test
    fun `manual backup exposes only released user-facing data`() {
        assertEquals(
            listOf(
                Category.BOARD_LOGBOOK,
                Category.CLIMB_LISTS,
                Category.OWN_CLIMBS,
                Category.TRAINING,
                Category.BODY_STATS,
                Category.FUEL,
            ),
            ManualImportCategories.VISIBLE,
        )
    }

    @Test
    fun `manual board logbook selection includes private climb notes on the wire`() {
        assertEquals(
            setOf(Category.BOARD_LOGBOOK, Category.CLIMB_NOTES),
            ManualImportCategories.withBundledClimbNotes(setOf(Category.BOARD_LOGBOOK)),
        )
        assertFalse(Category.CLIMB_NOTES in ManualImportCategories.withBundledClimbNotes(setOf(Category.CLIMB_LISTS)))
    }

    @Test
    fun `wire-format climb notes appear as board logbook in manual import`() {
        assertEquals(setOf(Category.BOARD_LOGBOOK), ManualImportCategories.toManualCategories(setOf(Category.CLIMB_NOTES)))
        // Hidden legacy categories are never preselected.
        assertEquals(
            setOf(Category.CLIMB_LISTS),
            ManualImportCategories.toManualCategories(setOf(Category.PROFILE, Category.BOARD_SESSIONS, Category.CLIMB_LISTS)),
        )
    }

    // ── Android's JSON sanity check and format detection ────────────────

    @Test
    fun `only json that starts with a brace and names the app passes the sanity check`() {
        val ok = "  \n{\"version\":3,\"app\":\"CruxCoach\"}"
        assertEquals(ok, ImportFiles.decode(ok.encodeToByteArray()), "leading whitespace is fine, the text is kept as it is")
        for (bad in listOf("[]", "hello", "", "{\"version\":3}", "<html>\"app\"")) {
            val e = assertFailsWith<ImportFileRejected>(bad) { ImportFiles.decode(bad.encodeToByteArray()) }
            assertEquals(ImportFailure.NOT_CRUXCOACH_EXPORT, e.failure)
        }
    }

    @Test
    fun `a csv zip is turned back into the json document`() {
        val json = """{"version":3,"app":"CruxCoach","exportedAt":"2026-09-20T08:00:00Z","boardAscents":[],"boardBids":[]}"""
        val archive = CruxCoachCsvArchive.fromJson(json, CruxCoachCsvArchive.ALL_CATEGORIES)
        assertEquals(CruxCoachCsvArchive.toJson(archive), ImportFiles.decode(archive))
    }

    // ── Android's safeImportErrorReason ──────────────────────────────────

    private fun problemOf(json: String): ImportProblem {
        val e = assertFailsWith<Exception> { CruxCoachBackup.preview(json) }
        return ImportFiles.problem(e)
    }

    @Test
    fun `parser errors never quote the file`() {
        val secret = "my private comment"
        val problem = problemOf("""{"app":"CruxCoach","version":3,"exportedAt":"x","boardAscents":[{"comment":"$secret",""")
        assertEquals(ImportFailure.INVALID_FORMAT, problem.failure)
        assertEquals("", problem.detail)
    }

    @Test
    fun `a newer backup version asks for an update`() {
        val problem = problemOf("""{"version":4,"app":"CruxCoach","exportedAt":"2026-09-20T08:00:00Z"}""")
        assertEquals(ImportFailure.NEWER_VERSION, problem.failure)
    }

    @Test
    fun `the codec's own validation message passes through shortened`() {
        val problem = problemOf("""{"version":3,"app":"CruxCoach","exportedAt":"2026-09-20T08:00:00Z","nostrPubkey":"XYZ"}""")
        assertEquals(ImportFailure.INVALID_BACKUP, problem.failure)
        assertEquals("invalid backup: nostrPubkey", problem.detail)
        val long = ImportFiles.problem(IllegalArgumentException("invalid backup: " + "x".repeat(300)))
        assertEquals(120, long.detail.length)
    }

    @Test
    fun `anything else shows only the exception class`() {
        val problem = ImportFiles.problem(IllegalStateException("Unknown logbook entryType secret-user-text"))
        assertEquals(ImportFailure.OTHER, problem.failure)
        assertEquals("IllegalStateException", problem.detail)
        assertEquals(ImportFailure.TOO_LARGE, ImportFiles.problem(ImportFileRejected(ImportFailure.TOO_LARGE)).failure)
    }

    @Test
    fun `android reads at most 64 MB`() {
        assertEquals(64L * 1024 * 1024, ImportFiles.MAX_IMPORT_BYTES)
    }

    // ── The selective store on real databases ───────────────────────────

    private val pubkey = "a".repeat(64)
    private val stranger = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private val ascentUuid = "11111111-1111-1111-1111-111111111111"
    private val climbUuid = "22222222-2222-2222-2222-222222222222"
    private val favouriteUuid = "33333333-3333-3333-3333-333333333333"

    private class Device {
        private val secureDriver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val boardDriver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
            override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
            override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
        }
        val personal: PersonalBoardRepositoryImpl
        val payloads: CruxCoachBackupPayloadStore
        val manual: CruxCoachManualImportStore

        init {
            SecureDatabase.Schema.create(secureDriver)
            BoardDatabase.Schema.create(boardDriver)
            val secure = SecureDatabase(secureDriver)
            personal = PersonalBoardRepositoryImpl(secure)
            val board = BoardRepositoryImpl(BoardDatabase(boardDriver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter)))
            payloads = CruxCoachBackupPayloadStore(secure, board, personal)
            manual = CruxCoachManualImportStore(secure, board, personal, null)
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

    private fun exported(owner: String): String {
        source.personal.insertAscent(
            uuid = ascentUuid, climbUuid = climbUuid, angle = 40, isMirror = false, attemptId = 1,
            bidCount = 3, quality = 4, difficulty = 17, isBenchmark = false, comment = "crux",
            climbedAt = "2026-09-01 18:30:00", synced = false, climbName = "Test Problem",
            difficultyAverage = 17.4, climbFrames = "p1100r12", framesCount = 1,
        )
        source.personal.addClimbToList(source.personal.ensureFavoritesListExists(), favouriteUuid)
        return assertNotNull(source.payloads.exportJson("2026-09-20T08:00:00Z", owner, com.cruxcoach.data.CruxCoachBackup.Category.DEFAULTS))
    }

    @Test
    fun `only the chosen categories are written`() {
        val json = exported(pubkey)
        val preview = CruxCoachBackup.preview(json)
        assertTrue(Category.BOARD_LOGBOOK in preview.detectedCategories())
        assertTrue(Category.CLIMB_LISTS in preview.detectedCategories())

        val lists = target.manual.importSelected(json, setOf(Category.CLIMB_LISTS), pubkey, null)
        assertEquals(0, lists.boardAscents)
        assertEquals(0, target.personal.countUserLogbook(), "the logbook was not chosen")
        assertTrue(target.personal.isClimbFavorited(favouriteUuid))

        val logbook = target.manual.importSelected(
            json, ManualImportCategories.withBundledClimbNotes(setOf(Category.BOARD_LOGBOOK)), pubkey, null,
        )
        assertEquals(1, logbook.boardAscents)
        assertEquals(1, target.personal.countUserLogbook())

        // Android's duplicate handling: a second run writes nothing new.
        val again = target.manual.importSelected(json, setOf(Category.BOARD_LOGBOOK), pubkey, null)
        assertEquals(0, again.boardAscents)
        assertTrue(again.skippedDuplicates >= 1)
        assertEquals(1, target.personal.countUserLogbook())
    }

    @Test
    fun `a file of another identity is refused unless the user accepted it`() {
        val json = exported(stranger)
        val refused = assertFailsWith<IllegalArgumentException> {
            target.manual.importSelected(json, setOf(Category.BOARD_LOGBOOK), pubkey, null)
        }
        assertEquals(ImportFailure.INVALID_BACKUP, ImportFiles.problem(refused).failure)
        assertTrue(refused.message!!.startsWith("invalid backup: nostrPubkey does not match"))
        assertEquals(0, target.personal.countUserLogbook(), "refused before any write")

        // "Import anyway": no identity check, the active identity takes over the drafts.
        val accepted = target.manual.importSelected(json, setOf(Category.BOARD_LOGBOOK), null, pubkey)
        assertEquals(1, accepted.boardAscents)
        assertEquals(1, target.personal.countUserLogbook())
    }
}
