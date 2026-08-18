package com.cruxcoach.android.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Durable hand-off across an APK update during an offline-share session. */
class LocalShareResumeStore(private val context: Context) {

    data class Pending(
        val requiredVersionCode: Long,
        val apkPath: String?,
        val apkVersionName: String?,
        val boardPath: String?,
        val board: LocalShareProtocol.BoardArtifact?,
    )

    private val preferences =
        context.getSharedPreferences("local_share_resume_v1", Context.MODE_PRIVATE)

    fun save(pending: Pending) {
        val json = JSONObject()
            .put("requiredVersionCode", pending.requiredVersionCode)
            .put("apkPath", pending.apkPath)
            .put("apkVersionName", pending.apkVersionName)
        pending.board?.let { board ->
            json.put("boardPath", pending.boardPath)
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
        preferences.edit().putString(KEY, json.toString()).apply()
    }

    fun load(): Pending? {
        val raw = preferences.getString(KEY, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val apkPath = json.optString("apkPath").takeIf { it.isNotBlank() && it != "null" }
            val boardPath = json.optString("boardPath").takeIf { it.isNotBlank() && it != "null" }
            // Never trust a persisted/raw path blindly. Both artifacts must
            // remain inside this app's cache directory.
            apkPath?.let { requireInsideCache(it) }
            boardPath?.let { requireInsideCache(it) }
            val board = boardPath?.let {
                LocalShareProtocol.BoardArtifact(
                    artifact = LocalShareProtocol.Artifact(
                        path = LocalShareProtocol.BOARD_PATH,
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
            Pending(
                requiredVersionCode = json.getLong("requiredVersionCode"),
                apkPath = apkPath,
                apkVersionName = json.optString("apkVersionName").takeIf {
                    it.isNotBlank() && it != "null"
                },
                boardPath = boardPath,
                board = board,
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
