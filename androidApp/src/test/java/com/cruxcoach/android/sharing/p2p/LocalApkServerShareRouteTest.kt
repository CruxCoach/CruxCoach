package com.cruxcoach.android.sharing.p2p

import com.cruxcoach.android.util.LocalApkServer
import com.cruxcoach.android.util.LocalShareProtocol
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The offline-share server (hotspot session) carries the direct channel on its own port. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class LocalApkServerShareRouteTest {
    private val dir = kotlin.io.path.createTempDirectory("share-route").toFile()
    private var server: LocalApkServer? = null

    @After fun stop() {
        server?.stop()
        dir.deleteRecursively()
    }

    @Test fun share_routes_answer_only_when_a_channel_is_attached() {
        val apk = File(dir, "app.apk").apply { writeBytes(ByteArray(16)) }
        val local = LocalApkServer(apkFile = apk, snapshotDir = dir).also { server = it }
        val port = local.start(port = 0, hostIp = "127.0.0.1")
        val base = "http://127.0.0.1:$port"
        // Without a channel the path is not a share route (legacy behaviour).
        val landing = URL(base + ShareHttpRoutes.HELLO).openConnection() as HttpURLConnection
        assertEquals(200, landing.responseCode)
        assertEquals(true, landing.contentType?.startsWith("text/html"))
        landing.disconnect()
        local.shareExchange = object : ShareExchangeServer {
            override fun hello() = ShareHello(listOf("0123456789abcdef"))
            override fun exchange(request: ByteArray, maxBytes: Int) = request + byteArrayOf(9)
        }
        val client = ShareHttpClient()
        assertEquals(ShareHello(listOf("0123456789abcdef")), client.hello(base))
        assertContentEquals(byteArrayOf(1, 2, 9), client.exchange(base, byteArrayOf(1, 2)))
        // The APK share keeps working next to the channel.
        val manifest = URL(base + LocalShareProtocol.MANIFEST_PATH).openConnection() as HttpURLConnection
        assertEquals(200, manifest.responseCode)
        manifest.disconnect()
    }
}
