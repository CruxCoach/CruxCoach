package com.cruxcoach.android.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class BoardSyncConnectivityTest {
    private val connectivity = mockk<ConnectivityManager>(relaxed = true)
    private val callback = slot<ConnectivityManager.NetworkCallback>()
    private val wifi = mockk<Network>()
    private val mobile = mockk<Network>()

    private fun capabilities(wifi: Boolean, validated: Boolean = true) = mockk<NetworkCapabilities>().also {
        every { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns validated
        every { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns wifi
    }

    private fun TestScope.manager(initialNetwork: Network? = null): BoardSyncManager {
        every { connectivity.activeNetwork } returns initialNetwork
        every { connectivity.registerDefaultNetworkCallback(capture(callback)) } returns Unit
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.CONNECTIVITY_SERVICE) connectivity else super.getSystemService(name)
        }
        val importer = mockk<BoardDatabaseImporter>(relaxed = true)
        every { importer.isImported() } returns true
        val preferences = mockk<UserPreferences>(relaxed = true)
        every { preferences.lastSyncTimestamp } returns flowOf(null)
        return BoardSyncManager(
            importer = importer,
            blossomSyncManager = mockk(relaxed = true),
            userPreferences = preferences,
            appContext = context,
            boardRepository = mockk(relaxed = true),
            personalBoardRepo = mockk(relaxed = true),
            boardLocationRepository = mockk(relaxed = true),
            moonBoardCatalogueSync = mockk(relaxed = true),
            auroraCatalogueSync = mockk(relaxed = true),
            quantumCatalogueSync = mockk(relaxed = true),
            integrityVerifier = mockk(relaxed = true),
            scope = backgroundScope,
        )
    }

    @Test
    fun `offline banner clears after wifi validation without a screen or sync action`() = runTest {
        val manager = manager()
        runCurrent()
        assertFalse(manager.state.value.networkAvailable)

        callback.captured.onAvailable(wifi)
        callback.captured.onCapabilitiesChanged(wifi, capabilities(wifi = true, validated = false))
        runCurrent()
        assertFalse(manager.state.value.networkAvailable)
        assertFalse(manager.state.value.wifiConnected)

        callback.captured.onCapabilitiesChanged(wifi, capabilities(wifi = true))
        runCurrent()
        assertTrue(manager.state.value.networkAvailable)
        assertTrue(manager.state.value.wifiConnected)
        assertFalse(manager.state.value.isSyncing)
        // The synchronous snapshot remains offline; the callback's capabilities
        // must drive recovery instead of racing another activeNetwork query.
        verify(exactly = 0) { connectivity.getNetworkCapabilities(any()) }

        callback.captured.onLost(wifi)
        runCurrent()
        assertFalse(manager.state.value.networkAvailable)
        assertFalse(manager.state.value.wifiConnected)
    }

    @Test
    fun `wifi to mobile handover stays online when the old network is lost`() = runTest {
        val manager = manager()
        runCurrent()
        callback.captured.onAvailable(wifi)
        callback.captured.onCapabilitiesChanged(wifi, capabilities(wifi = true))
        callback.captured.onAvailable(mobile)
        callback.captured.onCapabilitiesChanged(mobile, capabilities(wifi = false))
        callback.captured.onLost(wifi)
        callback.captured.onCapabilitiesChanged(wifi, capabilities(wifi = true))
        runCurrent()
        assertTrue(manager.state.value.networkAvailable)
        assertFalse(manager.state.value.wifiConnected)

        callback.captured.onLost(mobile)
        runCurrent()
        assertFalse(manager.state.value.networkAvailable)
    }

    @Test
    fun `validation and transport changes update warnings on the same network`() = runTest {
        val manager = manager()
        runCurrent()
        callback.captured.onAvailable(wifi)
        callback.captured.onCapabilitiesChanged(wifi, capabilities(wifi = true))
        runCurrent()
        assertTrue(manager.state.value.wifiConnected)

        callback.captured.onCapabilitiesChanged(wifi, capabilities(wifi = true, validated = false))
        runCurrent()
        assertFalse(manager.state.value.networkAvailable)
        assertFalse(manager.state.value.wifiConnected)

        callback.captured.onCapabilitiesChanged(wifi, capabilities(wifi = false))
        runCurrent()
        assertTrue(manager.state.value.networkAvailable)
        assertFalse(manager.state.value.wifiConnected)
    }

    @Test
    fun `already connected startup has correct state and observer is released with scope`() = runTest {
        every { connectivity.getNetworkCapabilities(wifi) } returns capabilities(wifi = true)
        val manager = manager(initialNetwork = wifi)
        runCurrent()
        assertTrue(manager.state.value.networkAvailable)
        assertTrue(manager.state.value.wifiConnected)

        backgroundScope.cancel()
        runCurrent()
        verify(exactly = 1) { connectivity.unregisterNetworkCallback(callback.captured) }
    }
}
