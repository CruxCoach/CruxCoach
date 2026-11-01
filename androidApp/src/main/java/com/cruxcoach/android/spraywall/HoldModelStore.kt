package com.cruxcoach.android.spraywall

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * The hold segmentation model (FEAT-078): BoardSesh's published RF-DETR-Seg
 * nano export (Apache-2.0; trained on the Roboflow climbing-holds-and-volumes
 * set, CC BY 4.0, with masks from d12's MIT model). Not part of the APK: the
 * app downloads it once when the climber asks, from a Blossom mirror when it
 * holds the file and else from the publisher, and runs it only after its size
 * and SHA-256 match the pin below.
 */
object HoldModel {
    const val VERSION = "2026-09-18-seg"
    const val FILE_NAME = "hold-seg-2026-09-18-int8.onnx"
    const val SHA256 = "04847246ceaef144759cd7e1edb00268217a71c1a32709813c2957318572fcb1"
    const val BYTES = 32_536_853L
    const val SOURCE_URL = "https://media.boardsesh.com/models/hold-detector/$VERSION/model-int8.onnx"

    /** Blossom serves a file under its SHA-256, so a hit is the pinned file by construction (still verified). */
    val MIRRORS = listOf("https://blossom.cruxcoach.org")

    val urls: List<String> get() = MIRRORS.map { "${it.trimEnd('/')}/$SHA256" } + SOURCE_URL
}

@Singleton
class HoldModelStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("blossom") private val client: OkHttpClient,
) {
    sealed interface State {
        data object Missing : State
        data class Downloading(val doneBytes: Long) : State
        data object Ready : State
        data object Failed : State
    }

    private val mutex = Mutex()
    private val _state = MutableStateFlow<State>(if (isReady()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    val file: File get() = File(File(context.filesDir, DIR), HoldModel.FILE_NAME)

    /** The file is there with the pinned size; its hash was checked when it was written. */
    fun isReady(): Boolean = file.length() == HoldModel.BYTES

    /** Downloads and verifies the model unless it is already there. */
    suspend fun download(): Boolean = mutex.withLock {
        if (isReady()) {
            _state.value = State.Ready
            return@withLock true
        }
        _state.value = State.Downloading(0)
        val ok = withContext(Dispatchers.IO) { HoldModel.urls.any { url -> fetch(url) } }
        _state.value = if (ok) State.Ready else State.Failed
        ok
    }

    private fun fetch(url: String): Boolean {
        val dir = file.parentFile ?: return false
        dir.mkdirs()
        val part = File(dir, "${HoldModel.FILE_NAME}.part")
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) return false
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            done += n
                            if (done > HoldModel.BYTES) return false
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            _state.value = State.Downloading(done)
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                done == HoldModel.BYTES && hash == HoldModel.SHA256 && part.renameTo(file)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            part.delete()
        }
    }

    fun remove() {
        file.delete()
        _state.value = State.Missing
    }

    companion object {
        const val DIR = "hold_model"
    }
}
