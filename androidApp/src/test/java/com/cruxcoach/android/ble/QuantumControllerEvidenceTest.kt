package com.cruxcoach.android.ble

import android.bluetooth.BluetoothGatt
import com.cruxcoach.domain.board.QuantumActivePlayer
import com.cruxcoach.domain.board.QuantumBoardBroadcastParser
import com.cruxcoach.domain.board.QuantumBoardPacketEncoder
import com.cruxcoach.domain.board.QuantumBoardModel
import com.cruxcoach.domain.board.QuantumBroadcast
import com.cruxcoach.domain.board.QuantumCommand
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantumControllerEvidenceTest {

    private val route = "00112233-4455-6677-8899-aabbccddeeff"
    private val ownUser = "ffeeddcc-bbaa-4988-8766-554433221100"
    private val foreignUser = "11111111-2222-4333-8444-555555555555"

    @Test
    fun `Quantum is not writable until local notification and CCCD both succeed`() {
        assertFalse(quantumNotificationSetupConfirmed(false, BluetoothGatt.GATT_SUCCESS))
        assertFalse(quantumNotificationSetupConfirmed(true, null))
        assertFalse(quantumNotificationSetupConfirmed(true, BluetoothGatt.GATT_FAILURE))
        assertTrue(quantumNotificationSetupConfirmed(true, BluetoothGatt.GATT_SUCCESS))
    }

    @Test
    fun `service discovery callback or fallback can complete each GATT only once`() {
        assertTrue(
            serviceDiscoveryCompletionAllowed(
                connecting = true,
                currentGattMatches = true,
                gattClosed = false,
                alreadyHandled = false,
            ),
        )
        // Represents either the real callback after fallback, or the delayed
        // fallback after the real callback claimed this GATT attempt.
        assertFalse(
            serviceDiscoveryCompletionAllowed(
                connecting = true,
                currentGattMatches = true,
                gattClosed = false,
                alreadyHandled = true,
            ),
        )
        assertFalse(serviceDiscoveryCompletionAllowed(false, true, false, false))
        assertFalse(serviceDiscoveryCompletionAllowed(true, false, false, false))
        assertFalse(serviceDiscoveryCompletionAllowed(true, true, true, false))
    }

    @Test
    fun `fff5 metadata maps every verified controller type and dimensions`() {
        val cases = listOf(
            Triple(0, QuantumBoardModel.XL, 15 to 15),
            Triple(1, QuantumBoardModel.M, 12 to 12),
            Triple(2, QuantumBoardModel.S, 8 to 12),
            Triple(3, QuantumBoardModel.BELAY, 8 to 12),
            Triple(4, QuantumBoardModel.L, 15 to 12),
        )

        cases.forEach { (type, model, dimensions) ->
            val parsed = parseQuantumControllerMetadata(
                metadata(type, dimensions.first, dimensions.second),
            )
            assertEquals(model, parsed?.model)
            assertEquals(dimensions.first, parsed?.columns)
            assertEquals(dimensions.second, parsed?.rows)
        }
    }

    @Test
    fun `fff5 metadata rejects missing unknown and internally inconsistent records`() {
        assertEquals(null, parseQuantumControllerMetadata(ByteArray(40)))
        assertEquals(null, parseQuantumControllerMetadata(metadata(99, 15, 15)))
        assertEquals(null, parseQuantumControllerMetadata(metadata(1, 15, 15)))
    }

    @Test
    fun `anonymous Quantum identity is never a projection or removal scope`() {
        val ownsIdentity: (String) -> Boolean = {
            it.equals(ownUser, ignoreCase = true)
        }

        assertFalse(isScopedQuantumUserId(QuantumBoardPacketEncoder.ZERO_UUID, ownsIdentity))
        assertTrue(isScopedQuantumUserId(ownUser.uppercase(), ownsIdentity))
        assertFalse(isScopedQuantumUserId(foreignUser, ownsIdentity))
    }

    @Test
    fun `notification sizing rejects impossible prefixes and bounds route lists`() {
        assertEquals(
            QUANTUM_NOTIFICATION_NEED_MORE,
            quantumNotificationFrameSize(byteArrayOf()),
        )
        assertEquals(
            QUANTUM_NOTIFICATION_NEED_MORE,
            quantumNotificationFrameSize(byteArrayOf(1)),
        )
        assertEquals(
            QUANTUM_NOTIFICATION_INVALID,
            quantumNotificationFrameSize(byteArrayOf(1, 0x7e)),
        )
        assertEquals(
            QUANTUM_NOTIFICATION_INVALID,
            quantumNotificationFrameSize(byteArrayOf(1, 0x41, 5, 0)),
        )
        assertEquals(
            QUANTUM_NOTIFICATION_INVALID,
            quantumNotificationFrameSize(byteArrayOf(1, 0x47, 0, 1)),
        )

        val onePlayerFrameSize = 4 + QuantumBoardBroadcastParser.PLAYER_BYTES
        assertEquals(
            onePlayerFrameSize,
            quantumNotificationFrameSize(byteArrayOf(1, 0x41, 1, 0)),
        )
        assertEquals(
            4 + BoardLayerManager.MAX_LAYER_IDENTITIES * QuantumBoardBroadcastParser.PLAYER_BYTES,
            quantumNotificationFrameSize(
                byteArrayOf(1, 0x47, BoardLayerManager.MAX_LAYER_IDENTITIES.toByte(), 0),
            ),
        )
    }

    @Test
    fun `stale notification prefix cannot make later fragmented route list independently trusted`() {
        val accumulator = QuantumNotificationAccumulator()

        assertTrue(accumulator.consume(byteArrayOf(1, 0x47, 0)).isEmpty())
        assertTrue(accumulator.consume(byteArrayOf(1, 0x47)).isEmpty())
        val recovered = accumulator.consume(byteArrayOf(0, 0)).single()

        assertEquals(listOf<Byte>(1, 0x47, 0, 0), recovered.bytes.toList())
        assertTrue(recovered.crossedCallbackBoundary)
    }

    @Test
    fun `explicit route list confirmation cannot be satisfied by cached fff4 revision`() {
        val before = QuantumControllerState(
            authoritative = true,
            revision = 10,
            authoritativeRevision = 10,
            routeListRevision = 7,
        )
        val cachedRead = before.copy(revision = 11, authoritativeRevision = 11)
        assertFalse(hasFreshExplicitQuantumRouteList(before, cachedRead))
        assertTrue(
            hasFreshExplicitQuantumRouteList(
                before,
                cachedRead.copy(revision = 12, authoritativeRevision = 12, routeListRevision = 12),
            ),
        )
    }

    @Test
    fun `Quantum write fence binds the exact physical controller`() {
        val expected = BoardLayerBoardIdentity(
            physicalBoardId = "quantum:serial:controller-a",
            productSizeId = 9201,
        )
        val boardA = DiscoveredBoard(
            displayName = "Quantum", serial = "CONTROLLER-A", apiLevel = 1,
            address = "AA:AA:AA:AA:AA:AA", rssi = -40, boardBrand = BoardBrand.QUANTUM,
        )
        val boardB = boardA.copy(serial = "controller-b", address = "BB:BB:BB:BB:BB:BB")

        assertTrue(quantumBoardWriteFenceMatches(boardA, QuantumBoardModel.XL, expected))
        assertFalse(quantumBoardWriteFenceMatches(boardB, QuantumBoardModel.XL, expected))
        assertFalse(quantumBoardWriteFenceMatches(boardA, QuantumBoardModel.M, expected))
        assertFalse(quantumBoardWriteFenceMatches(boardA, null, expected))
        assertFalse(quantumBoardWriteFenceMatches(boardA, QuantumBoardModel.XL, null))
        assertFalse(
            quantumBoardWriteFenceMatches(
                boardA.copy(boardBrand = BoardBrand.KILTER),
                QuantumBoardModel.XL,
                expected,
            ),
        )
    }

    @Test
    fun `notification recovery can discard invalid bytes and size the next frame`() {
        val buffered = mutableListOf<Byte>(0x55, 1, 0x7e, 1, 0x64, 0)

        while (buffered.isNotEmpty()) {
            val size = quantumNotificationFrameSize(buffered.toByteArray())
            if (size != QUANTUM_NOTIFICATION_INVALID) break
            buffered.removeAt(0)
        }

        assertEquals(listOf<Byte>(1, 0x64, 0), buffered)
        assertEquals(3, quantumNotificationFrameSize(buffered.toByteArray()))
    }

    @Test
    fun `Quantum projection requires every hold to have an LED mapping`() {
        val holds = listOf(BoardHold(10, 1), BoardHold(20, 2))

        assertTrue(hasCompleteQuantumLedMapping(holds, mapOf(10 to 100, 20 to 200)))
        assertFalse(hasCompleteQuantumLedMapping(holds, mapOf(10 to 100)))
        assertFalse(hasCompleteQuantumLedMapping(holds, mapOf(10 to -1, 20 to 200)))
        assertFalse(hasCompleteQuantumLedMapping(holds, mapOf(10 to 100, 20 to 65_536)))
        assertFalse(hasCompleteQuantumLedMapping(emptyList(), emptyMap()))
    }

    @Test
    fun `board scoped cleanup cannot cross a board swap or globally clear Quantum`() {
        assertTrue(boardScopedCommandAllowed(BoardBrand.KILTER, BoardBrand.KILTER))
        assertFalse(boardScopedCommandAllowed(BoardBrand.QUANTUM, BoardBrand.KILTER))
        assertTrue(genericBoardClearAllowed(BoardBrand.KILTER))
        assertFalse(genericBoardClearAllowed(BoardBrand.QUANTUM))
    }

    @Test
    fun `only complete fff4 route list publishes state and avoids explicit fallback`() {
        val full = QuantumBroadcast.RouteList(
            QuantumCommand.REQUEST_USER_ROUTE_LIST,
            listOf(player(ownUser)),
        )
        assertTrue(quantumFff4PublishesSnapshot(full))
        assertFalse(quantumReadRequiresRouteListFallback(classifyQuantumFff4Evidence(full)))
        assertTrue(
            quantumReadRequiresRouteListFallback(
                classifyQuantumFff4Evidence(QuantumBroadcast.BoardCleared),
            ),
        )
        assertFalse(quantumFff4PublishesSnapshot(QuantumBroadcast.BoardCleared))
        // The same event is authoritative when received as the direct
        // notification acknowledgement to an explicit global clear.
        assertEquals(
            QuantumControllerEvidence.AUTHORITATIVE,
            classifyQuantumControllerEvidence(QuantumBroadcast.BoardCleared),
        )
        assertEquals(
            QuantumControllerEvidence.INFORMATIONAL,
            classifyQuantumFff4Evidence(QuantumBroadcast.BoardCleared),
        )

        assertTrue(
            quantumReadRequiresRouteListFallback(
                classifyQuantumFff4Evidence(QuantumBroadcast.UserTurnedOff(ownUser)),
            ),
        )
        assertTrue(
            quantumReadRequiresRouteListFallback(
                classifyQuantumFff4Evidence(
                    QuantumBroadcast.Exception(QuantumCommand.ACTIVATE_WALL, 7),
                ),
            ),
        )
        assertTrue(
            quantumReadRequiresRouteListFallback(
                classifyQuantumFff4Evidence(
                    QuantumBoardBroadcastParser.parse(byteArrayOf(1, 0x47, 1, 0)),
                ),
            ),
        )
    }

    @Test
    fun `delta revision cannot satisfy a fresh authoritative precondition`() {
        val before = QuantumControllerState(
            players = listOf(player(ownUser)),
            revision = 10,
            authoritativeRevision = 10,
            authoritative = true,
        )
        val deltaOnly = before.copy(revision = 11, players = emptyList())
        assertFalse(hasFreshQuantumSnapshot(before, deltaOnly))

        val fresh = deltaOnly.copy(authoritativeRevision = 12, revision = 12)
        assertTrue(hasFreshQuantumSnapshot(before, fresh))
        assertFalse(
            hasFreshQuantumSnapshot(
                before,
                fresh.copy(lastFailure = QuantumCommandFailure.REFUSED),
            ),
        )
    }

    @Test
    fun `projection confirmation requires exact route user and color`() {
        val confirmed = QuantumControllerState(
            players = listOf(player(ownUser)),
            revision = 4,
            authoritativeRevision = 4,
            authoritative = true,
        )
        assertTrue(isQuantumProjectionConfirmed(confirmed, route.uppercase(), ownUser, 0xff00ffff.toInt()))
        assertFalse(isQuantumProjectionConfirmed(confirmed, route, foreignUser, 0x00ffff))
        assertFalse(isQuantumProjectionConfirmed(confirmed, route, ownUser, 0x00ff00))
        assertFalse(
            isQuantumProjectionConfirmed(
                confirmed.copy(lastFailure = QuantumCommandFailure.COLOR_TAKEN),
                route,
                ownUser,
                0x00ffff,
            ),
        )
    }

    @Test
    fun `authoritative player guard is order insensitive but exact`() {
        val expected = listOf(
            player(ownUser),
            player(foreignUser).copy(
                routeId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                remainingSeconds = 37,
                color = 0x123456,
            ),
        )

        assertTrue(
            quantumPlayersMatch(
                expected,
                expected.reversed().map { player ->
                    player.copy(
                        routeId = player.routeId.uppercase(),
                        userId = player.userId.uppercase(),
                        color = player.color or 0xff000000.toInt(),
                    )
                },
            ),
        )
        assertFalse(quantumPlayersMatch(expected, expected.dropLast(1)))
        assertFalse(
            quantumPlayersMatch(
                expected,
                expected.toMutableList().also { it[0] = it[0].copy(userId = foreignUser) },
            ),
        )
        assertTrue(
            quantumPlayersMatch(
                expected,
                expected.toMutableList().also { it[0] = it[0].copy(remainingSeconds = 1) },
            ),
        )
        assertFalse(
            quantumPlayersMatch(
                expected,
                expected.toMutableList().also { it[0] = it[0].copy(color = 0x654321) },
            ),
        )
    }

    @Test
    fun `scoped removal keeps foreign players and confirms only target absence`() {
        val before = QuantumControllerState(
            players = listOf(player(ownUser), player(foreignUser)),
            revision = 20,
            authoritativeRevision = 20,
            authoritative = true,
        )
        assertFalse(isQuantumScopedRemovalConfirmed(before, ownUser))

        val after = before.copy(
            players = listOf(player(foreignUser)),
            revision = 21,
            authoritativeRevision = 21,
        )
        assertTrue(isQuantumScopedRemovalConfirmed(after, ownUser))
        assertFalse(isQuantumScopedRemovalConfirmed(after.copy(authoritative = false), ownUser))
    }

    private fun player(userId: String) = QuantumActivePlayer(
        routeId = route,
        userId = userId,
        remainingSeconds = 0,
        color = 0x00ffff,
    )

    private fun metadata(type: Int, columns: Int, rows: Int): ByteArray =
        ByteArray(41).apply {
            this[34] = type.toByte()
            this[35] = (columns ushr 8).toByte()
            this[36] = columns.toByte()
            this[37] = (rows ushr 8).toByte()
            this[38] = rows.toByte()
        }
}
