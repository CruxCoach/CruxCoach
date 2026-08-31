package com.cruxcoach.android.ui.common

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.BleShareUiState
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.data.OnBoardClimbEntry
import com.cruxcoach.android.data.OnBoardSource
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BleStatusChipSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `collapsed board status keeps a 48 dp actionable surface`() {
        var expands = 0
        val climb = OnBoardClimbEntry(
            climbUuid = "fixture-climb",
            angle = 40,
            name = "Floats Your Boat",
            grade = "6a",
            source = OnBoardSource.LOCAL_MANAGER,
        )

        compose.setContent {
            CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
                BleStatusChip(
                    state = BleShareUiState(onBoardClimb = climb),
                    effectiveOnBoard = climb,
                    onExpand = { expands += 1 },
                    onAddToQueue = null,
                )
            }
        }

        compose.onNodeWithTag("ble_status_summary")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, expands)
    }
}
