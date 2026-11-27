package com.cruxcoach.android.ui.common

import android.app.Application
import androidx.compose.runtime.produceState
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/** What [effectsOnTestThread] is for: a result from another thread lands on the test's thread. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TestThreadEffectsTest {
    @get:Rule val compose = createComposeRule(effectsOnTestThread())

    @Test
    fun `an effect goes on on the test's thread after work on another one`() {
        val wroteOn = AtomicReference<Thread>()
        compose.setContent {
            produceState(false) {
                value = withContext(Dispatchers.IO) { true }
                wroteOn.set(Thread.currentThread())
            }
        }
        compose.waitUntil(WAIT_MS) { wroteOn.get() != null }
        assertSame(Thread.currentThread(), wroteOn.get())
    }

    private companion object {
        const val WAIT_MS = 60_000L
    }
}
