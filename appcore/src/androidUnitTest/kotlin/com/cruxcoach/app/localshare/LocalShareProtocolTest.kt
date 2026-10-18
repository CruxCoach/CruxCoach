package com.cruxcoach.app.localshare

import com.cruxcoach.app.sync.JvmFileSystem
import com.cruxcoach.app.testing.JvmHashing
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from Android `LocalShareProtocolTest` (receiver cases), plus the resume edges of `LocalShareClient`. */
class LocalShareProtocolTest {
    private val dir: File = Files.createTempDirectory("local-share").toFile()
    private val sender = FakeShareSender()
    private val files = JvmFileSystem(dir)
    private val client = LocalShareClient(sender, files, JvmHashing, retryDelayMs = 1, pollDelayMs = 1)
    private val base = "http://192.168.49.1:4949"

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun invitation_roundTripsCredentialsWithoutLandingPage() {
        val invitation = LocalShareProtocol.Invitation(
            baseUrl = "http://192.168.49.1:4949",
            ssid = "DIRECT-ab-CruxCoach1234",
            password = "correct horse battery staple",
        )
        assertEquals(invitation, LocalShareProtocol.parseInvitation(LocalShareProtocol.invitationUri(invitation)))
    }

    @Test
    fun connectedInvitation_roundTripsOnlyThePrivateOrigin() {
        val uri = LocalShareProtocol.connectedInvitationUri("http://192.168.49.1:4949/")
        assertEquals(LocalShareProtocol.ConnectedInvitation("http://192.168.49.1:4949"), LocalShareProtocol.parseConnectedInvitation(uri))
        assertTrue(uri.contains("base="))
        assertFalse(uri.contains("ssid", ignoreCase = true))
        assertFalse(uri.contains("password", ignoreCase = true))
    }

    @Test
    fun connectedInvitation_rejectsCredentialsAndNonLocalOrNonOriginBases() {
        assertNull(
            LocalShareProtocol.parseConnectedInvitation(
                LocalShareProtocol.invitationUri(LocalShareProtocol.Invitation("http://192.168.49.1:4949", "CruxCoach", "sentinel-secret")),
            ),
        )
        assertNull(LocalShareProtocol.parseConnectedInvitation("cruxcoach://offline-share?base=https%3A%2F%2Fexample.com"))
        assertNull(LocalShareProtocol.parseConnectedInvitation("cruxcoach://offline-share?base=http%3A%2F%2F192.168.49.1%3A4949%2Fpath"))
        assertNull(LocalShareProtocol.parseConnectedInvitation("cruxcoach://offline-share?base=http%3A%2F%2F8.8.8.8%3A4949"))
        assertFailsWith<IllegalArgumentException> { LocalShareProtocol.connectedInvitationUri("http://8.8.8.8:4949") }
    }

    @Test
    fun invitation_rejectsInvalidCredentials() {
        assertNull(LocalShareProtocol.parseInvitation("cruxcoach://offline-share?base=http%3A%2F%2F192.168.49.1&ssid=x&password=short"))
        assertNull(
            LocalShareProtocol.parseInvitation(
                "cruxcoach://offline-share?base=http%3A%2F%2F192.168.49.1%3A4949%2Fevil&ssid=valid&password=12345678",
            ),
        )
    }

    @Test
    fun originsMustBePlainHttpWithAPort() {
        assertEquals("http://192.168.49.1:4949", LocalShareProtocol.normalizeHttpOrigin(" http://192.168.49.1:4949/ "))
        assertNull(LocalShareProtocol.normalizeHttpOrigin("http://192.168.49.1"))
        assertNull(LocalShareProtocol.normalizeHttpOrigin("https://192.168.49.1:4949"))
        assertNull(LocalShareProtocol.normalizeHttpOrigin("http://user@192.168.49.1:4949"))
        assertNull(LocalShareProtocol.normalizeHttpOrigin("http://192.168.49.1:4949/?a=b"))
        assertNull(LocalShareProtocol.normalizeHttpOrigin("http://192.168.49.1:4949#x"))
        assertTrue(LocalShareProtocol.isPrivateIpv4("10.1.2.3"))
        assertTrue(LocalShareProtocol.isPrivateIpv4("172.31.0.1"))
        assertFalse(LocalShareProtocol.isPrivateIpv4("172.32.0.1"))
        assertFalse(LocalShareProtocol.isPrivateIpv4("192.169.0.1"))
        assertFalse(LocalShareProtocol.isPrivateIpv4("192.168.0.256"))
        assertFalse(LocalShareProtocol.isPrivateIpv4("example.com"))
    }

