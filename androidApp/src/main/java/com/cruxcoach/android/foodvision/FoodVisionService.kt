package com.cruxcoach.android.foodvision

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Runs the photo model in its own ":vision" process (FEAT-069).
 *
 * The model needs 2–4 GB. If Android reclaims that memory mid-run, only this
 * process dies; the app, its SQLCipher databases and the open screen
 * survive and the client reports the failure. The service is not part of
 * the Hilt graph and touches no app data: it reads the model files and one
 * raw RGB file from the cache and answers with the model's text.
 */
class FoodVisionService : Service() {

    private lateinit var ipcThread: HandlerThread
    private lateinit var messenger: Messenger
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "vision-worker") }

    @Volatile private var handle = 0L
    private var loadedKey: String? = null

    override fun onCreate() {
        super.onCreate()
        ipcThread = HandlerThread("vision-ipc").apply { start() }
        messenger = Messenger(IncomingHandler(ipcThread.looper))
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        // Returning 2–4 GB matters more than a tidy shutdown: a run may still
        // be inside native code, so end the whole process instead of freeing.
        super.onDestroy()
        if (Application.getProcessName().endsWith(VisionProtocol.PROCESS_SUFFIX)) Process.killProcess(Process.myPid())
    }

    private inner class IncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                VisionProtocol.MSG_ANALYZE -> {
                    val replyTo = msg.replyTo ?: return
                    val requestId = msg.arg1
                    val request = Bundle(msg.data)
                    worker.execute {
                        val result = runCatching { analyze(request) }.getOrElse { e ->
                            Log.w(TAG, "analysis failed", e)
                            VisionProtocol.failure(e.javaClass.simpleName)
                        }
                        try {
                            replyTo.send(Message.obtain(null, VisionProtocol.MSG_RESULT).apply {
                                arg1 = requestId
                                data = result
                            })
                        } catch (e: RemoteException) {
                            Log.w(TAG, "client gone", e)
                        }
                    }
                }
                VisionProtocol.MSG_CANCEL -> {
                    val h = handle
                    if (h != 0L) NativeFoodVision.nativeCancel(h)
                }
            }
        }
    }

    private fun analyze(request: Bundle): Bundle {
        NativeFoodVision.ensureLoaded()
        val model = request.getString(VisionProtocol.KEY_MODEL).orEmpty()
        val mmproj = request.getString(VisionProtocol.KEY_MMPROJ).orEmpty()
        val threads = request.getInt(VisionProtocol.KEY_THREADS, 4)
        val key = "$model|$mmproj|$threads"
        var loadMs = 0L
        if (handle == 0L || key != loadedKey) {
            if (handle != 0L) NativeFoodVision.nativeFree(handle)
            handle = 0L
            val started = SystemClock.elapsedRealtime()
            val h = NativeFoodVision.nativeLoad(model, mmproj, threads, VisionProtocol.CONTEXT_TOKENS, VisionProtocol.IMAGE_MAX_TOKENS)
            loadMs = SystemClock.elapsedRealtime() - started
            if (h == 0L) return VisionProtocol.failure(NativeFoodVision.nativeLastLoadError().ifEmpty { "model_load_failed" })
            handle = h
            loadedKey = key
        }
        val rgbFile = File(request.getString(VisionProtocol.KEY_RGB_PATH).orEmpty())
        val rgb = rgbFile.readBytes()
        val bytes = NativeFoodVision.nativeRun(
            handle,
            rgb,
            request.getInt(VisionProtocol.KEY_WIDTH),
            request.getInt(VisionProtocol.KEY_HEIGHT),
            request.getString(VisionProtocol.KEY_SYSTEM).orEmpty(),
            request.getString(VisionProtocol.KEY_USER).orEmpty(),
            request.getString(VisionProtocol.KEY_GRAMMAR).orEmpty(),
            request.getInt(VisionProtocol.KEY_MAX_TOKENS, VisionProtocol.MAX_TOKENS),
        )
        return Bundle().apply {
            putString(VisionProtocol.KEY_JSON, String(bytes, Charsets.UTF_8))
            putLong(VisionProtocol.KEY_LOAD_MS, loadMs)
        }
    }

    private companion object {
        const val TAG = "FoodVisionService"
    }
}

/** Message contract between [FoodVisionClient] and [FoodVisionService]. */
internal object VisionProtocol {
    const val PROCESS_SUFFIX = ":vision"
    const val MSG_ANALYZE = 1
    const val MSG_CANCEL = 2
    const val MSG_RESULT = 3

    const val KEY_MODEL = "model"
    const val KEY_MMPROJ = "mmproj"
    const val KEY_THREADS = "threads"
    const val KEY_RGB_PATH = "rgb"
    const val KEY_WIDTH = "width"
    const val KEY_HEIGHT = "height"
    const val KEY_SYSTEM = "system"
    const val KEY_USER = "user"
    const val KEY_GRAMMAR = "grammar"
    const val KEY_MAX_TOKENS = "maxTokens"
    const val KEY_JSON = "json"
    const val KEY_LOAD_MS = "loadMs"

    /** Prompt + ~300 image tokens + answer fit comfortably. */
    const val CONTEXT_TOKENS = 2048
    /** Caps the image tokens the projector may produce for one photo. */
    const val IMAGE_MAX_TOKENS = 512
    const val MAX_TOKENS = 384

    fun failure(code: String): Bundle = Bundle().apply {
        putString(KEY_JSON, "{\"ok\":false,\"error\":\"${code.replace("\"", "")}\"}")
    }
}
