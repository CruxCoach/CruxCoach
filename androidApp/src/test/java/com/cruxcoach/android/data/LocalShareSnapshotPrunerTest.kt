package com.cruxcoach.android.data

import android.database.sqlite.SQLiteDatabase
import com.cruxcoach.domain.board.BoardBrand
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
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
