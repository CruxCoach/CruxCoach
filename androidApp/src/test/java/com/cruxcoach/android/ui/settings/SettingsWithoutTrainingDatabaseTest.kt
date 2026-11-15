package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.athlete.model.UnitSystem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The settings are where people look for updates, bug reports and backups.
 * When the training database cannot open (full storage, a damaged file), the
 * units row stays empty instead of taking the whole app down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettingsWithoutTrainingDatabaseTest {

    @Test
    fun `the units row stays empty instead of crashing when the training database will not open`() {
        val crashes = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> crashes += e }
        try {
            val service = mockk<AthleteService>(relaxed = true) {
                coEvery { ensureReady() } throws IllegalStateException("file is not a database")
            }
            val vm = UnitsSettingsViewModel(service)
            vm.select(UnitSystem.IMPERIAL)

            // Both coroutines, the one loading the units and the one choosing them, stop where the database fails. A
            // failure nobody caught reaches the handler before its coroutine counts as finished: once both are joined,
            // there is nothing left to wait for.
            runBlocking { vm.viewModelScope.coroutineContext.job.children.toList().joinAll() }
            coVerify(exactly = 2) { service.ensureReady() }
            assertNull(vm.units.value)
            assertTrue(crashes.isEmpty(), "uncaught: $crashes")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
