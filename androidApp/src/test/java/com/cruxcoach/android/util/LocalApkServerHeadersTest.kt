package com.cruxcoach.android.util

import java.io.File
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class LocalApkServerHeadersTest {

    @Test
    fun `every LAN response carries the shared browser security policy`() {
        val testDir = File("androidApp/build/tmp/local-apk-server-headers").apply { mkdirs() }
        val apk = File(testDir, "app.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val server = LocalApkServer(
            apkFile = apk,
            versionName = "9.8.7",
            appDisplayName = "Fork Board",
            sourceCodeUrl = "https://codeberg.org/example/fork/src/tag/v9.8.7",
            licenseText = "GNU GENERAL PUBLIC LICENSE".toByteArray(),
        )
        val port = server.start(port = 0, hostIp = "127.0.0.1")
        try {
            listOf("/", "/download.apk", "/favicon.ico", "/board.db", "/LICENSE").forEach { path ->
                val response = get(port, path)
                assertTrue(response.contains("X-Content-Type-Options: nosniff\r\n"), path)
                assertTrue(response.contains("Referrer-Policy: no-referrer\r\n"), path)
                assertTrue(response.contains("X-Frame-Options: DENY\r\n"), path)
                assertTrue(
                    response.contains("Content-Security-Policy: default-src 'none';"),
                    path,
                )
            }
            val landingPage = get(port, "/")
            assertTrue(landingPage.contains("Fork Board v9.8.7"))
            assertFalse(landingPage.contains("CruxCoach"))
            assertTrue(landingPage.contains("/example/fork/src/tag/v9.8.7"))
            assertTrue(
                get(port, "/download.apk")
                    .contains("Content-Disposition: attachment; filename=\"Fork_Board.apk\""),
            )
            val license = get(port, "/LICENSE")
            assertTrue(license.contains("GNU GENERAL PUBLIC LICENSE"))
        } finally {
            server.stop()
            apk.delete()
            testDir.delete()
        }
    }

    @Test
    fun `source directions are pinned to the shared build version`() {
        assertEquals(
            "https://codeberg.org/example/fork/src/tag/v9.8.7",
            ApkShareHelper.versionSourceUrl(
                apiBase = "https://codeberg.org/api/v1",
                owner = "example",
                repository = "fork",
                version = "9.8.7",
            ),
        )
    }

    @Test
    fun `shared APK filename is ASCII header safe and branded`() {
        assertEquals("Fork_Board.apk", ApkShareHelper.shareApkName("Fork Board"))
        assertEquals("app.apk", ApkShareHelper.shareApkName("  "))
        assertEquals("A_B.apk", ApkShareHelper.shareApkName("A/B"))
    }

    @Test
    fun `client that never sends a request is closed after read timeout`() {
        val apk = File.createTempFile("local-server-timeout", ".apk").apply {
            writeBytes(byteArrayOf(1))
            deleteOnExit()
        }
        val server = LocalApkServer(apkFile = apk, clientReadTimeoutMs = 100)
        val port = server.start(port = 0, hostIp = "127.0.0.1")
        val client = Socket("127.0.0.1", port)
        try {
            eventually { server.activeClientCountForTesting == 1 }
            eventually { server.activeClientCountForTesting == 0 }
            assertEquals(0, server.activeClientCountForTesting)
        } finally {
            client.close()
            server.stop()
            apk.delete()
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

    private fun eventually(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000L
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L)
        }
        assertTrue(predicate(), "condition was not met before timeout")
    }
}
