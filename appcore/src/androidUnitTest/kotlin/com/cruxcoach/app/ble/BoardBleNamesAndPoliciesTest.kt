package com.cruxcoach.app.ble

import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ports of Android's BoardBleNameTest, BoardControllerProfilesTest and the policy tests. */
class BoardBleNamesAndPoliciesTest {

    @Test
    fun quantumNamesRequireCurrentPrefixAndTwelveHexMac() {
        assertEquals("020000000001", BoardBleNames.quantumSerialOrNull("QB_020000000001"))
        assertEquals("020000000001", BoardBleNames.quantumSerialOrNull("QBB_020000000001"))
        assertEquals("AABBCCDDEEFF", BoardBleNames.quantumSerialOrNull("eWalls_room_AABBCCDDEEFF"))
        assertNull(BoardBleNames.quantumSerialOrNull("Quantum Board"))
        assertNull(BoardBleNames.quantumSerialOrNull("QB_not-a-mac"))
        assertNull(BoardBleNames.quantumSerialOrNull("qb_020000000001"))
        assertNull(BoardBleNames.quantumSerialOrNull("QB_0200000000011"))
    }

    @Test
    fun moonBoardNamesArePrefixAndCaseSensitive() {
        assertTrue(BoardBleNames.isMoonBoardName("MoonBoard Masters 2019"))
        assertTrue(BoardBleNames.isMoonBoardName("Moonboard"))
        assertFalse(BoardBleNames.isMoonBoardName("Kilter Board"))
        assertFalse(BoardBleNames.isMoonBoardName("my moonboard"))
    }

    @Test
    fun auroraBrandFromNameMapsEachFamilyPrefix() {
        assertEquals(BoardBrand.TENSION, BoardBleNames.auroraBrandFromName("Tension Board 2"))
        assertEquals(BoardBrand.GRASSHOPPER, BoardBleNames.auroraBrandFromName("Grasshopper Board"))
        assertEquals(BoardBrand.DECOY, BoardBleNames.auroraBrandFromName("Decoy Board"))
        assertEquals(BoardBrand.SOILL, BoardBleNames.auroraBrandFromName("So iLL Board"))
        assertEquals(BoardBrand.SOILL, BoardBleNames.auroraBrandFromName("So-iLL Board"))
        assertEquals(BoardBrand.TOUCHSTONE, BoardBleNames.auroraBrandFromName("Touchstone Board"))
        assertEquals(BoardBrand.KILTER, BoardBleNames.auroraBrandFromName("Kilter Board"))
        assertEquals(BoardBrand.KILTER, BoardBleNames.auroraBrandFromName("Some Unknown Board"))
    }

    @Test
    fun auroraNameGrammar() {
        assertEquals(Triple("Kilter Board", "ABC123", 3), BoardBleNames.parseBoardName("Kilter Board#ABC123@3"))
        assertEquals(Triple("Kilter Board", "ABC123", 2), BoardBleNames.parseBoardName("Kilter Board#ABC123"))
        assertEquals(Triple("Kilter Board", "ABC", 2), BoardBleNames.parseBoardName("Kilter Board#ABC@x"))
        assertEquals(Triple("Kilter Board", "", 3), BoardBleNames.parseBoardName("Kilter Board@3"))
        assertNull(BoardBleNames.parseBoardName("Kilter Board@x"))
        assertNull(BoardBleNames.parseBoardName("Some Headphones"))
    }

    @Test
    fun classifyCoversEveryFamilyAndRelays() {
        val kilter = BoardBleNames.classify("Kilter Board#A1@3", "id-1", -50)!!
        assertEquals(DiscoveredBoard("Kilter Board", "A1", 3, "id-1", -50, BoardBrand.KILTER), kilter)
        val moon = BoardBleNames.classify("MoonBoard A", "id-2", -60)!!
        assertEquals(BoardBrand.MOONBOARD, moon.boardBrand)
        assertEquals(0, moon.apiLevel)
        assertEquals("", moon.serial)
        val quantum = BoardBleNames.classify("QB_020000000001", "id-3", -70)!!
        assertEquals(BoardBrand.QUANTUM, quantum.boardBrand)
        assertEquals(1, quantum.apiLevel)
        assertEquals("020000000001", quantum.serial)
        val relay = BoardBleNames.classify("CruxCoach·Tension Board@2", "id-4", -40)!!
        assertTrue(relay.isCruxRelay)
        assertEquals(BoardBrand.TENSION, relay.boardBrand)
        assertEquals(2, relay.apiLevel)
        assertNull(BoardBleNames.classify("JBL Flip", "id-5", -40))
    }

    @Test
    fun uuidComparisonAcceptsCoreBluetoothShortForms() {
        assertTrue(BoardBleUuids.sameUuid("FFE0", BoardBleUuids.QUANTUM_SERVICE))
        assertTrue(BoardBleUuids.sameUuid("fff2", BoardBleUuids.QUANTUM_WRITE_CHAR))
        assertTrue(BoardBleUuids.sameUuid("6e400002-b5a3-f393-e0a9-e50e24dcca9e", BoardBleUuids.DATA_TRANSFER_CHAR))
        assertFalse(BoardBleUuids.sameUuid("FFF0", BoardBleUuids.QUANTUM_SERVICE))
    }

