package com.cruxcoach.android.ui.board

import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The detail opened every climb at the angle navigation handed it: a MoonBoard problem that only exists at 25°
 * showed at 40° with grade "?" and could be logged at 40°.
 */
class ClimbAngleFitTest {

    private val moonVariantAngles = setOf(25, 40)

    @Test
    fun `a MoonBoard problem opens at its own angle`() {
        assertEquals(
            25,
            ClimbAngleFit.fit(
                requested = 40, brand = BoardBrand.MOONBOARD,
                climbAngles = listOf(25), boardAngles = moonVariantAngles, setterAngle = null,
            ),
        )
    }

    @Test
    fun `a MoonBoard problem at the asked angle stays`() {
        assertEquals(
            40,
            ClimbAngleFit.fit(
                requested = 40, brand = BoardBrand.MOONBOARD,
                climbAngles = listOf(40), boardAngles = moonVariantAngles, setterAngle = null,
            ),
        )
    }

    @Test
    fun `a MoonBoard climb without stats is left alone`() {
        assertEquals(
            40,
            ClimbAngleFit.fit(
                requested = 40, brand = BoardBrand.MOONBOARD,
                climbAngles = emptyList(), boardAngles = moonVariantAngles, setterAngle = null,
            ),
        )
    }

    @Test
    fun `an adjustable board keeps every angle it is set up at`() {
        // Kilter climb with stats at 40 only, opened at 45 from the logbook: 45 is a Kilter angle.
        assertEquals(
            45,
            ClimbAngleFit.fit(
                requested = 45, brand = BoardBrand.KILTER,
                climbAngles = listOf(40), boardAngles = (0..70 step 5).toSet(), setterAngle = null,
            ),
        )
    }

    @Test
    fun `an angle the board does not have snaps to the nearest one`() {
        assertEquals(
            50,
            ClimbAngleFit.fit(
                requested = 60, brand = BoardBrand.TENSION,
                climbAngles = listOf(40), boardAngles = (0..50 step 5).toSet(), setterAngle = null,
            ),
        )
    }

    @Test
    fun `an impossible angle prefers the setter angle`() {
        assertEquals(
            30,
            ClimbAngleFit.fit(
                requested = 60, brand = BoardBrand.TENSION,
                climbAngles = listOf(30), boardAngles = (0..50 step 5).toSet(), setterAngle = 30,
            ),
        )
    }

    @Test
    fun `unknown board angles change nothing`() {
        // Mid-import the board's angle set can still be empty: no guessing from the climb's stats alone.
        assertEquals(
            45,
            ClimbAngleFit.fit(
                requested = 45, brand = BoardBrand.TENSION,
                climbAngles = listOf(40), boardAngles = emptySet(), setterAngle = null,
            ),
        )
    }

    @Test
    fun `a tie goes to the lower angle`() {
        assertEquals(
            25,
            ClimbAngleFit.fit(
                requested = 30, brand = BoardBrand.MOONBOARD,
                climbAngles = listOf(35, 25), boardAngles = moonVariantAngles, setterAngle = null,
            ),
        )
    }
}
