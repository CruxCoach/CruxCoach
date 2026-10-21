package com.cruxcoach.android.ble

import android.os.Build
import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class BoardConnectFlowPolicyTest {

    private data class Board(val address: String)

    @Test
    fun android12_always_discovers_even_with_a_remembered_controller() {
        // BLUETOOTH_SCAN is neverForLocation there, so withholding the scan
        // buys the user nothing and costs them the list of what is in range.
        assertEquals(
            BoardConnectFlow.DISCOVER,
            BoardConnectFlowPolicy.initialFlow(
                hasRememberedController = true,
                apiLevel = Build.VERSION_CODES.S,
            ),
        )
    }

    @Test
    fun legacy_android_tries_the_remembered_controller_before_scanning() {
        assertEquals(
            BoardConnectFlow.DIRECT_THEN_DISCOVER,
            BoardConnectFlowPolicy.initialFlow(
                hasRememberedController = true,
                apiLevel = Build.VERSION_CODES.R,
            ),
        )
    }

    @Test
    fun legacy_android_without_a_remembered_controller_has_to_scan() {
        assertEquals(
            BoardConnectFlow.DISCOVER,
            BoardConnectFlowPolicy.initialFlow(
                hasRememberedController = false,
                apiLevel = Build.VERSION_CODES.R,
            ),
        )
    }

    @Test
    fun a_single_board_needs_no_picking() {
        val only = Board("AA:BB")

        assertSame(only, BoardConnectFlowPolicy.autoConnectTarget(listOf(only)))
    }

    @Test
    fun several_boards_stay_a_manual_choice() {
        // Not even the remembered one gets grabbed: standing in front of two
        // walls, "the one you used last" is a hint, not an instruction. The
        // list badges it and waits for the tap.
        val boards = listOf(Board("AA:BB"), Board("CC:DD"), Board("EE:FF"))

        assertNull(BoardConnectFlowPolicy.autoConnectTarget(boards))
    }

    @Test
    fun nothing_in_range_connects_to_nothing() {
        assertNull(BoardConnectFlowPolicy.autoConnectTarget(emptyList<Board>()))
    }

    private fun discovered(address: String, brand: BoardBrand = BoardBrand.KILTER, relay: Boolean = false) =
        DiscoveredBoard("Kilter Board", "", 2, address, -50, brand, isCruxRelay = relay)

    @Test
    fun automatic_flows_take_the_remembered_wall_not_the_first_listed() {
        // Wall B advertises first (hash order); wall A, just released, comes back later.
        val wallB = discovered("BB:BB")
        val wallA = discovered("AA:AA")
        assertSame(wallA, BoardConnectFlowPolicy.automaticTarget(listOf(wallB, wallA), BoardBrand.KILTER, "aa:aa"))
    }

    @Test
    fun automatic_flows_leave_two_unremembered_walls_to_the_climber() {
        val boards = listOf(discovered("AA:AA"), discovered("BB:BB"))
        assertNull(BoardConnectFlowPolicy.automaticTarget(boards, BoardBrand.KILTER, null))
        assertNull(BoardConnectFlowPolicy.automaticTarget(boards, BoardBrand.KILTER, "CC:CC"))
    }

    @Test
    fun automatic_flows_skip_relays_and_other_brands() {
        val wall = discovered("AA:AA")
        val relay = discovered("RR:RR", relay = true)
        val moon = discovered("MM:MM", BoardBrand.MOONBOARD)
        assertSame(wall, BoardConnectFlowPolicy.automaticTarget(listOf(relay, moon, wall), BoardBrand.KILTER, null))
        assertNull(BoardConnectFlowPolicy.automaticTarget(listOf(relay), BoardBrand.KILTER, "RR:RR"))
    }
}
