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
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The editor's duplicate warning: several climbs can share the same holds
 * (catalogue duplicates, a climb published twice). The one-row read threw on
 * those and every publish of such holds failed. Real SQLite, same harness as
 * [InsertLocalDraftBrandTest].
 */
class FindClimbByFramesHashTest {
    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var repo: BoardRepositoryImpl

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    private val me = "a".repeat(64)
    private val someoneElse = "b".repeat(64)

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-dup-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        repo = BoardRepositoryImpl(BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter)))
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun climb(uuid: String, pubkey: String, hash: String = "same-holds") = repo.insertLocalDraft(
        draft = LocalClimbDraft(
            uuid = uuid,
            name = uuid,
            description = "",
            framesText = "p100r12p200r13p300r14",
            framesHash = hash,
            createdAt = "2026-09-24T10:00:00Z",
            createdByPubkey = pubkey,
            moveCount = 1L,
        ),
        layoutId = 1L,
        angle = 40L,
        setterGradeId = 20,
        bounds = null,
        boardBrand = "kilter",
    )

    @Test
    fun `several climbs with the same holds give one answer instead of an error`() {
        climb("theirs-1", someoneElse)
        climb("theirs-2", someoneElse)

        val found = repo.findClimbByFramesHash("same-holds", 1L, "kilter", ownPubkey = me)

        assertEquals("theirs-2", found?.uuid)
    }

    @Test
    fun `the own climb comes first`() {
        climb("mine", me)
        climb("theirs", someoneElse)

        assertEquals("mine", repo.findClimbByFramesHash("same-holds", 1L, "kilter", ownPubkey = me)?.uuid)
    }

    @Test
    fun `an own draft is reported as a draft`() {
        climb("mine", me)

        // The editor calls a draft match a draft, not a published climb (M-096).
        assertEquals("draft", repo.findClimbByFramesHash("same-holds", 1L, "kilter", ownPubkey = me)?.syncStatus)
    }

    @Test
    fun `a deleted climb is no duplicate`() {
        climb("gone", me)
        driver.execute(null, "UPDATE climbs SET sync_status = 'deleted' WHERE uuid = 'gone'", 0)

        assertNull(repo.findClimbByFramesHash("same-holds", 1L, "kilter", ownPubkey = me))
    }
}
