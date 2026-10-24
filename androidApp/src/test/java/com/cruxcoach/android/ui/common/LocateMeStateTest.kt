package com.cruxcoach.android.ui.common

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.cruxcoach.android.util.DeviceLocation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A "my location" tap keeps looking like a map app that listens while it is
 * open (owner test on GrapheneOS, 2026-10-24: requests were registered, but
 * 45 s indoors without a network location service were not enough).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class LocateMeStateTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val locations = shadowOf(manager)
    private val results = mutableListOf<LocateResult>()

    @Before
    fun setUp() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(3))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        locations.setLocationEnabled(true)
        for (provider in PROVIDERS) locations.setProviderEnabled(provider, true)
    }

    @Test
    fun `without any position a tap hints after 10 s and gives up only after 5 min`() = runTest {
        val state = state()
        state.locate()
        runCurrent()
        assertTrue(state.locating)

        advanceTimeBy(9_000)
        assertFalse(state.slow)
        advanceTimeBy(2_000)
        assertTrue("still-looking hint", state.slow)

        advanceTimeBy(4 * 60_000L)
        assertTrue("no message while it still looks", results.isEmpty())
        assertTrue(state.locating)

        advanceTimeBy(60_000)
        runCurrent()
        val failure = results.single() as LocateResult.Unavailable
        assertTrue(failure.attempt!!.asked.contains(LocationManager.GPS_PROVIDER))
        assertFalse(state.locating)
        assertFalse(state.slow)
        assertNoRequestsLeft()
    }

    @Test
    fun `a late live fix still lands`() = runTest {
        val state = state()
        state.locate()
        runCurrent()
        advanceTimeBy(2 * 60_000L)
        deliver(fix(LocationManager.GPS_PROVIDER))

        assertEquals(FULDA_LAT, (results.single() as LocateResult.Found).lat, 1e-6)
        assertFalse(state.locating)
        assertFalse(state.slow)
        assertNoRequestsLeft()
    }

    @Test
    fun `an old cached position shows at once and the search goes on`() = runTest {
        locations.simulateLocation(fix(LocationManager.GPS_PROVIDER, ageMs = 2 * 3_600_000L, lat = MARBURG_LAT))
        val state = state()
        state.locate()
        runCurrent()
        assertEquals(MARBURG_LAT, (results.single() as LocateResult.Found).lat, 1e-6)
        assertTrue(state.locating)

        advanceTimeBy(60_000)
        assertTrue("an old position is no answer to where am I now", state.slow)
        deliver(fix(LocationManager.GPS_PROVIDER))

        assertEquals(2, results.size)
        assertEquals(FULDA_LAT, (results.last() as LocateResult.Found).lat, 1e-6)
        assertFalse(state.locating)
    }

    @Test
    fun `a cached position from the last half hour is refined briefly and quietly`() = runTest {
        locations.simulateLocation(fix(LocationManager.NETWORK_PROVIDER, ageMs = 10 * 60_000L))
        val state = state()
        state.locate()
        runCurrent()
        assertTrue(results.single() is LocateResult.Found)

        advanceTimeBy(DeviceLocation.TIMEOUT_MS + 1_000)
        runCurrent()
        assertFalse(state.locating)
        assertFalse(state.slow)
        assertEquals("no failure after a good position", 1, results.size)
    }

    @Test
    fun `a fresh cached position answers alone`() = runTest {
        locations.simulateLocation(fix(LocationManager.NETWORK_PROVIDER, ageMs = 30_000))
        val state = state()
        state.locate()
        runCurrent()

        assertTrue(results.single() is LocateResult.Found)
        assertFalse(state.locating)
        assertNoRequestsLeft()
    }

    @Test
    fun `cancel stops the search without a message`() = runTest {
        val state = state()
        state.locate()
        runCurrent()
        advanceTimeBy(15_000)
        assertTrue(state.slow)

        state.cancel()
        runCurrent()
        assertFalse(state.locating)
        assertFalse(state.slow)
        assertTrue(results.isEmpty())
        assertNoRequestsLeft()
    }

    @Test
    fun `a quiet lookup stays short and never hints`() = runTest {
        val state = state()
        state.locateQuietly()
        runCurrent()
        advanceTimeBy(DeviceLocation.TIMEOUT_MS + 1_000)
        runCurrent()

        assertFalse(state.locating)
        assertFalse(state.slow)
        assertTrue(results.single() is LocateResult.Unavailable)
    }

    @Test
    fun `a tap during a quiet lookup turns it into a full search`() = runTest {
        val state = state()
        state.locateQuietly()
        runCurrent()
        advanceTimeBy(5_000)
        state.locate()
        runCurrent()

        advanceTimeBy(DeviceLocation.TIMEOUT_MS)
        assertTrue("past the quiet limit", state.locating)
        assertTrue(state.slow)
        assertTrue(results.isEmpty())
    }

    private fun TestScope.state() = LocateMeState(app, backgroundScope) { { results += it } }

    private fun TestScope.deliver(location: Location) {
        locations.simulateLocation(location)
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
    }

    private fun assertNoRequestsLeft() {
        for (provider in PROVIDERS) {
            assertTrue("$provider still listening", locations.getLocationRequests(provider).isEmpty())
        }
    }

    private fun fix(provider: String, ageMs: Long = 0, lat: Double = FULDA_LAT) = Location(provider).apply {
        latitude = lat
        longitude = FULDA_LNG
        accuracy = 25f
        time = System.currentTimeMillis() - ageMs
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - ageMs * 1_000_000L
    }

    private companion object {
        val PROVIDERS = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        const val FULDA_LAT = 50.5558
        const val FULDA_LNG = 9.6808
        const val MARBURG_LAT = 50.8021
    }
}
