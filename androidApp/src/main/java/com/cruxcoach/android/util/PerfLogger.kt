package com.cruxcoach.android.util

import android.os.Debug
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import com.cruxcoach.android.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Opt-in performance diagnostics for startup, queries, navigation, memory and
 * frame pacing. Debug builds enable it automatically. A release build enables
 * it for the next process start with:
 *
 * `adb shell setprop log.tag.PERF DEBUG`
 *
 * Diagnostic events use Log.i so the release shrinker keeps them; the runtime
 * DEBUG property is the explicit gate that prevents steady-state collection.
 */
object PerfLogger {
    const val TAG = "PERF"
    @JvmField val appStartMs = SystemClock.elapsedRealtime()

    private const val MAX_QUERY_LABELS = 64
    private const val OVERFLOW_QUERY_LABEL = "other"

    private val frameCount = AtomicLong(0)
    private val droppedFrameCount = AtomicLong(0)
    private var frameCallbackActive = false
    private var frameCallback: Choreographer.FrameCallback? = null
    private var frameChoreographer: Choreographer? = null

    private data class Milestone(val uptimeMs: Long, val label: String, val durationMs: Long?)
    private val milestones = ConcurrentLinkedQueue<Milestone>()
    private val startupReported = AtomicBoolean(false)

    @PublishedApi
    internal fun diagnosticEnabled(): Boolean =
        BuildConfig.DEBUG || Log.isLoggable(TAG, Log.DEBUG)

    @PublishedApi
    internal inline fun emit(message: () -> String) {
        if (diagnosticEnabled()) Log.i(TAG, message())
    }

    fun milestone(label: String) {
        if (!diagnosticEnabled()) return
        val t = uptimeMs()
        milestones.add(Milestone(t, label, null))
        emit { "[+${t}ms] 📍 $label" }
    }

    fun milestone(label: String, durationMs: Long) {
        if (!diagnosticEnabled()) return
        val t = uptimeMs()
        milestones.add(Milestone(t, label, durationMs))
        val level = when {
            durationMs > 100 -> "🔴"
            durationMs > 30 -> "🟡"
            else -> "🟢"
        }
        emit { "[+${t}ms] $level $label (${durationMs}ms)" }
    }

    /** Emits one log event per record so logcat and parsers keep boundaries. */
    fun reportStartupTimeline() {
        if (!diagnosticEnabled() || !startupReported.compareAndSet(false, true)) return
        val total = uptimeMs()
        emit { "event=startup_summary totalMs=$total milestones=${milestones.size}" }
        var previous = 0L
        for (m in milestones) {
            val delta = m.uptimeMs - previous
            emit {
                "event=startup_milestone uptimeMs=${m.uptimeMs} deltaMs=$delta " +
                    "durationMs=${m.durationMs ?: -1} label=${m.label}"
            }
            previous = m.uptimeMs
        }
        reportQueryStats(clearAfter = false)
        milestones.clear()
    }

    fun uptimeMs(): Long = SystemClock.elapsedRealtime() - appStartMs

    fun log(msg: String) {
        emit { "[+${uptimeMs()}ms] $msg" }
    }

    /** Actual failures remain visible even when performance diagnostics are off. */
    fun warn(msg: String) {
        Log.w(TAG, "[+${uptimeMs()}ms] $msg")
    }

    /** Actual failures remain visible even when performance diagnostics are off. */
    fun warn(msg: String, t: Throwable) {
        Log.w(TAG, "[+${uptimeMs()}ms] $msg", t)
    }

    inline fun <T> trace(label: String, block: () -> T): T {
        if (!diagnosticEnabled()) return block()
        val thread = if (Looper.myLooper() == Looper.getMainLooper()) "MAIN" else "IO"
        val start = SystemClock.elapsedRealtime()
        val result = block()
        val elapsed = SystemClock.elapsedRealtime() - start
        val level = when {
            elapsed > 100 -> "🔴"
            elapsed > 30 -> "🟡"
            else -> "🟢"
        }
        emit { "[+${start - appStartMs}ms][$thread] $level $label took ${elapsed}ms" }
        return result
    }

    suspend inline fun <T> traceSuspend(label: String, block: () -> T): T {
        if (!diagnosticEnabled()) return block()
        val startThread = Thread.currentThread().name
        val start = SystemClock.elapsedRealtime()
        val result = block()
        val elapsed = SystemClock.elapsedRealtime() - start
        val endThread = Thread.currentThread().name
        val threadInfo = if (startThread == endThread) startThread else "$startThread->$endThread"
        val level = when {
            elapsed > 100 -> "🔴"
            elapsed > 30 -> "🟡"
            else -> "🟢"
        }
        emit { "[+${start - appStartMs}ms][$threadInfo] $level $label took ${elapsed}ms" }
        return result
    }

    @PublishedApi
    internal data class QueryStats(
        val count: AtomicLong = AtomicLong(0),
        val totalMs: AtomicLong = AtomicLong(0),
        val maxMs: AtomicLong = AtomicLong(0),
    )

    @PublishedApi
    internal val dbQueryStats = ConcurrentHashMap<String, QueryStats>()

    @PublishedApi
    internal fun boundedQueryLabel(label: String): String {
        if (dbQueryStats.containsKey(label) || dbQueryStats.size < MAX_QUERY_LABELS) return label
        return OVERFLOW_QUERY_LABEL
    }

