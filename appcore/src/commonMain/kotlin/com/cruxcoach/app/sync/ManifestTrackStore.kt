package com.cruxcoach.app.sync

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.platform.WallClock

/**
 * Per-track sync markers: chunk hashes, import-version hashes and the rollback
 * watermark. Android keeps one SharedPreferences file per track; here the
 * file name is a key prefix in the shared [KeyValueStore], so clearing one
 * board never touches another board's incremental state.
 */
class ManifestTrackStore(
    private val keyValues: KeyValueStore,
    private val prefsName: String,
    private val clock: WallClock,
) {
    private fun key(name: String) = "$prefsName/$name"
    private fun importHashKey(chunkName: String, version: String) = "imported_${version}_sha256_$chunkName"

    fun lastAcceptedManifestTimestamp(): Long? {
        val raw = keyValues.getString(key(KEY_LAST_MANIFEST_CREATED_AT)) ?: return null
        val stored = raw.toLongOrNull()
        if (stored != null && CatalogueTrust.isCreatedAtAcceptable(stored, clock.epochSeconds())) return stored
        // An implausibly future (or unreadable) floor would block every real manifest forever.
        keyValues.putString(key(KEY_LAST_MANIFEST_CREATED_AT), null)
        return null
    }

    fun canApplyManifest(manifest: CatalogueManifest): Boolean =
        CatalogueTrust.isManifestAcceptable(manifest, lastAcceptedManifestTimestamp(), clock.epochSeconds())

    /** Empty for a stale or future manifest, so no caller can act on rollback hashes. */
    fun getChangedChunks(
        manifest: CatalogueManifest,
        requiredImportVersion: String? = null,
        requiresImportVersion: (CatalogueChunk) -> Boolean = { true },
    ): List<CatalogueChunk> {
        if (!canApplyManifest(manifest)) return emptyList()
        return manifest.chunks.filter { chunk ->
            keyValues.getString(key("chunk_sha256_${chunk.name}")) != chunk.sha256 || (
                requiredImportVersion != null && requiresImportVersion(chunk) &&
                    keyValues.getString(key(importHashKey(chunk.name, requiredImportVersion))) != chunk.sha256
                )
        }
    }

    fun saveAcceptedManifestTimestamp(manifest: CatalogueManifest) = saveCompletedManifest(manifest, emptyList())

    /** Only after every changed chunk imported. Never lowers the watermark. */
    fun saveCompletedManifest(
        manifest: CatalogueManifest,
        importedChunks: Iterable<CatalogueChunk>,
        importVersion: String? = null,
    ) {
        val incoming = CatalogueTrust.effectiveTimestamp(manifest)
        val lastAccepted = lastAcceptedManifestTimestamp()
        if (!CatalogueTrust.hasAcceptableTimestamps(manifest, clock.epochSeconds())) return
        if (lastAccepted != null && incoming < lastAccepted) return
        importedChunks.forEach { saveChunkHash(it.name, it.sha256, importVersion) }
        keyValues.putString(key(KEY_LAST_MANIFEST_CREATED_AT), incoming.toString())
    }

    fun saveChunkHash(chunkName: String, sha256: String, importVersion: String? = null) {
        keyValues.putString(key("chunk_sha256_$chunkName"), sha256)
        if (importVersion != null) keyValues.putString(key(importHashKey(chunkName, importVersion)), sha256)
    }

    /** Forces a full re-download and re-arms first-run manifest acceptance. */
    fun clear() {
        val prefix = key("")
        keyValues.keys().filter { it.startsWith(prefix) }.forEach { keyValues.putString(it, null) }
    }

    fun hasAnyChunkHash(): Boolean {
        val prefix = key("chunk_sha256_")
        return keyValues.keys().any { it.startsWith(prefix) }
    }

    private companion object {
        const val KEY_LAST_MANIFEST_CREATED_AT = "last_manifest_created_at"
    }
}
