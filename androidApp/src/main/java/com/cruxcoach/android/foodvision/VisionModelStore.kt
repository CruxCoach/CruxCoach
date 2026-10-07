package com.cruxcoach.android.foodvision

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.cruxcoach.android.R
import com.cruxcoach.athlete.logic.VisionCapability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Download, verification and removal of the photo model (FEAT-069).
 *
 * Files go to the app's external files directory because DownloadManager
 * cannot write to internal storage; they hold public model weights, nothing
 * personal. A model counts as installed only after both files matched their
 * pinned SHA-256. Only the two file URLs ever leave the device here.
 */
@Singleton
class VisionModelStore @Inject constructor(@ApplicationContext private val context: Context) {

    sealed interface State {
        data object Missing : State
        data class Downloading(val model: VisionModel, val doneBytes: Long, val waiting: Boolean) : State
        data class Verifying(val model: VisionModel) : State
        data class Ready(val model: VisionModel, val probe: Probe?) : State
        data class Failed(val model: VisionModel, val reason: Reason) : State
    }

    enum class Reason { DOWNLOAD_FAILED, HASH_MISMATCH }

    sealed interface StartResult {
        data object Started : StartResult
        data class NotEnoughSpace(val neededBytes: Long, val freeBytes: Long) : StartResult
        data object NoStorage : StartResult
    }

    data class Probe(val estimatedMs: Long, val verdict: VisionCapability.ProbeVerdict)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dm by lazy { context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun dir(): File? = context.getExternalFilesDir(DIR)?.also { it.mkdirs() }

    fun fileFor(file: ModelFile): File? = dir()?.let { File(it, file.fileName) }

    private fun initialState(): State {
        val model = VisionModels.byId(prefs.getString(KEY_MODEL, null)) ?: return State.Missing
        return when {
            prefs.getString(KEY_VERIFIED, null) == model.id && model.files.all { fileFor(it)?.length() == it.bytes } ->
                State.Ready(model, storedProbe(model))
            prefs.getString(KEY_DOWNLOADS, null) != null -> State.Downloading(model, 0, waiting = true)
            else -> State.Missing
        }
    }

    private fun storedProbe(model: VisionModel): Probe? {
        if (prefs.getString(KEY_PROBE_MODEL, null) != model.id) return null
        val ms = prefs.getLong(KEY_PROBE_MS, -1)
        val verdict = prefs.getString(KEY_PROBE_VERDICT, null)?.let { v ->
            VisionCapability.ProbeVerdict.entries.firstOrNull { it.name == v }
        }
        return if (ms >= 0 && verdict != null) Probe(ms, verdict) else null
    }

    /** Enqueues both files. [allowMobile] lets the download use mobile data. */
    suspend fun start(model: VisionModel, allowMobile: Boolean): StartResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dir = dir() ?: return@withContext StartResult.NoStorage
            removeFiles()
            val needed = model.downloadBytes + SPACE_MARGIN_BYTES
            val free = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (free < needed) return@withContext StartResult.NotEnoughSpace(needed, free)

