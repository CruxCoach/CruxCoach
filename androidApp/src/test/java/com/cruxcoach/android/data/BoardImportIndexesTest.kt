package com.cruxcoach.android.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.cruxcoach.db.board.BoardDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BoardImportIndexesTest {
    private lateinit var context: Context
    private val indexes = (BoardDatabaseImporter.CLIMB_INDEXES + BoardDatabaseImporter.STAT_INDEXES).toList()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        AndroidSqliteDriver(BoardDatabase.Schema, context, "cruxcoach.db").use {
            it.execute(null, "CREATE TABLE _probe(x INTEGER)", 0)
            it.execute(null, "DROP TABLE _probe", 0)
        }
    }

    @After
    fun tearDown() {
        context.deleteDatabase("cruxcoach.db")
    }

    private fun openDb() = SQLiteDatabase.openDatabase(
        context.getDatabasePath("cruxcoach.db").absolutePath, null, SQLiteDatabase.OPEN_READWRITE,
    )

    private fun guard() = BoardImportIndexes(::openDb, indexes)

    private fun indexNames(): Set<String> = openDb().use { db ->
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='index'", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    private fun assertIndexesPresent() {
        assertTrue("A live catalogue must keep every browse index", indexNames().containsAll(indexes.map { it.first }))
        // This is the indexed query shape which failed on the Nokia after
        // the original importer abandoned a partially completed index drop.
        openDb().use { db ->
            db.rawQuery("SELECT COUNT(*) FROM climb_stats INDEXED BY idx_climb_stats_by_popularity", null).use {
                assertTrue(it.moveToFirst())
            }
        }
    }

    @Test
    fun emptyCatalogueStillDefersIndexesAndRestoresThemAfterFailure() {
        val importFailure = IllegalStateException("interrupted import")
        val actual = assertThrows(IllegalStateException::class.java) {
            guard().duringImport(onRebuild = {}) {
                assertTrue(indexNames().intersect(indexes.map { it.first }.toSet()).isEmpty())
                // Simulate an import which committed a row before a later
                // chunk failed; the usable row must be indexed afterwards.
                openDb().use { it.execSQL("INSERT INTO climbs(uuid,layout_id,name,frames) VALUES('new',1,'new','')") }
                throw importFailure
            }
        }
        assertSame(importFailure, actual)
        assertIndexesPresent()
    }

    @Test
    fun failedPreparationRollsBackEveryEarlierIndexDrop() {
        // A real SQLite error on the second DROP reproduces the old gap
        // before the import body's finally; no mocked transaction behavior.
        val invalid = indexes.take(1) + ("invalid index name" to "unused")
        var imported = false
        assertThrows(SQLiteException::class.java) {
            BoardImportIndexes(::openDb, invalid).duringImport(onRebuild = {}) { imported = true }
        }
        assertFalse(imported)
        assertIndexesPresent()
    }

    @Test
    fun existingRowsOfAnotherBoardKeepIndexesThroughoutImport() {
        openDb().use { db ->
            db.execSQL("INSERT INTO climbs(uuid,layout_id,name,frames,board_brand) VALUES('moon',2,'moon','','moonboard')")
        }
        guard().duringImport(onRebuild = {}) { assertIndexesPresent() }
        assertIndexesPresent()
    }

    @Test
    fun partialCatalogueWithOnlyStatsAlsoKeepsIndexes() {
        openDb().use { db ->
            db.execSQL("INSERT INTO climb_stats(climb_uuid,angle,layout_id) VALUES('partial',40,2)")
        }
        guard().duringImport(onRebuild = {}) { assertIndexesPresent() }
    }

    @Test
    fun retryRepairsMissingIndexesBeforeTouchingExistingCatalogue() {
        openDb().use { db ->
            db.execSQL("INSERT INTO climbs(uuid,layout_id,name,frames) VALUES('existing',1,'existing','')")
            db.execSQL("DROP INDEX idx_climb_stats_by_popularity")
            db.execSQL("DROP INDEX idx_climbs_origin")
        }
        guard().duringImport(onRebuild = {}) { assertIndexesPresent() }
    }

    @Test
    fun progressCallbackFailureCannotPreventIndexRestoration() {
        val progressFailure = IllegalStateException("observer failed")
        assertSame(progressFailure, assertThrows(IllegalStateException::class.java) {
            guard().duringImport(onRebuild = { throw progressFailure }) { }
        })
        assertIndexesPresent()
    }

    @Test
    fun cleanupFailureDoesNotHideOriginalImportFailure() {
        val importFailure = IllegalArgumentException("bad chunk")
        val progressFailure = IllegalStateException("observer failed")
        val actual = assertThrows(IllegalArgumentException::class.java) {
            guard().duringImport(onRebuild = { throw progressFailure }) { throw importFailure }
        }
        assertSame(importFailure, actual)
        assertEquals(listOf(progressFailure), actual.suppressed.toList())
        assertIndexesPresent()
    }
}
