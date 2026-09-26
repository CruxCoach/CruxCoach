package com.cruxcoach.android.data.blossom

import android.app.Application
import android.content.Context
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BlossomBetaImportUpgradeTest {
    private lateinit var manager: BlossomSyncManager
    private val version = BlossomSyncManager.BETA_IMPORT_VERSION

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("beta_upgrade_test", Context.MODE_PRIVATE).edit().clear().commit()
        manager = BlossomSyncManager(
            context, OkHttpClient(), prefsName = "beta_upgrade_test", nowSeconds = { 1000L },
        )
    }

    @Test
    fun `legacy downloaded beta is reimported without downloading unchanged catalogue`() {
        val manifest = manifest(chunk("climbs", "climbs"), chunk("beta", "beta"))
        manager.saveCompletedManifest(manifest, manifest.chunks) // Old app ignored beta.
        assertTrue(manager.getChangedChunks(manifest).isEmpty())
        assertEquals(listOf("beta"), changedBeta(manifest))
        // A failed import saves no capability marker and retries the same signed manifest.
        assertEquals(listOf("beta"), changedBeta(manifest))
        manager.saveCompletedManifest(manifest, listOf(manifest.chunks.last()), version)
        assertTrue(changedBeta(manifest).isEmpty())
    }

    @Test
    fun `partial success marks only imported beta and retains failed beta for retry`() {
        val manifest = manifest(chunk("beta_a", "beta"), chunk("beta_b", "beta"))
        manager.saveCompletedManifest(manifest, manifest.chunks)
        manager.saveChunkHash("beta_a", manifest.chunks.first().sha256, version)
        assertEquals(listOf("beta_b"), changedBeta(manifest))
        assertEquals(100L, manager.lastAcceptedManifestTimestamp())
    }

    @Test
    fun `downgraded app cannot mark a newly downloaded hash as beta imported`() {
        val original = manifest(chunk("beta", "beta"))
        manager.saveCompletedManifest(original, original.chunks, version)
        val update = manifest(chunk("beta", "beta", "b".repeat(64))).copy(createdAt = 200, eventCreatedAt = 200)
        manager.saveCompletedManifest(update, update.chunks) // Downgraded app ignores new beta.
        assertEquals(listOf("beta"), changedBeta(update))
        assertTrue("capability checks must not bypass rollback protection", changedBeta(original).isEmpty())
    }

    @Test
    fun `embedded and moon beta snapshots require successful capability import once`() {
        for (type in listOf("aurora", "quantum", "moonboard_beta")) {
            manager.clearStoredHashes()
            val manifest = manifest(chunk("snapshot", type))
            manager.saveCompletedManifest(manifest, manifest.chunks)
            assertEquals(manifest.chunks, manager.getChangedChunks(manifest, version))
            // Success includes an authoritative empty table: no row-count heuristic.
            manager.saveCompletedManifest(manifest, manifest.chunks, version)
            assertTrue(manager.getChangedChunks(manifest, version).isEmpty())
            manager.clearStoredHashes()
            assertEquals(manifest.chunks, manager.getChangedChunks(manifest, version))
        }
    }

    @Test
    fun `legacy untyped beta names receive the guard but explicit other types do not`() {
        val manifest = manifest(chunk("beta_old", "unknown"), chunk("beta_meta", "meta"))
        manager.saveCompletedManifest(manifest, manifest.chunks)
        assertEquals(listOf("beta_old"), changedBeta(manifest))
    }

    private fun changedBeta(manifest: BlossomManifest) = manager.getChangedChunks(
        manifest, version, { BlossomSyncManager.isBetaChunk(it) },
    ).map { it.name }

    private fun chunk(name: String, type: String, hash: String = "a".repeat(64)) = BlossomChunk(
        name = name, type = type, sha256 = hash, size = 100,
        urls = listOf("https://example.com/$name"),
    )

    private fun manifest(vararg chunks: BlossomChunk) = BlossomManifest(
        v = 2, board = "kilter", createdAt = 100, eventCreatedAt = 100,
        compression = "zstd", chunks = chunks.toList(),
    )
}
