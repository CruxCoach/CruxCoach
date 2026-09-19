package com.cruxcoach.android.ui.onboarding

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cruxcoach.android.data.BoardSyncState
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.board.BleConnectionState
import com.cruxcoach.android.ui.board.BleConnectionViewModel
import com.cruxcoach.android.ui.board.sync.BoardModelSelectionState
import com.cruxcoach.android.ui.board.sync.BoardSyncViewModel
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingDataStepTest {
    @get:Rule val compose = createComposeRule()
    private val onboarding = mockk<OnboardingViewModel>(relaxed = true)
    private val sync = mockk<BoardSyncViewModel>(relaxed = true)
    private fun show(fontScale: Float = 1f) {
        every { onboarding.state } returns MutableStateFlow(OnboardingState(currentStep = OnboardingStep.KILTER))
        every { onboarding.boardCatalogueSyncing } returns MutableStateFlow(true)
        every { sync.state } returns MutableStateFlow(BoardSyncState(isSyncing = true))
        every { sync.modelState } returns MutableStateFlow(BoardModelSelectionState())
        every { sync.boardCounts } returns MutableStateFlow(emptyMap())
        every { sync.downloadBrands } returns MutableStateFlow(setOf(BoardBrand.KILTER, BoardBrand.MOONBOARD))
        every { sync.activeBrand } returns MutableStateFlow(BoardBrand.KILTER)
        val ble = mockk<BleConnectionViewModel>(relaxed = true)
        every { ble.state } returns MutableStateFlow(BleConnectionState())
        var view: android.view.View? = null
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                CruxCoachTheme(DarkModeSetting.DARK) {
                    Surface {
                        OnboardingScreen(onComplete = {}, viewModel = onboarding, boardSyncViewModel = sync, bleViewModel = ble)
                    }
                }
            }
        }
        compose.waitForIdle()
        System.getenv("CRUXCOACH_UI_REVIEW_DIR")?.let { directory ->
            compose.runOnIdle {
                val root = requireNotNull(view)
                val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                java.io.File(directory).mkdirs()
                java.io.File(directory, "onboarding-data-$fontScale.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }

    @Test fun `two sections keep import actions beside their source without starting imports`() {
        show()
        compose.onNodeWithText("1. Board-Kataloge").assertIsDisplayed()
        compose.onNodeWithText("2. Private Kletterdaten").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("onboarding_cruxcoach_file_import").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_import_source_cruxcoach").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding_cruxcoach_file_import").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Aktiviert auch dein Daten-Backup.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Informationen zu Verschlüsseltes Backup wiederherstellen anzeigen")
            .performScrollTo().performClick()
        compose.onNodeWithText("Wiederherstellen nutzt das bisherige Konto und aktiviert das verschlüsselte Backup. Den Kontowechsel bestätigst du vor dem Fortfahren.")
            .performScrollTo().assertIsDisplayed()
        verify(exactly = 0) { onboarding.requestKeyImport() }
        compose.onNodeWithText("Schließen").performClick()
        compose.onNodeWithTag("onboarding_cruxcoach_file_import").performScrollTo()
        System.getenv("CRUXCOACH_UI_REVIEW_DIR")?.let { directory ->
            compose.runOnIdle {
                val root = android.view.inspector.WindowInspector.getGlobalWindowViews().last()
                val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                java.io.File(directory, "onboarding-cruxcoach-expanded.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        compose.onNodeWithTag("onboarding_import_source_cruxcoach").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding_cruxcoach_file_import").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_import_source_moonboard").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding_moon_import").performScrollTo().assertIsDisplayed()
        verify(exactly = 0) { onboarding.requestKeyImport() }
        verify(exactly = 0) { onboarding.kilterLogin() }
        verify(exactly = 0) { sync.startApiSync() }
    }

    @Test @Config(qualifiers = "de-w320dp-h640dp")
    fun `large text keeps starting without private import available during download`() {
        show(2f)
        compose.onNodeWithTag("onboarding_finish_button").assertIsDisplayed().assertIsEnabled().performClick()
        verify(exactly = 1) { onboarding.completeOnboarding(any(), skipTour = false) }
    }
}
