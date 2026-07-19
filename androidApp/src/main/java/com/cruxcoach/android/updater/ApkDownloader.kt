package com.cruxcoach.android.updater

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.StatFs
import android.util.Log
import androidx.core.net.toUri
import java.io.File

/**
 * Thin wrapper around Android's [DownloadManager] for the updater APK
 * (§5.3). Not a "downloader of arbitrary files" — owns the single
 * pending-update APK on disk. Public surface is deliberately narrow:
 * start, query, clear.
 *
 * Storage: target file lives under the app-scoped external files dir
 * (`getExternalFilesDir(null)/updater/pending-update-<versionName>.apk`).
 * DownloadManager refuses internal `/data/data` destinations on modern
 * Android (SecurityException "Unsupported path"); the external-files dir
 * is app-private on API 29+ (scoped storage) and does not need any
 * storage permission. The APK is still untrusted bytes until
 * [IntegrityVerifier] has cleared it.
 *
 * Pre-flight [StatFs] check rejects the enqueue when the target partition
 * does not have `apkSizeBytes + 16 MiB` free — otherwise DownloadManager
 * happily starts a download that will silently fail partway (§R4).
 */
class ApkDownloader(private val context: Context) {

    private val dm: DownloadManager by lazy {
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    /** Directory DownloadManager is allowed to write into (scoped external, no permission). */
    private fun updaterDir(): File? {
        val base = context.getExternalFilesDir(null) ?: return null
        val dir = File(base, "updater")
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) return null
        return dir
    }

    /** Prepare — does not enqueue. Returns the target file the caller should pass to [start]. */
    fun targetFileFor(versionName: String): File {
        val dir = updaterDir() ?: File(context.cacheDir, "updater").apply { mkdirs() }
        return File(dir, "pending-update-$versionName.apk")
    }

    /**
     * Enqueues the download if [PreFlight] is clean; returns the DownloadManager
     * id or a typed error. Idempotent w.r.t. the file — any existing file at
     * [targetFileFor] is deleted before enqueue so a retry never races an
     * old partial byte stream.
     */
    fun start(
        info: UpdateInfo,
        allowMobile: Boolean,
        sourceIndex: Int = 0,
    ): StartResult {
        val downloadUrl = info.downloadUrls.getOrNull(sourceIndex)
            ?: return StartResult.Error("No APK download source at index $sourceIndex")
        val target = targetFileFor(info.versionName)
        if (target.exists() && !target.delete()) {
            Log.w(TAG, "Could not delete previous cached APK at ${target.absolutePath}")
        }

        val headroomBytes = 16L * 1024 * 1024
        val needed = info.apkSizeBytes + headroomBytes
        val free = try {
            StatFs(target.parentFile!!.absolutePath).availableBytes
        } catch (e: Exception) {
            Log.w(TAG, "StatFs failed — proceeding without pre-flight", e)
            Long.MAX_VALUE
        }
        if (free < needed) {
            return StartResult.InsufficientStorage(neededBytes = needed, freeBytes = free)
        }

        val allowedTypes = if (allowMobile) {
            DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
        } else {
            DownloadManager.Request.NETWORK_WIFI
        }

        val request = DownloadManager.Request(downloadUrl.toUri())
            .setTitle(info.versionName)
            .setDestinationUri(Uri.fromFile(target))
            .setAllowedNetworkTypes(allowedTypes)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            .setAllowedOverMetered(allowMobile)
            .setAllowedOverRoaming(false)

        return try {
            val id = dm.enqueue(request)
            StartResult.Enqueued(id = id, target = target)
        } catch (e: Exception) {
            Log.w(TAG, "DownloadManager.enqueue failed", e)
            StartResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Live query for a previously-enqueued id. Null if DownloadManager forgot it. */
    fun query(id: Long): Status? {
        val q = DownloadManager.Query().setFilterById(id)
        return dm.query(q)?.use { c -> readStatus(c) }
    }

    /** Cancels the download and deletes any partial file. Safe to call on unknown ids. */
    fun cancel(id: Long) {
        runCatching { dm.remove(id) }
    }

    /** Deletes the cached APK file — call after successful install or fatal verify failure. */
    fun clearCacheFor(versionName: String) {
        val f = targetFileFor(versionName)
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "Could not delete cached APK at ${f.absolutePath}")
        }
    }

    /** Current transport — used to gate auto-download decisions (§6.14). */
    fun currentTransport(): Transport {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Transport.UNKNOWN
        val active = cm.activeNetwork ?: return Transport.OFFLINE
        val caps = cm.getNetworkCapabilities(active) ?: return Transport.OFFLINE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return Transport.OFFLINE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return Transport.OFFLINE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            else -> Transport.UNKNOWN
        }
    }

    private fun readStatus(c: Cursor): Status? {
        if (!c.moveToFirst()) return null
        val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val totalCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        val soFarCol = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
        val reasonCol = c.getColumnIndex(DownloadManager.COLUMN_REASON)
        val uriCol = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
        val dmStatus = c.getInt(statusCol)
        val total = c.getLong(totalCol)
        val soFar = c.getLong(soFarCol)
        val reason = c.getInt(reasonCol)
        val localUri = c.getString(uriCol)
        return Status(
            state = when (dmStatus) {
                DownloadManager.STATUS_PENDING -> State.PENDING
                DownloadManager.STATUS_RUNNING -> State.RUNNING
                DownloadManager.STATUS_PAUSED -> State.PAUSED
                DownloadManager.STATUS_SUCCESSFUL -> State.SUCCESSFUL
                DownloadManager.STATUS_FAILED -> State.FAILED
                else -> State.UNKNOWN
            },
            totalBytes = total,
            bytesSoFar = soFar,
            reason = reason,
            localUri = localUri,
        )
    }

    enum class Transport { WIFI, CELLULAR, OFFLINE, UNKNOWN }

    enum class State { PENDING, RUNNING, PAUSED, SUCCESSFUL, FAILED, UNKNOWN }

    data class Status(
        val state: State,
        val totalBytes: Long,
        val bytesSoFar: Long,
        val reason: Int,
        val localUri: String?,
    ) {
        val progressPercent: Int?
            get() = if (totalBytes > 0) ((bytesSoFar * 100) / totalBytes).toInt().coerceIn(0, 100) else null
    }

    sealed interface StartResult {
        data class Enqueued(val id: Long, val target: File) : StartResult
        data class InsufficientStorage(val neededBytes: Long, val freeBytes: Long) : StartResult
        data class Error(val message: String) : StartResult
    }

    companion object {
        private const val TAG = "ApkDownloader"
    }
}
