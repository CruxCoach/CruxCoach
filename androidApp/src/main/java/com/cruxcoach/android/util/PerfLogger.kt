package com.cruxcoach.android.util

import android.os.Debug
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Performance profiler for diagnosing startup latency, DB query times, and jank.
 * All output uses tag "PERF" — filter with: adb logcat -s PERF
 *
 * Features:
 * - Phase timing (trace { } blocks for sync and suspend)
 * - Startup timeline (ordered milestones from process start)
 * - DB query profiling (aggregate stats per query label)
 * - Frame drop detection (Choreographer-based)
 * - Memory snapshots
 * - DataStore read timing
 */
object PerfLogger {
    const val TAG = "PERF"
    @JvmField val appStartMs = SystemClock.elapsedRealtime()
    private val frameCount = AtomicLong(0)
    private val droppedFrameCount = AtomicLong(0)
    private var frameCallbackActive = false

    // ── Startup timeline ──────────────────────────────────────────────

    private data class Milestone(val uptimeMs: Long, val label: String, val durationMs: Long?)
    private val milestones = ConcurrentLinkedQueue<Milestone>()
    private var startupReported = false

    /** Record a startup milestone (no duration, just "reached X at T"). */
    fun milestone(label: String) {
        val t = uptimeMs()
        milestones.add(Milestone(t, label, null))
        Log.d(TAG, "[+${t}ms] 📍 $label")
    }

    /** Record a timed startup milestone. */
    fun milestone(label: String, durationMs: Long) {
        val t = uptimeMs()
        milestones.add(Milestone(t, label, durationMs))
        val level = when {
            durationMs > 100 -> "🔴"
            durationMs > 30  -> "🟡"
            else             -> "🟢"
        }
        Log.d(TAG, "[+${t}ms] $level $label (${durationMs}ms)")
    }

    /**
     * Print the full startup timeline. Call once when the first meaningful
     * content is visible (e.g., climb list populated in BoardBrowserVM).
     */
    fun reportStartupTimeline() {
        if (startupReported) return
        startupReported = true
        val total = uptimeMs()
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("╔══════════════════════════════════════════════════════════════")
        sb.appendLine("║  STARTUP TIMELINE (total: ${total}ms)")
        sb.appendLine("╠══════════════════════════════════════════════════════════════")
        var prev = 0L
        for (m in milestones) {
            val delta = m.uptimeMs - prev
            val dur = if (m.durationMs != null) " (took ${m.durationMs}ms)" else ""
            val level = when {
                m.durationMs != null && m.durationMs > 100 -> "🔴"
                m.durationMs != null && m.durationMs > 30  -> "🟡"
                delta > 100 -> "🔴"
                delta > 30  -> "🟡"
                else -> "🟢"
            }
            sb.appendLine("║  +${m.uptimeMs}ms (+${delta}ms) $level ${m.label}$dur")
            prev = m.uptimeMs
        }
        sb.appendLine("╠══════════════════════════════════════════════════════════════")
        // DB query summary
        val stats = dbQueryStats.toList().sortedByDescending { it.second.totalMs.get() }
        if (stats.isNotEmpty()) {
            sb.appendLine("║  DB QUERY SUMMARY (startup)")
            for ((label, s) in stats) {
                val count = s.count.get()
                val totalMs = s.totalMs.get()
                val maxMs = s.maxMs.get()
                val avgMs = if (count > 0) totalMs / count else 0
                val level = if (maxMs > 50) "🔴" else if (maxMs > 15) "🟡" else "🟢"
                sb.appendLine("║    $level $label: ${count}x, total=${totalMs}ms, max=${maxMs}ms, avg=${avgMs}ms")
            }
            sb.appendLine("╠══════════════════════════════════════════════════════════════")
        }
        // Pref read summary
        val prefStats = prefReadStats.toList().sortedByDescending { it.second.totalMs.get() }
        if (prefStats.isNotEmpty()) {
            val prefTotal = prefStats.sumOf { it.second.totalMs.get() }
            val prefCount = prefStats.sumOf { it.second.count.get() }
            sb.appendLine("║  DATASTORE READS: ${prefCount}x total, ${prefTotal}ms total")
            for ((label, s) in prefStats.take(10)) {
                val count = s.count.get()
                val totalMs = s.totalMs.get()
                val maxMs = s.maxMs.get()
                sb.appendLine("║    $label: ${count}x, total=${totalMs}ms, max=${maxMs}ms")
            }
            sb.appendLine("╠══════════════════════════════════════════════════════════════")
        }
        sb.appendLine("║  FIRST CONTENT AT: +${total}ms")
        sb.appendLine("╚══════════════════════════════════════════════════════════════")
        Log.i(TAG, sb.toString())
    }

