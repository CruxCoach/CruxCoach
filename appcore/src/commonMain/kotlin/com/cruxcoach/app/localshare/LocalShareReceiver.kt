package com.cruxcoach.app.localshare

import app.cash.sqldelight.db.SqlDriver
import com.cruxcoach.app.platform.FileSystem
import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.util.toHex
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.CancellationException

/** One visible step of a transfer: a code, and progress where Android shows a bar. */
class ReceiveStep(val code: String, val done: Long, val total: Long)

/** The sender has no board snapshot to give. */
class NoSnapshotException : Exception("Sender has no board snapshot")

/** The import refused the verified file ([sourceRejected]) or the database failed. */
class ImportFailedException(val sourceRejected: Boolean, detail: String) : Exception(detail)

/**
 * The receiving half of Android's offline share for catalogues (`BoardSyncManager.runOfflineShare`
 * + `importDownloadedBoard`), without the APK: an iPhone never installs one. Download with resume
 * and hash check, inflate with a bound, verify again, cut to the chosen families, import.
 */
class LocalShareReceiver(
    private val client: LocalShareClient,
    private val files: FileSystem,
    private val hashing: Hashing,
    private val gunzip: FileGunzip,
    private val openSnapshot: (String) -> SqlDriver,
    private val importer: LocalShareImporter,
    private val workDirectory: String,
) {
    /** Returns the climbs now on this phone per chosen family (wire value → count). */
    suspend fun receive(
        baseUrl: String,
        firstManifest: LocalShareProtocol.Manifest,
        chosenBrands: Set<String>,
        onStep: (ReceiveStep) -> Unit,
    ): List<String> {
        val initialBrands = resolveBrands(offeredBrands(firstManifest), chosenBrands)
        val manifest = if (firstManifest.boardStatus == "preparing") {
            // Arm the sender's expensive snapshot work only now that the receiver has said yes.
            client.requestSnapshotBuild(baseUrl, firstManifest.protocolVersion, firstManifest.sessionId)
            onStep(ReceiveStep(STEP_PREPARING, 0, 0))
            client.awaitReadyManifest(
                baseUrl = baseUrl,
                timeoutMs = SNAPSHOT_WAIT_MAX_MS,
                protocolVersion = firstManifest.protocolVersion,
                expectedSessionId = firstManifest.sessionId,
                onPreparing = { onStep(ReceiveStep(STEP_PREPARING, 0, 0)) },
            )
        } else firstManifest
        val board = manifest.board ?: throw NoSnapshotException()
        val brands = resolveBrands(offeredBrands(manifest), chosenBrands).ifEmpty { initialBrands }
        onStep(ReceiveStep(STEP_CHECKING, 0, 0))
        val target = "$workDirectory/local_board_${board.artifact.sha256.take(20)}.gz"
        val compressed = client.downloadResumable(
            baseUrl = baseUrl,
            artifact = board.artifact,
            targetPath = target,
            protocolVersion = manifest.protocolVersion,
            expectedSessionId = firstManifest.sessionId,
            onVerifying = { onStep(ReceiveStep(STEP_VERIFY, 0, 0)) },
            onProgress = { done, total -> onStep(ReceiveStep(STEP_DOWNLOAD, done, total)) },
        )
        try {
            client.notifyDownloadComplete(baseUrl, firstManifest.sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort, as on Android: the sender also stops on its own idle timeout.
        }
        importDownloaded(compressed, board, manifest.protocolVersion, brands, onStep)
        return brands
    }

    private fun importDownloaded(
        compressed: String,
        board: LocalShareProtocol.BoardArtifact,
        protocolVersion: Int,
        brands: List<String>,
        onStep: (ReceiveStep) -> Unit,
    ) {
        require(board.uncompressedSizeBytes in 1..LocalTransferLimits.MAX_DB_BYTES) { "Shared board snapshot exceeds size limit" }
        require(board.compression == "gzip") { "Unsupported share compression" }
        onStep(ReceiveStep(STEP_VERIFY, 0, 0))
        if (files.size(compressed) != board.artifact.sizeBytes || sha256(compressed) != board.artifact.sha256) {
            files.delete(compressed)
            throw VerificationFailed("Compressed board snapshot failed verification")
        }
        val raw = "$workDirectory/local_board_${board.uncompressedSha256.take(16)}.sqlite3"
        try {
            deleteDatabase(raw)
            LocalTransferLimits.requireSpace(files.freeSpaceBytes(), board.uncompressedSizeBytes)
            gunzip.gunzip(compressed, raw, board.uncompressedSizeBytes) { written ->
                onStep(ReceiveStep(STEP_EXTRACT, written, board.uncompressedSizeBytes))
            }
            onStep(ReceiveStep(STEP_VERIFY, 0, 0))
            if (files.size(raw) != board.uncompressedSizeBytes || sha256(raw) != board.uncompressedSha256) {
                throw VerificationFailed("Shared board snapshot failed verification")
            }
            // Verified first, cut second: the hash vouches for what the sender sent, the cut is
            // ours. A cut, not a gate: a file the pruner cannot open is left for the importer.
            try {
                val driver = openSnapshot(raw)
                try { LocalShareSnapshotPruner.prune(driver, brands.toSet()) } finally { driver.close() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            val result = importer.importSnapshot(raw, includeQuantum = protocolVersion == LocalShareProtocol.VERSION_V2) { step ->
                onStep(ReceiveStep(step.code, step.scanned, step.total))
            }
            if (result is LocalShareImportResult.Failed) throw ImportFailedException(result.sourceRejected, result.detail)
            files.delete(compressed)
        } finally {
            deleteDatabase(raw)
        }
    }

    private fun deleteDatabase(path: String) {
        files.delete(path)
        files.delete("$path-journal")
        files.delete("$path-wal")
        files.delete("$path-shm")
    }

    private fun sha256(path: String): String? = hashing.sha256OfFile(path)?.toHex()

    companion object {
        const val STEP_FETCH_MANIFEST = "fetchManifest"
        const val STEP_PREPARING = "preparing"
        const val STEP_CHECKING = "checking"
        const val STEP_DOWNLOAD = "download"
        const val STEP_VERIFY = "verify"
        const val STEP_EXTRACT = "extract"
        const val SNAPSHOT_WAIT_MAX_MS = 15 * 60_000L

        /**
         * The families a share carries, as its manifest declares them (Android sharedBoardBrands):
         * interactive boards with catalogue climbs; a v1 artifact never carries Quantum.
         */
        fun offeredBrands(manifest: LocalShareProtocol.Manifest): List<String> =
            manifest.declaredCatalogues
                .filter { it.climbCount > 0 }
                .mapNotNull { BoardBrand.fromWireOrNull(it.boardBrand) }
                .filter { it.isInteractive && (manifest.protocolVersion == LocalShareProtocol.VERSION_V2 || it != BoardBrand.QUANTUM) }
                .distinct()
                .map { it.wireValue }

        /** The receiver's choice wins; nothing left of it means the whole share (Android resolveShareBrands). */
        fun resolveBrands(offered: List<String>, chosen: Set<String>): List<String> =
            offered.filter { it in chosen }.ifEmpty { offered }
    }
}