    @Test
    fun palettesMatchAndroid() {
        assertEquals(LedHoldColors(0x1C, 0x1F, 0xE3, 0xF4), LedHoldColors.standardFor(BoardBrand.KILTER))
        assertEquals(LedHoldColors(0x1C, 0x03, 0xE0, 0xE3), LedHoldColors.standardFor(BoardBrand.TENSION))
        assertEquals(LedHoldColors(0x1C, 0xE3, 0xFF, 0x1F), LedHoldColors.standardFor(BoardBrand.SOILL))
        assertEquals(LedHoldColors(0x1C, 0x03, 0xE0, 0x03), LedHoldColors.standardFor(BoardBrand.MOONBOARD))
        assertEquals(LedHoldColors(0xE3, 0x03, 0x1C, 0xE0), LedHoldColors())
        val map = LedHoldColors.standardFor(BoardBrand.TENSION).toRoleColorMap()
        assertEquals(0x1C, map[1]); assertEquals(0x03, map[2]); assertEquals(0xE0, map[3]); assertEquals(0xE3, map[4])
        assertEquals(0x1C, map[12]); assertEquals(0xE3, map[45])
    }

    @Test
    fun roleColourPriority() {
        val catalogue = mapOf(12 to 0x11)
        assertEquals(catalogue, resolveRoleColors(BoardBrand.KILTER, catalogue, LedHoldColors.kilterStandard()))
        assertEquals(
            LedHoldColors.kilterStandard().toRoleColorMap(),
            resolveRoleColors(BoardBrand.KILTER, emptyMap(), LedHoldColors.kilterStandard()),
        )
        assertEquals(
            LedHoldColors.standardFor(BoardBrand.DECOY).toRoleColorMap(),
            resolveRoleColors(BoardBrand.DECOY, emptyMap(), LedHoldColors.kilterStandard()),
        )
    }

    @Test
    fun controllerProfiles() {
        val kilter = BoardControllerProfiles.resolve(BoardBrand.KILTER)
        assertEquals(BoardConnectionCapacity.SINGLE, kilter.connectionCapacity)
        assertEquals(BoardProjectionLifetime.RETAINED_AFTER_DISCONNECT, kilter.projectionLifetime)
        assertTrue(kilter.relaySupported)
        val tension = BoardControllerProfiles.resolve(BoardBrand.TENSION, advertisesWhileConnected = true)
        assertEquals(BoardConnectionCapacity.MULTIPLE, tension.connectionCapacity)
        assertFalse(tension.relaySupported)
        val moon = BoardControllerProfiles.resolve(BoardBrand.MOONBOARD, advertisesWhileConnected = true)
        assertEquals(BoardProjectionLifetime.UNTIL_LAST_CONNECTION, moon.projectionLifetime)
        val relay = BoardControllerProfiles.resolve(BoardBrand.KILTER, isCruxRelay = true)
        assertEquals(BoardConnectionCapacity.MULTIPLE, relay.connectionCapacity)
        assertFalse(relay.relaySupported)
    }

    @Test
    fun connectFlowAndIdentity() {
        assertEquals(BoardConnectFlow.DISCOVER, BoardConnectFlowPolicy.initialFlow(true))
        assertEquals(BoardConnectFlow.DIRECT_THEN_DISCOVER, BoardConnectFlowPolicy.initialFlow(true, true))
        assertEquals(BoardConnectFlow.DISCOVER, BoardConnectFlowPolicy.initialFlow(false, true))
        assertEquals("a", BoardConnectFlowPolicy.autoConnectTarget(listOf("a")))
        assertNull(BoardConnectFlowPolicy.autoConnectTarget(listOf("a", "b")))
        assertNull(BoardConnectFlowPolicy.autoConnectTarget(emptyList<String>()))
        val board = DiscoveredBoard("Quantum", "CONTROLLER-A", 1, "abcd-ef", -40, BoardBrand.QUANTUM)
        assertEquals("quantum:serial:controller-a", PhysicalBoardIdentity.resolve(board))
        assertEquals("quantum:ble:ABCD-EF", PhysicalBoardIdentity.resolve(board.copy(serial = "")))
        assertNull(PhysicalBoardIdentity.resolve(board.copy(serial = "", identifier = "")))
    }