    // ── Core timing ───────────────────────────────────────────────────

    /** Milliseconds since app process start. */
    fun uptimeMs(): Long = SystemClock.elapsedRealtime() - appStartMs

    /** Log a message with uptime prefix. */
    fun log(msg: String) {
        Log.d(TAG, "[+${uptimeMs()}ms] $msg")
    }

    /** Log a warning with uptime prefix. */
    fun warn(msg: String) {
        Log.w(TAG, "[+${uptimeMs()}ms] $msg")
    }

    /** Time a synchronous block and log it. Returns the block's result. */
    inline fun <T> trace(label: String, block: () -> T): T {
        val thread = if (Looper.myLooper() == Looper.getMainLooper()) "MAIN" else "IO"
        val start = SystemClock.elapsedRealtime()
        val result = block()
        val elapsed = SystemClock.elapsedRealtime() - start
        val level = when {
            elapsed > 100 -> "🔴"
            elapsed > 30  -> "🟡"
            else          -> "🟢"
        }
        Log.d(TAG, "[+${start - appStartMs}ms][$thread] $level $label took ${elapsed}ms")
        return result
    }

    /**
     * Time a suspend block. Same as [trace] but works with coroutines.
     * Measures wall clock time including suspension points.
     */
    suspend inline fun <T> traceSuspend(label: String, block: () -> T): T {
        val thread = Thread.currentThread().name
        val start = SystemClock.elapsedRealtime()
        val result = block()
        val elapsed = SystemClock.elapsedRealtime() - start
        val level = when {
            elapsed > 100 -> "🔴"
            elapsed > 30  -> "🟡"
            else          -> "🟢"
        }
        val endThread = Thread.currentThread().name
        val threadInfo = if (thread == endThread) thread else "$thread->$endThread"
        Log.d(TAG, "[+${start - appStartMs}ms][$threadInfo] $level $label took ${elapsed}ms")
        return result
    }

    // ── DB query profiling ────────────────────────────────────────────

    @PublishedApi internal data class QueryStats(
        val count: AtomicLong = AtomicLong(0),
        val totalMs: AtomicLong = AtomicLong(0),
        val maxMs: AtomicLong = AtomicLong(0)
    )
    @PublishedApi internal val dbQueryStats = java.util.concurrent.ConcurrentHashMap<String, QueryStats>()

    /** Time a database query and aggregate stats. */
    inline fun <T> traceQuery(label: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        val result = block()
        val elapsed = SystemClock.elapsedRealtime() - start
        val stats = dbQueryStats.getOrPut(label) { QueryStats() }
        stats.count.incrementAndGet()
        stats.totalMs.addAndGet(elapsed)
        stats.maxMs.updateAndGet { maxOf(it, elapsed) }
        if (elapsed > 50) {
            Log.w(TAG, "[+${start - appStartMs}ms] 🗄 SLOW QUERY: $label took ${elapsed}ms")
        }
        return result
    }

    /** Print all DB query stats collected so far. */
    fun reportQueryStats() {
        val stats = dbQueryStats.toList().sortedByDescending { it.second.totalMs.get() }
        if (stats.isEmpty()) return
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("╔══════════════════════════════════════════════════════════════")
        sb.appendLine("║  DB QUERY PERFORMANCE REPORT")
        sb.appendLine("╠══════════════════════════════════════════════════════════════")
        for ((label, s) in stats) {
            val count = s.count.get()
            val totalMs = s.totalMs.get()
            val maxMs = s.maxMs.get()
            val avgMs = if (count > 0) totalMs / count else 0
            val level = if (maxMs > 50) "🔴" else if (maxMs > 15) "🟡" else "🟢"
            sb.appendLine("║  $level $label")
            sb.appendLine("║     calls=${count}, total=${totalMs}ms, avg=${avgMs}ms, max=${maxMs}ms")
        }
        sb.appendLine("╚══════════════════════════════════════════════════════════════")
        Log.i(TAG, sb.toString())
    }

    // ── DataStore pref read profiling ─────────────────────────────────

    @PublishedApi internal val prefReadStats = java.util.concurrent.ConcurrentHashMap<String, QueryStats>()

