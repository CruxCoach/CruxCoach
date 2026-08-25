package com.cruxcoach.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class LocalShareDiscoveryTest {
    private val context: Context
        get() = org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun `connected invitation probes exact origin only through wifi network`() = runBlocking {
        val connectivity = mockk<ConnectivityManager>()
        val cellular = mockk<Network>()
        val wifi = mockk<Network>()
        val cellularCapabilities = mockk<NetworkCapabilities>()
        val wifiCapabilities = mockk<NetworkCapabilities>()
        every { cellularCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
        every { wifiCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        every { connectivity.activeNetwork } returns cellular
        every { connectivity.allNetworks } returns arrayOf(cellular, wifi)
        every { connectivity.getNetworkCapabilities(cellular) } returns cellularCapabilities
        every { connectivity.getNetworkCapabilities(wifi) } returns wifiCapabilities

        val manifest = manifest()
        val queriedNetworks = mutableListOf<Network>()
        val queriedOrigins = mutableListOf<String>()
        val client = mockk<LocalShareClient>()
        every {
            client.fetchManifest(any(), any(), any(), any(), any(), any())
        } answers {
            queriedNetworks += firstArg<Network>()
            queriedOrigins += secondArg<String>()
            manifest
        }

        val found = LocalShareDiscovery(context, connectivity).discoverAt(
            "http://192.168.49.1:4949/",
            client,
        )

        assertSame(wifi, found?.network)
        assertEquals("http://192.168.49.1:4949", found?.baseUrl)
        assertSame(manifest, found?.manifest)
        assertEquals(listOf(wifi), queriedNetworks)
        assertEquals(listOf("http://192.168.49.1:4949"), queriedOrigins)
    }

    @Test
    fun `connected invitation refuses public origin before probing any network`() = runBlocking {
        val connectivity = mockk<ConnectivityManager>(relaxed = true)
        val client = mockk<LocalShareClient>(relaxed = true)

        val found = LocalShareDiscovery(context, connectivity).discoverAt(
            "http://8.8.8.8:4949",
            client,
        )

        assertNull(found)
        verify(exactly = 0) {
            client.fetchManifest(any(), any(), any(), any(), any(), any())
        }
    }

    private fun manifest() = LocalShareProtocol.Manifest(
        protocolVersion = LocalShareProtocol.VERSION_V2,
        sessionId = "01234567-89ab-cdef-0123-456789abcdef",
        apkVersionCode = 8,
        apkVersionName = "0.2.2",
        apk = LocalShareProtocol.Artifact(
            path = LocalShareProtocol.APK_PATH,
            sizeBytes = 3,
            sha256 = "a".repeat(64),
        ),
        board = null,
        boardStatus = "preparing",
    )
}
