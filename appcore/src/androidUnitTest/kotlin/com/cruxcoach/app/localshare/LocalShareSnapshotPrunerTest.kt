package com.cruxcoach.app.localshare

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ported from Android `LocalShareSnapshotPrunerTest`, on a real SQLite file. */
class LocalShareSnapshotPrunerTest {
    private val file: File = File.createTempFile("share-prune", ".sqlite3").also { it.delete() }

    @AfterTest
    fun tearDown() {
        file.delete()
    }

    private fun seed(vararg statements: String) {
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.createStatement().use { s -> statements.forEach { s.executeUpdate(it) } }
        }
    }

    private fun <T> read(block: (Connection) -> T): T = DriverManager.getConnection("jdbc:sqlite:${file.path}").use(block)

    private fun Connection.strings(sql: String): List<String> =
        createStatement().use { s -> s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }

    private fun Connection.long(sql: String): Long = createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }

    private fun Connection.indexes(table: String): List<String> =
        strings("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='$table' ORDER BY name")

    private fun prune(keep: Set<String>): Long {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}")
        try {
            return LocalShareSnapshotPruner.prune(driver, keep)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `keeps the chosen families and everything hanging off their climbs`() {
        seed(
            "CREATE TABLE climbs (uuid TEXT PRIMARY KEY, board_brand TEXT)",
            "CREATE TABLE climb_stats (climb_uuid TEXT, angle INTEGER)",
            "CREATE TABLE quantum_route_refs (climb_uuid TEXT, route TEXT)",
            "CREATE TABLE placements (id INTEGER, board_brand TEXT)",
            "CREATE TABLE difficulty_grades (difficulty INTEGER)",
            "INSERT INTO climbs VALUES ('k1','kilter'),('k2',' Kilter '),('m1','moonboard'),('q1','quantum')",
            "INSERT INTO climb_stats VALUES ('k1',40),('m1',40),('q1',0)",
            "INSERT INTO quantum_route_refs VALUES ('q1','r')",
            "INSERT INTO placements VALUES (1,'kilter'),(2,'moonboard'),(3,NULL)",
            "INSERT INTO difficulty_grades VALUES (10)",
        )

        assertEquals(2L, prune(setOf("kilter")))
        read { db ->
            assertEquals(listOf("k1", "k2"), db.strings("SELECT uuid FROM climbs ORDER BY uuid"))
            assertEquals(listOf("k1"), db.strings("SELECT climb_uuid FROM climb_stats"))
            assertEquals(emptyList(), db.strings("SELECT climb_uuid FROM quantum_route_refs"))
            // A row that names no family is the importer's to judge, not ours to drop.
            assertEquals(listOf("1", "3"), db.strings("SELECT id FROM placements ORDER BY id"))
            assertEquals(listOf("10"), db.strings("SELECT difficulty FROM difficulty_grades"))
        }
    }

    @Test
    fun `CruxCoach community climbs never cross a share whatever was chosen`() {
        seed(
            "CREATE TABLE climbs (uuid TEXT PRIMARY KEY, board_brand TEXT, source TEXT)",
            "CREATE TABLE climb_stats (climb_uuid TEXT, angle INTEGER)",
            "INSERT INTO climbs VALUES ('m1','moonboard','kilter'),('m2','moonboard','nostr'),('k1','kilter','nostr'),('k2','kilter','local')",
            "INSERT INTO climb_stats VALUES ('M1',40),('m2',40),('k1',40)",
        )

        assertEquals(3L, prune(emptySet()))
        read { db ->
            assertEquals(listOf("m1"), db.strings("SELECT uuid FROM climbs"))
            assertEquals(listOf("M1"), db.strings("SELECT climb_uuid FROM climb_stats"))
        }
    }

    /** The shape a real sender ships: its live DB is auto_vacuum=FULL and carries browse indexes. */
    private fun seedSenderShapedSnapshot() {
        val statements = mutableListOf(
            "PRAGMA auto_vacuum=FULL",
            "VACUUM",
            "CREATE TABLE climbs (uuid TEXT PRIMARY KEY, board_brand TEXT, source TEXT)",
            "CREATE INDEX idx_climbs_board_brand ON climbs(board_brand)",
            "CREATE INDEX idx_climbs_source ON climbs(source)",
            "CREATE TABLE climb_stats (climb_uuid TEXT, angle INTEGER, PRIMARY KEY(climb_uuid, angle))",
            "CREATE INDEX idx_climb_stats_angle ON climb_stats(angle)",
            "CREATE TABLE placements (id INTEGER, board_brand TEXT)",
            "CREATE INDEX idx_placements_brand ON placements(board_brand)",
        )
        repeat(300) { i ->
            val brand = listOf("kilter", "moonboard", "tension")[i % 3]
            val source = if (i % 10 == 0) "nostr" else "kilter"
            statements += "INSERT INTO climbs VALUES ('c$i','$brand','$source')"
            statements += "INSERT INTO climb_stats VALUES ('C$i',40),('c$i',45)"
        }
        statements += "INSERT INTO placements VALUES (1,'kilter'),(2,'tension')"
        seed(*statements.toTypedArray())
        assertEquals(1L, read { it.long("PRAGMA auto_vacuum") })
    }

    @Test
    fun `a sender-shaped snapshot is cut in place and stays a sound database`() {
        seedSenderShapedSnapshot()

        val removed = prune(setOf("kilter", "moonboard"))

        // 100 tension climbs, plus the nostr ones among the other 200 (every tenth climb).
        assertEquals(100L + 20L, removed)
        read { db ->
            assertEquals(listOf("ok"), db.strings("PRAGMA integrity_check"))
            assertEquals(180L, db.long("SELECT COUNT(*) FROM climbs"))
            assertEquals(0L, db.long("SELECT COUNT(*) FROM climbs WHERE board_brand='tension' OR source='nostr'"))
            assertEquals(360L, db.long("SELECT COUNT(*) FROM climb_stats"))
            assertEquals(0L, db.long("SELECT COUNT(*) FROM climb_stats WHERE LOWER(climb_uuid) NOT IN (SELECT uuid FROM climbs)"))
            // No COMMIT-time page relocation for a file that is deleted after the import.
            assertEquals(2L, db.long("PRAGMA auto_vacuum"))
            // The cut tables lose their browse indexes, never their keys; untouched ones keep all.
            assertEquals(listOf("sqlite_autoindex_climbs_1"), db.indexes("climbs"))
            assertEquals(listOf("sqlite_autoindex_climb_stats_1"), db.indexes("climb_stats"))
            assertEquals(listOf("idx_placements_brand"), db.indexes("placements"))
        }
    }

    @Test
    fun `a cut that fails leaves the snapshot whole indexes included`() {
        seedSenderShapedSnapshot()
        seed("CREATE TRIGGER refuse BEFORE DELETE ON climb_stats BEGIN SELECT RAISE(ABORT, 'refused'); END")

        val failure = runCatching { prune(setOf("kilter")) }

        assertTrue(failure.isFailure)
        read { db ->
            assertEquals(listOf("ok"), db.strings("PRAGMA integrity_check"))
            assertEquals(300L, db.long("SELECT COUNT(*) FROM climbs"))
            assertEquals(600L, db.long("SELECT COUNT(*) FROM climb_stats"))
            assertEquals(listOf("idx_climbs_board_brand", "idx_climbs_source", "sqlite_autoindex_climbs_1"), db.indexes("climbs"))
            assertEquals(listOf("idx_climb_stats_angle", "sqlite_autoindex_climb_stats_1"), db.indexes("climb_stats"))
        }
    }

    @Test
    fun `a legacy single-family snapshot is left alone`() {
        seed("CREATE TABLE climbs (uuid TEXT PRIMARY KEY)", "INSERT INTO climbs VALUES ('a'),('b')")
        assertEquals(0L, prune(setOf("tension")))
        assertEquals(2, read { it.strings("SELECT uuid FROM climbs").size })
    }

    @Test
    fun `an empty choice cuts nothing`() {
        seed("CREATE TABLE climbs (uuid TEXT PRIMARY KEY, board_brand TEXT)", "INSERT INTO climbs VALUES ('m1','moonboard')")
        assertEquals(0L, prune(emptySet()))
        assertEquals(1, read { it.strings("SELECT uuid FROM climbs").size })
    }
}
