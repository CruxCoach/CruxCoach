package com.cruxcoach.app.nutrition

import com.cruxcoach.app.logbook.SerialDispatcher
import com.cruxcoach.app.sync.JvmFileSystem
import com.cruxcoach.athlete.logic.OffProduct
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of Android's OffRepositoryTest: the on-device product database
 * (FEAT-069) with a small extract instead of the 17 MB asset, on the same
 * SqlDriver code the iOS app runs. Assertions unchanged.
 */
class OffProductStoreTest {

    private val extract = """
        # Open Food Facts, https://world.openfoodfacts.org — Open Database License (ODbL) 1.0,
        # version: test-1
        # code	name_de	name_en	brand	kcal	protein_g	carbs_g	fat_g	serving_g	regions
        4337256725446	Edamame		Rewe beste Wahl	102	11	3.3	3.8		1
        4025500000001	Skyr Natur		Milbona	63	11	4	0.2	150	1	1 Becher (150 g)
        0012345678905		Icelandic Skyr Plain	Siggi's	60	11	4	0	150	2
        5000112646870	Fanta Orange	Fanta orange	Fanta	19	0	4.6	0	330	3
        broken line
    """.trimIndent()

    private lateinit var dir: File
    private lateinit var files: JvmFileSystem
    private val io = SerialDispatcher().dispatcher
    private lateinit var repo: OffProductStore

    private fun lines(text: String) = OffProductStore.LineReader { action ->
        for (line in text.lineSequence()) if (!action(line)) break
    }

    private fun repository(version: String, lines: String = extract) =
        OffProductStore(files, dir.absolutePath, NutritionTestKit::openJdbc, io).apply {
            source = { lines.lineSequence() }
            assetVersion = { version }
        }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("off").toFile()
        files = JvmFileSystem(dir)
        repo = repository("test-1")
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `builds once and searches names and brands of both languages`() = runBlocking<Unit> {
        assertTrue(repo.ensureReady())
        assertEquals(OffProductStore.State.Ready("test-1"), repo.state.value)
        assertEquals(listOf("Edamame"), repo.search("edama").map { it.nameDe })
        assertEquals(listOf("Fanta Orange"), repo.search("fanta").map { it.nameDe })
        assertEquals(listOf("Edamame"), repo.search("rewe").map { it.nameDe })
    }

    @Test
    fun `the app language decides which region comes first`() = runBlocking<Unit> {
        repo.preferredRegion = { OffProduct.REGION_GERMAN }
        assertEquals("Skyr Natur", repo.search("skyr").first().displayName(german = true))
        repo.preferredRegion = { OffProduct.REGION_ENGLISH }
        assertEquals("Icelandic Skyr Plain", repo.search("skyr").first().displayName(german = false))
    }

    @Test
    fun `barcodes match with or without the leading zero`() = runBlocking<Unit> {
        assertEquals("Icelandic Skyr Plain", repo.byBarcode("012345678905")?.nameEn)
        assertEquals("Icelandic Skyr Plain", repo.byBarcode("0012345678905")?.nameEn)
        assertEquals(330.0, repo.byBarcode("5000112646870")?.servingG)
        assertEquals("1 Becher (150 g)", repo.byBarcode("4025500000001")?.servingLabel)
        assertNull(repo.byBarcode("4000000000000"))
    }

    @Test
    fun `an update replaces the database only when it is newer, and survives an older bundled extract`() = runBlocking<Unit> {
        repo = repository("2026-10-07 4")
        assertTrue(repo.ensureReady())
        var update = extract.replace("# version: test-1", "# version: 2026-11-01").replace("Skyr Natur", "Skyr Natur neu")
        assertTrue(repo.installUpdate(lines(update)))
        assertEquals("Skyr Natur neu", repo.byBarcode("4025500000001")?.nameDe)
        assertEquals("2026-11-01", repo.currentVersion())
        // The same or an older update is ignored.
        assertTrue(!repo.installUpdate(lines(update)))
        update = extract.replace("# version: test-1", "# version: 2026-09-01")
        assertTrue(!repo.installUpdate(lines(update)))

        // After a restart the older bundled extract does not undo the update …
        val restarted = repository("2026-10-07 4", "")
        assertEquals("Skyr Natur neu", restarted.byBarcode("4025500000001")?.nameDe)
        // … but a newer bundled one (an app update) replaces it.
        val appUpdate = repository("2026-12-01 4")
        assertEquals("Skyr Natur", appUpdate.byBarcode("4025500000001")?.nameDe)
        assertEquals("2026-12-01 4", appUpdate.currentVersion())
    }