    @Test
    fun manifestParserRejectsRemoteOrTraversalArtifactPaths() {
        assertFailsWith<IllegalArgumentException> { LocalShareProtocol.parseManifest(manifestJson(apkPath = "https://evil.example/app.apk")) }
        assertFailsWith<IllegalArgumentException> { LocalShareProtocol.parseManifest(manifestJson(apkPath = "/../app.apk")) }
        assertFailsWith<IllegalArgumentException> { LocalShareProtocol.parseManifest(manifestJson(apkPath = "/app.apk\\r\\nX-Evil: yes")) }
    }

    @Test
    fun manifestParsesEveryAdvertisedBoardCatalogue() {
        val hash = "b".repeat(64)
        val json = manifestJson(
            protocol = LocalShareProtocol.VERSION,
            board = """{
                "status": "ready", "path": "/board.db.gz", "compression": "gzip",
                "sizeBytes": 20, "sha256": "$hash", "uncompressedSizeBytes": 100,
                "uncompressedSha256": "$hash", "schemaVersion": 25,
                "catalogues": [
                  {"boardBrand":"kilter","climbCount":174000},
                  {"boardBrand":"moonboard","climbCount":98000},
                  {"boardBrand":"tension","climbCount":12000}
                ]}""",
        )
        val board = LocalShareProtocol.parseManifest(json).board!!
        assertEquals(listOf("kilter", "moonboard", "tension"), board.catalogues.map { it.boardBrand })
        assertEquals(98_000L, board.catalogues[1].climbCount)
    }

    @Test
    fun manifestBindsProtocolVersionToItsBoardArtifactPath() {
        val hash = "c".repeat(64)
        fun ready(protocol: Int, path: String) = manifestJson(
            protocol = protocol,
            board = """{"status":"ready","path":"$path","compression":"gzip","sizeBytes":20,"sha256":"$hash",
                "uncompressedSizeBytes":100,"uncompressedSha256":"$hash","schemaVersion":27,"catalogues":[]}""",
        )
        assertEquals(
            LocalShareProtocol.BOARD_PATH,
            LocalShareProtocol.parseManifest(ready(LocalShareProtocol.VERSION, LocalShareProtocol.BOARD_PATH)).board!!.artifact.path,
        )
        assertEquals(
            LocalShareProtocol.V2_BOARD_PATH,
            LocalShareProtocol.parseManifest(ready(LocalShareProtocol.VERSION_V2, LocalShareProtocol.V2_BOARD_PATH)).board!!.artifact.path,
        )
        assertFailsWith<IllegalArgumentException> { LocalShareProtocol.parseManifest(ready(LocalShareProtocol.VERSION, LocalShareProtocol.V2_BOARD_PATH)) }
        assertFailsWith<IllegalArgumentException> { LocalShareProtocol.parseManifest(ready(LocalShareProtocol.VERSION_V2, LocalShareProtocol.BOARD_PATH)) }
    }

