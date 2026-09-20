package com.cruxcoach.app.imports

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.FramesBinaryCodec

/** Real in-memory BoardDB + SecureDB, the production schemas and repositories. */
class ImportTestDb {
    private val boardDriver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val secureDriver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    val board: BoardDatabase
    val secure: SecureDatabase
    val boardRepo: BoardRepositoryImpl
    val personalRepo: PersonalBoardRepositoryImpl

    init {
        BoardDatabase.Schema.create(boardDriver)
        SecureDatabase.Schema.create(secureDriver)
        board = BoardDatabase(boardDriver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        secure = SecureDatabase(secureDriver)
        boardRepo = BoardRepositoryImpl(board, boardDriver)
        personalRepo = PersonalBoardRepositoryImpl(secure)
    }

    fun close() {
        boardDriver.close()
        secureDriver.close()
    }

    /** A catalogue climb, addressed by the uuid scheme the importer will compute. */
    fun climb(
        uuid: String,
        name: String = uuid,
        brand: String = "moonboard",
        layoutId: Long = 1L,
        angle: Long = 40L,
        difficulty: Double? = 18.0,
        frames: String = "p100r12p101r14",
        pubkey: String? = null,
        setter: String = "setter",
    ) {
        board.boardQueries.insertLocalDraft(
            uuid = uuid, layout_id = layoutId, setter_username = setter, name = name,
            frames = frames, edge_left = 0L, edge_right = 144L, edge_bottom = 0L, edge_top = 156L,
            created_at = "2026-08-20T00:00:00Z", description = "", move_count = 1L, hsm = 0L,
            created_by_pubkey = pubkey, frames_hash = "hash-$uuid", board_brand = brand,
        )
        board.boardQueries.upsertClimbStat(
            climb_uuid = uuid, angle = angle, display_difficulty = difficulty,
            difficulty_average = difficulty, quality_average = 2.5, ascensionist_count = 7L,
            benchmark_difficulty = null, fa_username = null, fa_at = null,
            official_kilter_difficulty = null,
        )
    }

    /** Makes the climb look like a synced catalogue row rather than a local draft. */
    fun markCatalogue(uuid: String) {
        boardDriver.execute(null, "UPDATE climbs SET origin = 'kilter', source = 'kilter' WHERE uuid = ?", 1) {
            bindString(0, uuid)
        }
    }

    fun moonCatalogueComplete() {
        board.boardQueries.upsertSyncState("moonboard_catalogue_complete_v1", "complete")
    }

    fun countAscents(): Long = secure.ascentsQueries.countAscentsWithExternalIdPrefix("%").executeAsOne()
    fun countBids(): Long = secure.bidsQueries.countBidsWithExternalIdPrefix("%").executeAsOne()
    fun countStaged(): Long = secure.moonImportStagingQueries.countStagedMoonImports().executeAsOne()
}
