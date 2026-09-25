package com.cruxcoach.android.data

import android.database.sqlite.SQLiteDatabase
import com.cruxcoach.domain.board.BoardBrand
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class LocalShareSnapshotPrunerTest {

    private lateinit var file: File

    @Before fun setUp() {
        file = File.createTempFile("share-prune", ".sqlite3")
        file.delete()
    }

    @After fun tearDown() { file.delete() }

    private fun seed(block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use(block)
    }

    private fun <T> read(block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use(block)

    private fun SQLiteDatabase.strings(sql: String): List<String> =
        rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    @Test fun `keeps the chosen families and everything hanging off their climbs`() {
        seed { db ->
            db.execSQL("CREATE TABLE climbs (uuid TEXT PRIMARY KEY, board_brand TEXT)")
            db.execSQL("CREATE TABLE climb_stats (climb_uuid TEXT, angle INTEGER)")
            db.execSQL("CREATE TABLE quantum_route_refs (climb_uuid TEXT, route TEXT)")
            db.execSQL("CREATE TABLE placements (id INTEGER, board_brand TEXT)")
            db.execSQL("CREATE TABLE difficulty_grades (difficulty INTEGER)")
            db.execSQL("INSERT INTO climbs VALUES ('k1','kilter'),('k2',' Kilter '),('m1','moonboard'),('q1','quantum')")
            db.execSQL("INSERT INTO climb_stats VALUES ('k1',40),('m1',40),('q1',0)")
            db.execSQL("INSERT INTO quantum_route_refs VALUES ('q1','r')")
            db.execSQL("INSERT INTO placements VALUES (1,'kilter'),(2,'moonboard'),(3,NULL)")
            db.execSQL("INSERT INTO difficulty_grades VALUES (10)")
        }

        val removed = LocalShareSnapshotPruner.prune(file, setOf(BoardBrand.KILTER))

        assertEquals(2L, removed)
        read { db ->
            assertEquals(listOf("k1", "k2"), db.strings("SELECT uuid FROM climbs ORDER BY uuid"))
            assertEquals(listOf("k1"), db.strings("SELECT climb_uuid FROM climb_stats"))
            assertEquals(emptyList<String>(), db.strings("SELECT climb_uuid FROM quantum_route_refs"))
            // A row that names no family is the importer's to judge, not ours to drop.
            assertEquals(listOf("1", "3"), db.strings("SELECT id FROM placements ORDER BY id"))
            assertEquals(listOf("10"), db.strings("SELECT difficulty FROM difficulty_grades"))
        }
    }

    @Test fun `CruxCoach community climbs never cross a share, whatever was chosen`() {
        seed { db ->
            db.execSQL("CREATE TABLE climbs (uuid TEXT PRIMARY KEY, board_brand TEXT, source TEXT)")
            db.execSQL("CREATE TABLE climb_stats (climb_uuid TEXT, angle INTEGER)")
            db.execSQL(
                "INSERT INTO climbs VALUES ('m1','moonboard','kilter'),('m2','moonboard','nostr')," +
                    "('k1','kilter','nostr'),('k2','kilter','local')"
            )
            // Stats keyed with a differently cased uuid still belong to their climb.
            db.execSQL("INSERT INTO climb_stats VALUES ('M1',40),('m2',40),('k1',40)")
        }

        // No family choice at all: only the community rows go.
        assertEquals(3L, LocalShareSnapshotPruner.prune(file, emptySet()))
        read { db ->
            assertEquals(listOf("m1"), db.strings("SELECT uuid FROM climbs"))
            assertEquals(listOf("M1"), db.strings("SELECT climb_uuid FROM climb_stats"))
        }
    }

    private fun SQLiteDatabase.long(sql: String): Long =
        rawQuery(sql, null).use { c -> c.moveToFirst(); c.getLong(0) }

    private fun SQLiteDatabase.indexes(table: String): List<String> =
        strings("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='$table' ORDER BY name")

    /** The shape a real sender ships: its live DB is auto_vacuum=FULL and carries browse indexes. */
    private fun seedSenderShapedSnapshot() = seed { db ->
        db.execSQL("PRAGMA auto_vacuum=FULL")
        db.execSQL("VACUUM")
        db.execSQL("CREATE TABLE climbs (uuid TEXT PRIMARY KEY, board_brand TEXT, source TEXT)")
        db.execSQL("CREATE INDEX idx_climbs_board_brand ON climbs(board_brand)")
        db.execSQL("CREATE INDEX idx_climbs_source ON climbs(source)")
        db.execSQL("CREATE TABLE climb_stats (climb_uuid TEXT, angle INTEGER, PRIMARY KEY(climb_uuid, angle))")
        db.execSQL("CREATE INDEX idx_climb_stats_angle ON climb_stats(angle)")
        db.execSQL("CREATE TABLE placements (id INTEGER, board_brand TEXT)")
        db.execSQL("CREATE INDEX idx_placements_brand ON placements(board_brand)")
        db.beginTransaction()
        try {
            repeat(300) { i ->
                val brand = listOf("kilter", "moonboard", "tension")[i % 3]
                val source = if (i % 10 == 0) "nostr" else "kilter"
                db.execSQL("INSERT INTO climbs VALUES ('c$i','$brand','$source')")
                db.execSQL("INSERT INTO climb_stats VALUES ('C$i',40),('c$i',45)")
            }
            db.execSQL("INSERT INTO placements VALUES (1,'kilter'),(2,'tension')")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        assertEquals(1L, db.long("PRAGMA auto_vacuum"))
    }

    @Test fun `a sender-shaped snapshot is cut in place and stays a sound database`() {
        seedSenderShapedSnapshot()

        val removed = LocalShareSnapshotPruner.prune(file, setOf(BoardBrand.KILTER, BoardBrand.MOONBOARD))

        // 100 tension climbs, plus the nostr ones among the other 200 (every tenth climb).
        assertEquals(100L + 20L, removed)
        read { db ->
            assertEquals(listOf("ok"), db.strings("PRAGMA integrity_check"))
            assertEquals(180L, db.long("SELECT COUNT(*) FROM climbs"))
            assertEquals(0L, db.long("SELECT COUNT(*) FROM climbs WHERE board_brand='tension' OR source='nostr'"))
            assertEquals(360L, db.long("SELECT COUNT(*) FROM climb_stats"))
            assertEquals(
                0L,
                db.long("SELECT COUNT(*) FROM climb_stats WHERE LOWER(climb_uuid) NOT IN (SELECT uuid FROM climbs)"),
            )
            // No COMMIT-time page relocation for a file that is deleted after the import.
            assertEquals(2L, db.long("PRAGMA auto_vacuum"))
            // The cut tables lose their browse indexes, never their keys; untouched ones keep all.
            assertEquals(listOf("sqlite_autoindex_climbs_1"), db.indexes("climbs"))
            assertEquals(listOf("sqlite_autoindex_climb_stats_1"), db.indexes("climb_stats"))
            assertEquals(listOf("idx_placements_brand"), db.indexes("placements"))
        }
    }

    @Test fun `a cut that fails leaves the snapshot whole, indexes included`() {
        seedSenderShapedSnapshot()
        seed { db ->
            db.execSQL(
                "CREATE TRIGGER refuse BEFORE DELETE ON climb_stats BEGIN SELECT RAISE(ABORT, 'refused'); END"
            )
        }

        val failure = runCatching { LocalShareSnapshotPruner.prune(file, setOf(BoardBrand.KILTER)) }

        assertTrue(failure.isFailure)
        read { db ->
            assertEquals(listOf("ok"), db.strings("PRAGMA integrity_check"))
            assertEquals(300L, db.long("SELECT COUNT(*) FROM climbs"))
            assertEquals(600L, db.long("SELECT COUNT(*) FROM climb_stats"))
            assertEquals(
                listOf("idx_climbs_board_brand", "idx_climbs_source", "sqlite_autoindex_climbs_1"),
                db.indexes("climbs"),
            )
            assertEquals(
                listOf("idx_climb_stats_angle", "sqlite_autoindex_climb_stats_1"),
                db.indexes("climb_stats"),
            )
        }
    }

    @Test fun `a legacy single-family snapshot is left alone`() {
        seed { db ->
            db.execSQL("CREATE TABLE climbs (uuid TEXT PRIMARY KEY)")
            db.execSQL("INSERT INTO climbs VALUES ('a'),('b')")
        }
        assertEquals(0L, LocalShareSnapshotPruner.prune(file, setOf(BoardBrand.TENSION)))
        assertEquals(2, read { it.strings("SELECT uuid FROM climbs").size })
    }

    @Test fun `an empty choice cuts nothing`() {
        seed { db ->
            db.execSQL("CREATE TABLE climbs (uuid TEXT PRIMARY KEY, board_brand TEXT)")
            db.execSQL("INSERT INTO climbs VALUES ('m1','moonboard')")
        }
        assertEquals(0L, LocalShareSnapshotPruner.prune(file, emptySet()))
        assertEquals(1, read { it.strings("SELECT uuid FROM climbs").size })
    }
}
