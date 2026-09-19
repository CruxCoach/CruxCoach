package com.cruxcoach.app.browse.testing

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.FramesBinaryCodec

class MemoryKeyValueStore : KeyValueStore {
    val values = HashMap<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
    override fun keys(): Set<String> = values.keys.toSet()
}

/** Real in-memory BoardDB + SecureDB with the :shared repository impls. */
class BrowseTestDb {
    private val boardDriver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val secureDriver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }
    val board: BoardDatabase
    val boardRepo: BoardRepositoryImpl
    val personalRepo: PersonalBoardRepositoryImpl

    init {
        BoardDatabase.Schema.create(boardDriver)
        SecureDatabase.Schema.create(secureDriver)
        board = BoardDatabase(boardDriver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        boardRepo = BoardRepositoryImpl(board)
        personalRepo = PersonalBoardRepositoryImpl(SecureDatabase(secureDriver))
    }

    fun close() {
        boardDriver.close()
        secureDriver.close()
    }

    fun climb(
        uuid: String,
        name: String = uuid,
        brand: String = "kilter",
        layoutId: Int = 1,
        angle: Int = 40,
        difficulty: Double? = 15.0,
        ascensionists: Long = 10L,
        benchmark: Double? = null,
        frames: String = "p100r12p101r14",
        hsm: Long = 0L,
        /** 'kilter' | 'quantum' | 'boardsesh' = catalogue row; 'cruxcoach' = community; 'local' = own draft. */
        provenance: String = "kilter",
        pubkey: String = "pk-$uuid",
    ) {
        board.boardQueries.insertLocalDraft(
            uuid = uuid, layout_id = layoutId.toLong(), setter_username = "setter", name = name,
            frames = frames, edge_left = 0L, edge_right = 144L, edge_bottom = 0L, edge_top = 156L,
            created_at = "2026-08-20T00:00:00Z", description = "", move_count = 1L, hsm = hsm,
            created_by_pubkey = pubkey, frames_hash = "hash-$uuid", board_brand = brand,
        )
        if (provenance != "local") {
            val source = if (provenance == "cruxcoach") "nostr" else if (provenance == "boardsesh") "kilter" else provenance
            boardDriver.execute(null, "UPDATE climbs SET origin = ?, source = ? WHERE uuid = ?", 3) {
                bindString(0, provenance)
                bindString(1, source)
                bindString(2, uuid)
            }
        }
        board.boardQueries.upsertClimbStat(
            climb_uuid = uuid, angle = angle.toLong(), display_difficulty = difficulty,
            difficulty_average = difficulty, quality_average = 2.5, ascensionist_count = ascensionists,
            benchmark_difficulty = benchmark, fa_username = null, fa_at = null, official_kilter_difficulty = null,
        )
    }

    fun send(climbUuid: String, angle: Int = 40) = personalRepo.insertAscent(
        uuid = "asc-$climbUuid", climbUuid = climbUuid, angle = angle.toLong(), isMirror = false, attemptId = 0,
        bidCount = 1, quality = 3, difficulty = null, isBenchmark = false, comment = null,
        climbedAt = "2026-08-21 10:00:00", synced = false, climbName = climbUuid, difficultyAverage = 15.0,
        climbFrames = "p100r12", framesCount = 1,
    )

    fun attempt(climbUuid: String, angle: Int = 40) = personalRepo.insertBid(
        uuid = "bid-$climbUuid", climbUuid = climbUuid, angle = angle.toLong(), isMirror = false, bidCount = 2,
        comment = null, climbedAt = "2026-08-21 09:00:00", synced = false, climbName = climbUuid,
        difficultyAverage = 15.0,
    )
}
