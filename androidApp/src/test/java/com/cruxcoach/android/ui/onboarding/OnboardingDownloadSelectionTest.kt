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
@Config(application = Application::class, qualifiers = "de")
class OnboardingDownloadSelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `first step shows inline choices and only Continue confirms the edited selection`() {
        val onboarding = mockk<OnboardingViewModel>(relaxed = true)
        every { onboarding.state } returns MutableStateFlow(OnboardingState())
        val sync = mockk<BoardSyncViewModel>(relaxed = true)
        coEvery { sync.initialDownloadSelection() } returns setOf(BoardBrand.KILTER)
        val saved = CompletableDeferred<Unit>()
        coEvery { sync.confirmOnboardingDownloads(any()) } coAnswers { saved.await() }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                OnboardingScreen(onComplete = {}, viewModel = onboarding, boardSyncViewModel = sync)
            }
        }
        compose.onNode(isDialog()).assertDoesNotExist()
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
}
