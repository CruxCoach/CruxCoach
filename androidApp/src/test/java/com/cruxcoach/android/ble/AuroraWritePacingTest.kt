package com.cruxcoach.android.ble

import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Only an Aurora-family controller whose RX characteristic lacks
 * write-without-response (the Kilter-built box) gets the Kilter app's 100 ms
 * gap between chunks. Property bits: WRITE = 8, WRITE_NO_RESPONSE = 4.
 */
class AuroraWritePacingTest {

    @Test
    fun `a write-only Aurora characteristic is paced like the Kilter app`() {
        assertEquals(AURORA_WRITE_ONLY_CHUNK_DELAY_MS, auroraWritePacingMs(BoardBrand.KILTER, 8))
        assertEquals(AURORA_WRITE_ONLY_CHUNK_DELAY_MS, auroraWritePacingMs(BoardBrand.TENSION, 8))
        assertEquals(100L, AURORA_WRITE_ONLY_CHUNK_DELAY_MS)
    }

    @Test
    fun `boxes offering write-without-response keep the unpaced path`() {
        assertEquals(0L, auroraWritePacingMs(BoardBrand.KILTER, 12))
        assertEquals(0L, auroraWritePacingMs(BoardBrand.KILTER, 4))
    }

    @Test
    fun `Quantum, MoonBoard and an unknown brand are never paced here`() {
        // Quantum frames are single writes; MoonBoard passes its own UART gap.
        assertEquals(0L, auroraWritePacingMs(BoardBrand.QUANTUM, 8))
        assertEquals(0L, auroraWritePacingMs(BoardBrand.MOONBOARD, 8))
        assertEquals(0L, auroraWritePacingMs(null, 8))
    }
}
