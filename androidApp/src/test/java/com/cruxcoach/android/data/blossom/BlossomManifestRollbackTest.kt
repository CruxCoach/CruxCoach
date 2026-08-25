package com.cruxcoach.android.data.blossom

import android.app.Application
import android.content.Context
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
    private lateinit var context: Context
    private val prefsNames = listOf("rollback_kilter_test", "rollback_quantum_test")

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
        assertFalse(BlossomSyncManager.isManifestAcceptable(envelopeWins, 201L))
        assertTrue(BlossomSyncManager.isManifestAcceptable(envelopeWins, 200L))
        assertTrue(BlossomSyncManager.isManifestAcceptable(envelopeWins, 199L))
        assertTrue(BlossomSyncManager.isManifestAcceptable(envelopeWins, null))

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

    private fun manager(prefsName: String, dTag: String) = BlossomSyncManager(
        context = context,
        okHttpClient = OkHttpClient(),
        manifestDTag = dTag,
        prefsName = prefsName,
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
