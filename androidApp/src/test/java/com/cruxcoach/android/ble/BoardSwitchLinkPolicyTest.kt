package com.cruxcoach.android.ble

import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardSwitchLinkPolicyTest {
    private val kilter16x12 = ActiveBoardSelection(BoardBrand.KILTER, 1, 28)
    private val kilter12x12 = ActiveBoardSelection(BoardBrand.KILTER, 1, 10)
    private val homewall = ActiveBoardSelection(BoardBrand.KILTER, 8, 21)
    private val tension = ActiveBoardSelection(BoardBrand.TENSION, 9, 1)

    @Test
    fun `switching to another Kilter size while connected releases the link`() {
        val policy = BoardSwitchLinkPolicy()
        policy.onLinkUp(kilter16x12, BoardBrand.KILTER, onboarded = true)
        assertTrue(policy.onSelectionChanged(kilter12x12, BoardBrand.KILTER, onboarded = true))
    }

    @Test
    fun `switching the layout or the brand releases the link too`() {
        val policy = BoardSwitchLinkPolicy()
        policy.onLinkUp(kilter12x12, BoardBrand.KILTER, onboarded = true)
        assertTrue(policy.onSelectionChanged(homewall, BoardBrand.KILTER, onboarded = true))
        policy.onLinkUp(kilter12x12, BoardBrand.KILTER, onboarded = true)
        assertTrue(policy.onSelectionChanged(tension, BoardBrand.KILTER, onboarded = true))
    }

    @Test
    fun `an unchanged selection keeps the link`() {
        val policy = BoardSwitchLinkPolicy()
        policy.onLinkUp(kilter16x12, BoardBrand.KILTER, onboarded = true)
        assertFalse(policy.onSelectionChanged(kilter16x12, BoardBrand.KILTER, onboarded = true))
    }

    @Test
    fun `a link made during onboarding follows the board chosen afterwards`() {
        val policy = BoardSwitchLinkPolicy()
        policy.onLinkUp(kilter12x12, BoardBrand.KILTER, onboarded = false)
        assertFalse(policy.onSelectionChanged(kilter16x12, BoardBrand.KILTER, onboarded = true))
        assertFalse(policy.onSelectionChanged(kilter12x12, BoardBrand.KILTER, onboarded = true))
        // The next connection is bound normally.
        policy.onLinkDown()
        policy.onLinkUp(kilter12x12, BoardBrand.KILTER, onboarded = true)
        assertTrue(policy.onSelectionChanged(kilter16x12, BoardBrand.KILTER, onboarded = true))
    }

    @Test
    fun `correcting a mismatched selection toward the connected board keeps the link`() {
        // Connected to a Tension box while Kilter is selected: the mismatch
        // prompt switches the selection to Tension.
        val policy = BoardSwitchLinkPolicy()
        policy.onLinkUp(kilter12x12, BoardBrand.TENSION, onboarded = true)
        assertFalse(policy.onSelectionChanged(tension, BoardBrand.TENSION, onboarded = true))
        // A catalogue download may rewrite the corrected selection: keep the link.
        assertFalse(policy.onSelectionChanged(tension.copy(productSizeId = 2), BoardBrand.TENSION, onboarded = true))
    }

    @Test
    fun `without a link nothing is released`() {
        val policy = BoardSwitchLinkPolicy()
        assertFalse(policy.onSelectionChanged(kilter12x12, null, onboarded = true))
        policy.onLinkUp(kilter16x12, BoardBrand.KILTER, onboarded = true)
        policy.onLinkDown()
        assertFalse(policy.onSelectionChanged(kilter12x12, null, onboarded = true))
    }
}
