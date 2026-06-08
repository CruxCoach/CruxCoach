package com.cruxcoach.android.data

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.data.repository.LocalClimbDraft
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.domain.board.FramesBinaryCodec
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for the Aurora-family draft mis-tag.
 *
 * Aurora boards (Tension, Grasshopper, Decoy, So iLL, Touchstone) reuse
 * Kilter's low layout-ids, so [BoardRepositoryImpl.insertLocalDraft] previously
 * derived `board_brand` from the layout-id via BoardBrand.fromLayoutId(), which
 * always returns "kilter" for those layouts. `getDraftClimbs` filters by the
 * active board's brand, so an Aurora draft got tagged "kilter" and vanished
 * from the active board's drafts drawer (it looked lost). The fix threads the
 * active board's real brand into insertLocalDraft.
 *
 * Runs against a real in-memory SQLite (JdbcSqliteDriver), same harness as
 * [InsertLocalDraftPreservationTest].
 */
class InsertLocalDraftBrandTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: BoardDatabase
    private lateinit var repo: BoardRepositoryImpl

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-draft-brand-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        repo = BoardRepositoryImpl(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun draft(uuid: String) = LocalClimbDraft(
        uuid = uuid,
        name = "Test",
        description = "",
        framesText = "p100r1p200r2p300r3",
        framesHash = "hash-$uuid",
        createdAt = "2026-06-08T10:00:00Z",
        createdByPubkey = null,
        moveCount = 1L,
    )

    @Test
    fun explicitAuroraBrand_isPersisted_andVisibleUnderThatBrandOnly() {
        // Tension layout id 9 — BoardBrand.fromLayoutId(9) resolves to KILTER,
        // so without the explicit brand this draft would be tagged "kilter".
        repo.insertLocalDraft(
            draft = draft("tb-1"),
            layoutId = 9L,
            angle = 40L,
            setterGradeId = 20,
            bounds = null,
            boardBrand = "tension",
        )

        val underTension = db.boardQueries.getDraftClimbs(boardBrand = "tension", pubkey = null).executeAsList()
        assertTrue(underTension.any { it.uuid == "tb-1" }, "draft must be visible on the Tension board")

        val underKilter = db.boardQueries.getDraftClimbs(boardBrand = "kilter", pubkey = null).executeAsList()
        assertTrue(underKilter.none { it.uuid == "tb-1" }, "draft must NOT be mis-tagged as kilter")
    }

    @Test
    fun nullBrand_fallsBackToLayoutDerivedKilter() {
        // Kilter Original layout id 1, no explicit brand → derive "kilter".
        repo.insertLocalDraft(
            draft = draft("k-1"),
            layoutId = 1L,
            angle = 40L,
            setterGradeId = 20,
            bounds = null,
            boardBrand = null,
        )

        val underKilter = db.boardQueries.getDraftClimbs(boardBrand = "kilter", pubkey = null).executeAsList()
        assertTrue(underKilter.any { it.uuid == "k-1" }, "Kilter draft must be visible under kilter")
    }
}
