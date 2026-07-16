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
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CommunityClimbConcurrencySqlTest {
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
        val tmp = Files.createTempDirectory("cruxcoach-community-concurrency-")
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

    private fun remote(
        uuid: String,
        author: String,
        createdAt: Long,
        eventId: String,
        name: String,
    ) {
        repo.upsertCommunityClimb(
            uuid = uuid,
            layoutId = 1L,
            setterUsername = "setter",
            name = name,
            framesText = "p100r1p200r2p300r3",
            description = "",
            moveCount = 2L,
            nostrEventId = eventId,
            nostrDTag = "cruxcoach:climb:${author.take(8)}:$uuid",
            createdByPubkey = author,
            framesHash = "hash-$eventId",
            createdAt = Instant.ofEpochSecond(createdAt).toString(),
            angle = 40L,
            difficultyAverage = 20.0,
            qualityAverage = null,
            bounds = null,
            boardBrand = "kilter",
        )
    }

    @Test
    fun `older tied and foreign events cannot overwrite at SQL boundary`() {
        val uuid = "remote-climb"
        val author = "a".repeat(64)
        remote(uuid, author, 2_000L, "newest", "Newest")

        remote(uuid, author, 1_999L, "older", "Older")
        remote(uuid, author, 2_000L, "tie", "Tie")
        remote(uuid, "b".repeat(64), 2_001L, "foreign", "Foreign")

        assertEquals(Instant.ofEpochSecond(2_000L).toString(), repo.getClimbCreatedAt(uuid))
        assertEquals("newest", repo.getCommunityClimbDeleteContext(uuid)?.nostrEventId)
    }

    @Test
    fun `remote event cannot replace a locally authored row`() {
        val uuid = "local-climb"
        val owner = "a".repeat(64)
        repo.insertLocalDraft(
            draft = LocalClimbDraft(
                uuid = uuid,
                name = "Local",
                description = "",
                framesText = "p100r1p200r2p300r3",
                framesHash = "local-hash",
                createdAt = Instant.ofEpochSecond(1_000L).toString(),
                createdByPubkey = owner,
                moveCount = 2L,
            ),
            layoutId = 1L,
            angle = 40L,
            setterGradeId = 20,
            bounds = null,
            boardBrand = "kilter",
        )

        remote(uuid, owner, 2_000L, "echo", "Remote echo")

        assertEquals(Instant.ofEpochSecond(1_000L).toString(), repo.getClimbCreatedAt(uuid))
        assertEquals(null, repo.getCommunityClimbDeleteContext(uuid)?.nostrEventId)
    }

    @Test
    fun `timestamp reservation persists before network and late completion cannot regress it`() {
        val uuid = "publish-climb"
        val owner = "c".repeat(64)
        repo.insertLocalDraft(
            draft = LocalClimbDraft(
                uuid = uuid,
                name = "Publish",
                description = "",
                framesText = "p100r1p200r2p300r3",
                framesHash = "publish-hash",
                createdAt = Instant.ofEpochSecond(900L).toString(),
                createdByPubkey = owner,
                moveCount = 2L,
            ),
            layoutId = 1L,
            angle = 40L,
            setterGradeId = 20,
            bounds = null,
            boardBrand = "kilter",
        )

        val first = repo.reserveNextNostrCreatedAt(uuid, 1_000L)
        val second = repo.reserveNextNostrCreatedAt(uuid, 1_000L)
        assertEquals(1_000L, first)
        assertEquals(1_001L, second)

        repo.markClimbPublishedNostr(
            uuid = uuid,
            nostrEventId = "late-first",
            nostrDTag = "d",
            pubkey = owner,
            createdAtIso = Instant.ofEpochSecond(first).toString(),
        )

        assertEquals(Instant.ofEpochSecond(second).toString(), repo.getClimbCreatedAt(uuid))
        assertEquals(null, repo.getCommunityClimbDeleteContext(uuid)?.nostrEventId)
    }
}
