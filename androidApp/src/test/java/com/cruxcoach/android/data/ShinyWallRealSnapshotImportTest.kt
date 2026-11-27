package com.cruxcoach.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.ShinyWallFrameEncoder
import com.cruxcoach.domain.board.ShinyWallVariant
import io.mockk.mockk
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Imports a real Shiny Wall catalogue snapshot end to end and checks that
 * every imported climb encodes for its variant. The snapshot is never part
 * of the repository: this runs only when `CRUXCOACH_SHINYWALL_SNAPSHOT`
 * points to a local `shinywall_snapshot_v1.sqlite3`, so CI pays nothing.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class ShinyWallRealSnapshotImportTest {
    private val snapshotPath: String? = System.getenv("CRUXCOACH_SHINYWALL_SNAPSHOT")
    private lateinit var context: Context
    private lateinit var targetPath: File

    @Before fun setUp() {
        assumeTrue("CRUXCOACH_SHINYWALL_SNAPSHOT not set", snapshotPath?.let { File(it).isFile } == true)
        context = org.robolectric.RuntimeEnvironment.getApplication()
        targetPath = context.getDatabasePath("cruxcoach.db")
        targetPath.parentFile?.mkdirs()
        app.cash.sqldelight.driver.android.AndroidSqliteDriver(BoardDatabase.Schema, context, "cruxcoach.db").use { driver ->
            driver.execute(null, "CREATE TABLE IF NOT EXISTS _probe (x INTEGER)", 0)
            driver.execute(null, "DROP TABLE IF EXISTS _probe", 0)
        }
    }

    @After fun tearDown() {
        if (::targetPath.isInitialized) runCatching { targetPath.delete() }
    }

    @Test fun `the real snapshot imports and every climb encodes for its variant`() {
        val importer = BoardDatabaseImporter(context, mockk(relaxed = true), mockk(relaxed = true))
        val started = System.nanoTime()
        importer.importShinyWallSnapshot(File(snapshotPath!!))
        val millis = (System.nanoTime() - started) / 1_000_000

        SQLiteDatabase.openDatabase(targetPath.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            var climbs = 0
            val perVariant = mutableMapOf<ShinyWallVariant, Int>()
            db.rawQuery("SELECT layout_id, frames FROM climbs WHERE board_brand='shinywall'", null).use { c ->
                while (c.moveToNext()) {
                    climbs++
                    val variant = ShinyWallVariant.fromLayoutId(c.getLong(0))!!
                    perVariant[variant] = (perVariant[variant] ?: 0) + 1
                    val holds = BoardClimbParser.parseFrames(c.getBlob(1).decodeToString())
                    assertTrue("climb without holds", holds.isNotEmpty())
                    assertTrue(
                        "climb does not encode for ${variant.code}",
                        ShinyWallFrameEncoder.encodeRoute(holds, variant) != null,
                    )
                }
            }
            val stats = db.rawQuery("SELECT COUNT(*) FROM climb_stats WHERE layout_id BETWEEN 9301 AND 9306", null)
                .use { it.moveToFirst(); it.getInt(0) }
            val orphanStats = db.rawQuery(
                "SELECT COUNT(*) FROM climb_stats WHERE climb_uuid NOT IN (SELECT uuid FROM climbs)", null,
            ).use { it.moveToFirst(); it.getInt(0) }
            println("Shiny Wall real snapshot: $climbs climbs $perVariant, $stats stats rows, ${millis} ms")
            assertTrue(climbs > 1000)
            assertTrue(stats >= climbs)
            assertEquals(0, orphanStats)
        }
    }
}
