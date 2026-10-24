package com.cruxcoach.android.ui.common

import android.app.Application
import android.os.Build
import com.cruxcoach.android.util.DeviceLocation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The detail line under "could not be found" (owner test on GrapheneOS without ADB, 2026-10-24). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LocateDetailTest {
    private val resources get() = RuntimeEnvironment.getApplication().resources
    private val android = "Android ${Build.VERSION.RELEASE}"

    private val timedOut = DeviceLocation.Attempt(
        precise = false,
        enabled = listOf("passive", "gps", "fused"),
        asked = listOf("fused", "network", "gps", "passive"),
        refused = emptyList(),
        waitedMs = 45_020,
    )

    private val nothingAsked = DeviceLocation.Attempt(
        precise = true,
        enabled = listOf("passive"),
        asked = emptyList(),
        refused = listOf("fused", "network", "gps"),
        waitedMs = 3,
    )

    private val withSatellites = timedOut.copy(precise = true, waitedMs = 300_000, satellitesSeen = 7, satellitesUsed = 0)

    @Test
    fun `satellite counts follow the sources`() {
        assertEquals(
            "Details: $android · precise location · on: passive, gps, fused · " +
                "asked: fused, network, gps, passive · satellites: 7 seen, 0 used · no answer in 300 s",
            locateDetail(resources, withSatellites),
        )
    }

    @Test
    @Config(qualifiers = "de")
    fun `satellite counts in german`() {
        assertEquals(
            "Details: $android · genauer Standort · aktiv: passive, gps, fused · " +
                "gefragt: fused, network, gps, passive · Satelliten: 7 gesehen, 0 genutzt · keine Antwort in 300 s",
            locateDetail(resources, withSatellites),
        )
    }

    @Test
    fun `english names permission, sources and wait`() {
        assertEquals(
            "Details: $android · approximate location · on: passive, gps, fused · " +
                "asked: fused, network, gps, passive · no answer in 45 s",
            locateDetail(resources, timedOut),
        )
        assertEquals(
            "Details: $android · precise location · on: passive · asked: none · " +
                "not available: fused, network, gps · no answer in 0 s",
            locateDetail(resources, nothingAsked),
        )
    }

    @Test
    @Config(qualifiers = "de")
    fun `german names permission, sources and wait`() {
        assertEquals(
            "Details: $android · ungefährer Standort · aktiv: passive, gps, fused · " +
                "gefragt: fused, network, gps, passive · keine Antwort in 45 s",
            locateDetail(resources, timedOut),
        )
        assertEquals(
            "Details: $android · genauer Standort · aktiv: passive · gefragt: keine · " +
                "nicht verfügbar: fused, network, gps · keine Antwort in 0 s",
            locateDetail(resources, nothingAsked),
        )
    }
}
