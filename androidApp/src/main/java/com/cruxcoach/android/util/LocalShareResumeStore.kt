package com.cruxcoach.android.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Durable hand-off across an APK update during an offline-share session. */
class LocalShareResumeStore(private val context: Context) {

    data class Pending(
        val requiredVersionCode: Long,
        val protocolVersion: Int = LocalShareProtocol.VERSION,
        val apkPath: String?,
        val apkVersionName: String?,
        val boardPath: String?,
        val board: LocalShareProtocol.BoardArtifact?,
        /**
         * The Shiny Wall catalogue received next to the board snapshot (v2 `families`
         * only), imported by the updated app after the board. Records written before
         * 0.2.5.1 have neither field.
         */
        val shinyWallPath: String? = null,
        val shinyWall: LocalShareProtocol.FamilyArtifact? = null,
    )

    private val preferences =
        context.getSharedPreferences("local_share_resume_v1", Context.MODE_PRIVATE)

    fun save(pending: Pending) {
        require(
            pending.protocolVersion == LocalShareProtocol.VERSION ||
                pending.protocolVersion == LocalShareProtocol.VERSION_V2,
        ) { "Unsupported persisted share protocol" }
        val expectedBoardPath = if (pending.protocolVersion == LocalShareProtocol.VERSION_V2) {
            LocalShareProtocol.V2_BOARD_PATH
        } else {
            LocalShareProtocol.BOARD_PATH
        }
        pending.board?.let {
            require(it.artifact.path == expectedBoardPath) {
                "Persisted artifact does not match share protocol"
            }
        }
        val json = JSONObject()
            .put("requiredVersionCode", pending.requiredVersionCode)
            .put("protocolVersion", pending.protocolVersion)
            .put("apkPath", pending.apkPath)
            .put("apkVersionName", pending.apkVersionName)
        pending.board?.let { board ->
            json.put("boardPath", pending.boardPath)
                .put("boardArtifactPath", board.artifact.path)
                .put("boardSizeBytes", board.artifact.sizeBytes)
                .put("boardSha256", board.artifact.sha256)
                .put("boardUncompressedSizeBytes", board.uncompressedSizeBytes)
                .put("boardUncompressedSha256", board.uncompressedSha256)
                .put("boardSchemaVersion", board.schemaVersion)
                .put(
                    "boardCatalogues",
                    JSONArray().apply {
                        board.catalogues.forEach { catalogue ->
                            put(
                                JSONObject()
                                    .put("boardBrand", catalogue.boardBrand)
                                    .put("climbCount", catalogue.climbCount),
                            )
                        }
                    },
                )
        }
        // Only the one family a v2 manifest can name; anything else is not persisted and
        // the catalogue then follows over the internet.
        val shinyWall = pending.shinyWall
        if (shinyWall != null && pending.shinyWallPath != null &&
            pending.protocolVersion == LocalShareProtocol.VERSION_V2 &&
            shinyWall.boardBrand == LocalShareProtocol.SHINYWALL_BRAND &&
            shinyWall.format == LocalShareProtocol.SHINYWALL_FORMAT &&
            shinyWall.artifact.path == LocalShareProtocol.V2_SHINYWALL_PATH &&
            shinyWall.compression == "gzip"
        ) {
            json.put("shinyWallPath", pending.shinyWallPath)
                .put("shinyWallFormat", shinyWall.format)
                .put("shinyWallSizeBytes", shinyWall.artifact.sizeBytes)
                .put("shinyWallSha256", shinyWall.artifact.sha256)
                .put("shinyWallUncompressedSizeBytes", shinyWall.uncompressedSizeBytes)
                .put("shinyWallUncompressedSha256", shinyWall.uncompressedSha256)
                .put("shinyWallClimbCount", shinyWall.climbCount)
        }
        preferences.edit().putString(KEY, json.toString()).apply()
    }

