package com.cruxcoach.app.nutrition

import com.cruxcoach.app.logbook.SerialDispatcher
import com.cruxcoach.app.sync.CatalogueChunk
import com.cruxcoach.app.sync.CatalogueManifest
import com.cruxcoach.app.sync.FakeKeyValueStore
import com.cruxcoach.app.sync.JvmFileSystem
import com.cruxcoach.app.sync.ManifestTrackStore
import com.cruxcoach.app.testing.FixedClock
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The product update channel (Android FoodProductsSync): which manifest is
 * taken, that only a newer extract replaces the database, and that a database
 * rebuilt from an older bundle gets the update again. The manifest's
 * signature and the chunk's hash are checked by ManifestFetcher and
 * ChunkDownloader, which have their own tests.
 */
class FoodProductsSyncTest {
    private val bundled = """
        # version: 2026-10-07 4
        4025500000001	Skyr Natur		Milbona	63	11	4	0.2	150	1	1 Becher (150 g)
    """.trimIndent()
    private val update = """
        # Open Food Facts, https://world.openfoodfacts.org — Open Database License (ODbL) 1.0,
        # version: 2026-11-01
        4025500000001	Skyr Natur neu		Milbona	63	11	4	0.2	150	1	1 Becher (150 g)
    """.trimIndent()

    private val now = 1_800_000_000L
    private lateinit var dir: File
    private lateinit var files: JvmFileSystem
    private val io = SerialDispatcher().dispatcher
    private val keyValues = FakeKeyValueStore()
    private lateinit var store: OffProductStore
    private var downloads = 0

    private fun manifest(
        board: String = "food-products",
        source: String? = "openfoodfacts",
        sha: String = "a".repeat(64),
        createdAt: Long = now - 100,
    ) = CatalogueManifest(
        v = 1, board = board, source = source, createdAt = createdAt, compression = "zstd",
        chunks = listOf(CatalogueChunk("off-products", "tsv", sha, 123, listOf("https://blossom.example/abc"))),
    )

    private fun sync(manifest: CatalogueManifest?) = FoodProductsSync(
        products = store,
        manifest = { manifest },
        download = { _, _, output -> downloads++; File(output).writeText(update); true },
        track = ManifestTrackStore(keyValues, FoodProductsSync.PREFS_NAME, FixedClock(now)),
        keyValues = keyValues,
        files = files,
        workDirectory = dir.absolutePath,
        lines = { path -> OffProductStore.LineReader { action -> File(path).useLines { s -> for (l in s) if (!action(l)) break } } },
        io = io,
    )

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("offsync").toFile()
        files = JvmFileSystem(dir)
        store = OffProductStore(files, dir.absolutePath, NutritionTestKit::openJdbc, io).apply {
            source = { bundled.lineSequence() }
            assetVersion = { "2026-10-07 4" }
        }
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `nothing is fetched while the product database is not on the phone`() = runBlocking<Unit> {
        assertEquals(FoodProductsSync.Result.Skipped, sync(manifest()).sync())
        assertEquals(0, downloads)
    }

    @Test
    fun `a newer extract is installed once, and the same manifest again downloads nothing`() = runBlocking<Unit> {
        assertTrue(store.ensureReady())
        assertEquals(FoodProductsSync.Result.Updated("2026-11-01"), sync(manifest()).sync())
        assertEquals("Skyr Natur neu", store.byBarcode("4025500000001")?.nameDe)
        assertEquals("2026-11-01", keyValues.getString(FoodProductsSync.KEY_INSTALLED))
        // The unpacked update does not stay on the phone.
        assertTrue(!File(dir, "food_products_update.tsv").exists())
        assertEquals(FoodProductsSync.Result.AlreadyCurrent, sync(manifest()).sync())
        assertEquals(1, downloads)
        // An older manifest (a rollback) is not taken.
        assertEquals(FoodProductsSync.Result.AlreadyCurrent, sync(manifest(sha = "b".repeat(64), createdAt = now - 10_000)).sync())
        assertEquals(1, downloads)
    }

    @Test
    fun `only the food products dataset from Open Food Facts is taken`() = runBlocking<Unit> {
        assertTrue(store.ensureReady())
        assertTrue(sync(manifest(board = "board-db")).sync() is FoodProductsSync.Result.Failed)
        assertTrue(sync(manifest(source = "elsewhere")).sync() is FoodProductsSync.Result.Failed)
        assertTrue(sync(null).sync() is FoodProductsSync.Result.Failed)
        assertEquals(0, downloads)
        assertEquals("Skyr Natur", store.byBarcode("4025500000001")?.nameDe)
    }

    @Test
    fun `a database rebuilt from the older bundle gets the update again`() = runBlocking<Unit> {
        assertTrue(store.ensureReady())
        assertEquals(FoodProductsSync.Result.Updated("2026-11-01"), sync(manifest()).sync())
        // Removed in the settings, then built again from the bundled (older) extract.
        store.remove()
        assertEquals("Skyr Natur", store.byBarcode("4025500000001")?.nameDe)
        assertEquals(FoodProductsSync.Result.Updated("2026-11-01"), sync(manifest()).sync())
        assertEquals(2, downloads)
        assertEquals("Skyr Natur neu", store.byBarcode("4025500000001")?.nameDe)
    }
}
