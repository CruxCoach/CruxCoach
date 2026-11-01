package com.cruxcoach.android.data.blossom

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class BlossomManifestRetryTest {

    private val dTag = "cruxcoach/tension-db"
    /** Mirrors the production per-relay bound in fetchManifestFromRelay. */
    private val relayTimeoutMs = 15_000L
    private val relays = listOf("wss://a.example", "wss://b.example", "wss://c.example")

    private fun manifest(
        createdAt: Long,
        eventId: String = createdAt.toString(16).padStart(64, '0'),
        eventCreatedAt: Long = createdAt,
    ) = BlossomManifest(
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
        eventCreatedAt = eventCreatedAt,
        eventId = eventId,
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

    @Test
    fun `NIP-01 tie picks the lower event id regardless of relay order`() = runTest {
        val higherId = "f".repeat(64)
        val lowerId = "0".repeat(64)
        val result = BlossomSyncManager.fetchManifestWithRetry(dTag, relays) { relay ->
            when (relay) {
                "wss://a.example" -> manifest(100, eventId = higherId)
                "wss://b.example" -> manifest(100, eventId = lowerId)
                else -> null
            }
        }
        assertEquals(lowerId, result.eventId)
    }

    @Test
    fun `Nostr envelope timestamp wins over manifest content timestamp`() = runTest {
        val result = BlossomSyncManager.fetchManifestWithRetry(dTag, relays) { relay ->
            when (relay) {
                "wss://a.example" -> manifest(createdAt = 999, eventCreatedAt = 100)
                "wss://b.example" -> manifest(createdAt = 1, eventCreatedAt = 101)
                else -> null
            }
        }
        assertEquals(101L, result.eventCreatedAt)
        assertEquals(1L, result.createdAt)
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

    /**
     * Issue #39: a relay that accepts the connection but never answers used to
     * hold every catalogue for the full relay timeout. Once two relays have
     * answered, the third only gets the grace period.
     */
    @Test
    fun `stops waiting for a hanging relay once two manifests are in`() = runTest {
        var hangingRelayCancelled = false
        val result = BlossomSyncManager.fetchManifestWithRetry(dTag, relays) { relay ->
            when (relay) {
                "wss://a.example" -> { delay(100); manifest(100) }
                "wss://b.example" -> { delay(200); manifest(200) }
                else -> try {
                    withTimeout(relayTimeoutMs) { awaitCancellation() }
                } catch (e: CancellationException) {
                    if (e !is TimeoutCancellationException) hangingRelayCancelled = true
                    throw e
                }
            }
        }
        assertEquals(200L, result.createdAt)
        assertEquals(
            "returns after the grace period, not after the relay timeout",
            200L + BlossomSyncManager.MANIFEST_GRACE_MS,
            testScheduler.currentTime,
        )
        assertTrue("the hanging relay is cancelled", hangingRelayCancelled)
    }

    @Test
    fun `a newer manifest within the grace period still wins`() = runTest {
        val result = BlossomSyncManager.fetchManifestWithRetry(dTag, relays) { relay ->
            when (relay) {
                "wss://a.example" -> { delay(100); manifest(100) }
                "wss://b.example" -> { delay(200); manifest(200) }
                else -> { delay(1_500); manifest(300) }
            }
        }
        assertEquals(300L, result.createdAt)
        assertEquals("no wait beyond the last answer", 1_500L, testScheduler.currentTime)
    }

    /**
     * Below two manifests the pass keeps the full relay timeout, so a slow
     * mobile connection still gets its answer. An empty answer or an error
     * does not count towards the two.
     */
    @Test
    fun `waits for a slow relay while fewer than two manifests are in`() = runTest {
        val fourRelays = relays + "wss://d.example"
        val result = BlossomSyncManager.fetchManifestWithRetry(dTag, fourRelays) { relay ->
            when (relay) {
                "wss://a.example" -> { delay(100); manifest(100) }
                "wss://b.example" -> { delay(50); null }
                "wss://c.example" -> { delay(80); throw java.io.IOException("relay 503") }
                else -> { delay(10_000); manifest(300) }
            }
        }
        assertEquals(300L, result.createdAt)
        assertEquals(10_000L, testScheduler.currentTime)
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
