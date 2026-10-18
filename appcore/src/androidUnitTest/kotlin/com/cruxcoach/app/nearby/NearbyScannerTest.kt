package com.cruxcoach.app.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Android `NearbyClimbScanner`'s bookkeeping rules, driven with a manual clock. */
class NearbyScannerTest {
    private var now = 0L
    private val scanner = NearbyScanner { now }

    private fun climb(id: String, uuid: String, angle: Int = 40, rssi: Int = -60, survives: Boolean = true) =
        scanner.onManufacturerData(id, NearbyClimbProtocol.COMPANY_ID,
            NearbyClimbProtocol.encodeClimbData(uuid, angle, projectionSurvivesDisconnect = survives), rssi)

    private fun session(id: String, sessionId: Int, rssi: Int = -60) =
        scanner.onManufacturerData(id, NearbyClimbProtocol.COMPANY_ID,
            NearbyClimbProtocol.encodeSessionAdvertisement(sessionId, 3, "Host"), rssi)

    @Test
    fun `a climb that stops advertising becomes the board's last climb after three seconds`() {
        climb("p1", UUID_A)
        now = 3_000
        scanner.cleanup()
        assertTrue(!scanner.nearbyClimbs.value.single().isLastClimb)
        now = 3_001
        scanner.cleanup()
        assertTrue(scanner.nearbyClimbs.value.single().isLastClimb)
    }

    private fun boardConnected(id: String) =
        scanner.onManufacturerData(id, NearbyClimbProtocol.COMPANY_ID, NearbyClimbProtocol.encodeBoardConnected(), -60)

    @Test
    fun `a volatile last climb expires after thirty seconds`() {
        climb("p1", UUID_A, survives = false)
        now = 3_001
        scanner.cleanup()
        assertTrue(scanner.nearbyClimbs.value.single().isLastClimb)
        // A BoardConnected event runs the expiry without clearing last climbs itself.
        now = 30_000
        boardConnected("b1")
        assertTrue(scanner.nearbyClimbs.value.any { it.climbUuid == UUID_A })
        now = 30_001
        boardConnected("b1")
        assertTrue(scanner.nearbyClimbs.value.none { it.climbUuid == UUID_A })
    }

    @Test
    fun `a retained last climb outlives thirty seconds`() {
        climb("p1", UUID_A, survives = true)
        now = 3_001
        scanner.cleanup()
        now = 60_000
        boardConnected("b1")
        assertTrue(scanner.nearbyClimbs.value.single { it.climbUuid == UUID_A }.isLastClimb)
    }

    @Test
    fun `new climb data replaces every last climb`() {
        climb("p1", UUID_A)
        now = 3_001
        scanner.cleanup()
        climb("p2", UUID_B)
        assertEquals(listOf(UUID_B to false), scanner.nearbyClimbs.value.map { it.climbUuid to it.isLastClimb })
    }

    @Test
    fun `one entry per climb keeps the strongest sender and occupied boards stay distinct`() {
        climb("p1", UUID_A, rssi = -70)
        climb("p2", UUID_A, rssi = -50)
        scanner.onManufacturerData("b1", NearbyClimbProtocol.COMPANY_ID, NearbyClimbProtocol.encodeBoardConnected(), -60)
        scanner.onManufacturerData("b2", NearbyClimbProtocol.COMPANY_ID, NearbyClimbProtocol.encodeBoardConnected(), -60)
        val climbs = scanner.nearbyClimbs.value
        assertEquals("p2", climbs.single { !it.connectedOnly }.identifier)
        assertEquals(2, climbs.count { it.connectedOnly })
    }

    @Test
    fun `gone removes a sender at once and repeated gones within ten seconds are ignored`() {
        session("h1", 7)
        climb("h1", UUID_A)
        scanner.onManufacturerData("h1", NearbyClimbProtocol.COMPANY_ID, NearbyClimbProtocol.encodeGone(), -60)
        assertTrue(scanner.nearbyClimbs.value.isEmpty())
        assertTrue(scanner.nearbySessions.value.isEmpty())
        // The sender comes back; a late GONE copy from the same burst must not remove it.
        now = 5_000
        session("h1", 7)
        scanner.onManufacturerData("h1", NearbyClimbProtocol.COMPANY_ID, NearbyClimbProtocol.encodeGone(), -60)
        assertEquals(1, scanner.nearbySessions.value.size)
    }

    @Test
    fun `sessions go stale after five seconds and dedupe by session id`() {
        session("h1", 7, rssi = -70)
        session("h2", 7, rssi = -55)
        session("h3", 8)
        assertEquals(listOf(7 to "h2", 8 to "h3"), scanner.nearbySessions.value.map { it.sessionId to it.identifier })
        now = 5_001
        scanner.cleanup()
        assertTrue(scanner.nearbySessions.value.isEmpty())
    }

    @Test
    fun `a session keeps its last scan response climb when one is missed`() {
        scanner.onManufacturerData("h1", NearbyClimbProtocol.SESSION_CLIMB_COMPANY_ID,
            NearbyClimbProtocol.encodeClimbData(UUID_A, 35), -60)
        session("h1", 7)
        assertEquals(UUID_A to 35, scanner.nearbySessions.value.single().let { it.currentClimbUuid to it.currentClimbAngle })
        session("h1", 7)
        assertEquals(UUID_A, scanner.nearbySessions.value.single().currentClimbUuid)
    }

    @Test
    fun `signals below the floor and foreign payloads are ignored`() {
        climb("p1", UUID_A, rssi = -83)
        scanner.onManufacturerData("p2", 0x004C, NearbyClimbProtocol.encodeClimbData(UUID_A, 40), -50)
        scanner.onManufacturerData("p3", NearbyClimbProtocol.COMPANY_ID, byteArrayOf(1, 2, 3, 4, 5, 6), -50)
        assertTrue(scanner.nearbyClimbs.value.isEmpty())
        assertNull(scanner.nearbySessions.value.firstOrNull())
    }

    companion object {
        const val UUID_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val UUID_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
    }
}
