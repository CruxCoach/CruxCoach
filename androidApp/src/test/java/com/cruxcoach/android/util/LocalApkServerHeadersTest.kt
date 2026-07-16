package com.cruxcoach.android.util

import java.io.File
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class LocalApkServerHeadersTest {

    @Test
    fun `every LAN response carries the shared browser security policy`() {
        val testDir = File("androidApp/build/tmp/local-apk-server-headers").apply { mkdirs() }
        val apk = File(testDir, "CruxCoach.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val server = LocalApkServer(apkFile = apk)
        val port = server.start(port = 0, hostIp = "127.0.0.1")
        try {
            listOf("/", "/download.apk", "/favicon.ico", "/board.db").forEach { path ->
                val response = get(port, path)
                assertTrue(response.contains("X-Content-Type-Options: nosniff\r\n"), path)
                assertTrue(response.contains("Referrer-Policy: no-referrer\r\n"), path)
                assertTrue(response.contains("X-Frame-Options: DENY\r\n"), path)
                assertTrue(
                    response.contains("Content-Security-Policy: default-src 'none';"),
                    path,
                )
            }
        } finally {
            server.stop()
            apk.delete()
            testDir.delete()
        }
    }

    private fun get(port: Int, path: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(
            "GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.US_ASCII)
        )
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
    }
}