            val networks = DownloadManager.Request.NETWORK_WIFI or
                (if (allowMobile) DownloadManager.Request.NETWORK_MOBILE else 0)
            val ids = model.files.mapIndexed { i, file ->
                val request = DownloadManager.Request(sourceFor(file).toUri())
                    .setTitle(context.getString(R.string.fvp_download_title, i + 1, model.files.size))
                    .setDestinationUri(Uri.fromFile(File(dir, file.fileName)))
                    .setAllowedNetworkTypes(networks)
                    .setAllowedOverMetered(allowMobile)
                    .setAllowedOverRoaming(false)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                dm.enqueue(request)
            }
            prefs.edit {
                putString(KEY_MODEL, model.id)
                putString(KEY_DOWNLOADS, ids.joinToString(","))
                remove(KEY_VERIFIED)
            }
            _state.value = State.Downloading(model, 0, waiting = true)
            StartResult.Started
        }
    }

    /**
     * Polls DownloadManager; once both files are complete it verifies them.
     * Call repeatedly while a download is shown.
     */
    suspend fun refresh(): State = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = _state.value
            if (current !is State.Downloading) return@withContext current
            val model = current.model
            val ids = prefs.getString(KEY_DOWNLOADS, null)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
            if (ids.size != model.files.size) {
                return@withContext fail(model, Reason.DOWNLOAD_FAILED)
            }
            var done = 0L
            var allComplete = true
            var waiting = false
            ids.forEachIndexed { i, id ->
                val row = query(id) ?: return@withContext fail(model, Reason.DOWNLOAD_FAILED)
                when (row.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        done += model.files[i].bytes
                        adoptDownloadedFile(row.localUri, model.files[i])
                    }
                    DownloadManager.STATUS_FAILED -> return@withContext fail(model, Reason.DOWNLOAD_FAILED)
                    else -> {
                        allComplete = false
                        done += row.doneBytes.coerceAtLeast(0)
                        if (row.status == DownloadManager.STATUS_PENDING || row.status == DownloadManager.STATUS_PAUSED) waiting = true
                    }
                }
            }
            if (!allComplete) {
                return@withContext State.Downloading(model, done, waiting).also { _state.value = it }
            }
            _state.value = State.Verifying(model)
            val ok = model.files.all { f -> fileFor(f)?.let { sha256(it) } == f.sha256 }
            if (!ok) {
                removeFiles()
                return@withContext fail(model, Reason.HASH_MISMATCH)
            }
            prefs.edit {
                putString(KEY_VERIFIED, model.id)
                remove(KEY_DOWNLOADS)
            }
            State.Ready(model, storedProbe(model)).also { _state.value = it }
        }
    }

    fun saveProbe(model: VisionModel, probe: Probe) {
        prefs.edit {
            putString(KEY_PROBE_MODEL, model.id)
            putLong(KEY_PROBE_MS, probe.estimatedMs)
            putString(KEY_PROBE_VERDICT, probe.verdict.name)
        }
        val current = _state.value
        if (current is State.Ready && current.model == model) _state.value = current.copy(probe = probe)
    }

    /** Size a mirror reports for a URL (HEAD), or null; tests replace it. */
    internal var headLength: (String) -> Long? = ::contentLength

    /** The first mirror that holds the file with the expected size, else Hugging Face. */
    internal fun sourceFor(file: ModelFile): String =
        VisionModels.mirrorUrls(file).firstOrNull { headLength(it) == file.bytes } ?: file.url

    private fun contentLength(url: String): Long? = runCatching {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "HEAD"
            connection.connectTimeout = MIRROR_TIMEOUT_MS
            connection.readTimeout = MIRROR_TIMEOUT_MS
            if (connection.responseCode == 200) connection.contentLengthLong.takeIf { it >= 0 } else null
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** Cancels a running download or removes an installed model. */
    suspend fun remove() = mutex.withLock {
        withContext(Dispatchers.IO) {
            prefs.getString(KEY_DOWNLOADS, null)?.split(',')?.mapNotNull { it.toLongOrNull() }?.forEach { id ->
                runCatching { dm.remove(id) }
            }
            removeFiles()
            prefs.edit { clear() }
            _state.value = State.Missing
        }
    }

    /** Bytes the installed or downloading model occupies on disk. */
    fun bytesOnDisk(): Long = dir()?.listFiles()?.sumOf { it.length() } ?: 0L

    private fun fail(model: VisionModel, reason: Reason): State {
        prefs.edit { remove(KEY_DOWNLOADS) }
        return State.Failed(model, reason).also { _state.value = it }
    }

    private fun removeFiles() {
        dir()?.listFiles()?.forEach { if (!it.delete()) Log.w(TAG, "could not delete ${it.name}") }
    }

    private class Row(val status: Int, val doneBytes: Long, val localUri: String?)

    private fun query(id: Long): Row? = runCatching {
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return@use null
            Row(
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                doneBytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)),
            )
        }
    }.getOrNull()

    /** DownloadManager may pick "name-1.gguf" when a file existed; move it to the expected name. */
    private fun adoptDownloadedFile(localUri: String?, file: ModelFile) {
        val target = fileFor(file) ?: return
        val actual = localUri?.toUri()?.path?.let(::File) ?: return
        if (actual.absolutePath != target.absolutePath && actual.exists()) {
            target.delete()
            if (!actual.renameTo(target)) Log.w(TAG, "could not move ${actual.name} to ${target.name}")
        }
    }

    companion object {
        private const val TAG = "VisionModelStore"
        private const val MIRROR_TIMEOUT_MS = 5_000
        private const val PREFS = "foodvision"
        private const val DIR = "foodvision"
        private const val KEY_MODEL = "model"
        private const val KEY_DOWNLOADS = "downloads"
        private const val KEY_VERIFIED = "verified"
        private const val KEY_PROBE_MODEL = "probe_model"
        private const val KEY_PROBE_MS = "probe_ms"
        private const val KEY_PROBE_VERDICT = "probe_verdict"
        private const val SPACE_MARGIN_BYTES = 300L * 1024 * 1024

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
