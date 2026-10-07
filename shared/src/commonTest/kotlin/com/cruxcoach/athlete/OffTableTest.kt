package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.OffProduct
import com.cruxcoach.athlete.logic.OffTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OffTableTest {

    @Test
    fun parsesBothNamesServingAndRegions() {
        val p = OffTable.parseLine("4337256725446\tEdamame\t\tRewe beste Wahl\t102\t11\t3.3\t3.8\t\t1")!!
        assertEquals(OffProduct("4337256725446", "Edamame", "", "Rewe beste Wahl", 102.0, 11.0, 3.3, 3.8, null, 1), p)
        assertEquals("Edamame", p.displayName(german = false))

        val both = OffTable.parseLine("5000112646870\tFanta Orange\tFanta orange\tFanta\t19\t0\t4.6\t0\t330\t3")!!
        assertEquals("Fanta Orange", both.displayName(german = true))
        assertEquals("Fanta orange", both.displayName(german = false))
        assertEquals(330.0, both.servingG)
    }

    @Test
    fun skipsCommentsAndBrokenLines() {
        assertNull(OffTable.parseLine("# version: 2026-10-07"))
        assertNull(OffTable.parseLine("123\t\t\tBrand\t1\t1\t1\t1\t\t1"))
        assertNull(OffTable.parseLine("123\tName"))
        assertEquals("2026-10-07", OffTable.version("# version: 2026-10-07"))
    }

    @Test
    fun barcodeKeyMakesUpcAndEanOneProduct() {
        assertEquals(OffTable.barcodeKey("012345678905"), OffTable.barcodeKey("0012345678905"))
        assertEquals(4337256725446L, OffTable.barcodeKey("4337256725446"))
        assertNull(OffTable.barcodeKey("12345678901234567890"))
        assertNull(OffTable.barcodeKey("ABC"))
        assertNull(OffTable.barcodeKey("000"))
        assertEquals(listOf("012345678905", "0012345678905", "12345678905"), OffTable.barcodeVariants("012345678905"))
    }

    @Test
    fun servingLabelsOnlyInGermanOrEnglish() {
        listOf("1 cup (30 g)", "330 ml", "1 Riegel (40 g)", "2 Scheiben (50 g)", "1 bar (24 g)", "1 portion (100 ml)")
            .forEach { kotlin.test.assertTrue(OffTable.readableServing(it), it) }
        // Seen on the test phone for a German Milbona skyr.
        listOf("1 vasetto (350 g)", "1 emballage (300 g)", "1 pot de 150 g")
            .forEach { kotlin.test.assertFalse(OffTable.readableServing(it), it) }
    }

    @Test
    fun ftsQueryUsesPrefixesAndNeutralisesOperators() {
        assertEquals("skyr* natur*", OffTable.ftsQuery("Skyr Natur"))
        assertEquals("oat* or* milk*", OffTable.ftsQuery("oat OR \"milk\"-"))
        assertNull(OffTable.ftsQuery("a ! ?"))
    }
}
