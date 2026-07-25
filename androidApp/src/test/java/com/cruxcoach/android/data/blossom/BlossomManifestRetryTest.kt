package com.cruxcoach.android.data.blossom

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [BlossomSyncManager.fetchManifestWithRetry].
 *
 * Regression cover for "some boards fail on the initial download": a fresh
 * install syncs seven catalogues back to back and each one used to get a
 * single shot at its manifest query, so a catalogue that happened to land in
 * a bad relay window failed for the whole run while its siblings succeeded.
 *
 * The relay call is injected, so these tests exercise the retry policy itself
 * without a live relay. Robolectric is only here for android.util.Log;
 * `runTest` skips the backoff delays.
 */
// Plain android.app.Application: the production one boots the Hilt graph,
// which reaches NostrKeyStore -> androidx.security MasterKey -> AndroidKeyStore,
// and that provider does not exist in the Robolectric JVM.
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class BlossomManifestRetryTest {

    private val dTag = "cruxcoach/tension-db"
    private val relays = listOf("wss://a.example", "wss://b.example", "wss://c.example")

    private fun manifest(createdAt: Long) = BlossomManifest(
        v = 1,
        board = "tension",
        productId = 1,
        createdAt = createdAt,
        compression = "zstd",
        chunks = listOf(
            BlossomChunk(
                name = "tension-full",
                type = "snapshot",
                sha256 = "a".repeat(64),
                size = 1024L,
                urls = listOf("https://mirror.example/blob"),
            )
        ),
    )

    @Test
    fun `returns the manifest when a relay answers on the first pass`() = runTest {
        var calls = 0
        val result = BlossomSyncManager.fetchManifestWithRetry(dTag, relays) {
            calls++
            manifest(100)
        }
        assertEquals(100L, result.createdAt)
        assertEquals("one pass only", relays.size, calls)
    }

    @Test
    fun `picks the freshest manifest when relays disagree`() = runTest {
        val result = BlossomSyncManager.fetchManifestWithRetry(dTag, relays) { relay ->
            when (relay) {
                "wss://a.example" -> manifest(100)
                "wss://b.example" -> manifest(300)
                else -> manifest(200)
            }
        }
        assertEquals("newest wins regardless of relay order", 300L, result.createdAt)
    }

    /** The actual regression: one bad window must not fail the board. */
    @Test
    fun `recovers when the entire first pass fails`() = runTest {
        var pass = 0
        val result = BlossomSyncManager.fetchManifestWithRetry(dTag, relays) { relay ->
            if (relay == relays.first()) pass++
            if (pass <= 1) throw java.io.IOException("relay 503") else manifest(42)
        }
        assertEquals(42L, result.createdAt)
    }

    /**
     * A relay answering "I have no such event" is a miss, not a failure — and
     * it happens for real: publishes routinely reach only 2 of 3 relays, so
     * the third is legitimately empty until it catches up.
     */
    @Test
    fun `recovers when the first pass finds no event anywhere`() = runTest {
        var pass = 0
        val result = BlossomSyncManager.fetchManifestWithRetry(dTag, relays) { relay ->
            if (relay == relays.first()) pass++
            if (pass <= 1) null else manifest(7)
        }
        assertEquals(7L, result.createdAt)
    }

    @Test
    fun `gives up after the configured number of passes`() = runTest {
        var calls = 0
        val error = assertThrows(BlossomSyncException::class.java) {
            runBlocking {
                BlossomSyncManager.fetchManifestWithRetry(dTag, relays, passes = 3) {
                    calls++
                    throw java.io.IOException("relay down")
                }
            }
        }
        assertEquals("every relay tried on every pass", relays.size * 3, calls)
        // The d-tag must be in the message — with seven catalogues syncing,
        // a bare "manifest fetch failed" cannot be attributed to a board.
        assertTrue(
            "message names the board, got: ${error.message}",
            error.message!!.contains(dTag)
        )
    }

    /** Real cancellation must propagate, not be retried into oblivion. */
    @Test
    fun `does not swallow cancellation`() = runTest {
        var calls = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                BlossomSyncManager.fetchManifestWithRetry(dTag, relays) {
                    calls++
                    throw CancellationException("scope gone")
                }
            }
        }
        assertTrue("aborted during the first pass, got $calls calls", calls <= relays.size)
    }
}
