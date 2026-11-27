package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.EquipmentV2
import kotlin.test.Test
import kotlin.test.assertTrue

/** Owner 2026-11-22: bands do not stand in for a cable – cable work is planned in kilos, which a band cannot do. */
class EquipmentSubstitutesTest {

    @Test
    fun bandsDoNotStandInForACable() {
        assertTrue(!EquipmentV2.satisfied(EquipmentV2.CABLE, setOf(EquipmentV2.BANDS)))
        assertTrue(EquipmentV2.satisfied(EquipmentV2.CABLE, setOf(EquipmentV2.CABLE)))
        // Bands are still bands, and the other stand-ins stay: rings make dip bars, a kettlebell loads a pick-up block.
        assertTrue(EquipmentV2.satisfied(EquipmentV2.BANDS, setOf(EquipmentV2.BANDS)))
        assertTrue(EquipmentV2.satisfied(EquipmentV2.DIP_BARS, setOf(EquipmentV2.RINGS)))
        assertTrue(EquipmentV2.satisfied(EquipmentV2.PLATES, setOf(EquipmentV2.KETTLEBELL)))
    }
}
