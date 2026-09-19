package com.cruxcoach.android.ui.onboarding

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.cruxcoach.android.ui.board.sync.BoardSyncViewModel
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w360dp-h800dp")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class OnboardingDownloadSelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `first step shows catalogue choices directly and only Continue confirms the edited selection`() {
        val onboarding = mockk<OnboardingViewModel>(relaxed = true)
        every { onboarding.state } returns MutableStateFlow(OnboardingState())
        val sync = mockk<BoardSyncViewModel>(relaxed = true)
        coEvery { sync.initialDownloadSelection() } returns setOf(BoardBrand.KILTER)
        val ble = mockk<com.cruxcoach.android.ui.board.BleConnectionViewModel>(relaxed = true)
        every { ble.state } returns MutableStateFlow(com.cruxcoach.android.ui.board.BleConnectionState())
        val saved = CompletableDeferred<Unit>()
        coEvery { sync.confirmOnboardingDownloads(any()) } coAnswers { saved.await() }
        val restoration = StateRestorationTester(compose)
        var view: android.view.View? = null
        restoration.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            com.cruxcoach.android.ui.theme.CruxCoachTheme(com.cruxcoach.android.data.DarkModeSetting.DARK) {
                androidx.compose.material3.Surface {
                    OnboardingScreen(onComplete = {}, viewModel = onboarding, boardSyncViewModel = sync, bleViewModel = ble)
                }
            }
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithTag("setup_connect").assertDoesNotExist()
        System.getenv("CRUXCOACH_UI_REVIEW_DIR")?.let { directory ->
            compose.runOnIdle {
                val root = requireNotNull(view)
                val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                java.io.File(directory).mkdirs()
                java.io.File(directory, "onboarding-first.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        compose.onNodeWithTag("board_selection_kilter").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("board_selection_moonboard").performScrollTo().performClick().assertIsOn()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("board_selection_moonboard").performScrollTo().assertIsOn()
        compose.onNodeWithTag("board_selection_kilter").performScrollTo().assertIsOff()
        coVerify(exactly = 0) { sync.confirmOnboardingDownloads(any()) }
        compose.onNodeWithTag("onboarding_next_button").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("onboarding_next_button").assertIsNotEnabled()
        verify(exactly = 0) { onboarding.nextStep() }
        compose.runOnIdle { saved.complete(Unit) }
        compose.waitForIdle()
        coVerify(exactly = 1) { sync.confirmOnboardingDownloads(setOf(BoardBrand.MOONBOARD)) }
        verify(exactly = 1) { onboarding.nextStep() }
    }
    @Test
    @Config(qualifiers = "de-w320dp-h640dp")
    fun `large text keeps catalogue bulk selection and continue reachable without a skip action`() {
        val onboarding = mockk<OnboardingViewModel>(relaxed = true)
        every { onboarding.state } returns MutableStateFlow(OnboardingState())
        val sync = mockk<BoardSyncViewModel>(relaxed = true)
        coEvery { sync.initialDownloadSelection() } returns emptySet()
        val ble = mockk<com.cruxcoach.android.ui.board.BleConnectionViewModel>(relaxed = true)
        every { ble.state } returns MutableStateFlow(com.cruxcoach.android.ui.board.BleConnectionState())
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)
            ) {
                MaterialTheme {
                    OnboardingScreen(onComplete = {}, viewModel = onboarding, boardSyncViewModel = sync, bleViewModel = ble)
                }
            }
        }
        compose.onNodeWithTag("onboarding_next_button").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("settings_change_active_board").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("board_selection_moonboard").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithTag("setup_toggle_all_catalogues").performScrollTo().performClick()
        compose.onNodeWithTag("board_selection_kilter").performScrollTo().assertIsOn()
        compose.onNodeWithTag("board_selection_moonboard").performScrollTo().assertIsOn()
        compose.onNodeWithTag("setup_toggle_all_catalogues").performScrollTo().performClick()
        compose.onNodeWithTag("board_selection_kilter").performScrollTo().assertIsOff()
        compose.onNodeWithTag("board_selection_moonboard").performScrollTo().assertIsOff()
        compose.onNodeWithText("Einrichtung überspringen").assertDoesNotExist()
        coVerify(exactly = 0) { sync.confirmOnboardingDownloads(any()) }
        compose.onNodeWithTag("onboarding_next_button").assertIsDisplayed().performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) { sync.confirmOnboardingDownloads(emptySet()) }
        verify(exactly = 1) { onboarding.nextStep() }
    }

}
