package com.cruxcoach.android.foodvision

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.athlete.logic.OffProduct
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The on-device product database (FEAT-069) with a small extract instead of the 12 MB asset. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OffRepositoryTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private val extract = """
        # Open Food Facts, https://world.openfoodfacts.org — Open Database License (ODbL) 1.0,
        # version: test-1
        # code	name_de	name_en	brand	kcal	protein_g	carbs_g	fat_g	serving_g	regions
        4337256725446	Edamame		Rewe beste Wahl	102	11	3.3	3.8		1
        4025500000001	Skyr Natur		Milbona	63	11	4	0.2	150	1
        0012345678905		Icelandic Skyr Plain	Siggi's	60	11	4	0	150	2
        5000112646870	Fanta Orange	Fanta orange	Fanta	19	0	4.6	0	330	3
        broken line
    """.trimIndent()

    private lateinit var repo: OffRepository

    private fun repository(version: String, lines: String = extract) = OffRepository(context).apply {
        source = { lines.reader().buffered() }
        assetVersion = { version }
    }

    @Before
    fun setUp() {
        context.getDatabasePath(OffRepository.DB_NAME).delete()
        repo = repository("test-1")
    }

    @After
    fun tearDown() {
        context.getDatabasePath(OffRepository.DB_NAME).delete()
    }

    @Test
    fun `builds once and searches names and brands of both languages`() = runBlocking {
        assertTrue(repo.ensureReady())
        assertEquals(OffRepository.State.Ready("test-1"), repo.state.value)
        assertEquals(listOf("Edamame"), repo.search("edama").map { it.nameDe })
        assertEquals(listOf("Fanta Orange"), repo.search("fanta").map { it.nameDe })
        assertEquals(listOf("Edamame"), repo.search("rewe").map { it.nameDe })
    }

    @Test
    fun `the app language decides which region comes first`() = runBlocking {
        repo.preferredRegion = { OffProduct.REGION_GERMAN }
        assertEquals("Skyr Natur", repo.search("skyr").first().displayName(german = true))
        repo.preferredRegion = { OffProduct.REGION_ENGLISH }
        assertEquals("Icelandic Skyr Plain", repo.search("skyr").first().displayName(german = false))
    }

    @Test
    fun `barcodes match with or without the leading zero`() = runBlocking {
        assertEquals("Icelandic Skyr Plain", repo.byBarcode("012345678905")?.nameEn)
        assertEquals("Icelandic Skyr Plain", repo.byBarcode("0012345678905")?.nameEn)
        assertEquals(330.0, repo.byBarcode("5000112646870")?.servingG)
        assertNull(repo.byBarcode("4000000000000"))
    }

    @Test
    fun `a newer bundled extract replaces the database`() = runBlocking {
        assertTrue(repo.ensureReady())
        val updated = repository("test-2", extract.replace("Skyr Natur", "Skyr Natur 0,2 %"))
        assertTrue(updated.ensureReady())
        assertEquals(OffRepository.State.Ready("test-2"), updated.state.value)
        assertEquals("Skyr Natur 0,2 %", updated.byBarcode("4025500000001")?.nameDe)
        // The same version opens the existing database without rebuilding.
        val again = repository("test-2", "")
        assertTrue(again.ensureReady())
        assertEquals("Skyr Natur 0,2 %", again.byBarcode("4025500000001")?.nameDe)
    }
}
