package com.cruxcoach.android.ui.common

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Looper
import android.os.SystemClock
import com.cruxcoach.android.util.DeviceLocation
import io.mockk.every
import io.mockk.mockk
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
import java.util.concurrent.Executor

/**
 * A "my location" tap keeps looking until a position arrives, like a map app
 * that listens while it is open (owner tests on GrapheneOS, 2026-10-24:
 * requests were registered, but 45 s indoors without a network location
 * service were not enough; then: "so lange … suchen/warten, bis er verfügbar
 * ist"). Only the user, leaving the screen, or a reason the user can act on
 * ends it.
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
    fun `without any position a tap hints after 10 s and looks on for hours`() = runTest {
        val state = state()
        state.locate()
        runCurrent()
        assertTrue(state.locating)

        advanceTimeBy(9_000)
        assertFalse(state.slow)
        advanceTimeBy(2_000)
        assertTrue("still-looking hint", state.slow)

        advanceTimeBy(3 * 3_600_000L)
        runCurrent()
        assertTrue("no message while it looks", results.isEmpty())
        assertTrue(state.locating)
        assertTrue(state.slow)
        assertFalse(locations.getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty())

        deliver(fix(LocationManager.GPS_PROVIDER))
        assertEquals(FULDA_LAT, (results.single() as LocateResult.Found).lat, 1e-6)
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

        advanceTimeBy(30 * 60_000L)
        assertTrue("an old position is no answer to where am I now", state.slow)
        assertTrue(state.locating)
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
    fun `cancel stops the search and says what it tried`() = runTest {
        val state = state()
        state.locate()
        runCurrent()
        // The detail line reports real elapsed time, so move the phone's clock as well.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(95))
        advanceTimeBy(95_000)
        assertTrue(state.slow)

        state.cancel()
        runCurrent()
        val stopped = results.single() as LocateResult.Stopped
        assertTrue(LocationManager.GPS_PROVIDER in stopped.attempt!!.asked)
        assertEquals(95_000L, stopped.attempt.waitedMs)
        assertFalse(state.locating)
        assertFalse(state.slow)
        assertNoRequestsLeft()
    }

    @Test
    fun `switching location off during the search says so`() = runTest {
        val state = state()
        state.locate()
        runCurrent()
        advanceTimeBy(20_000)

        locations.setLocationEnabled(false)
        app.sendBroadcast(Intent(LocationManager.MODE_CHANGED_ACTION))
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()

        assertEquals(LocateResult.Disabled, results.single())
        assertFalse(state.locating)
        assertFalse(state.slow)
        assertNoRequestsLeft()
    }

    @Test
    fun `without any location source the tap says so at once`() = runTest {
        // As Android does it when a provider is missing or not allowed; Robolectric's shadow
        // re-creates removed providers instead, hence the mock (see DeviceLocationTest).
        val refusing = mockk<LocationManager>(relaxed = true)
        every { refusing.isLocationEnabled } returns true
        every { refusing.getProviders(true) } returns emptyList()
        every { refusing.allProviders } returns emptyList()
        every { refusing.getLastKnownLocation(any()) } returns null
        every {
            refusing.requestLocationUpdates(any<String>(), any<LocationRequest>(), any<Executor>(), any<LocationListener>())
        } answers { throw IllegalArgumentException("provider \"${firstArg<String>()}\" does not exist") }
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.LOCATION_SERVICE) refusing else super.getSystemService(name)
        }
        val state = LocateMeState(context, backgroundScope) { { results += it } }
        state.locate()
        runCurrent()

        val failure = results.single() as LocateResult.Unavailable
        assertTrue(failure.attempt!!.asked.isEmpty())
        assertTrue(LocationManager.GPS_PROVIDER in failure.attempt.refused)
        assertFalse(state.locating)
    }

    @Test
    fun `the search pauses in the background and goes on without moving the map back`() = runTest {
        locations.simulateLocation(fix(LocationManager.GPS_PROVIDER, ageMs = 2 * 3_600_000L, lat = MARBURG_LAT))
        val state = state()
        state.locate()
        runCurrent()
        assertEquals(1, results.size)

        state.onStopped()
        runCurrent()
        assertFalse(state.locating)
        assertNoRequestsLeft()

        state.onStarted()
        runCurrent()
        assertTrue(state.locating)
        assertFalse(locations.getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty())
        assertEquals("the old position is not shown again", 1, results.size)

        deliver(fix(LocationManager.GPS_PROVIDER))
        assertEquals(FULDA_LAT, (results.last() as LocateResult.Found).lat, 1e-6)
        assertFalse(state.locating)
    }

    @Test
    fun `back from another app the fix it found is the answer`() = runTest {
        val state = state()
        state.locate()
        runCurrent()
        state.onStopped()
        runCurrent()
        // Another app (a map app, say) found the position meanwhile.
        locations.simulateLocation(fix(LocationManager.GPS_PROVIDER))

        state.onStarted()
        runCurrent()
        assertEquals(FULDA_LAT, (results.single() as LocateResult.Found).lat, 1e-6)
        assertFalse(state.locating)
        assertNoRequestsLeft()
    }

    @Test
    fun `a quiet lookup stays short, never hints and ends without a word`() = runTest {
        val state = state()
        state.locateQuietly()
        runCurrent()
        advanceTimeBy(DeviceLocation.TIMEOUT_MS + 1_000)
        runCurrent()

        assertFalse(state.locating)
        assertFalse(state.slow)
        assertTrue(results.isEmpty())
        assertNoRequestsLeft()
    }

    @Test
    fun `a quiet lookup does not come back after the background`() = runTest {
        val state = state()
        state.locateQuietly()
        runCurrent()
        state.onStopped()
        state.onStarted()
        runCurrent()

        assertFalse(state.locating)
        assertNoRequestsLeft()
    }

    @Test
    fun `a tap during a quiet lookup turns it into a search without a limit`() = runTest {
        val state = state()
        state.locateQuietly()
        runCurrent()
        advanceTimeBy(5_000)
        state.locate()
        runCurrent()

        advanceTimeBy(3_600_000L)
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
