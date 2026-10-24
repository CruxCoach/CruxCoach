package com.cruxcoach.android.util

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Looper
import android.os.SystemClock
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * "My location" on the board map and in the gym picker (owner report
 * 2026-10-24: no position on a phone with location on). Each provider stays
 * silent until the test delivers a fix, like a phone without a fix yet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceLocationTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val locations = shadowOf(manager)

    @Before
    fun setUp() {
        // A phone that has been running for a while, so a cached fix can be hours old.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(3))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        locations.setLocationEnabled(true)
        for (provider in listOf(FUSED, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            locations.setProviderEnabled(provider, true)
        }
    }

    @Test
    fun `gps answers while fused has no fix`() = runTest {
        val result = locate()
        advanceTimeBy(5_000)
        deliver(fix(LocationManager.GPS_PROVIDER))

        assertFulda(result)
        assertNoListenersLeft()
    }

    @Test
    fun `a cold gps start after 30 seconds still finds the position`() = runTest {
        val result = locate()
        advanceTimeBy(30_000)
        deliver(fix(LocationManager.GPS_PROVIDER))

        assertFulda(result)
    }

    @Test
    fun `approximate location also asks gps`() = runTest {
        // Android 12+ "Approximate": fused then runs in low-power mode and,
        // without a network location service, never produces a fix itself.
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val result = locate()
        advanceTimeBy(5_000)
        deliver(fix(LocationManager.GPS_PROVIDER))

        assertFulda(result)
    }

    @Test
    fun `the first provider with a fix wins and every request stops`() = runTest {
        val result = locate()
        advanceTimeBy(1_000)
        deliver(fix(LocationManager.NETWORK_PROVIDER))

        assertFulda(result)
        assertEquals(LocationManager.NETWORK_PROVIDER, result.getCompleted()!!.provider)
        assertNoListenersLeft()
    }

    @Test
    fun `a fresh cached fix answers at once without asking`() = runTest {
        locations.simulateLocation(fix(LocationManager.NETWORK_PROVIDER, ageMs = 30_000))
        val result = locate()

        assertFulda(result)
        assertNoListenersLeft()
    }

    @Test
    fun `a cached fix from the last half hour answers after a short wait`() = runTest {
        locations.simulateLocation(fix(LocationManager.GPS_PROVIDER, ageMs = 10 * 60_000L))
        val result = locate()
        advanceTimeBy(5_000)
        assertFalse("a live fix still gets a few seconds", result.isCompleted)

        advanceTimeBy(5_000)
        runCurrent()
        assertFulda(result)
        assertNoListenersLeft()
    }

    @Test
    fun `a live fix beats a recent cached one`() = runTest {
        locations.simulateLocation(fix(LocationManager.NETWORK_PROVIDER, ageMs = 10 * 60_000L, lat = 50.80, lng = 8.77))
        val result = locate()
        advanceTimeBy(2_000)
        deliver(fix(LocationManager.GPS_PROVIDER))

        assertFulda(result)
    }

    @Test
    fun `an old cached fix is the last resort after the full wait`() = runTest {
        locations.simulateLocation(fix(LocationManager.GPS_PROVIDER, ageMs = 2 * 3_600_000L))
        val result = locate()
        advanceTimeBy(40_000)
        assertFalse(result.isCompleted)

        advanceTimeBy(10_000)
        runCurrent()
        assertFulda(result)
    }

    @Test
    fun `without any fix the answer is null once a cold gps start had its time`() = runTest {
        val result = locate()
        advanceTimeBy(40_000)
        assertFalse("still waiting for a cold gps start", result.isCompleted)

        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(result.isCompleted)
        assertNull(result.getCompleted())
        assertNoListenersLeft()
    }

    @Test
    fun `leaving the screen stops every request`() = runTest {
        val result = locate()
        assertTrue(locations.getLocationRequests(LocationManager.GPS_PROVIDER).isNotEmpty())

        result.cancel()
        runCurrent()
        assertNoListenersLeft()
    }

    @Test
    fun `without permission nothing is asked`() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val result = locate()

        assertTrue(result.isCompleted)
        assertNull(result.getCompleted())
        assertNoListenersLeft()
    }

    @Test
    fun `a provider Android lists as off is still asked and answers once it comes on`() = runTest {
        // GrapheneOS owner test 2026-10-24: no request seemed to reach the phone at all.
        locations.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        val result = lookup()
        assertTrue(locations.getLocationRequests(LocationManager.GPS_PROVIDER).isNotEmpty())

        advanceTimeBy(3_000)
        locations.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        deliver(fix(LocationManager.GPS_PROVIDER))

        assertTrue(result.isCompleted)
        assertEquals(FULDA_LAT, result.getCompleted().location!!.latitude, 1e-6)
        assertNoListenersLeft()
    }

    @Test
    fun `the attempt says what was tried and how long it waited`() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        locations.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        val result = lookup()
        // The detail line reports real elapsed time, so move the phone's clock as well.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(46))
        advanceTimeBy(46_000)
        runCurrent()

        val lookup = result.getCompleted()
        assertNull(lookup.location)
        val attempt = lookup.attempt
        assertFalse(attempt.precise)
        assertFalse(LocationManager.NETWORK_PROVIDER in attempt.enabled)
        assertTrue(LocationManager.GPS_PROVIDER in attempt.enabled)
        assertEquals(listOf(FUSED, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER), attempt.asked)
        assertTrue(attempt.refused.isEmpty())
        assertEquals(46_000L, attempt.waitedMs)
        assertNoListenersLeft()
    }

    @Test
    fun `when every provider refuses the answer comes at once and names them`() = runTest {
        // As Android does it: a missing provider throws IllegalArgumentException, a forbidden one
        // SecurityException (LocationManagerService.registerLocationListener). Robolectric's
        // shadow re-creates removed providers instead, hence the mock.
        val refusing = mockk<LocationManager>(relaxed = true)
        every { refusing.getProviders(true) } returns listOf(LocationManager.PASSIVE_PROVIDER)
        every { refusing.allProviders } returns listOf(LocationManager.PASSIVE_PROVIDER)
        every { refusing.getLastKnownLocation(any()) } returns null
        every {
            refusing.requestLocationUpdates(any<String>(), any<LocationRequest>(), any<Executor>(), any<LocationListener>())
        } answers {
            val provider = firstArg<String>()
            if (provider == LocationManager.PASSIVE_PROVIDER) throw SecurityException("not allowed")
            throw IllegalArgumentException("provider \"$provider\" does not exist")
        }
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.LOCATION_SERVICE) refusing else super.getSystemService(name)
        }
        val result = async { DeviceLocation.locate(context) }.also { runCurrent() }

        assertTrue("no waiting when nothing could be asked", result.isCompleted)
        val lookup = result.getCompleted()
        assertNull(lookup.location)
        assertTrue(lookup.attempt.asked.isEmpty())
        assertEquals(
            listOf(FUSED, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER),
            lookup.attempt.refused,
        )
        assertEquals(listOf(LocationManager.PASSIVE_PROVIDER), lookup.attempt.enabled)
    }

    @Test
    @Config(sdk = [28])
    fun `android 9 gps answers while network has no fix`() = runTest {
        val result = locate()
        advanceTimeBy(5_000)
        deliver(fix(LocationManager.GPS_PROVIDER))

        assertFulda(result)
        assertNoListenersLeft()
    }

    private fun TestScope.locate(): Deferred<Location?> =
        async { DeviceLocation.current(app) }.also { runCurrent() }

    private fun TestScope.lookup(): Deferred<DeviceLocation.Lookup> =
        async { DeviceLocation.locate(app) }.also { runCurrent() }

    /** A fix arrives; the location manager posts it to the main thread. */
    private fun TestScope.deliver(location: Location) {
        locations.simulateLocation(location)
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
    }

    private fun assertFulda(result: Deferred<Location?>) {
        assertTrue("no answer yet", result.isCompleted)
        val location = result.getCompleted()
        assertNotNull("no position", location)
        assertEquals(FULDA_LAT, location!!.latitude, 1e-6)
        assertEquals(FULDA_LNG, location.longitude, 1e-6)
    }

    private fun assertNoListenersLeft() {
        for (provider in listOf(FUSED, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            assertTrue("$provider still listening", locations.getLocationRequests(provider).isEmpty())
        }
    }

    private fun fix(provider: String, ageMs: Long = 0, lat: Double = FULDA_LAT, lng: Double = FULDA_LNG) =
        Location(provider).apply {
            latitude = lat
            longitude = lng
            accuracy = 25f
            time = System.currentTimeMillis() - ageMs
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - ageMs * 1_000_000L
        }

    private companion object {
        const val FUSED = "fused"
        const val FULDA_LAT = 50.5558
        const val FULDA_LNG = 9.6808
    }
}
