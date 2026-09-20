package com.cruxcoach.app.map

import com.cruxcoach.app.platform.FileSystem
import com.cruxcoach.app.testing.Fixtures
import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PagesBoardMapParserTest {

    private val locations = PagesBoardMapParser.parse(Fixtures.text("map/boards-sample.geojson")).locations

    @Test
    fun `one row per installed board, walls expanded`() {
        assertEquals(
            listOf(
                "pages-525000,134000-kilter-0-0",
                "pages-525000,134000-kilter-0-1",
                "pages-525000,134000-moonboard-1-0",
                "pages-481000,115000-moonboard-0-0",
                "pages-481000,115000-tension-1-0",
                "pages-80000,70000-kilter-0-0",
            ),
            locations.map { it.id },
        )
    }

    @Test
    fun `Kilter walls carry layout ids, sizes and the fixed angle`() {
        val original = locations[0]
        assertEquals("Boulderwelt Berlin", original.name)
        assertEquals(KILTER_ORIGINAL_LAYOUT, original.layoutId)
        assertEquals("12x12", original.sizeLabel)
        assertEquals(10, original.productSizeId)
        assertEquals(Adjustability.ADJUSTABLE, original.adjustability)
        assertNull(original.fixedAngle)
        assertEquals("Musterstrasse 1", original.address)
        assertEquals("https://boulderwelt.example", original.url)
        assertEquals(true, original.wellpass)

        val homewall = locations[1]
        assertEquals(KILTER_HOMEWALL_LAYOUT, homewall.layoutId)
        assertEquals(Adjustability.FIXED, homewall.adjustability)
        assertEquals(40, homewall.fixedAngle)
    }

    @Test
    fun `MoonBoard variants map to layout ids, access and adjustability`() {
        val commercial = locations[2]
        assertEquals(BoardBrand.MOONBOARD, commercial.boardBrand)
        assertEquals(2, commercial.layoutId)
        assertEquals("MoonBoard 2016", commercial.layoutName)
        assertEquals(AccessType.PUBLIC, commercial.accessType)
        assertEquals(Adjustability.FIXED, commercial.adjustability)
        assertEquals(true, commercial.hasLed)

        val private = locations[3]
        assertEquals(5, private.layoutId)
        assertEquals(AccessType.PRIVATE, private.accessType)
        assertEquals(Adjustability.ADJUSTABLE, private.adjustability)
        assertEquals(false, private.hasLed)
    }

    @Test
    fun `unknown brands, blank names and broken geometry are dropped`() {
        // The third board of that venue carries an unrecognised family and is refused.
        assertEquals(2, locations.count { it.id.startsWith("pages-481000,115000-") })
        assertTrue(locations.none { it.name.isBlank() })
        assertTrue(locations.none { it.name == "Broken geometry" })
    }

    @Test
    fun `a country code that is not two letters becomes the unknown marker`() {
        assertEquals("??", locations[3].countryCode)
        assertEquals("DE", locations[0].countryCode)
    }

    @Test
    fun `a Kilter board without walls still yields one row`() {
        val row = locations.last()
        assertEquals("Kilter without walls", row.name)
        assertNull(row.layoutId)
        assertEquals(Adjustability.UNKNOWN, row.adjustability)
    }

    @Test
    fun `an unreadable or malformed bundle yields no locations instead of throwing`() {
        assertTrue(BundledPagesBoardMapSource(MissingFiles, "/nowhere").load().locations.isEmpty())
        assertTrue(BundledPagesBoardMapSource(TextFiles("not json"), "/x").load().locations.isEmpty())
        assertTrue(BundledPagesBoardMapSource(TextFiles("[]"), "/x").load().locations.isEmpty())
    }

    private object MissingFiles : StubFileSystem() {
        override fun readBytes(path: String, maxBytes: Long): ByteArray? = null
    }

    private class TextFiles(private val text: String) : StubFileSystem() {
        override fun readBytes(path: String, maxBytes: Long): ByteArray? {
            assertEquals("/x/boards.geojson", path)
            return text.encodeToByteArray()
        }
    }

    private abstract class StubFileSystem : FileSystem {
        override fun workDirectory(): String = "/tmp"
        override fun exists(path: String): Boolean = false
        override fun size(path: String): Long = 0
        override fun delete(path: String): Boolean = false
        override fun move(from: String, to: String): Boolean = false
        override fun readBytes(path: String, maxBytes: Long): ByteArray? = null
        override fun writeBytes(path: String, data: ByteArray): Boolean = false
        override fun freeSpaceBytes(): Long = 0
    }
}