    /** Time a DataStore .first() read. */
    suspend inline fun <T> tracePref(key: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        val result = block()
        val elapsed = SystemClock.elapsedRealtime() - start
        val stats = prefReadStats.getOrPut(key) { QueryStats() }
        stats.count.incrementAndGet()
        stats.totalMs.addAndGet(elapsed)
        stats.maxMs.updateAndGet { maxOf(it, elapsed) }
        if (elapsed > 30) {
            Log.w(TAG, "[+${start - appStartMs}ms] ⚙ SLOW PREF: $key took ${elapsed}ms")
        }
        return result
    }

    // ── Memory ────────────────────────────────────────────────────────

    /** Log current heap memory usage. */
    fun logMemory(label: String) {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val totalMb = runtime.totalMemory() / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        val nativeKb = Debug.getNativeHeapAllocatedSize() / 1024
        Log.d(TAG, "[+${uptimeMs()}ms] 📊 MEM $label: heap=${usedMb}/${totalMb}MB (max=${maxMb}MB), native=${nativeKb}KB")
    }

    // ── Frame monitoring ──────────────────────────────────────────────

    /**
     * Start monitoring frame drops via Choreographer.
     * Call once from Activity.onCreate().
     * Logs every time a frame takes >20ms (dropped frame).
     * Logs a summary every 5 seconds.
     */
    fun startFrameMonitor() {
        if (frameCallbackActive) return
        frameCallbackActive = true
        val choreographer = Choreographer.getInstance()
        var lastFrameNanos = 0L
        var summaryStart = SystemClock.elapsedRealtime()

        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastFrameNanos > 0) {
                    val frameDurationMs = (frameTimeNanos - lastFrameNanos) / 1_000_000
                    frameCount.incrementAndGet()
                    if (frameDurationMs > 20) {
                        droppedFrameCount.incrementAndGet()
                        if (frameDurationMs > 50) {
                            Log.w(TAG, "[+${uptimeMs()}ms] 🖼 JANK: frame took ${frameDurationMs}ms")
                        }
                    }
                }
                lastFrameNanos = frameTimeNanos

                // Summary every 5 seconds
                val now = SystemClock.elapsedRealtime()
                if (now - summaryStart >= 5_000) {
                    val total = frameCount.get()
                    val dropped = droppedFrameCount.get()
                    val pct = if (total > 0) dropped * 100 / total else 0
                    Log.d(TAG, "[+${uptimeMs()}ms] 🖼 FRAMES: total=$total, dropped=$dropped ($pct%), avg=${if (total > 0) (now - summaryStart) / total else 0}ms/frame")
                    logMemory("periodic")
                    frameCount.set(0)
                    droppedFrameCount.set(0)
                    summaryStart = now
                }

                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
        log("🖼 Frame monitor started")
    }

    /** Log when a coroutine scope launches work. */
    fun logCoroutine(scope: String, action: String) {
        val thread = Thread.currentThread().name
        Log.d(TAG, "[+${uptimeMs()}ms] 🔄 COROUTINE [$scope] $action (thread=$thread)")
    }

    // ── Navigation profiling ──────────────────────────────────────────

    private val navStartMs = AtomicLong(0)

    /** Call when a navigation click happens (start of navigation pipeline). */
    fun navStart(from: String, to: String) {
        val t = SystemClock.elapsedRealtime()
        navStartMs.set(t)
        Log.d(TAG, "[+${t - appStartMs}ms] 🧭 NAV START: $from → $to")
    }

    /** Log a navigation milestone (relative to navStart). */
    fun navMilestone(label: String) {
        val start = navStartMs.get()
        val now = SystemClock.elapsedRealtime()
        val sinceNav = if (start > 0) now - start else -1
        val level = when {
            sinceNav > 200 -> "🔴"
            sinceNav > 80  -> "🟡"
            else           -> "🟢"
        }
        Log.d(TAG, "[+${now - appStartMs}ms] 🧭 $level NAV +${sinceNav}ms: $label")
    }

    /** Call when the destination screen's first meaningful content is visible. */
    fun navEnd(label: String) {
        val start = navStartMs.get()
        val now = SystemClock.elapsedRealtime()
        val total = if (start > 0) now - start else -1
        val level = when {
            total > 300 -> "🔴"
            total > 150 -> "🟡"
            else        -> "🟢"
        }
        Log.d(TAG, "[+${now - appStartMs}ms] 🧭 $level NAV COMPLETE: $label (total=${total}ms)")
        navStartMs.set(0)
    }
}