    @Test
    fun `a bundled table only gets its search index on the phone`() = runBlocking<Unit> {
        // What scripts/build_off_asset.py ships: the finished table without FTS, here written as the unpacked file.
        val staged = File(dir, "staged.db")
        NutritionTestKit.openJdbc(staged.absolutePath, scratch = false).also { d ->
            d.execute(null, "CREATE TABLE product(code INTEGER PRIMARY KEY, name_de TEXT NOT NULL, name_en TEXT NOT NULL, brand TEXT NOT NULL, " +
                "kcal REAL NOT NULL, protein REAL NOT NULL, carbs REAL NOT NULL, fat REAL NOT NULL, serving REAL, regions INTEGER NOT NULL, " +
                "serving_label TEXT)", 0)
            d.execute(null, "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)", 0)
            d.execute(null, "INSERT INTO product VALUES (4025500000001, 'Skyr Natur', '', 'Milbona', 63, 11, 4, 0.2, 150, 1, '1 Becher (150 g)')", 0)
            d.execute(null, "INSERT INTO meta VALUES ('version', '2026-10-07'), ('fts', '0')", 0)
        }.close()
        var unpackedTo = ""
        val bundled = OffProductStore(files, dir.absolutePath, NutritionTestKit::openJdbc, io).apply {
            // The zstd step: the bundled file lands at the staging path.
            prebuilt = { tmp -> unpackedTo = tmp; staged.renameTo(File(tmp)) }
            assetVersion = { "2026-10-07 1" }
        }
        assertEquals("Skyr Natur", bundled.search("skyr").single().nameDe)
        assertEquals("1 Becher (150 g)", bundled.byBarcode("4025500000001")?.servingLabel)
        assertEquals("2026-10-07 1", bundled.currentVersion())
        assertTrue(!File(unpackedTo).exists())
    }

    @Test
    fun `a newer bundled extract replaces the database`() = runBlocking<Unit> {
        assertTrue(repo.ensureReady())
        val updated = repository("test-2", extract.replace("Skyr Natur", "Skyr Natur 0,2 %"))
        assertTrue(updated.ensureReady())
        assertEquals(OffProductStore.State.Ready("test-2"), updated.state.value)
        assertEquals("Skyr Natur 0,2 %", updated.byBarcode("4025500000001")?.nameDe)
        // The same version opens the existing database without rebuilding.
        val again = repository("test-2", "")
        assertTrue(again.ensureReady())
        assertEquals("Skyr Natur 0,2 %", again.byBarcode("4025500000001")?.nameDe)
    }

    @Test
    fun `a failed unpack leaves no half-written file and is not retried on every search`() = runBlocking<Unit> {
        var attempts = 0
        val broken = OffProductStore(files, dir.absolutePath, NutritionTestKit::openJdbc, io).apply {
            prebuilt = { tmp -> attempts++; File(tmp).writeText("not a database"); false }
            assetVersion = { "2026-10-07 1" }
        }
        assertTrue(!broken.ensureReady())
        assertEquals(OffProductStore.State.Failed, broken.state.value)
        assertTrue(broken.search("skyr").isEmpty())
        assertEquals(1, attempts)
        assertTrue(!File(dir, "${OffProductStore.DB_NAME}.tmp").exists())
        assertEquals(0L, broken.bytesOnDisk())
    }

    @Test
    fun `removing frees the space and the next search builds it again`() = runBlocking<Unit> {
        assertTrue(repo.ensureReady())
        assertTrue(repo.bytesOnDisk() > 0)
        repo.remove()
        assertEquals(OffProductStore.State.Missing, repo.state.value)
        assertEquals(0L, repo.bytesOnDisk())
        assertEquals("Skyr Natur", repo.byBarcode("4025500000001")?.nameDe)
        assertTrue(repo.bytesOnDisk() > 0)
    }
}
