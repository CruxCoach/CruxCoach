package com.cruxcoach.android.util

import android.net.Uri
import android.net.Network
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import javax.net.SocketFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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
    fun apkResponseUsesV1ResponderUnlessReceiverExplicitlyRequestsCurrentV2() {
        assertEquals(
            LocalShareResponseContract.PUBLISHED_V1_COMPATIBILITY_RESPONDER,
            responseContractForApkRequest(null),
        )
        assertEquals(
            LocalShareResponseContract.PUBLISHED_V1_COMPATIBILITY_RESPONDER,
            responseContractForApkRequest("1"),
        )
        assertEquals(
            LocalShareResponseContract.PUBLISHED_V1_COMPATIBILITY_RESPONDER,
            responseContractForApkRequest("unexpected"),
        )
        assertEquals(
            LocalShareResponseContract.CURRENT_V2_WRITER,
            responseContractForApkRequest(" 2 "),
        )
        assertEquals(
            LocalShareProtocol.VERSION_V2,
            LocalShareResponseContract.CURRENT_V2_WRITER.protocolVersion,
        )
    }

    @Test
    fun snapshotBuildSequencerNeverRunsV1AndV2Together() {
        val sequencer = SnapshotBuildSequencer()

        assertTrue(sequencer.request(LocalShareProtocol.VERSION))
        assertEquals(LocalShareProtocol.VERSION, sequencer.activeProtocolVersion)
        assertFalse(sequencer.request(LocalShareProtocol.VERSION_V2))
        assertFalse(sequencer.request(LocalShareProtocol.VERSION_V2))
        assertFalse(sequencer.request(LocalShareProtocol.VERSION))
        assertEquals(listOf(LocalShareProtocol.VERSION_V2), sequencer.pending)

        assertEquals(
            LocalShareProtocol.VERSION_V2,
            sequencer.complete(LocalShareProtocol.VERSION),
        )
        assertEquals(LocalShareProtocol.VERSION_V2, sequencer.activeProtocolVersion)
        assertTrue(sequencer.pending.isEmpty())

        // A retry of the other generation is queued behind v2 as well.
        assertFalse(sequencer.request(LocalShareProtocol.VERSION))
        assertEquals(
            LocalShareProtocol.VERSION,
            sequencer.complete(LocalShareProtocol.VERSION_V2),
        )
        assertNull(sequencer.complete(LocalShareProtocol.VERSION))
        assertNull(sequencer.activeProtocolVersion)
    }

    @Test
    fun unversionedAndOldSnapshotCachesAreDeletedInsteadOfAdopted() {
        val sidecars = listOf(
            "no metadata sidecar" to null,
            "metadata without scrub version" to "{}",
            "metadata from old scrub contract" to
                """{"snapshotScrubContractVersion":${LocalApkServer.SNAPSHOT_SCRUB_CONTRACT_VERSION - 1}}""",
        )

        sidecars.forEachIndexed { index, (label, metadata) ->
            val cacheDir = File(tempDir, "cache-$index").apply { mkdirs() }
            val apk = File(cacheDir, "app.apk").apply { writeText("apk") }
            val live = File(cacheDir, "live.db").apply { writeText("live") }
            val artifacts = listOf(
                LocalApkServer.SNAPSHOT_NAME,
                "${LocalApkServer.SNAPSHOT_NAME}-wal",
                "${LocalApkServer.SNAPSHOT_NAME}-shm",
                LocalApkServer.COMPRESSED_SNAPSHOT_NAME,
                "${LocalApkServer.SNAPSHOT_METADATA_NAME}.tmp",
            ).map { name -> File(cacheDir, name).apply { writeText("unverified") } }
            val metadataFile = File(cacheDir, LocalApkServer.SNAPSHOT_METADATA_NAME)
            if (metadata != null) metadataFile.writeText(metadata)

            val candidate = LocalApkServer(
                apkFile = apk,
                boardDbFile = live,
                snapshotDir = cacheDir,
            )
            try {
                candidate.start(port = 0, hostIp = "127.0.0.1")
                artifacts.forEach { artifact ->
                    assertFalse(artifact.exists(), "$label must delete ${artifact.name}")
                }
                assertFalse(metadataFile.exists(), "$label must delete metadata")
            } finally {
                candidate.stop()
            }
        }
    }

    @Test
    fun v2ApkDownloadIdentifiesProtocolOnTheSharedApkPath() = runBlocking {
        val bytes = "v2-apk".toByteArray()
        val source = File(tempDir, "protocol-source.apk").apply { writeBytes(bytes) }
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        val requestHeaders = mutableMapOf<String, String>()
        val serving = thread {
            socket.accept().use { client ->
                val reader = client.getInputStream().bufferedReader()
                reader.readLine()
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrBlank()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        requestHeaders[line.substring(0, separator).trim().lowercase()] =
                            line.substring(separator + 1).trim()
                    }
                }
                client.getOutputStream().buffered().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes)
                    flush()
                }
            }
            socket.close()
        }
        val network = mockk<Network>()
        every { network.socketFactory } returns SocketFactory.getDefault()

        LocalShareClient().downloadResumable(
            network = network,
            baseUrl = "http://127.0.0.1:$port",
            artifact = LocalShareProtocol.Artifact(
                path = LocalShareProtocol.APK_PATH,
                sizeBytes = source.length(),
                sha256 = LocalShareProtocol.sha256(source),
            ),
            target = File(tempDir, "protocol-received.apk"),
            protocolVersion = LocalShareProtocol.VERSION_V2,
            expectedSessionId = "01234567-89ab-cdef-0123-456789abcdef",
            onProgress = { _, _ -> },
        )

        serving.join(2_000)
        assertEquals(
            "2",
            requestHeaders[LocalShareProtocol.PROTOCOL_HEADER.lowercase()],
        )
        assertEquals(
            "01234567-89ab-cdef-0123-456789abcdef",
            requestHeaders[LocalShareProtocol.SESSION_HEADER.lowercase()],
        )
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
    fun connectedInvitation_roundTripsOnlyThePrivateOrigin() {
        val uri = LocalShareProtocol.connectedInvitationUri("http://192.168.49.1:4949/")

        assertEquals(
            LocalShareProtocol.ConnectedInvitation("http://192.168.49.1:4949"),
            LocalShareProtocol.parseConnectedInvitation(Uri.parse(uri)),
        )
        assertTrue(uri.contains("base="))
        assertFalse(uri.contains("ssid", ignoreCase = true))
        assertFalse(uri.contains("password", ignoreCase = true))
        assertFalse(uri.contains("sentinel-secret"))
    }

    @Test
    fun connectedInvitation_rejectsCredentialsAndNonLocalOrNonOriginBases() {
        assertNull(
            LocalShareProtocol.parseConnectedInvitation(
                Uri.parse(LocalShareProtocol.invitationUri(
                    LocalShareProtocol.Invitation(
                        "http://192.168.49.1:4949", "CruxCoach", "sentinel-secret",
                    ),
                )),
            ),
        )
        assertNull(
            LocalShareProtocol.parseConnectedInvitation(
                Uri.parse("cruxcoach://offline-share?base=https%3A%2F%2Fexample.com"),
            ),
        )
        assertNull(
            LocalShareProtocol.parseConnectedInvitation(
                Uri.parse("cruxcoach://offline-share?base=http%3A%2F%2F192.168.49.1%3A4949%2Fpath"),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            LocalShareProtocol.connectedInvitationUri("http://8.8.8.8:4949")
        }
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
        assertTrue(landing.contains("base="))
        assertFalse(landing.contains("ssid=", ignoreCase = true))
        assertFalse(landing.contains("password=", ignoreCase = true))
        assertFalse(landing.contains("sentinel-secret"))
        assertFalse(landing.contains("location.href=openAppUri"))
        assertTrue(landing.contains("No need to return to this page"))
        assertFalse(landing.contains("/board.db"))
        assertFalse(landing.contains("import-board-db"))

        val catchAllConnection = URL("http://127.0.0.1:$port/unrecognised")
            .openConnection() as HttpURLConnection
        val catchAllLanding = catchAllConnection.inputStream.bufferedReader().use { it.readText() }
        catchAllConnection.disconnect()
        assertTrue(catchAllLanding.contains("href=\"cruxcoach://offline-share"))
        assertFalse(catchAllLanding.contains("ssid=", ignoreCase = true))
        assertFalse(catchAllLanding.contains("password=", ignoreCase = true))

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
    fun stalledHeaderClientIsClosedAtTheAbsoluteDeadline() {
        val apk = File(tempDir, "stalled.apk").apply { writeBytes(ByteArray(1024)) }
        val stopped = CountDownLatch(1)
        server = LocalApkServer(apkFile = apk, autoShutdownMs = 120L).also {
            it.onAutoShutdown = { stopped.countDown() }
        }
        val port = server!!.start(port = 0, hostIp = "127.0.0.1")
        val stalled = Socket("127.0.0.1", port).apply { soTimeout = 1_000 }

        // Send no request line. The server thread is blocked in readLine(),
        // but the hard session deadline owns and closes this accepted socket.
        assertTrue(stopped.await(1, TimeUnit.SECONDS))
        val closedByServer = runCatching { stalled.getInputStream().read() }
            .fold(onSuccess = { it == -1 }, onFailure = { true })
        stalled.close()

        assertTrue(closedByServer)
    }

    @Test
    fun overlongRequestLineIsClosedBeforeItCanGrowWithoutBound() {
        val apk = File(tempDir, "bounded-request.apk").apply { writeText("apk") }
        server = LocalApkServer(apkFile = apk)
        val port = server!!.start(port = 0, hostIp = "127.0.0.1")
        val client = Socket("127.0.0.1", port).apply { soTimeout = 2_000 }

        runCatching {
            client.getOutputStream().apply {
                write(ByteArray(9 * 1024) { 'A'.code.toByte() })
                flush()
            }
        }
        val closedWithoutResponse = runCatching { client.getInputStream().read() }
            .fold(onSuccess = { it == -1 }, onFailure = { true })
        client.close()

        assertTrue(closedWithoutResponse)
    }

    @Test
    fun excessiveHeaderCountIsRejectedWithoutServingTheRequest() {
        val apk = File(tempDir, "bounded-headers.apk").apply { writeText("apk") }
        server = LocalApkServer(apkFile = apk)
        val port = server!!.start(port = 0, hostIp = "127.0.0.1")
        val client = Socket("127.0.0.1", port).apply { soTimeout = 2_000 }
        val request = buildString {
            append("GET / HTTP/1.1\r\n")
            repeat(33) { append("X-Test-$it: value\r\n") }
            append("\r\n")
        }

        client.getOutputStream().apply {
            write(request.toByteArray(Charsets.US_ASCII))
            flush()
        }
        val closedWithoutResponse = runCatching { client.getInputStream().read() }
            .fold(onSuccess = { it == -1 }, onFailure = { true })
        client.close()

        assertTrue(closedWithoutResponse)
    }

    @Test
    fun totalRequestHeadIsBoundedEvenWhenLinesAndCountAreIndividuallyValid() {
        val apk = File(tempDir, "bounded-total-head.apk").apply { writeText("apk") }
        server = LocalApkServer(apkFile = apk)
        val port = server!!.start(port = 0, hostIp = "127.0.0.1")
        val client = Socket("127.0.0.1", port).apply { soTimeout = 2_000 }
        val request = buildString {
            append("GET / HTTP/1.1\r\n")
            repeat(32) { index ->
                append("X-Test-$index: ")
                append("v".repeat(600))
                append("\r\n")
            }
            append("\r\n")
        }

        runCatching {
            client.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
        }
        val closedWithoutResponse = runCatching { client.getInputStream().read() }
            .fold(onSuccess = { it == -1 }, onFailure = { true })
        client.close()

        assertTrue(closedWithoutResponse)
    }

    @Test
    fun optionalSessionHeaderBindsArtifactsWithoutBreakingHeaderlessV1Clients() {
        val apk = File(tempDir, "session.apk").apply { writeText("apk") }
        val completed = CountDownLatch(1)
        val bulkStarted = CountDownLatch(1)
        server = LocalApkServer(apkFile = apk).also {
            it.onReceiverComplete = { completed.countDown() }
            it.onBulkTransferStarted = { bulkStarted.countDown() }
        }
        val port = server!!.start(port = 0, hostIp = "127.0.0.1")
        val network = mockk<Network>()
        every { network.socketFactory } returns SocketFactory.getDefault()
        val client = LocalShareClient()
        val manifest = client.fetchManifest(network, "http://127.0.0.1:$port")

        // A stale/other manifest is rejected before an artifact request can
        // arm work or a completion request can invoke the callback.
        assertFailsWith<ShareSessionChangedException> {
            client.requestSnapshotBuild(
                network,
                "http://127.0.0.1:$port",
                manifest.protocolVersion,
                "ffffffff-ffff-ffff-ffff-ffffffffffff",
            )
        }
        assertFalse(bulkStarted.await(150, TimeUnit.MILLISECONDS))
        assertFailsWith<ShareSessionChangedException> {
            client.notifyDownloadComplete(
                network,
                "http://127.0.0.1:$port",
                "ffffffff-ffff-ffff-ffff-ffffffffffff",
            )
        }
        assertFalse(completed.await(150, TimeUnit.MILLISECONDS))

        // Missing remains the immutable old-client behavior; matching binds
        // every modern follow-up to the manifest the user accepted.
        client.notifyDownloadComplete(network, "http://127.0.0.1:$port")
        assertTrue(completed.await(1, TimeUnit.SECONDS))
        assertEquals(
            manifest.sessionId,
            client.fetchManifest(
                network,
                "http://127.0.0.1:$port",
                protocolVersion = manifest.protocolVersion,
                expectedSessionId = manifest.sessionId,
            ).sessionId,
        )
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
    fun manifestBindsProtocolVersionToItsBoardArtifactPath() {
        val hash = "c".repeat(64)
        fun ready(protocol: Int, path: String) = """
            {
              "protocolVersion": $protocol,
              "sessionId": "01234567-89ab-cdef-0123-456789abcdef",
              "apk": {"path":"/CruxCoach.apk","versionCode":8,"versionName":"0.2.2",
                      "sizeBytes":42,"sha256":"$hash"},
              "board": {"status":"ready","path":"$path","compression":"gzip",
                        "sizeBytes":20,"sha256":"$hash","uncompressedSizeBytes":100,
                        "uncompressedSha256":"$hash","schemaVersion":27,"catalogues":[]}
            }
        """.trimIndent()

        assertEquals(
            LocalShareProtocol.BOARD_PATH,
            LocalShareProtocol.parseManifest(
                ready(LocalShareProtocol.VERSION, LocalShareProtocol.BOARD_PATH),
            ).board!!.artifact.path,
        )
        assertEquals(
            LocalShareProtocol.V2_BOARD_PATH,
            LocalShareProtocol.parseManifest(
                ready(LocalShareProtocol.VERSION_V2, LocalShareProtocol.V2_BOARD_PATH),
            ).board!!.artifact.path,
        )
        assertFailsWith<IllegalArgumentException> {
            LocalShareProtocol.parseManifest(
                ready(LocalShareProtocol.VERSION, LocalShareProtocol.V2_BOARD_PATH),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LocalShareProtocol.parseManifest(
                ready(LocalShareProtocol.VERSION_V2, LocalShareProtocol.BOARD_PATH),
            )
        }
    }

    @Test
    fun receiverPrefersV2WhenThePeerSupportsIt() {
        val apk = File(tempDir, "v2.apk").apply { writeText("apk") }
        server = LocalApkServer(apkFile = apk)
        val port = server!!.start(port = 0, hostIp = "127.0.0.1")
        val network = mockk<Network>()
        every { network.socketFactory } returns SocketFactory.getDefault()

        val manifest = LocalShareClient().fetchManifest(
            network,
            "http://127.0.0.1:$port",
        )

        assertEquals(LocalShareProtocol.VERSION_V2, manifest.protocolVersion)
    }

    @Test
    fun receiverFallsBackWhenOldPeerReturnsLandingHtmlForUnknownV2Path() {
        val hash = "d".repeat(64)
        val v1Manifest = manifestJson(apkPath = LocalShareProtocol.APK_PATH, hash = hash)
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { socket ->
            val servedPaths = mutableListOf<String>()
            val serving = kotlin.concurrent.thread {
                repeat(2) { requestIndex ->
                    socket.accept().use { client ->
                        val input = client.getInputStream().bufferedReader()
                        servedPaths += input.readLine().split(' ')[1]
                        while (!input.readLine().isNullOrEmpty()) Unit
                        val body = if (requestIndex == 0) "<html>old landing page</html>" else v1Manifest
                        val bytes = body.toByteArray()
                        client.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray(),
                            )
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
            val network = mockk<Network>()
            every { network.socketFactory } returns SocketFactory.getDefault()

            val manifest = LocalShareClient().fetchManifest(
                network,
                "http://127.0.0.1:${socket.localPort}",
            )

            serving.join(2_000)
            assertEquals(LocalShareProtocol.VERSION, manifest.protocolVersion)
            assertEquals(
                listOf(LocalShareProtocol.V2_MANIFEST_PATH, LocalShareProtocol.MANIFEST_PATH),
                servedPaths,
            )
        }
    }

    @Test
    fun receiverFallsBackToV1OnlyWhenV2ManifestIsActuallyMissing() {
        val hash = "e".repeat(64)
        val v1Manifest = manifestJson(apkPath = LocalShareProtocol.APK_PATH, hash = hash)
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { socket ->
            val port = socket.localPort
            val servedPaths = mutableListOf<String>()
            val serving = thread {
                repeat(2) { requestIndex ->
                    socket.accept().use { client ->
                        val input = client.getInputStream().bufferedReader()
                        servedPaths += input.readLine().split(' ')[1]
                        while (!input.readLine().isNullOrEmpty()) Unit
                        val body = if (requestIndex == 0) ByteArray(0) else v1Manifest.toByteArray()
                        val status = if (requestIndex == 0) "404 Not Found" else "200 OK"
                        client.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray(),
                            )
                            write(body)
                            flush()
                        }
                    }
                }
            }
            val network = mockk<Network>()
            every { network.socketFactory } returns SocketFactory.getDefault()

            val manifest = LocalShareClient().fetchManifest(network, "http://127.0.0.1:$port")

            serving.join(2_000)
            assertEquals(LocalShareProtocol.VERSION, manifest.protocolVersion)
            assertEquals(
                listOf(LocalShareProtocol.V2_MANIFEST_PATH, LocalShareProtocol.MANIFEST_PATH),
                servedPaths,
            )
        }
    }

    @Test
    fun malformedV2JsonDoesNotSilentlyDowngrade() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { socket ->
            val servedPaths = mutableListOf<String>()
            val serving = kotlin.concurrent.thread {
                socket.accept().use { client ->
                    val input = client.getInputStream().bufferedReader()
                    servedPaths += input.readLine().split(' ')[1]
                    while (!input.readLine().isNullOrEmpty()) Unit
                    val bytes = "{ definitely-not-a-manifest".toByteArray()
                    client.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray(),
                        )
                        write(bytes)
                        flush()
                    }
                }
            }
            val network = mockk<Network>()
            every { network.socketFactory } returns SocketFactory.getDefault()

            assertFailsWith<java.io.IOException> {
                LocalShareClient().fetchManifest(
                    network,
                    "http://127.0.0.1:${socket.localPort}",
                )
            }
            serving.join(2_000)
            assertEquals(listOf(LocalShareProtocol.V2_MANIFEST_PATH), servedPaths)
        }
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
