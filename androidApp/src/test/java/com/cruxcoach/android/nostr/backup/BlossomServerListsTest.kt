package com.cruxcoach.android.nostr.backup

import android.app.Application
import com.cruxcoach.android.nostr.UrlValidation
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.Collections

/**
 * The Blossom servers of backups and photos since cdn.hzrd149.com stopped taking uploads (2026-11-10): nothing
 * is uploaded there any more, and what an earlier version put there is still read and deleted there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BlossomServerListsTest {
    private val retired = "https://cdn.hzrd149.com"

    @Test fun `uploads go to three servers and never to the retired one`() {
        assertEquals(
            listOf("https://nostr.download", "https://blossom.data.haus", "https://blossom.yakihonne.com"),
            BlossomUploader.DEFAULT_SERVERS,
        )
        assertFalse(retired in BlossomUploader.DEFAULT_SERVERS)
        assertEquals(listOf(retired), BlossomUploader.RETIRED_SERVERS)
    }

    @Test fun `a blob of unknown origin is looked for on the upload servers first, then on the retired one`() {
        assertEquals(BlossomUploader.DEFAULT_SERVERS + retired, BlossomUploader.KNOWN_SERVERS)
        assertEquals(BlossomUploader.KNOWN_SERVERS.distinct(), BlossomUploader.KNOWN_SERVERS)
        assertTrue(BlossomUploader.KNOWN_SERVERS.all { UrlValidation.isValidBlossom(it) && !it.endsWith("/") })
    }

    @Test fun `the previous backup is deleted on the retired server too and every server is asked once`() {
        val own = "https://blossom.example.org"
        assertEquals(
            listOf(own) + BlossomUploader.DEFAULT_SERVERS + retired,
            BackupRepository.cleanupServers(listOf(own) + BlossomUploader.DEFAULT_SERVERS),
        )
        // Someone who names the retired server in the own server list: it keeps its place and is not asked twice.
        assertEquals(
            listOf(retired, "https://nostr.download"),
            BackupRepository.cleanupServers(listOf(retired, "https://nostr.download")),
        )
    }

    @Test fun `a restore reads the servers its pointer names, also one that is no default any more`() {
        assertEquals(
            listOf("https://nostr.download", retired),
            BackupRepository.restoreServers(pointer(servers = listOf("https://nostr.download", retired))),
        )
        // An address that fails the shared gate is never dialled.
        assertEquals(
            listOf(retired),
            BackupRepository.restoreServers(pointer(servers = listOf("http://plain.example.org", retired))),
        )
    }

    @Test fun `a backup that lies only on the retired server is found and downloaded there`() = runTest {
        val blob = ByteArray(4096) { (it * 31).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(blob).joinToString("") { "%02x".format(it) }
        val asked = Collections.synchronizedList(mutableListOf<String>())
        // The server that took the upload of 0.2.4 has the blob; the other default of that version lost it.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            asked += "${chain.request().method} ${url.host}"
            val found = url.host == "cdn.hzrd149.com" && url.encodedPath == "/$sha"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (found) 200 else 404).message(if (found) "OK" else "Not Found")
                .body((if (found) blob else ByteArray(0)).toResponseBody(null)).build()
        }.build()
        val uploader = BlossomUploader(client, mockk(), mockk())
        val servers = BackupRepository.restoreServers(
            pointer(sha256 = sha, size = blob.size.toLong(), servers = listOf("https://nostr.download", retired)),
        )

        assertArrayEquals(blob, uploader.download(sha, servers, maxBytes = blob.size + 1024L))
        assertEquals(listOf("GET nostr.download", "GET cdn.hzrd149.com"), asked.toList())
        // The check before the restore dialog ("is the blob still there?") sees it as well.
        assertTrue(uploader.verifyExists(sha, servers))
        // Without the retired server in the pointer there would be nothing to restore.
        assertFalse(uploader.verifyExists(sha, listOf("https://nostr.download")))
    }

    private fun pointer(
        sha256: String = "a".repeat(64),
        size: Long = 1024,
        servers: List<String>,
    ) = BackupPointer(
        sha256 = sha256,
        size = size,
        servers = servers,
        updatedAt = 1_700_000_000L,
        deviceId = "device-1",
        categories = listOf("ASCENTS"),
    )
}
