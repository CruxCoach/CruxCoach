package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.UnitSystem
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Appearance & language: the units arrive from the training database a moment after the page
 * opens. The page below them must not jump when they do, or a tap meant for the next row lands
 * on a unit chip (seen on the test phone, 2026-11-09).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UnitsSectionPlaceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the page below the units does not move when they arrive`() {
        val ready = CompletableDeferred<Unit>()
        val service = mockk<AthleteService>(relaxed = true) {
            coEvery { ensureReady() } coAnswers { ready.await() }
            every { repo.observeProfile() } returns flowOf(AthleteProfile(units = UnitSystem.IMPERIAL))
        }
        val vm = UnitsSettingsViewModel(service)
        compose.setContent {
            MaterialTheme {
                Column {
                    UnitsSection(vm)
                    Text("Next row", Modifier.testTag("below"))
                }
            }
        }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("below").getUnclippedBoundsInRoot().top
        // While loading, the held row is neither readable nor tappable.
        assertTrue(compose.onAllNodesWithText("US (oz, fl oz, cups, lb, in)").fetchSemanticsNodes().isEmpty())

        ready.complete(Unit)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("settings_units").fetchSemanticsNodes().isNotEmpty() }

        assertEquals(before, compose.onNodeWithTag("below").getUnclippedBoundsInRoot().top)
    }
}
