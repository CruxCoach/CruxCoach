package com.cruxcoach.android.ble

import android.os.Build
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

        assertSame(
            only,
            BoardConnectFlowPolicy.autoConnectTarget(listOf(only), null, Board::address),
        )
    }

    @Test
    fun the_remembered_board_wins_among_several() {
        val boards = listOf(Board("AA:BB"), Board("CC:DD"), Board("EE:FF"))

        assertEquals(
            Board("CC:DD"),
            BoardConnectFlowPolicy.autoConnectTarget(boards, "cc:dd", Board::address),
        )
    }

    @Test
    fun several_boards_and_no_remembered_match_stay_a_manual_choice() {
        val boards = listOf(Board("AA:BB"), Board("CC:DD"))

        assertNull(BoardConnectFlowPolicy.autoConnectTarget(boards, null, Board::address))
        assertNull(BoardConnectFlowPolicy.autoConnectTarget(boards, "99:99", Board::address))
    }

    @Test
    fun nothing_in_range_connects_to_nothing() {
        assertNull(
            BoardConnectFlowPolicy.autoConnectTarget(emptyList(), "AA:BB", Board::address)
        )
    }

    @Test
    fun a_duplicated_remembered_address_is_not_an_answer() {
        // Two endpoints claiming the same address is nonsense the flow must
        // not resolve by guessing — ask the user.
        val boards = listOf(Board("AA:BB"), Board("aa:bb"))

        assertNull(BoardConnectFlowPolicy.autoConnectTarget(boards, "AA:BB", Board::address))
    }
}
