// Port of androidApp/.../foodvision/VisionModelStore.kt (0e1bad0c6) on the
// platform contracts: the same explicit, user-started download, space check,
// mirror choice, SHA-256 verification, probe record and removal. Android hands
// the transfer to DownloadManager; iOS has no such service for an app, so the
// store runs the transfer itself while the app is open and waits, as
// DownloadManager does, while the only network is mobile data the user did
// not allow.
package com.cruxcoach.app.foodvision

import com.cruxcoach.app.platform.FileSystem
import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.athlete.logic.VisionCapability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val http: HttpTransport,
    private val network: VisionNetwork,
    /** Lives as long as the core: a download goes on while the photo sheet is closed. */
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** How often a waiting or running transfer looks at the network again. */
    private val networkCheckMs: Long = 2_000,
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
    private var transfer: Job? = null
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Where a model file lives: the app's work directory, excluded from backups. */
    fun path(file: ModelFile): String = "${files.workDirectory().trimEnd('/')}/$DIR_PREFIX${file.fileName}"

    private fun partPath(file: ModelFile): String = path(file) + ".part"

    private fun initialState(): State {
        val id = keyValues.getString(KEY_MODEL)
        val model = models.firstOrNull { it.id == id }
        if (model != null && keyValues.getString(KEY_VERIFIED) == model.id &&
            model.files.all { files.exists(path(it)) && files.size(path(it)) == it.bytes }
        ) {
            return State.Ready(model, storedProbe(model))
        }
        // A transfer ends with the app on iOS: what an earlier start left behind is
        // incomplete and goes, so the next download starts clean.
        if (model != null) {
            removeFiles()
            clearKeys()
        }
        return State.Missing
    }

    private fun storedProbe(model: VisionModel): Probe? {
        if (keyValues.getString(KEY_PROBE_MODEL) != model.id) return null
        val ms = keyValues.getString(KEY_PROBE_MS)?.toLongOrNull() ?: return null
        val verdict = keyValues.getString(KEY_PROBE_VERDICT)?.let { v ->
            VisionCapability.ProbeVerdict.entries.firstOrNull { it.name == v }
        } ?: return null
        return if (ms >= 0) Probe(ms, verdict) else null
    }

    /** Starts both files. [allowMobile] lets the transfer use mobile data. */
    suspend fun start(model: VisionModel, allowMobile: Boolean): StartResult = mutex.withLock {
        transfer?.cancelAndJoin()
        transfer = null
        withContext(io) {
            if (files.workDirectory().isBlank()) return@withContext StartResult.NoStorage
            removeFiles()
            val needed = model.downloadBytes + SPACE_MARGIN_BYTES
            // A negative answer means the system could not tell, as Android's StatFs fallback.
            val free = files.freeSpaceBytes()
            if (free in 0 until needed) return@withContext StartResult.NotEnoughSpace(needed, free)
            keyValues.putString(KEY_MODEL, model.id)
            keyValues.putString(KEY_VERIFIED, null)
            _state.value = State.Downloading(model, 0, waiting = true)
            transfer = scope.launch(io) { download(model, allowMobile) }
            StartResult.Started
        }
    }

    private suspend fun download(model: VisionModel, allowMobile: Boolean) {
        var done = 0L
        for (file in model.files) {
            if (!transferFile(model, file, done, allowMobile)) {
                fail(model, Reason.DOWNLOAD_FAILED)
                return
            }
            done += file.bytes
        }
        _state.value = State.Verifying(model)
        val ok = model.files.all { f -> hashing.sha256OfFile(path(f))?.let { hex(it) } == f.sha256 }
        if (!ok) {
            removeFiles()
            fail(model, Reason.HASH_MISMATCH)
            return
        }
        keyValues.putString(KEY_VERIFIED, model.id)
        _state.value = State.Ready(model, storedProbe(model))
    }

    private class NetworkBlocked : Exception()

    private fun allowed(allowMobile: Boolean): Boolean = when (network.current()) {
        VisionNetwork.UNMETERED -> true
        VisionNetwork.METERED -> allowMobile
        else -> false
    }

    /** One file to its final name; false when the server or the transfer failed. */
    private suspend fun transferFile(model: VisionModel, file: ModelFile, before: Long, allowMobile: Boolean): Boolean {
        val part = partPath(file)
        var lostConnections = 0
        while (true) {
            if (!allowed(allowMobile)) {
                _state.value = State.Downloading(model, before, waiting = true)
                delay(networkCheckMs)
                continue
            }
            _state.value = State.Downloading(model, before, waiting = false)
            files.delete(part)
            val source = sourceFor(file)
            var shown = 0L
            val result = try {
                whileAllowed(allowMobile) {
                    http.download(source, part, file.bytes, IDLE_TIMEOUT_SECONDS) { received, _ ->
                        // Progress arrives per network block; the screen needs a few steps per second at most.
                        if (received - shown >= PROGRESS_STEP_BYTES || received == file.bytes) {
                            shown = received
                            _state.value = State.Downloading(model, before + received, waiting = false)
                        }
                    }
                }
            } catch (e: NetworkBlocked) {
                files.delete(part)
                continue
            }
            if (result is HttpResult.Ok && result.response.status in 200..299 && files.size(part) == file.bytes) {
                files.delete(path(file))
                return files.move(part, path(file))
            }
            files.delete(part)
            // A connection that drops waits for the network again, like DownloadManager; a
            // server that refuses or sends the wrong size ends the download.
            if (result is HttpResult.Failed && result.reason == HttpFailure.OFFLINE && lostConnections++ < MAX_LOST_CONNECTIONS) continue
            return false
        }
    }

    /** Runs [block] and cancels it as soon as the network stops being one the user allowed. */
    private suspend fun <T> whileAllowed(allowMobile: Boolean, block: suspend () -> T): T = coroutineScope {
        val watcher = launch {
            while (true) {
                delay(networkCheckMs)
                if (!allowed(allowMobile)) throw NetworkBlocked()
            }
        }
        try {
            block()
        } finally {
            watcher.cancel()
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
        transfer?.cancelAndJoin()
        transfer = null
        withContext(io) {
            removeFiles()
            clearKeys()
            _state.value = State.Missing
        }
    }

    private fun fail(model: VisionModel, reason: Reason) {
        removeFiles()
        keyValues.putString(KEY_VERIFIED, null)
        _state.value = State.Failed(model, reason)
    }

    private fun clearKeys() {
        listOf(KEY_MODEL, KEY_VERIFIED, KEY_PROBE_MODEL, KEY_PROBE_MS, KEY_PROBE_VERDICT).forEach { keyValues.putString(it, null) }
    }

    private fun removeFiles() {
        for (model in models) {
            for (file in model.files) {
                if (files.exists(path(file))) files.delete(path(file))
                if (files.exists(partPath(file))) files.delete(partPath(file))
            }
        }
    }

    companion object {
        private const val DIR_PREFIX = "foodvision-"
        private const val KEY_MODEL = "foodvision.model"
        private const val KEY_VERIFIED = "foodvision.verified"
        private const val KEY_PROBE_MODEL = "foodvision.probe_model"
        private const val KEY_PROBE_MS = "foodvision.probe_ms"
        private const val KEY_PROBE_VERDICT = "foodvision.probe_verdict"
        private const val SPACE_MARGIN_BYTES = 300L * 1024 * 1024
        private const val MIRROR_TIMEOUT_SECONDS = 5
        /** No byte for this long ends a transfer (an idle timeout, not a total one). */
        private const val IDLE_TIMEOUT_SECONDS = 60
        private const val PROGRESS_STEP_BYTES = 4L * 1024 * 1024
        private const val MAX_LOST_CONNECTIONS = 20

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
