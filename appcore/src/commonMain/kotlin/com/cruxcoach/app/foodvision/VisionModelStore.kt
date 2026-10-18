// Port of androidApp/.../foodvision/VisionModelStore.kt (0e1bad0c6) on the
// platform contracts: the same explicit, user-started download, space check,
// mirror choice, SHA-256 verification, probe record and removal. Android hands
// both files to DownloadManager; iOS hands them to a background URLSession
// ([ModelDownloads]), so the download goes on while the app is closed or the
// screen is locked, and a relaunched app picks it up by the stored ids.
package com.cruxcoach.app.foodvision

import com.cruxcoach.app.platform.FileSystem
import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.athlete.logic.VisionCapability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The link a transfer would use now. */
interface VisionNetwork {
    /** [NONE], [UNMETERED] (Wi-Fi, Ethernet) or [METERED] (mobile data, a hotspot). */
    fun current(): String

    companion object {
        const val NONE = "none"
        const val UNMETERED = "unmetered"
        const val METERED = "metered"
    }
}

class VisionModelStore(
    private val files: FileSystem,
    private val hashing: Hashing,
    private val keyValues: KeyValueStore,
    /** Only for the mirror's HEAD; the files themselves go through [downloads]. */
    private val http: HttpTransport,
    private val downloads: ModelDownloads,
    /** Lives as long as the core: the store follows a download while the photo sheet is closed. */
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** How often a running download is looked at, as Android's sheet polls DownloadManager. */
    private val pollMs: Long = 1_000,
    /** The catalogue; tests use tiny stand-ins. */
    private val models: List<VisionModel> = VisionModels.ALL,
) {
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

    private val mutex = Mutex()
    private var follower: Job? = null
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        // A download started before the app was closed: the system kept it going.
        if (_state.value is State.Downloading) follow()
    }

    /** Where a model file lives: the app's work directory, excluded from backups. */
    fun path(file: ModelFile): String = "${files.workDirectory().trimEnd('/')}/$DIR_PREFIX${file.fileName}"

    private fun partPath(file: ModelFile): String = path(file) + ".part"

    private fun initialState(): State {
        val id = keyValues.getString(KEY_MODEL)
        val model = models.firstOrNull { it.id == id } ?: return State.Missing
        return when {
            keyValues.getString(KEY_VERIFIED) == model.id &&
                model.files.all { files.exists(path(it)) && files.size(path(it)) == it.bytes } ->
                State.Ready(model, storedProbe(model))
            keyValues.getString(KEY_DOWNLOADS) != null -> State.Downloading(model, 0, waiting = true)
            else -> {
                // A damaged install, or a record without its transfers: start clean.
                removeFiles()
                clearKeys()
                State.Missing
            }
        }
    }

    private fun storedProbe(model: VisionModel): Probe? {
        if (keyValues.getString(KEY_PROBE_MODEL) != model.id) return null
        val ms = keyValues.getString(KEY_PROBE_MS)?.toLongOrNull() ?: return null
        val verdict = keyValues.getString(KEY_PROBE_VERDICT)?.let { v ->
            VisionCapability.ProbeVerdict.entries.firstOrNull { it.name == v }
        } ?: return null
        return if (ms >= 0) Probe(ms, verdict) else null
    }

    private fun storedIds(): List<String> =
        keyValues.getString(KEY_DOWNLOADS)?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    /** Enqueues both files. [allowMobile] lets the download use mobile data. */
    suspend fun start(model: VisionModel, allowMobile: Boolean): StartResult = mutex.withLock {
        withContext(io) {
            if (files.workDirectory().isBlank()) return@withContext StartResult.NoStorage
            // A transfer of an earlier start (gigabytes, perhaps on mobile data) must not run on unseen.
            storedIds().forEach { downloads.remove(it) }
            keyValues.putString(KEY_DOWNLOADS, null)
            removeFiles()
            val needed = model.downloadBytes + SPACE_MARGIN_BYTES
            // A negative answer means the system could not tell, as Android's StatFs fallback.
            val free = files.freeSpaceBytes()
            if (free in 0 until needed) return@withContext StartResult.NotEnoughSpace(needed, free)
            keyValues.putString(KEY_MODEL, model.id)
            keyValues.putString(KEY_VERIFIED, null)
            val ids = ArrayList<String>()
            for (file in model.files) {
                val id = downloads.enqueue(sourceFor(file), path(file), file.bytes, allowMobile)
                if (id == null) {
                    ids.forEach { downloads.remove(it) }
                    fail(model, Reason.DOWNLOAD_FAILED)
                    return@withContext StartResult.Started
                }
                ids += id
            }
            keyValues.putString(KEY_DOWNLOADS, ids.joinToString(","))
            _state.value = State.Downloading(model, 0, waiting = true)
            follow()
            StartResult.Started
        }
    }

    /** Looks at the transfers until the download ends one way or the other. */
    private fun follow() {
        if (follower?.isActive == true) return
        follower = scope.launch(io) {
            while (isActive && _state.value is State.Downloading) {
                refresh()
                if (_state.value !is State.Downloading) break
                delay(pollMs)
            }
        }
    }

    /** Asks the system how far both files are; once both are complete it verifies them. */
    suspend fun refresh(): State = mutex.withLock {
        withContext(io) {
            val current = _state.value
            if (current !is State.Downloading) return@withContext current
            val model = current.model
            val ids = storedIds()
            if (ids.size != model.files.size) return@withContext fail(model, Reason.DOWNLOAD_FAILED)
            var done = 0L
            var allComplete = true
            var waiting = false
            ids.forEachIndexed { i, id ->
                val row = downloads.query(id) ?: return@withContext fail(model, Reason.DOWNLOAD_FAILED)
                when (row.status) {
                    ModelDownloads.SUCCESSFUL -> done += model.files[i].bytes
                    ModelDownloads.FAILED -> return@withContext fail(model, Reason.DOWNLOAD_FAILED)
                    else -> {
                        allComplete = false
                        done += row.doneBytes.coerceIn(0, model.files[i].bytes)
                        if (row.status == ModelDownloads.PENDING) waiting = true
                    }
                }
            }
            if (!allComplete) {
                return@withContext State.Downloading(model, done, waiting).also { _state.value = it }
            }
            _state.value = State.Verifying(model)
            val ok = model.files.all { f ->
                files.size(path(f)) == f.bytes && hashing.sha256OfFile(path(f))?.let { hex(it) } == f.sha256
            }
            ids.forEach { downloads.remove(it) }
            keyValues.putString(KEY_DOWNLOADS, null)
            if (!ok) {
                removeFiles()
                return@withContext fail(model, Reason.HASH_MISMATCH)
            }
            keyValues.putString(KEY_VERIFIED, model.id)
            State.Ready(model, storedProbe(model)).also { _state.value = it }
        }
    }

    /** The first mirror that holds the file with the expected size, else Hugging Face. */
    internal suspend fun sourceFor(file: ModelFile): String {
        for (url in VisionModels.mirrorUrls(file)) {
            val head = try {
                http.request("HEAD", url, emptyMap(), null, file.bytes, MIRROR_TIMEOUT_SECONDS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val length = (head as? HttpResult.Ok)?.response?.takeIf { it.status == 200 }?.headers?.get("content-length")?.toLongOrNull()
            if (length == file.bytes) return url
        }
        return file.url
    }

    fun saveProbe(model: VisionModel, probe: Probe) {
        keyValues.putString(KEY_PROBE_MODEL, model.id)
        keyValues.putString(KEY_PROBE_MS, probe.estimatedMs.toString())
        keyValues.putString(KEY_PROBE_VERDICT, probe.verdict.name)
        val current = _state.value
        if (current is State.Ready && current.model == model) _state.value = current.copy(probe = probe)
    }

    /** Cancels a running download or removes an installed model. */
    suspend fun remove() = mutex.withLock {
        follower?.cancel()
        follower = null
        withContext(io) {
            storedIds().forEach { downloads.remove(it) }
            removeFiles()
            clearKeys()
            _state.value = State.Missing
        }
    }

    private fun fail(model: VisionModel, reason: Reason): State {
        // A model has two files: when one fails, the other transfer (gigabytes,
        // perhaps on mobile data) must not keep running out of reach of remove().
        storedIds().forEach { downloads.remove(it) }
        keyValues.putString(KEY_DOWNLOADS, null)
        keyValues.putString(KEY_VERIFIED, null)
        removeFiles()
        return State.Failed(model, reason).also { _state.value = it }
    }

    private fun clearKeys() {
        listOf(KEY_MODEL, KEY_DOWNLOADS, KEY_VERIFIED, KEY_PROBE_MODEL, KEY_PROBE_MS, KEY_PROBE_VERDICT)
            .forEach { keyValues.putString(it, null) }
    }

    private fun removeFiles() {
        for (model in models) {
            for (file in model.files) {
                if (files.exists(path(file))) files.delete(path(file))
                // Left behind by the 0.2.4 builds that ran the transfer in the app.
                if (files.exists(partPath(file))) files.delete(partPath(file))
            }
        }
    }

    companion object {
        private const val DIR_PREFIX = "foodvision-"
        private const val KEY_MODEL = "foodvision.model"
        private const val KEY_DOWNLOADS = "foodvision.downloads"
        private const val KEY_VERIFIED = "foodvision.verified"
        private const val KEY_PROBE_MODEL = "foodvision.probe_model"
        private const val KEY_PROBE_MS = "foodvision.probe_ms"
        private const val KEY_PROBE_VERDICT = "foodvision.probe_verdict"
        private const val SPACE_MARGIN_BYTES = 300L * 1024 * 1024
        private const val MIRROR_TIMEOUT_SECONDS = 5

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