    inline fun <T> traceQuery(label: String, block: () -> T): T {
        if (!diagnosticEnabled()) return block()
        val start = SystemClock.elapsedRealtime()
        val result = block()
        val elapsed = SystemClock.elapsedRealtime() - start
        val stableLabel = boundedQueryLabel(label)
        val stats = dbQueryStats.getOrPut(stableLabel) { QueryStats() }
        stats.count.incrementAndGet()
        stats.totalMs.addAndGet(elapsed)
        stats.maxMs.updateAndGet { maxOf(it, elapsed) }
        if (elapsed > 50) {
            emit { "event=slow_query label=$stableLabel durationMs=$elapsed uptimeMs=${start - appStartMs}" }
        }
        return result
    }

    fun reportQueryStats(clearAfter: Boolean = false) {
        if (!diagnosticEnabled()) return
        val stats = dbQueryStats.toList().sortedByDescending { it.second.totalMs.get() }
        for ((label, value) in stats) {
            val count = value.count.get()
            val totalMs = value.totalMs.get()
            val averageMs = if (count > 0) totalMs / count else 0
            emit {
                "event=query_summary label=$label calls=$count totalMs=$totalMs " +
                    "avgMs=$averageMs maxMs=${value.maxMs.get()}"
            }
        }
        if (clearAfter) dbQueryStats.clear()
    }

    fun logMemory(label: String) {
        if (!diagnosticEnabled()) return
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val totalMb = runtime.totalMemory() / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        val nativeKb = Debug.getNativeHeapAllocatedSize() / 1024
        emit {
            "event=memory label=$label heapUsedMb=$usedMb heapTotalMb=$totalMb " +
                "heapMaxMb=$maxMb nativeKb=$nativeKb uptimeMs=${uptimeMs()}"
        }
    }

    /** Starts the main-thread frame callback only while diagnostics are enabled. */
    fun startFrameMonitor() {
        if (!diagnosticEnabled() || frameCallbackActive) return
        frameCallbackActive = true
        frameCount.set(0)
        droppedFrameCount.set(0)
        val choreographer = Choreographer.getInstance()
        frameChoreographer = choreographer
        var lastFrameNanos = 0L
        var summaryStart = SystemClock.elapsedRealtime()

        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!frameCallbackActive || !diagnosticEnabled()) {
                    frameCallbackActive = false
                    frameCallback = null
                    frameChoreographer = null
                    dbQueryStats.clear()
                    return
                }
                if (lastFrameNanos > 0) {
                    val durationMs = (frameTimeNanos - lastFrameNanos) / 1_000_000
                    frameCount.incrementAndGet()
                    if (durationMs > 20) {
                        droppedFrameCount.incrementAndGet()
                        if (durationMs > 50) {
                            emit { "event=jank frameMs=$durationMs uptimeMs=${uptimeMs()}" }
                        }
                    }
                }
                lastFrameNanos = frameTimeNanos

                val now = SystemClock.elapsedRealtime()
                if (now - summaryStart >= 5_000) {
                    val total = frameCount.getAndSet(0)
                    val dropped = droppedFrameCount.getAndSet(0)
                    val percent = if (total > 0) dropped * 100 / total else 0
                    emit {
                        "event=frame_summary frames=$total dropped=$dropped droppedPct=$percent " +
                            "windowMs=${now - summaryStart}"
                    }
                    logMemory("periodic")
                    summaryStart = now
                }
                if (frameCallbackActive) choreographer.postFrameCallback(this)
            }
        }
        frameCallback = callback
        choreographer.postFrameCallback(callback)
        log("🖼 Frame monitor started")
    }

    fun stopFrameMonitor() {
        if (!frameCallbackActive) return
        frameCallbackActive = false
        frameCallback?.let { callback -> frameChoreographer?.removeFrameCallback(callback) }
        frameCallback = null
        frameChoreographer = null
        reportQueryStats(clearAfter = true)
        log("🖼 Frame monitor stopped")
    }

    fun logCoroutine(scope: String, action: String) {
        emit {
            "[+${uptimeMs()}ms] 🔄 COROUTINE [$scope] $action " +
                "(thread=${Thread.currentThread().name})"
        }
    }

    private val navStartMs = AtomicLong(0)

    fun navStart(from: String, to: String) {
        if (!diagnosticEnabled()) return
        val now = SystemClock.elapsedRealtime()
        navStartMs.set(now)
        emit { "[+${now - appStartMs}ms] 🧭 NAV START: $from → $to" }
    }

    fun navMilestone(label: String) {
        if (!diagnosticEnabled()) return
        val start = navStartMs.get()
        val now = SystemClock.elapsedRealtime()
        val sinceNav = if (start > 0) now - start else -1
        val level = when {
            sinceNav > 200 -> "🔴"
            sinceNav > 80 -> "🟡"
            else -> "🟢"
        }
        emit { "[+${now - appStartMs}ms] 🧭 $level NAV +${sinceNav}ms: $label" }
    }

    fun navEnd(label: String) {
        if (!diagnosticEnabled()) return
        val start = navStartMs.get()
        val now = SystemClock.elapsedRealtime()
        val total = if (start > 0) now - start else -1
        val level = when {
            total > 300 -> "🔴"
            total > 150 -> "🟡"
            else -> "🟢"
        }
        emit { "[+${now - appStartMs}ms] 🧭 $level NAV COMPLETE: $label (total=${total}ms)" }
        navStartMs.set(0)
    }
}
