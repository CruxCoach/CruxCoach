package com.cruxcoach.android.util

import android.net.Uri
import android.net.Network
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.SocketFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class LocalShareProtocolTest {
    private lateinit var tempDir: File
    private var server: LocalApkServer? = null

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory("local-share-test").toFile()
    }

    @After
    fun tearDown() {
        server?.stop()
        tempDir.deleteRecursively()
    }

    @Test
    fun invitation_roundTripsCredentialsWithoutLandingPage() {
        val invitation = LocalShareProtocol.Invitation(
            baseUrl = "http://192.168.49.1:4949",
            ssid = "DIRECT-ab-CruxCoach1234",
            password = "correct horse battery staple",
        )

        val parsed = LocalShareProtocol.parseInvitation(
            Uri.parse(LocalShareProtocol.invitationUri(invitation)),
        )

        assertEquals(invitation, parsed)
    }

    @Test
    fun invitation_rejectsInvalidCredentials() {
        assertNull(
            LocalShareProtocol.parseInvitation(
                Uri.parse("cruxcoach://offline-share?base=http%3A%2F%2F192.168.49.1&ssid=x&password=short"),
            ),
        )
        assertNull(
            LocalShareProtocol.parseInvitation(
                Uri.parse(
                    "cruxcoach://offline-share?base=" +
                        "http%3A%2F%2F192.168.49.1%3A4949%2Fevil&ssid=valid&password=12345678",
                ),
            ),
        )
    }

    @Test
    fun manifestParserRejectsRemoteOrTraversalArtifactPaths() {
        val hash = "a".repeat(64)
        val json = manifestJson(apkPath = "https://evil.example/app.apk", hash = hash)
        assertFailsWith<IllegalArgumentException> { LocalShareProtocol.parseManifest(json) }

        val traversal = manifestJson(apkPath = "/../app.apk", hash = hash)
        assertFailsWith<IllegalArgumentException> { LocalShareProtocol.parseManifest(traversal) }

        val requestInjection = manifestJson(apkPath = "/app.apk\\r\\nX-Evil: yes", hash = hash)
        assertFailsWith<IllegalArgumentException> {
            LocalShareProtocol.parseManifest(requestInjection)
        }
    }

    @Test
    fun serverAdvertisesEverythingAndSupportsByteRangeResume() {
        val apkBytes = "0123456789abcdef".toByteArray()
        val apk = File(tempDir, "app.apk").apply { writeBytes(apkBytes) }
        server = LocalApkServer(
            apkFile = apk,
            boardDbFile = null,
            snapshotDir = tempDir,
            apkVersionCode = 8,
            apkVersionName = "0.2.2",
            openAppUri = "cruxcoach://offline-share?base=http%3A%2F%2F127.0.0.1%3A4949&amp=x",
        )
        val port = server!!.start(port = 0, hostIp = "127.0.0.1")

        val manifestConnection = URL("http://127.0.0.1:$port${LocalShareProtocol.MANIFEST_PATH}")
            .openConnection() as HttpURLConnection
        val manifest = manifestConnection.inputStream.bufferedReader().use {
            LocalShareProtocol.parseManifest(it.readText())
        }
        manifestConnection.disconnect()
        assertEquals(8, manifest.apkVersionCode)
        assertEquals("unavailable", manifest.boardStatus)
        assertNull(manifest.board)
        assertEquals(LocalShareProtocol.sha256(apk), manifest.apk.sha256)

        val rangeConnection = URL("http://127.0.0.1:$port${LocalShareProtocol.APK_PATH}")
            .openConnection() as HttpURLConnection
        rangeConnection.setRequestProperty("Range", "bytes=6-")
        assertEquals(HttpURLConnection.HTTP_PARTIAL, rangeConnection.responseCode)
        assertEquals("bytes 6-15/16", rangeConnection.getHeaderField("Content-Range"))
        assertEquals("bytes", rangeConnection.getHeaderField("Accept-Ranges"))
        assertContentEquals(apkBytes.copyOfRange(6, apkBytes.size), rangeConnection.inputStream.readBytes())
        rangeConnection.disconnect()

        val landingConnection = URL("http://127.0.0.1:$port/")
            .openConnection() as HttpURLConnection
        val landing = landingConnection.inputStream.bufferedReader().use { it.readText() }
        landingConnection.disconnect()
        assertTrue(landing.contains("Install directly from the nearby device"))
        assertTrue(landing.contains("/CruxCoach.apk"))
        assertTrue(landing.contains("Install CruxCoach"))
        assertTrue(landing.contains("Already installed? Open CruxCoach"))
        assertTrue(landing.contains("aria-label=\"CruxCoach\""))
        assertTrue(landing.contains("href=\"cruxcoach://offline-share"))
        assertFalse(landing.contains("location.href=openAppUri"))
        assertTrue(landing.contains("No need to return to this page"))
        assertFalse(landing.contains("/board.db"))
        assertFalse(landing.contains("import-board-db"))

        val completion = CountDownLatch(1)
        server!!.onReceiverComplete = { completion.countDown() }
        val completeConnection = URL(
            "http://127.0.0.1:$port${LocalShareProtocol.COMPLETE_PATH}",
        ).openConnection() as HttpURLConnection
        completeConnection.requestMethod = "POST"
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, completeConnection.responseCode)
        completeConnection.disconnect()
        assertTrue(completion.await(1, TimeUnit.SECONDS))

        assertTrue(LocalApkServer.AUTO_SHUTDOWN_MS >= 15 * 60_000L)
    }

    @Test
    fun repeatedHttpRequestsDoNotExtendTheFixedShareDeadline() {
        val apk = File(tempDir, "deadline.apk").apply { writeText("apk") }
        val stopped = CountDownLatch(1)
        server = LocalApkServer(
            apkFile = apk,
            autoShutdownMs = 150L,
        ).also { it.onAutoShutdown = { stopped.countDown() } }
        val port = server!!.start(port = 0, hostIp = "127.0.0.1")

        var successfulRequests = 0
        val requestThread = kotlin.concurrent.thread {
            repeat(8) {
                runCatching {
                    val connection = URL("http://127.0.0.1:$port/")
                        .openConnection() as HttpURLConnection
                    connection.connectTimeout = 200
                    connection.readTimeout = 200
                    connection.inputStream.use { it.readBytes() }
                    connection.disconnect()
                    successfulRequests++
                }
                Thread.sleep(35)
            }
        }

        assertTrue(stopped.await(1, TimeUnit.SECONDS))
        requestThread.join(1_000)
        assertTrue(successfulRequests >= 2)
    }

    @Test
    fun manifestParsesEveryAdvertisedBoardCatalogue() {
        val hash = "b".repeat(64)
        val json = """
            {
              "protocolVersion": 1,
              "sessionId": "01234567-89ab-cdef-0123-456789abcdef",
              "apk": {
                "path": "/CruxCoach.apk", "versionCode": 8,
                "versionName": "0.2.2", "sizeBytes": 42, "sha256": "$hash"
              },
              "board": {
                "status": "ready", "path": "/board.db.gz", "compression": "gzip",
                "sizeBytes": 20, "sha256": "$hash", "uncompressedSizeBytes": 100,
                "uncompressedSha256": "$hash", "schemaVersion": 25,
                "catalogues": [
                  {"boardBrand":"kilter","climbCount":174000},
                  {"boardBrand":"moonboard","climbCount":98000},
                  {"boardBrand":"tension","climbCount":12000}
                ]
              }
            }
        """.trimIndent()

        val board = LocalShareProtocol.parseManifest(json).board!!

        assertEquals(
            listOf("kilter", "moonboard", "tension"),
            board.catalogues.map { it.boardBrand },
        )
        assertEquals(98_000L, board.catalogues[1].climbCount)
    }

    @Test
    fun gzipRoundTripIsStreamingAndOutputBounded() {
        val input = File(tempDir, "input.db").apply {
            writeBytes(ByteArray(256 * 1024) { index -> (index % 251).toByte() })
        }
        val compressed = File(tempDir, "input.db.gz")
        val restored = File(tempDir, "restored.db")

        ShareCompression.gzip(input, compressed)
        ShareCompression.gunzip(compressed, restored, input.length())

        assertContentEquals(input.readBytes(), restored.readBytes())
        assertFailsWith<java.io.IOException> {
            ShareCompression.gunzip(compressed, File(tempDir, "too-large.db"), input.length() - 1)
        }
    }

    @Test
    fun receiverResumesPartialArtifactAndVerifiesIt() = runBlocking {
        val apkBytes = ByteArray(192 * 1024) { index -> (index % 239).toByte() }
        val apk = File(tempDir, "resume-source.apk").apply { writeBytes(apkBytes) }
        server = LocalApkServer(apkFile = apk)
        val port = server!!.start(port = 0, hostIp = "127.0.0.1")
        val network = mockk<Network>()
        every { network.socketFactory } returns SocketFactory.getDefault()
        val target = File(tempDir, "received.apk")
        File(target.path + ".part").writeBytes(apkBytes.copyOfRange(0, 73_111))

        LocalShareClient().downloadResumable(
            network = network,
            baseUrl = "http://127.0.0.1:$port",
            artifact = LocalShareProtocol.Artifact(
                path = LocalShareProtocol.APK_PATH,
                sizeBytes = apk.length(),
                sha256 = LocalShareProtocol.sha256(apk),
            ),
            target = target,
            onProgress = { _, _ -> },
        )

        assertContentEquals(apkBytes, target.readBytes())
        assertFalse(File(target.path + ".part").exists())
    }

    private fun manifestJson(apkPath: String, hash: String): String = """
        {
          "protocolVersion": 1,
          "sessionId": "01234567-89ab-cdef-0123-456789abcdef",
          "apk": {
            "path": "$apkPath",
            "versionCode": 8,
            "versionName": "0.2.2",
            "sizeBytes": 42,
            "sha256": "$hash"
          },
          "board": { "status": "preparing" }
        }
    """.trimIndent()
}
