package com.cruxcoach.android.data.blossom

import android.app.Application
import android.content.Context
import com.cruxcoach.android.nostr.NostrEventPolicy
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Regression coverage for the per-track signed-manifest rollback guard. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = Application::class)
class BlossomManifestRollbackTest {
    private val nowSeconds = 1_700_000_000L
    private lateinit var context: Context
    private val prefsNames = listOf("rollback_kilter_test", "rollback_quantum_test")

    @Test
    fun `chunk storage estimate covers compressed output and import headroom`() {
        val compressed = 53_820_337L

        assertEquals(
            compressed * 5L + 128L * 1024 * 1024,
            BlossomSyncManager.requiredFreeBytesForChunk(compressed),
        )
        assertEquals(
            128L * 1024 * 1024,
            BlossomSyncManager.requiredFreeBytesForChunk(0L),
        )
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefsNames.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After
    fun tearDown() {
        prefsNames.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun `effective timestamp and acceptance use the same envelope ordering`() {
        val envelopeWins = manifest(contentCreatedAt = 900, eventCreatedAt = 200)
        val contentFallback = manifest(contentCreatedAt = 150, eventCreatedAt = 0)

        assertEquals(200L, BlossomSyncManager.effectiveTimestamp(envelopeWins))
        assertEquals(150L, BlossomSyncManager.effectiveTimestamp(contentFallback))
        assertFalse(BlossomSyncManager.isManifestAcceptable(envelopeWins, 201L, nowSeconds))
        assertTrue(BlossomSyncManager.isManifestAcceptable(envelopeWins, 200L, nowSeconds))
        assertTrue(BlossomSyncManager.isManifestAcceptable(envelopeWins, 199L, nowSeconds))
        assertTrue(BlossomSyncManager.isManifestAcceptable(envelopeWins, null, nowSeconds))

        val selected = BlossomSyncManager.selectPreferredManifest(
            listOf(envelopeWins, contentFallback),
        )
        assertEquals(envelopeWins, selected)
    }

    @Test
    fun `older replay is a non-destructive no-op and cannot lower watermark`() {
        val manager = manager(prefsNames[0], BlossomSyncManager.MANIFEST_D_TAG)
        val accepted = manifest(contentCreatedAt = 200, hash = "a".repeat(64))
        manager.saveCompletedManifest(accepted, accepted.chunks)

        val replay = manifest(contentCreatedAt = 100, hash = "b".repeat(64))
        assertFalse(manager.canApplyManifest(replay))
        assertTrue(manager.getChangedChunks(replay).isEmpty())
        manager.saveAcceptedManifestTimestamp(replay)

        val prefs = context.getSharedPreferences(prefsNames[0], Context.MODE_PRIVATE)
        assertEquals("a".repeat(64), prefs.getString("chunk_sha256_snapshot", null))
        assertEquals(200L, manager.lastAcceptedManifestTimestamp())
    }

    @Test
    fun `watermark advances only when caller records completed application`() {
        val manager = manager(prefsNames[0], BlossomSyncManager.MANIFEST_D_TAG)
        val old = manifest(contentCreatedAt = 100, hash = "a".repeat(64))
        manager.saveAcceptedManifestTimestamp(old)

        val newer = manifest(contentCreatedAt = 200, hash = "b".repeat(64))
        assertEquals(listOf("snapshot"), manager.getChangedChunks(newer).map { it.name })
        assertEquals("inspection alone must not advance", 100L, manager.lastAcceptedManifestTimestamp())

        manager.saveChunkHash("snapshot", newer.chunks.single().sha256)
        assertEquals("saving a partial chunk must not advance", 100L, manager.lastAcceptedManifestTimestamp())
        manager.saveCompletedManifest(newer, newer.chunks)
        assertEquals(200L, manager.lastAcceptedManifestTimestamp())
        assertEquals(
            newer.chunks.single().sha256,
            context.getSharedPreferences(prefsNames[0], Context.MODE_PRIVATE)
                .getString("chunk_sha256_snapshot", null),
        )

        val equalTimestampRetry = manifest(contentCreatedAt = 200, hash = "c".repeat(64))
        assertTrue(manager.canApplyManifest(equalTimestampRetry))
    }

    @Test
    fun `tracks are isolated and deliberate reset rearms first-run acceptance`() {
        val kilter = manager(prefsNames[0], BlossomSyncManager.MANIFEST_D_TAG)
        val quantum = manager(prefsNames[1], BlossomSyncManager.QUANTUM_D_TAG)
        kilter.saveAcceptedManifestTimestamp(manifest(contentCreatedAt = 300))

        val older = manifest(contentCreatedAt = 100)
        assertFalse(kilter.canApplyManifest(older))
        assertTrue(quantum.canApplyManifest(older))
        assertNull(quantum.lastAcceptedManifestTimestamp())

        kilter.clearStoredHashes()
        assertNull(kilter.lastAcceptedManifestTimestamp())
        assertTrue(kilter.canApplyManifest(older))
    }

    @Test
    fun `far future envelope cannot advance watermark or persist chunk hashes`() {
        val manager = manager(prefsNames[0], BlossomSyncManager.MANIFEST_D_TAG)
        val accepted = manifest(
            contentCreatedAt = nowSeconds,
            eventCreatedAt = nowSeconds,
            hash = "a".repeat(64),
        )
        manager.saveCompletedManifest(accepted, accepted.chunks)

        val future = manifest(
            contentCreatedAt = nowSeconds,
            eventCreatedAt = nowSeconds + NostrEventPolicy.MAX_FUTURE_SKEW_SECONDS + 1,
            hash = "b".repeat(64),
        )
        assertFalse(manager.canApplyManifest(future))
        manager.saveCompletedManifest(future, future.chunks)

        val prefs = context.getSharedPreferences(prefsNames[0], Context.MODE_PRIVATE)
        assertEquals(nowSeconds, manager.lastAcceptedManifestTimestamp())
        assertEquals("a".repeat(64), prefs.getString("chunk_sha256_snapshot", null))
    }

    @Test
    fun `future content timestamp cannot poison the separate cursor seed`() {
        val manager = manager(prefsNames[0], BlossomSyncManager.MANIFEST_D_TAG)
        val futureContent = manifest(
            contentCreatedAt = nowSeconds + NostrEventPolicy.MAX_FUTURE_SKEW_SECONDS + 1,
            eventCreatedAt = nowSeconds,
        )

        assertFalse(manager.canApplyManifest(futureContent))
        manager.saveAcceptedManifestTimestamp(futureContent)
        assertNull(manager.lastAcceptedManifestTimestamp())
    }

    @Test
    fun `timestamps at the future skew boundary remain acceptable`() {
        val boundary = nowSeconds + NostrEventPolicy.MAX_FUTURE_SKEW_SECONDS
        val manager = manager(prefsNames[0], BlossomSyncManager.MANIFEST_D_TAG)
        val manifest = manifest(contentCreatedAt = boundary, eventCreatedAt = boundary)

        assertTrue(manager.canApplyManifest(manifest))
        manager.saveAcceptedManifestTimestamp(manifest)
        assertEquals(boundary, manager.lastAcceptedManifestTimestamp())
    }

    @Test
    fun `upgrade removes poisoned stored watermark but retains chunk hashes`() {
        val prefs = context.getSharedPreferences(prefsNames[0], Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(
                "last_manifest_created_at",
                nowSeconds + NostrEventPolicy.MAX_FUTURE_SKEW_SECONDS + 1,
            )
            .putString("chunk_sha256_snapshot", "a".repeat(64))
            .commit()
        val manager = manager(prefsNames[0], BlossomSyncManager.MANIFEST_D_TAG)
        val current = manifest(
            contentCreatedAt = nowSeconds,
            eventCreatedAt = nowSeconds,
            hash = "b".repeat(64),
        )

        assertTrue(manager.canApplyManifest(current))
        assertEquals("a".repeat(64), prefs.getString("chunk_sha256_snapshot", null))

        manager.saveAcceptedManifestTimestamp(current)
        assertEquals(nowSeconds, manager.lastAcceptedManifestTimestamp())
        assertEquals("a".repeat(64), prefs.getString("chunk_sha256_snapshot", null))
    }

    private fun manager(prefsName: String, dTag: String) = BlossomSyncManager(
        context = context,
        okHttpClient = OkHttpClient(),
        manifestDTag = dTag,
        prefsName = prefsName,
        nowSeconds = { nowSeconds },
    )

    private fun manifest(
        contentCreatedAt: Long,
        eventCreatedAt: Long = contentCreatedAt,
        hash: String = "a".repeat(64),
    ) = BlossomManifest(
        v = 1,
        board = "test",
        createdAt = contentCreatedAt,
        compression = "zstd",
        chunks = listOf(
            BlossomChunk(
                name = "snapshot",
                type = "snapshot",
                sha256 = hash,
                size = 1024,
                urls = listOf("https://mirror.example/snapshot"),
            ),
        ),
        eventCreatedAt = eventCreatedAt,
        eventId = contentCreatedAt.toString(16).padStart(64, '0'),
    )
}