    fun load(): Pending? {
        val raw = preferences.getString(KEY, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val apkPath = json.optString("apkPath").takeIf { it.isNotBlank() && it != "null" }
            val boardPath = json.optString("boardPath").takeIf { it.isNotBlank() && it != "null" }
            // Records written by 0.2.1 have neither field and are exactly the
            // legacy, Quantum-scrubbed v1 contract.
            val protocolVersion = json.optInt(
                "protocolVersion",
                LocalShareProtocol.VERSION,
            )
            require(
                protocolVersion == LocalShareProtocol.VERSION ||
                    protocolVersion == LocalShareProtocol.VERSION_V2,
            ) { "Unsupported persisted share protocol" }
            val expectedArtifactPath = if (protocolVersion == LocalShareProtocol.VERSION_V2) {
                LocalShareProtocol.V2_BOARD_PATH
            } else {
                LocalShareProtocol.BOARD_PATH
            }
            val artifactPath = json.optString("boardArtifactPath", expectedArtifactPath)
            require(artifactPath == expectedArtifactPath) {
                "Persisted artifact does not match share protocol"
            }
            // Never trust a persisted/raw path blindly. Both artifacts must
            // remain inside this app's cache directory.
            apkPath?.let { requireInsideCache(it) }
            boardPath?.let { requireInsideCache(it) }
            val board = boardPath?.let {
                LocalShareProtocol.BoardArtifact(
                    artifact = LocalShareProtocol.Artifact(
                        path = artifactPath,
                        sizeBytes = json.getLong("boardSizeBytes"),
                        sha256 = json.getString("boardSha256"),
                    ),
                    compression = "gzip",
                    uncompressedSizeBytes = json.getLong("boardUncompressedSizeBytes"),
                    uncompressedSha256 = json.getString("boardUncompressedSha256"),
                    schemaVersion = json.getInt("boardSchemaVersion"),
                    catalogues = json.optJSONArray("boardCatalogues")?.let { array ->
                        buildList {
                            repeat(array.length()) { index ->
                                val item = array.getJSONObject(index)
                                add(
                                    LocalShareProtocol.BoardCatalogue(
                                        boardBrand = item.getString("boardBrand"),
                                        climbCount = item.getLong("climbCount"),
                                    ),
                                )
                            }
                        }
                    }.orEmpty(),
                )
            }
            // Optional and on its own: a Shiny Wall part this version cannot trust is
            // dropped (the catalogue then follows over the internet) without costing the
            // board snapshot or the APK hand-off.
            val shinyWallPath = json.optString("shinyWallPath").takeIf { it.isNotBlank() && it != "null" }
            val shinyWall = shinyWallPath?.let { path ->
                runCatching {
                    require(protocolVersion == LocalShareProtocol.VERSION_V2) {
                        "Shiny Wall travels only in the v2 share"
                    }
                    require(
                        json.optString("shinyWallFormat", LocalShareProtocol.SHINYWALL_FORMAT) ==
                            LocalShareProtocol.SHINYWALL_FORMAT,
                    ) { "Unknown Shiny Wall format" }
                    requireInsideCache(path)
                    LocalShareProtocol.FamilyArtifact(
                        boardBrand = LocalShareProtocol.SHINYWALL_BRAND,
                        format = LocalShareProtocol.SHINYWALL_FORMAT,
                        artifact = LocalShareProtocol.Artifact(
                            path = LocalShareProtocol.V2_SHINYWALL_PATH,
                            sizeBytes = json.getLong("shinyWallSizeBytes"),
                            sha256 = json.getString("shinyWallSha256"),
                        ),
                        compression = "gzip",
                        uncompressedSizeBytes = json.getLong("shinyWallUncompressedSizeBytes"),
                        uncompressedSha256 = json.getString("shinyWallUncompressedSha256"),
                        climbCount = json.optLong("shinyWallClimbCount", 0L).coerceAtLeast(0L),
                    )
                }.getOrNull()
            }
            Pending(
                requiredVersionCode = json.getLong("requiredVersionCode"),
                protocolVersion = protocolVersion,
                apkPath = apkPath,
                apkVersionName = json.optString("apkVersionName").takeIf {
                    it.isNotBlank() && it != "null"
                },
                boardPath = boardPath,
                board = board,
                shinyWallPath = shinyWallPath?.takeIf { shinyWall != null },
                shinyWall = shinyWall,
            )
        }.getOrElse {
            clear()
            null
        }
    }

    fun clear() {
        preferences.edit().remove(KEY).apply()
    }

    private fun requireInsideCache(path: String) {
        val cache = context.cacheDir.canonicalFile
        val file = File(path).canonicalFile
        require(file.path.startsWith(cache.path + File.separator)) { "Artifact outside cache" }
    }

    private companion object {
        const val KEY = "pending"
    }
}
