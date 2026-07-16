package com.cruxcoach.android.data

import android.content.Context
import android.util.Base64
import android.util.Log
import com.cruxcoach.android.R
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.SupportedBoard
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Handles APK download, version checking, and database extraction from
 * Kilter Board legacy APKs (via APKPure).
 *
 * Responsibilities:
 * - HEAD request to check for new APK versions
 * - Full APK download with progress reporting
 * - ZIP extraction to locate `assets/db.sqlite3` inside the APK bundle
 * - Version code persistence after successful import
 *
 * This class owns all HTTP/network and ZIP logic so that
 * [BoardDatabaseImporter] can focus purely on database import.
 */
class ApkDownloader(
    private val context: Context,
    private val boardRepository: BoardRepository
) {
    companion object {
        private const val TAG = "ApkDownloader"
        private const val APK_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
    }

    // ── APK version check ─────────────────────────────────────────────

    sealed class UpdateCheck {
        data class Available(val versionCode: String) : UpdateCheck()
        data object UpToDate : UpdateCheck()
        data class Error(val message: String) : UpdateCheck()
    }

    /**
     * Performs a lightweight HEAD request to APKPure to check if a new APK
     * version is available. The 302 redirect URL contains a base64-encoded
     * path segment with the package name and version code.
     *
     * Compares against the last known version stored in `sync_states`.
     * Returns [UpdateCheck.UpToDate] if the version hasn't changed.
     */
    fun checkForUpdate(): UpdateCheck {
        return try {
            val url = URL("https://d.apkpure.net/b/APK/${SupportedBoard.KILTER.appPackage}?version=latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", APK_USER_AGENT)
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            try {
                val responseCode = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (location == null || responseCode != 302) {
                    return UpdateCheck.Error(
                        context.getString(R.string.apk_update_unexpected_response, responseCode),
                    )
                }
                val versionCode = extractVersionCode(location)
                    ?: return UpdateCheck.Error(context.getString(R.string.apk_update_version_unrecognized))
                val lastKnown = boardRepository.getSyncState("apk_version_code")
                Log.d(TAG, "APK version check: remote=$versionCode, local=$lastKnown")
                if (lastKnown == versionCode) {
                    UpdateCheck.UpToDate
                } else {
                    UpdateCheck.Available(versionCode)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            UpdateCheck.Error(context.getString(R.string.apk_update_check_failed))
        }
    }

    /**
     * Extracts a version identifier from the APKPure redirect URL.
     * The URL path contains a base64-encoded segment like
     * "Y29tLmF1cm9yYWNsaW1iaW5nLmtpbHRlcmJvYXJkXzIzMF9mN2FiNmZlYg"
     * which decodes to "com.auroraclimbing.kilterboard_230_f7ab6feb".
     * We extract "230_f7ab6feb" as a stable version identifier.
     */
    private fun extractVersionCode(redirectUrl: String): String? {
        return try {
            val pathSegment = redirectUrl
                .substringAfter("/b/APK/")
                .substringBefore("/")
                .substringBefore("?")
            val decoded = String(Base64.decode(pathSegment, Base64.DEFAULT))
            val parts = decoded.split("_")
            if (parts.size >= 3) {
                parts.drop(parts.size - 2).joinToString("_")
            } else {
                decoded.substringAfter("_")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract version code from redirect URL", e)
            null
        }
    }

    /** Persists the APK version code after a successful import. */
    fun saveApkVersionCode(versionCode: String) {
        boardRepository.upsertSyncState("apk_version_code", versionCode)
    }

    // ── Download + extraction ─────────────────────────────────────────

    /**
     * Downloads the Kilter Board legacy APK from APKPure and extracts `assets/db.sqlite3`
     * into [targetDbFile]. Reports download progress via [onDownloadProgress].
     *
     * @param board which Kilter Board variant to download
     * @param targetDbFile where to write the extracted database
     * @param onDownloadProgress callback with (bytesRead, totalBytes)
     */
    fun downloadAndExtractDatabase(
        targetDbFile: File,
        onDownloadProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ) {
        val tempApk = File(context.cacheDir, "aurora_apk_download.zip")
        try {
            val apkUrl = "https://d.apkpure.net/b/APK/${SupportedBoard.KILTER.appPackage}?version=latest"
            downloadFile(apkUrl, tempApk, onDownloadProgress)
            extractDatabase(tempApk, targetDbFile)
        } finally {
            tempApk.delete()
        }
    }

    private fun downloadFile(
        urlString: String,
        target: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ) {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", APK_USER_AGENT)
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception(context.getString(R.string.apk_download_failed_http, conn.responseCode))
            }
            val totalBytes = conn.contentLength.toLong()
            var bytesRead = 0L
            val buffer = ByteArray(8192)
            conn.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    var len: Int
                    while (input.read(buffer).also { len = it } != -1) {
                        output.write(buffer, 0, len)
                        bytesRead += len
                        onProgress?.invoke(bytesRead, totalBytes)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Extracts `assets/db.sqlite3` from the APK bundle.
     * APKPure delivers an outer ZIP containing `com.auroraclimbing.*.apk`;
     * the actual DB is inside that inner APK.
     */
    private fun extractDatabase(apkBundle: File, target: File) {
        var found = false
        ZipInputStream(apkBundle.inputStream().buffered()).use { outerZip ->
            var entry = outerZip.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".apk") && entry.name.startsWith("com.auroraclimbing.")) {
                    found = extractDbFromApk(outerZip, target)
                    break
                }
                if (entry.name == "assets/db.sqlite3") {
                    FileOutputStream(target).use { out -> outerZip.copyTo(out) }
                    found = true
                    break
                }
                entry = outerZip.nextEntry
            }
        }
        if (!found) {
            throw Exception("db.sqlite3 nicht im APK gefunden")
        }
    }

    private fun extractDbFromApk(apkStream: InputStream, target: File): Boolean {
        ZipInputStream(apkStream).use { innerZip ->
            var entry = innerZip.nextEntry
            while (entry != null) {
                if (entry.name == "assets/db.sqlite3") {
                    FileOutputStream(target).use { out -> innerZip.copyTo(out) }
                    return true
                }
                entry = innerZip.nextEntry
            }
        }
        return false
    }
}
