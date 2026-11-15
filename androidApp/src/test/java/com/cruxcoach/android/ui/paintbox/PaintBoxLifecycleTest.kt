package com.cruxcoach.android.ui.paintbox

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.cruxcoach.android.ble.ConnectionState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class PaintBoxLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `a configuration change only pauses live`() {
        val vm = mockk<PaintBoxViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(PaintBoxState(
            document = PaintDocument(board = PaintBoard("kilter", 1, 10)), loading = false, live = true))
        every { vm.connection } returns MutableStateFlow(ConnectionState.CONNECTED)
        compose.setContent { MaterialTheme { PaintBoxScreen({}, {}, vm) } }
        compose.waitForIdle()
        compose.activityRule.scenario.recreate()
        verify(atLeast = 1) { vm.stop() }
        verify(exactly = 0) { vm.leave() }
    }
}