    @Test
    fun idleDisconnectRules() {
        fun arm(capacity: BoardConnectionCapacity, survives: Boolean, state: ConnectionState = ConnectionState.CONNECTED,
                suppressed: Boolean = false, seconds: Int = 60) =
            BoardProjectionPolicy.shouldArmIdleDisconnect(seconds, state, suppressed, capacity, survives)
        assertTrue(arm(BoardConnectionCapacity.SINGLE, true))
        assertFalse(arm(BoardConnectionCapacity.MULTIPLE, false))
        assertFalse(arm(BoardConnectionCapacity.UNKNOWN, true))
        assertFalse(arm(BoardConnectionCapacity.SINGLE, false))
        assertFalse(arm(BoardConnectionCapacity.SINGLE, true, state = ConnectionState.SENDING))
        assertFalse(arm(BoardConnectionCapacity.SINGLE, true, suppressed = true))
        assertFalse(arm(BoardConnectionCapacity.SINGLE, true, seconds = 0))
        assertTrue(BoardProjectionPolicy.hasSendablePayload(BoardBrand.MOONBOARD, 0, "p1r42p2r44"))
        assertFalse(BoardProjectionPolicy.hasSendablePayload(BoardBrand.MOONBOARD, 0, " "))
        assertFalse(BoardProjectionPolicy.projectionSurvivesDisconnect(BoardBrand.MOONBOARD))
        assertTrue(BoardProjectionPolicy.projectionSurvivesDisconnect(BoardBrand.QUANTUM))
    }

    @Test
    fun deliveryPolicyQuantumNeverAutoSends() {
        BoardBrand.entries.filter { it.isInteractive }.forEach { brand ->
            val d = BoardDeliveryPolicy.resolve(BoardSendMode.AUTOMATIC, brand, SessionRole.NONE,
                boardConnected = true, hasDirectPayload = true)
            assertEquals(BoardDeliveryTarget.DIRECT_BOARD, d.target)
            assertEquals(!brand.supportsIndependentClimbLayers, d.dispatchAutomatically, "$brand")
            assertTrue(d.showAction)
        }
        val explicit = BoardDeliveryPolicy.resolve(BoardSendMode.EXPLICIT, BoardBrand.KILTER, SessionRole.NONE,
            boardConnected = true, hasDirectPayload = true)
        assertFalse(explicit.dispatchAutomatically)
        assertTrue(explicit.showAction)
        val session = BoardDeliveryPolicy.resolve(BoardSendMode.AUTOMATIC, BoardBrand.QUANTUM, SessionRole.PARTICIPANT,
            boardConnected = false, hasDirectPayload = false)
        assertEquals(BoardDeliveryTarget.SHARED_QUEUE, session.target)
        val joining = BoardDeliveryPolicy.resolve(BoardSendMode.AUTOMATIC, BoardBrand.QUANTUM, SessionRole.NONE,
            sessionConnecting = true, boardConnected = true, hasDirectPayload = true)
        assertEquals(BoardDeliveryTarget.NONE, joining.target)
        assertFalse(joining.showAction)
        listOf(false to true, true to false).forEach { (connected, payload) ->
            val d = BoardDeliveryPolicy.resolve(BoardSendMode.EXPLICIT, BoardBrand.KILTER, SessionRole.NONE,
                boardConnected = connected, hasDirectPayload = payload)
            assertEquals(BoardDeliveryTarget.NONE, d.target)
        }
        val none = BoardDeliveryDecision(BoardDeliveryTarget.NONE, false, false)
        assertEquals(BoardDetailLampMode.CONNECT, BoardDeliveryPolicy.lampMode(none, true, false, false, false))
        assertEquals(BoardDetailLampMode.HIDDEN, BoardDeliveryPolicy.lampMode(none, true, false, true, false))
        assertEquals(BoardDetailLampMode.LIGHT, BoardDeliveryPolicy.lampMode(explicit, true, true, false, false))
        assertEquals(BoardDetailLampMode.HIDDEN, BoardDeliveryPolicy.lampMode(explicit, true, true, false, true))
    }

    @Test
    fun sendModePolicy() {
        val a = BoardSendMode.AUTOMATIC
        val e = BoardSendMode.EXPLICIT
        assertEquals(e, BoardSendModePolicy.resolve(BoardConnectionCapacity.SINGLE, e, a))
        assertEquals(e, BoardSendModePolicy.resolve(BoardConnectionCapacity.MULTIPLE, a, e))
        assertEquals(a, BoardSendModePolicy.resolve(BoardConnectionCapacity.UNKNOWN, a, a))
        assertEquals(e, BoardSendModePolicy.resolve(BoardConnectionCapacity.UNKNOWN, a, e))
        assertEquals(e, BoardSendModePolicy.resolve(BoardConnectionCapacity.SINGLE, a, e, hostingForOthers = true))
        assertTrue(BoardSendModePolicy.shouldAutoSendAfterCapacityResolution(
            BoardConnectionCapacity.SINGLE, BoardConnectionCapacity.MULTIPLE, e, a))
        assertFalse(BoardSendModePolicy.shouldAutoSendAfterCapacityResolution(
            BoardConnectionCapacity.MULTIPLE, BoardConnectionCapacity.MULTIPLE, e, a))
        assertFalse(BoardSendModePolicy.shouldAutoSendAfterCapacityResolution(
            BoardConnectionCapacity.SINGLE, BoardConnectionCapacity.MULTIPLE, a, a))
    }
}
