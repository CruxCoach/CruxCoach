package com.cruxcoach.android.foodvision

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/** One analysed photo as the native engine reported it. */
data class VisionOutcome(
    val ok: Boolean,
    val error: String?,
    val text: String,
    val promptTokens: Int = 0,
    val outputTokens: Int = 0,
    val imageMs: Long = 0,
    val generateMs: Long = 0,
    val loadMs: Long = 0,
) {
    companion object {
        /** The ":vision" process died, almost always because Android needed the memory. */
        const val PROCESS_DIED = "process_died"
        const val BIND_FAILED = "bind_failed"
        const val CANCELLED = "cancelled"

        fun parse(json: String, loadMs: Long): VisionOutcome = runCatching {
            val o = JSONObject(json)
            VisionOutcome(
                ok = o.optBoolean("ok"),
                error = o.optString("error").ifEmpty { null },
                text = o.optString("text"),
                promptTokens = o.optInt("promptTokens"),
                outputTokens = o.optInt("outputTokens"),
                imageMs = o.optLong("imageMs"),
                generateMs = o.optLong("generateMs"),
                loadMs = loadMs,
            )
        }.getOrElse { VisionOutcome(false, "bad_reply", "") }
    }
}

data class VisionRequest(
    val model: VisionModel,
    val weightsPath: String,
    val projectorPath: String,
    val image: PreparedImage,
    val prompt: VisionPrompt,
    val maxTokens: Int = VisionProtocol.MAX_TOKENS,
)

/**
 * Talks to [FoodVisionService] from the main process. Keep one instance
 * while the photo screen is open — the model then stays loaded between
 * photos — and call [release] when it closes, which lets the ":vision"
 * process end and return its memory. Main thread only.
 */
class FoodVisionClient(private val context: Context) {

    private var service: Messenger? = null
    private var connection: ServiceConnection? = null
    private var pending: CancellableContinuation<Bundle?>? = null
    private var requestId = 0

    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
        // A cancelled run still answers; only the current request may resume.
        if (msg.what == VisionProtocol.MSG_RESULT && msg.arg1 == requestId) {
            val data = msg.data
            pending?.let { if (it.isActive) it.resume(data) }
            pending = null
        }
        true
    })

    suspend fun analyze(request: VisionRequest): VisionOutcome {
        val target = connect() ?: return VisionOutcome(false, VisionOutcome.BIND_FAILED, "")
        val payload = Bundle().apply {
            putString(VisionProtocol.KEY_MODEL, request.weightsPath)
            putString(VisionProtocol.KEY_MMPROJ, request.projectorPath)
            putInt(VisionProtocol.KEY_THREADS, DeviceFactsReader.inferenceThreads())
            putString(VisionProtocol.KEY_RGB_PATH, request.image.rgbFile.absolutePath)
            putInt(VisionProtocol.KEY_WIDTH, request.image.width)
            putInt(VisionProtocol.KEY_HEIGHT, request.image.height)
            putString(VisionProtocol.KEY_SYSTEM, request.prompt.system)
            putString(VisionProtocol.KEY_USER, request.prompt.user)
            putString(VisionProtocol.KEY_GRAMMAR, request.prompt.grammar)
            putInt(VisionProtocol.KEY_MAX_TOKENS, request.maxTokens)
        }
        val id = ++requestId
        val reply = suspendCancellableCoroutine<Bundle?> { cont ->
            pending = cont
            try {
                target.send(Message.obtain(null, VisionProtocol.MSG_ANALYZE).apply {
                    arg1 = id
                    data = payload
                    replyTo = replies
                })
            } catch (e: RemoteException) {
                pending = null
                cont.resume(null)
            }
            cont.invokeOnCancellation {
                runCatching { target.send(Message.obtain(null, VisionProtocol.MSG_CANCEL)) }
            }
        } ?: return VisionOutcome(false, VisionOutcome.PROCESS_DIED, "")
        return VisionOutcome.parse(reply.getString(VisionProtocol.KEY_JSON).orEmpty(), reply.getLong(VisionProtocol.KEY_LOAD_MS))
    }

    private suspend fun connect(): Messenger? {
        service?.let { return it }
        return suspendCancellableCoroutine { cont ->
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val messenger = Messenger(binder)
                    service = messenger
                    if (cont.isActive) cont.resume(messenger)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    // The process died. A running analysis will never answer.
                    service = null
                    pending?.let { if (it.isActive) it.resume(null) }
                    pending = null
                }

                override fun onBindingDied(name: ComponentName) {
                    onServiceDisconnected(name)
                    release()
                }

                override fun onNullBinding(name: ComponentName) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            connection = conn
            val bound = runCatching {
                context.bindService(Intent(context, FoodVisionService::class.java), conn, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) {
                connection = null
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    fun release() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
        pending?.let { if (it.isActive) it.resume(null) }
        pending = null
    }
}
