package com.cruxcoach.android.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.cruxcoach.data.BoardDriverFactory
import org.junit.After
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Device 2026-10-21 (Nokia, `dumpsys dbinfo`): both connections to the board DB ran with journalMode=TRUNCATE, and
 * the browser's `SELECT EXISTS(…)` waited minutes behind the importer's `INSERT OR REPLACE INTO climb_stats …`.
 * The driver's `PRAGMA journal_mode = WAL` was prepared but never stepped. In WAL mode a reader is never locked out
 * by a writer, which is what these tests pin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BoardDbWalTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file get() = context.getDatabasePath("cruxcoach.db")
    private var driver: SqlDriver? = null

    @After fun close() {
        driver?.close()
        context.deleteDatabase("cruxcoach.db")
    }

    private fun SqlDriver.text(sql: String): String? =
        executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 0).value

    private fun openDriver(): SqlDriver = BoardDriverFactory(context).createDriver().also { driver = it }

    /** The file header's read/write versions: 2 = WAL, 1 = rollback journal (no connection needed to look). */
    private fun walOnDisk(): Boolean = file.inputStream().use { input ->
        val header = ByteArray(20).also { input.read(it) }
        header[18].toInt() == 2 && header[19].toInt() == 2
    }

    @Test
    fun `the app's board connection runs in WAL mode`() {
        assertEquals("wal", openDriver().text("PRAGMA journal_mode")?.lowercase())
    }

    @Test
    fun `a read answers at once while the importer holds the write lock`() {
        val app = openDriver()
        app.text("SELECT EXISTS(SELECT 1 FROM climbs LIMIT 1)")
        BoardDbConnection.openForWrite(file).use { importer ->
            // Android's beginTransaction() is BEGIN EXCLUSIVE – in rollback mode no reader gets in until it ends.
            importer.beginTransaction()
            try {
                importer.execSQL("DELETE FROM climbs WHERE uuid = 'none'")
                val started = System.nanoTime()
                assertEquals("0", app.text("SELECT EXISTS(SELECT 1 FROM climbs LIMIT 1)"))
                val ms = (System.nanoTime() - started) / 1_000_000
                assertTrue(ms < 1_000, "the read waited $ms ms behind the import")
            } finally {
                importer.endTransaction()
            }
        }
    }

    @Test
    fun `an import folds its WAL back when its connection closes`() {
        openDriver().text("SELECT 1")
        val importer = BoardDbConnection.openForWrite(file)
        importer.beginTransaction()
        try {
            importer.execSQL("CREATE TABLE IF NOT EXISTS wal_probe(x BLOB)")
            repeat(200) { importer.execSQL("INSERT INTO wal_probe VALUES (randomblob(4096))") }
            importer.setTransactionSuccessful()
        } finally {
            importer.endTransaction()
        }
        val wal = File(file.path + "-wal")
        assertTrue(wal.length() > 500_000, "the fixture must leave a large WAL, got ${wal.length()}")
        // The app's connection stays open, as on the phone: nothing else would shrink the file.
        BoardDbConnection.closeAfterImport(importer)
        assertEquals(0L, wal.length())
    }

    @Test
    fun `an import connection opened alone keeps the file in WAL mode`() {
        openDriver().text("SELECT 1")
        driver?.close(); driver = null
        // The framework default asks for TRUNCATE on open; with the app's connection closed that would stick.
        BoardDbConnection.openForWrite(file).use { it.rawQuery("PRAGMA user_version", null).use { c -> c.moveToFirst() } }
        assertTrue(walOnDisk(), "the import connection switched the board DB back to rollback mode")
    }
}