    @Test
    fun manifestRejectsBadHashesBrandsAndUnknownVersions() {
        assertFailsWith<IllegalArgumentException> { LocalShareProtocol.parseManifest(manifestJson(apkHash = "z".repeat(64))) }
        assertFailsWith<IllegalArgumentException> { LocalShareProtocol.parseManifest(manifestJson(protocol = 3)) }
        assertFailsWith<IllegalArgumentException> { LocalShareProtocol.parseManifest("{ definitely-not-a-manifest") }
        assertFailsWith<IllegalArgumentException> {
            LocalShareProtocol.parseManifest(
                manifestJson(board = """{"status":"preparing","catalogues":[{"boardBrand":"Kilter Board","climbCount":1}]}"""),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LocalShareProtocol.parseManifest(
                manifestJson(
                    board = """{"status":"preparing","catalogues":[{"boardBrand":"kilter","climbCount":1},{"boardBrand":"kilter","climbCount":2}]}""",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> { LocalShareProtocol.parseManifest(manifestJson(board = """{"status":"maybe"}""")) }
    }

    @Test
    fun receiverRejectsOversizedManifestBeforeRequestingArtifact() {
        assertEquals(42L, LocalShareProtocol.parseManifest(manifestJson()).apk.sizeBytes)
        assertFailsWith<LimitExceeded> { LocalShareProtocol.parseManifest(manifestJson(apkSize = LocalTransferLimits.MAX_APK_BYTES + 1)) }
    }

    @Test
    fun `a preparing sender may already name its catalogues`() {
        val plain = LocalShareProtocol.parseManifest(manifestJson(protocol = LocalShareProtocol.VERSION))
        assertEquals(null, plain.board)
        assertEquals(emptyList(), plain.declaredCatalogues)
        val declaring = LocalShareProtocol.parseManifest(
            manifestJson(board = """{ "status": "preparing", "catalogues": [{"boardBrand": "moonboard", "climbCount": 289231}] }"""),
        )
        assertEquals(null, declaring.board)
        assertEquals(listOf(LocalShareProtocol.BoardCatalogue("moonboard", 289231L)), declaring.declaredCatalogues)
    }

    @Test
    fun receiverPrefersV2WhenThePeerSupportsIt() = runTest {
        sender.json(LocalShareProtocol.V2_MANIFEST_PATH, manifestJson(protocol = LocalShareProtocol.VERSION_V2))
        sender.json(LocalShareProtocol.MANIFEST_PATH, manifestJson(protocol = LocalShareProtocol.VERSION))
        assertEquals(LocalShareProtocol.VERSION_V2, client.fetchManifest(base).protocolVersion)
        assertEquals(listOf(LocalShareProtocol.V2_MANIFEST_PATH), sender.requests.map { it.path })
    }

    @Test
    fun receiverFallsBackWhenOldPeerReturnsLandingHtmlForUnknownV2Path() = runTest {
        sender.json(LocalShareProtocol.V2_MANIFEST_PATH, "<html>old landing page</html>")
        sender.json(LocalShareProtocol.MANIFEST_PATH, manifestJson(protocol = LocalShareProtocol.VERSION))
        assertEquals(LocalShareProtocol.VERSION, client.fetchManifest(base).protocolVersion)
        assertEquals(listOf(LocalShareProtocol.V2_MANIFEST_PATH, LocalShareProtocol.MANIFEST_PATH), sender.requests.map { it.path })
    }

    @Test
    fun receiverFallsBackToV1OnlyWhenV2ManifestIsActuallyMissing() = runTest {
        sender.json(LocalShareProtocol.MANIFEST_PATH, manifestJson(protocol = LocalShareProtocol.VERSION))
        assertEquals(LocalShareProtocol.VERSION, client.fetchManifest(base).protocolVersion)
        assertEquals(listOf(LocalShareProtocol.V2_MANIFEST_PATH, LocalShareProtocol.MANIFEST_PATH), sender.requests.map { it.path })
    }

    @Test
    fun malformedV2JsonDoesNotSilentlyDowngrade() = runTest {
        sender.json(LocalShareProtocol.V2_MANIFEST_PATH, "{ definitely-not-a-manifest")
        sender.json(LocalShareProtocol.MANIFEST_PATH, manifestJson(protocol = LocalShareProtocol.VERSION))
        assertFailsWith<InvalidManifestException> { client.fetchManifest(base) }
        assertEquals(listOf(LocalShareProtocol.V2_MANIFEST_PATH), sender.requests.map { it.path })
    }

    @Test
    fun aManifestOfAnotherSessionIsAChangedSender() = runTest {
        sender.json(LocalShareProtocol.V2_MANIFEST_PATH, manifestJson())
        assertFailsWith<ShareSessionChangedException> {
            client.fetchManifest(base, LocalShareProtocol.VERSION_V2, expectedSessionId = "fedcba98-7654-3210-fedc-ba9876543210")
        }
        sender.conflict = true
        assertFailsWith<ShareSessionChangedException> { client.fetchManifest(base, LocalShareProtocol.VERSION_V2) }
    }

    @Test
    fun receiverResumesPartialArtifactAndVerifiesIt() = runTest {
        val bytes = ByteArray(192 * 1024) { index -> (index % 239).toByte() }
        sender.artifacts[LocalShareProtocol.V2_BOARD_PATH] = bytes
        val target = File(dir, "received.gz")
        File(target.path + ".part").writeBytes(bytes.copyOfRange(0, 73_111))

        client.downloadResumable(base, artifact(bytes), target.path, LocalShareProtocol.VERSION_V2, null) { _, _ -> }

        assertContentEquals(bytes, target.readBytes())
        assertFalse(File(target.path + ".part").exists())
        assertEquals("bytes=73111-", sender.requests.single().headers["Range"])
    }

    @Test
    fun aDroppedTransferResumesWhereItStopped() = runTest {
        val bytes = ByteArray(300 * 1024) { index -> (index % 251).toByte() }
        sender.artifacts[LocalShareProtocol.V2_BOARD_PATH] = bytes
        sender.dropAfterBytes = 100_000
        val target = File(dir, "dropped.gz")

        client.downloadResumable(base, artifact(bytes), target.path, LocalShareProtocol.VERSION_V2, "01234567-89ab-cdef-0123-456789abcdef") { _, _ -> }

        assertContentEquals(bytes, target.readBytes())
        assertEquals(listOf(null, "bytes=100000-"), sender.requests.map { it.headers["Range"] })
        assertTrue(sender.requests.all { it.headers[LocalShareProtocol.SESSION_HEADER] == "01234567-89ab-cdef-0123-456789abcdef" })
    }

    @Test
    fun aSenderThatIgnoresTheRangeStartsTheFileOver() = runTest {
        val bytes = ByteArray(50 * 1024) { index -> (index % 13).toByte() }
        sender.artifacts[LocalShareProtocol.V2_BOARD_PATH] = bytes
        sender.ignoreRange = true
        val target = File(dir, "restart.gz")
        File(target.path + ".part").writeBytes(ByteArray(10_000) { 7 })

        client.downloadResumable(base, artifact(bytes), target.path, LocalShareProtocol.VERSION_V2, null) { _, _ -> }

        assertContentEquals(bytes, target.readBytes())
    }

    @Test
    fun aResumeAtTheWrongOffsetIsNeverAppended() = runTest {
        val bytes = ByteArray(50 * 1024) { index -> (index % 17).toByte() }
        sender.artifacts[LocalShareProtocol.V2_BOARD_PATH] = bytes
        sender.wrongRangeStart = 0
        val target = File(dir, "wrong.gz")
        File(target.path + ".part").writeBytes(bytes.copyOfRange(0, 1000))

        assertFailsWith<LocalShareIoException> {
            client.downloadResumable(base, artifact(bytes), target.path, LocalShareProtocol.VERSION_V2, null) { _, _ -> }
        }
        assertFalse(target.exists())
        assertEquals(LocalShareClient.MAX_TRANSFER_FAILURES + 1, sender.requests.size)
        assertEquals(1000L, File(target.path + ".part").length())
    }

    @Test
    fun aBodyThatFailsItsHashIsDeleted() = runTest {
        val bytes = ByteArray(4096) { 1 }
        sender.artifacts[LocalShareProtocol.V2_BOARD_PATH] = bytes
        val target = File(dir, "forged.gz")

        assertFailsWith<VerificationFailed> {
            client.downloadResumable(
                base, LocalShareProtocol.Artifact(LocalShareProtocol.V2_BOARD_PATH, bytes.size.toLong(), "0".repeat(64)),
                target.path, LocalShareProtocol.VERSION_V2, null,
            ) { _, _ -> }
        }
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun aChangedSessionIsNeverRetried() = runTest {
        val bytes = ByteArray(4096) { 2 }
        sender.artifacts[LocalShareProtocol.V2_BOARD_PATH] = bytes
        sender.conflict = true
        assertFailsWith<ShareSessionChangedException> {
            client.downloadResumable(base, artifact(bytes), File(dir, "x.gz").path, LocalShareProtocol.VERSION_V2, null) { _, _ -> }
        }
        assertEquals(1, sender.requests.size)
    }

    @Test
    fun directOversizedArtifactCannotBypassManifestGuardOrKeepItsPartial() = runTest {
        val target = File(dir, "oversize.gz")
        val part = File(target.path + ".part").apply { writeText("partial") }
        assertFailsWith<LimitExceeded> {
            client.downloadResumable(
                base,
                LocalShareProtocol.Artifact(LocalShareProtocol.V2_BOARD_PATH, LocalTransferLimits.MAX_COMPRESSED_DB_BYTES + 1, "aa".repeat(32)),
                target.path, LocalShareProtocol.VERSION_V2, null,
            ) { _, _ -> error("oversized transfer made progress") }
        }
        assertFalse(part.exists())
        assertFalse(target.exists())
        assertTrue(sender.requests.isEmpty())
    }

    @Test
    fun aFullDiskStopsBeforeTheFirstRequest() = runTest {
        val bytes = ByteArray(4096) { 3 }
        sender.artifacts[LocalShareProtocol.V2_BOARD_PATH] = bytes
        files.freeSpace = LocalTransferLimits.RESERVE_BYTES
        assertFailsWith<InsufficientSpace> {
            client.downloadResumable(base, artifact(bytes), File(dir, "full.gz").path, LocalShareProtocol.VERSION_V2, null) { _, _ -> }
        }
        assertTrue(sender.requests.isEmpty())
    }

    @Test
    fun gunzipIsOutputBounded() {
        val input = ByteArray(256 * 1024) { index -> (index % 251).toByte() }
        val compressed = File(dir, "input.db.gz").apply { writeBytes(gzip(input)) }
        val restored = File(dir, "restored.db")
        JvmFileGunzip.gunzip(compressed.path, restored.path, input.size.toLong()) { }
        assertContentEquals(input, restored.readBytes())
        assertFailsWith<LimitExceeded> { JvmFileGunzip.gunzip(compressed.path, File(dir, "big.db").path, input.size - 1L) { } }
    }

    private fun artifact(bytes: ByteArray) = LocalShareProtocol.Artifact(LocalShareProtocol.V2_BOARD_PATH, bytes.size.toLong(), sha256Hex(bytes))
}
