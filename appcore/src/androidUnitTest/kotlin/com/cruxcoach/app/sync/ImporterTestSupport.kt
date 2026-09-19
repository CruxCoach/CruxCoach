package com.cruxcoach.app.sync

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.BoardDatabaseHandle
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.domain.board.FramesBinaryCodec
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager

/** A real file-backed BoardDB created from the generated schema, plus builders for synthetic source files. */
class ImporterFixture {
    val dir: File = Files.createTempDirectory("importer").toFile()
    private val targetFile = File(dir, "cruxcoach.db")
    private val driver = JdbcSqliteDriver("jdbc:sqlite:${targetFile.absolutePath}").also { BoardDatabase.Schema.create(it) }
    val handle = BoardDatabaseHandle(
        BoardDatabase(
            driver,
            Climbs.Adapter(object : ColumnAdapter<String, ByteArray> {
                override fun decode(databaseValue: ByteArray) = FramesBinaryCodec.decode(databaseValue)
                override fun encode(value: String) = FramesBinaryCodec.encode(value)
            }),
        ),
        driver,
    )
    val importer = CatalogueImporter(handle)

    /** Builds a source SQLite file from DDL/DML statements and returns its path. */
    fun source(name: String, vararg statements: String): String {
        val file = File(dir, name)
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s -> statements.forEach { s.executeUpdate(it.trimIndent()) } }
        }
        return file.absolutePath
    }

    /** Independent connection: sees only what the importer committed. */
    fun rows(sql: String): List<List<Any?>> =
        DriverManager.getConnection("jdbc:sqlite:${targetFile.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    val n = rs.metaData.columnCount
                    buildList { while (rs.next()) add((1..n).map { rs.getObject(it) }) }
                }
            }
        }

    fun scalar(sql: String): Any? = rows(sql).single().single()
    fun long(sql: String): Long = (scalar(sql) as Number).toLong()
    fun execTarget(sql: String) {
        DriverManager.getConnection("jdbc:sqlite:${targetFile.absolutePath}").use { c -> c.createStatement().use { it.executeUpdate(sql) } }
    }

    fun frames(uuid: String): String =
        FramesBinaryCodec.decode(
            DriverManager.getConnection("jdbc:sqlite:${targetFile.absolutePath}").use { c ->
                c.createStatement().use { s -> s.executeQuery("SELECT frames FROM climbs WHERE uuid='$uuid'").use { it.next(); it.getBytes(1) } }
            },
        )

    fun hotPathIndexesPresent(): Set<String> =
        rows("SELECT name FROM sqlite_master WHERE type='index'").map { it[0] as String }
            .filter { n -> CatalogueImporter.HOT_PATH_INDEXES.any { it.first == n } }.toSet()

    companion object {
        /** Mirrors the real `climbs-YYYY-MM` chunk (inspected 2026-09). */
        const val KILTER_CLIMBS_DDL = """
            CREATE TABLE climbs (
                uuid TEXT PRIMARY KEY, layout_id INTEGER NOT NULL,
                setter_username TEXT, name TEXT NOT NULL, frames TEXT NOT NULL,
                frames_count INTEGER NOT NULL DEFAULT 1, is_listed INTEGER NOT NULL DEFAULT 1,
                edge_left INTEGER, edge_right INTEGER, edge_bottom INTEGER, edge_top INTEGER,
                created_at TEXT, description TEXT NOT NULL DEFAULT '',
                is_nomatch INTEGER NOT NULL DEFAULT 0, frames_pace INTEGER NOT NULL DEFAULT 0,
                hsm INTEGER NOT NULL DEFAULT 0, move_count INTEGER NOT NULL DEFAULT 0,
                origin TEXT NOT NULL DEFAULT 'kilter', created_by_pubkey TEXT)"""

        const val STATS_DDL = """
            CREATE TABLE climb_stats (
                climb_uuid TEXT NOT NULL, angle INTEGER NOT NULL,
                display_difficulty REAL, difficulty_average REAL, quality_average REAL,
                ascensionist_count INTEGER DEFAULT 0, benchmark_difficulty REAL, fa_username TEXT, fa_at TEXT,
                PRIMARY KEY (climb_uuid, angle))"""

        /** Mirrors the real `meta` chunk. */
        val KILTER_META_DDL = arrayOf(
            "CREATE TABLE layouts (id INTEGER PRIMARY KEY, product_id INTEGER NOT NULL)",
            "CREATE TABLE placements (id INTEGER PRIMARY KEY, hole_id INTEGER NOT NULL, layout_id INTEGER NOT NULL, set_id INTEGER NOT NULL, default_placement_role_id INTEGER)",
            "CREATE TABLE holes (id INTEGER PRIMARY KEY, x INTEGER NOT NULL, y INTEGER NOT NULL)",
            "CREATE TABLE product_sizes (id INTEGER PRIMARY KEY, product_id INTEGER NOT NULL, name TEXT NOT NULL, edge_left INTEGER NOT NULL, edge_right INTEGER NOT NULL, edge_bottom INTEGER NOT NULL, edge_top INTEGER NOT NULL, image_filename TEXT, is_listed INTEGER NOT NULL DEFAULT 1)",
            "CREATE TABLE product_sizes_layouts_sets (id INTEGER PRIMARY KEY, product_size_id INTEGER NOT NULL, layout_id INTEGER NOT NULL, set_id INTEGER NOT NULL, image_filename TEXT, is_listed INTEGER NOT NULL DEFAULT 1)",
            "CREATE TABLE leds (hole_id INTEGER NOT NULL, product_size_id INTEGER NOT NULL, position INTEGER NOT NULL, PRIMARY KEY (hole_id, product_size_id))",
            "CREATE TABLE shared_syncs (table_name TEXT PRIMARY KEY, last_synchronized_at TEXT NOT NULL)",
        )
    }
}
