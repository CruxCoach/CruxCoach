package com.cruxcoach.android.ui.settings

import android.app.Application
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.athlete.model.UnitSystem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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

            coVerify(timeout = 5_000, exactly = 2) { service.ensureReady() }
            Thread.sleep(300) // an uncaught failure would reach the handler right after
            assertNull(vm.units.value)
            assertTrue(crashes.isEmpty(), "uncaught: $crashes")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
