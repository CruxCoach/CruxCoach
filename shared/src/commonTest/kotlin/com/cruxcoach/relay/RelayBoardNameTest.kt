package com.cruxcoach.relay

import com.cruxcoach.domain.relay.RelayBoardName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelayBoardNameTest {

    @Test
    fun transparent_prefixesCruxCoach_keepsProductAndApiSuffix() {
        assertEquals("CruxCoach·Kilter Board@3", RelayBoardName.transparent("Kilter Board@3"))
    }

    @Test
    fun transparent_dropsSerial_fromHashForm() {
        assertEquals("CruxCoach·Kilter Board@3", RelayBoardName.transparent("Kilter Board#A1B2@3"))
    }

    @Test
    fun transparent_noApiSuffix_whenAbsent() {
        assertEquals("CruxCoach·MoonBoard", RelayBoardName.transparent("MoonBoard"))
    }

    @Test
    fun transparentBoard_restoresScannerSeparatedApiLevel() {
        assertEquals(
            "CruxCoach·Kilter Board@3",
            RelayBoardName.transparentBoard("Kilter Board", 3),
        )
    }

    @Test
    fun transparent_trimsProductToFitByteBudget_keepingPrefixAndApi() {
        val name = RelayBoardName.transparent("Kilter Board Original Homewall Mega@3")
        assertTrue(name.encodeToByteArray().size <= RelayBoardName.MAX_NAME_BYTES, "name=$name")
        assertTrue(name.startsWith(RelayBoardName.PREFIX), "name=$name")
        assertTrue(name.endsWith("@3"), "name=$name") // @apiLevel is never sacrificed
    }

    @Test
    fun isRelayName_recognisesOurRelays_notRealBoards() {
        assertTrue(RelayBoardName.isRelayName("CruxCoach·Kilter Board@3"))
        assertTrue(RelayBoardName.isRelayName("CruxRelay·Kilter Board@3"))
        assertTrue(RelayBoardName.isRelayName(RelayBoardName.transparent("Kilter Board@3")))
        assertTrue(!RelayBoardName.isRelayName("Kilter Board@3"))
        assertTrue(!RelayBoardName.isRelayName("MoonBoard"))
    }

    @Test
    fun unwrap_restoresBoardName_forNormalScannerParsing() {
        assertEquals(
            "Kilter Board@2",
            RelayBoardName.unwrap("CruxCoach·Kilter Board@2"),
        )
        assertEquals(
            "Kilter Board@2",
            RelayBoardName.unwrap("CruxRelay·Kilter Board@2"),
        )
        assertEquals("MoonBoard", RelayBoardName.unwrap("MoonBoard"))
    }

    @Test
    fun serialMarked_fallback_keepsProductAndApiPristine() {
        val name = RelayBoardName.serialMarked("Kilter Board@3")
        assertEquals("Kilter Board#CR@3", name)
        assertTrue(name.encodeToByteArray().size <= RelayBoardName.MAX_NAME_BYTES)
    }
}
