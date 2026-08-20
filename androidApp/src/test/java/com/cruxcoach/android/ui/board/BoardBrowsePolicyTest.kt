package com.cruxcoach.android.ui.board

import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals

class BoardBrowsePolicyTest {
    @Test
    fun `Quantum model layout bypasses only the incompatible edge-fit predicate`() {
        assertEquals(0, BoardBrowsePolicy.productSizeId(BoardBrand.QUANTUM, 9201))

        // Regression guard: all established boards retain their selected size
        // and therefore the existing strict Kilter/Aurora containment rule.
        BoardBrand.entries.filter { it != BoardBrand.QUANTUM }.forEach { brand ->
            assertEquals(123, BoardBrowsePolicy.productSizeId(brand, 123), brand.name)
        }
    }

    @Test
    fun `unsupported stale Quantum filters are neutralized`() {
        assertEquals(
            ClimbTypeFilter.BOULDER,
            BoardBrowsePolicy.climbType(BoardBrand.QUANTUM, ClimbTypeFilter.ROUTE),
        )
        assertEquals(false, BoardBrowsePolicy.benchmarkOnly(BoardBrand.QUANTUM, true))
        assertEquals(
            OriginFilter.ALL,
            BoardBrowsePolicy.origin(BoardBrand.QUANTUM, OriginFilter.BOARDSESH),
        )
    }

    @Test
    fun `existing board filter values are unchanged`() {
        for (brand in BoardBrand.entries.filter { it != BoardBrand.QUANTUM }) {
            assertEquals(
                ClimbTypeFilter.ROUTE,
                BoardBrowsePolicy.climbType(brand, ClimbTypeFilter.ROUTE),
                brand.name,
            )
            assertEquals(true, BoardBrowsePolicy.benchmarkOnly(brand, true), brand.name)
            assertEquals(
                OriginFilter.BOARDSESH,
                BoardBrowsePolicy.origin(brand, OriginFilter.BOARDSESH),
                brand.name,
            )
        }
    }

    @Test
    fun `Quantum rule bits never alter another board exclusion mask`() {
        assertEquals(17L, BoardBrowsePolicy.exclusionMask(BoardBrand.QUANTUM, 4L, 17L))
        BoardBrand.entries.filter { it != BoardBrand.QUANTUM }.forEach { brand ->
            assertEquals(4L, BoardBrowsePolicy.exclusionMask(brand, 4L, 17L), brand.name)
        }
    }
}
