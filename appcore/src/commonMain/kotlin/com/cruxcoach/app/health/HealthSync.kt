package com.cruxcoach.app.health

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.athlete.logic.BoardOverlap
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDaySource
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.HydrationEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Outcome of the last climbing sync, shown in the settings card (Android `HealthSyncResult`). */
internal data class HealthSyncResult(
    val atMillis: Long,
    val added: Int = 0,
    val updated: Int = 0,
    /** Sessions left out because the athlete already logged that day by hand. */
    val skippedManual: Int = 0,
    /** Sessions the board logbook already counts in part or in full (not counted twice). */
    val boardOverlaps: Int = 0,
    val failed: Boolean = false,
)

/** A climbing workout as the import needs it (Android `HealthSession`). */
internal data class HealthSession(
    val id: String,
    val climbingType: Boolean,
    val title: String?,
    val notes: String?,
    val startMs: Long,
    val endMs: Long,
)

/**
 * The app's one health-store layer on iOS, the port of Android's `HealthConnectSource` (reading, FEAT-071)
 * and `HealthConnectExporter` (writing, FEAT-069). Reading runs only while the athlete turned it on
 * ([com.cruxcoach.athlete.model.CoachProfile.healthConnectEnabled]), writing only while the export is on
 * ([com.cruxcoach.athlete.model.AthleteProfile.healthConnectExport]) and HealthKit reports write access.
 * Every store call is guarded and bounded in time, so a missing store, a missing entitlement or revoked
 * access never crash anything and never count as success.
 */
internal class HealthSync(
    private val service: AthleteService,
    private val store: () -> HealthStore?,
    private val keyValues: KeyValueStore,
    private val io: CoroutineDispatcher,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val now: () -> Long = { nowMs() },
) {
    private val _lastSync = MutableStateFlow<HealthSyncResult?>(null)
    val lastSync: StateFlow<HealthSyncResult?> = _lastSync.asStateFlow()

    private val exportMutex = Mutex()
    private val climbMutex = Mutex()

    /** The store when there is one on this device. */
    fun availableStore(): HealthStore? = store()?.takeIf { s -> runCatching { s.isAvailable() }.getOrDefault(false) }

    /** On only when the athlete turned it on and the store is there. */
    suspend fun readEnabled(): Boolean = withContext(io) {
        availableStore() != null &&
            runCatching { service.ensureReady(); service.repo.profile().coach.healthConnectEnabled }.getOrDefault(false)
    }

    suspend fun exportEnabled(): Boolean = withContext(io) {
        availableStore() != null &&
            runCatching { service.ensureReady(); service.repo.profile().healthConnectExport }.getOrDefault(false)
    }

    // ── Access ───────────────────────────────────────────────────────

    /** "done" or "failed" (the store refused the request: entitlement missing, restricted …). */
    suspend fun requestAccess(read: List<String>, write: List<String>): String {
        val s = availableStore() ?: return FAILED
        // The athlete may leave the sheet open for a while; no time limit here.
        return awaitCallback(FAILED, timeoutMs = null) { done -> s.requestAuthorization(read, write) { done(it) } }
            .takeIf { it == DONE } ?: FAILED
    }

    /** "asked", "notAsked" or "failed". */
    suspend fun requestStatus(read: List<String>, write: List<String>): String {
        val s = availableStore() ?: return FAILED
        return awaitCallback(FAILED, STORE_TIMEOUT_MS) { done -> s.requestStatus(read, write) { done(it) } }
            .takeIf { it == ASKED || it == NOT_ASKED } ?: FAILED
    }

    /** "authorized", "denied" or "notDetermined". */
    fun writeStatus(): String {
        val s = availableStore() ?: return NOT_DETERMINED
        return runCatching { s.writeStatus(HealthTypes.WRITE) }.getOrDefault(NOT_DETERMINED)
            .takeIf { it == AUTHORIZED || it == DENIED } ?: NOT_DETERMINED
    }

    // ── Reading: sleep ───────────────────────────────────────────────

    /**
     * Hours slept last night: sleep that ends between 15:00 yesterday and 15:00 today, merged, minus awake
     * time. Null when off, without access or without data — never a guess. HealthKit has no sessions:
     * in-bed and asleep samples stand in for Health Connect's session span, awake samples for its awake stages.
     */
    suspend fun lastNightSleepHours(today: LocalDate): Double? {
        if (!readEnabled()) return null
        val s = availableStore() ?: return null
        val tz = zone()
        val windowEnd = LocalDateTime(today, LocalTime(15, 0)).toInstant(tz).toEpochMilliseconds()
        val windowStart = windowEnd - DAY_MS
        // Sleep may start before the window (went to bed at 22:00), so read a wider range
        // and keep what ends inside it.
        val result = awaitCallback<HealthSleepResult?>(null, STORE_TIMEOUT_MS) { done ->
            s.sleepSamples(windowStart - 12 * HOUR_MS, windowEnd) { done(it) }
        } ?: return null
        if (!result.ok) return null
        return sleepHours(result.samples, windowStart, windowEnd)
    }

    // ── Reading: climbing workouts ───────────────────────────────────

    /**
     * Imports climbing workouts of the last [days] days as climbing days. Never duplicates (the workout id is
     * kept), never touches an entry the athlete made by hand, and leaves out a workout on a day the athlete
     * already logged by hand. Returns the number of new entries.
     */
    suspend fun syncClimbing(days: Int = 14): Int = climbMutex.withLock {
        if (!readEnabled()) return@withLock 0
        val result = runCatching { readAndImport(days) }.getOrElse { e ->
            if (e is CancellationException) throw e
            println("HealthSync: climbing sync failed: ${e::class.simpleName}")
            null
        }
        _lastSync.value = result ?: HealthSyncResult(now(), failed = true)
        result?.added ?: 0
    }

    private suspend fun readAndImport(days: Int): HealthSyncResult? {
        val s = availableStore() ?: return null
        val tz = zone()
        val from = withContext(io) { service.ensureReady(); service.today() }.minus(DatePeriod(days = days - 1))
        val start = from.atStartOfDayIn(tz).toEpochMilliseconds()
        val read = awaitCallback<HealthWorkoutResult?>(null, STORE_TIMEOUT_MS) { done ->
            s.climbingWorkouts(start, now()) { done(it) }
        } ?: return null
        if (!read.ok) return null
        val sessions = read.workouts.take(MAX_WORKOUTS).filter { it.id.isNotBlank() && it.id.length <= MAX_ID_LENGTH }.map { w ->
            HealthSession(w.id, climbingType = true, title = w.title.takeIf { it.isNotBlank() }, notes = null, startMs = w.startMs, endMs = w.endMs)
        }
        return withContext(io) { importSessions(sessions, from, tz) }
    }

    /**
     * The import of [sessions] since [from], on plain session data so tests can feed it without a store. Each
     * session's minutes that a board session or logged attempts already cover are stored with the entry
     * ([AthleteService.saveBoardOverlap]) and not counted twice (audit W3). Blocking: call on the IO dispatcher.
     */
    fun importSessions(sessions: List<HealthSession>, from: LocalDate, zone: TimeZone): HealthSyncResult {
        val repo = service.repo
        val existing = repo.climbingDaysSince(from.toString())
        val manualDays = existing.filter { it.source == ClimbingDaySource.MANUAL }.map { it.day }.toSet()
        // A board session may have ended a moment ago: read the logbook fresh.
        service.invalidateLogbook()
        val evidence = runCatching { service.boardEvidence(from.minus(DatePeriod(days = 1)).toString()) }.getOrDefault(BoardOverlap.Evidence())
        var added = 0
        var updated = 0
        var skipped = 0
        var overlapping = 0
        // One transaction: the screens re-derive once, not per session.
        repo.transaction {
            for (r in sessions) {
                val kind = HealthMapping.kindFor(r.climbingType, r.title, r.notes) ?: continue
                val day = Instant.fromEpochMilliseconds(r.startMs).toLocalDateTime(zone).date.toString()
                val minutes = ((r.endMs - r.startMs) / 60_000L).toInt()
                if (minutes < HealthMapping.MIN_MINUTES) continue
                val known = repo.climbingDayByExternalId(r.id)
                if (known == null && day in manualDays) { skipped++; continue }
                if (known != null && known.source != ClimbingDaySource.HEALTH_CONNECT) continue
                val entry = ClimbingDayEntry(
                    id = known?.id ?: repo.newId(),
                    day = day,
                    kind = kind,
                    minutes = minutes.coerceAtMost(HealthMapping.MAX_MINUTES),
                    intensity = HealthMapping.intensityFor(r.title, r.notes),
                    // The shared model has one "synced from the phone's health store" source; on iOS that is HealthKit.
                    source = ClimbingDaySource.HEALTH_CONNECT,
                    externalId = r.id,
                    note = r.title?.take(120),
                    updatedAt = now(),
                )
                if (known == null) {
                    repo.saveClimbingDay(entry); added++
                } else if (known.day != entry.day || known.minutes != entry.minutes || known.kind != entry.kind) {
                    // Keep an intensity the athlete may have corrected in the app.
                    repo.saveClimbingDay(entry.copy(intensity = known.intensity)); updated++
                }
                val covered = BoardOverlap.coveredMinutes(
                    BoardOverlap.Window(r.startMs, r.endMs), entry.minutes, day, kind, evidence,
                )
                // Every sync looks again: a board session saved after the watch's keeps the entry honest.
                service.saveBoardOverlap(entry.id, coveredMinutes = covered)
                if (covered > 0) overlapping++
            }
        }
        return HealthSyncResult(now(), added, updated, skipped, boardOverlaps = overlapping)
    }

    // ── Writing: meals and water ─────────────────────────────────────

    /**
     * Writes one day's entries: new and changed ones are written (a higher sync version replaces the old
     * sample), entries that are gone are deleted, unchanged ones are skipped. Without the store or without
     * write access for all five types nothing happens. Returns "done", "unchanged", "noAccess" or "failed";
     * the day's stamps are kept only after the store said "done", so a failed write is retried next time.
     */
    suspend fun syncDay(day: String, entries: List<FoodLogEntry>, water: List<HydrationEntry>): String = exportMutex.withLock {
        val s = availableStore() ?: return@withLock NO_ACCESS
        if (writeStatus() != AUTHORIZED) return@withLock NO_ACCESS
        val key = HealthExport.STAMP_PREFIX + day
        val before = HealthExport.decodeStamps(runCatching { keyValues.getString(key) }.getOrNull())
        val plan = HealthExport.plan(entries, water, before, now(), zone(), now())
        if (plan.samples.isEmpty() && plan.deleteSyncIds.isEmpty()) {
            if (before != plan.stamps) runCatching { keyValues.putString(key, HealthExport.encodeStamps(plan.stamps)) }
            return@withLock UNCHANGED
        }
        val result = awaitCallback(FAILED, STORE_TIMEOUT_MS) { done -> s.writeNutrition(plan.samples, plan.deleteSyncIds) { done(it) } }
        if (result != DONE) return@withLock FAILED
        runCatching { keyValues.putString(key, HealthExport.encodeStamps(plan.stamps)) }
        println("HealthSync: $day ${plan.written} written, ${plan.removed} removed")
        DONE
    }

    /** [syncDay] with the day's entries from the database, only while the export is switched on. */
    suspend fun exportDayIfEnabled(day: String): String {
        if (!exportEnabled()) return NO_ACCESS
        val (entries, water) = withContext(io) {
            runCatching { service.repo.foodLog(day) to service.repo.hydration(day) }.getOrNull()
        } ?: return FAILED
        return syncDay(day, entries, water)
    }

    /**
     * Runs a store call that answers through a callback; [fallback] when it never answers within
     * [timeoutMs] or throws. The store may answer from any thread, once — a late or second answer is ignored.
     */
    private suspend fun <T> awaitCallback(fallback: T, timeoutMs: Long?, call: (done: (T) -> Unit) -> Unit): T {
        val answer = CompletableDeferred<T>()
        try {
            call { answer.complete(it) }
        } catch (e: Exception) {
            println("HealthSync: store call failed: ${e::class.simpleName}")
            return fallback
        }
        return if (timeoutMs == null) answer.await() else withTimeoutOrNull(timeoutMs) { answer.await() } ?: fallback
    }

    companion object {
        const val DONE = "done"
        const val FAILED = "failed"
        const val UNCHANGED = "unchanged"
        const val NO_ACCESS = "noAccess"
        const val ASKED = "asked"
        const val NOT_ASKED = "notAsked"
        const val AUTHORIZED = "authorized"
        const val DENIED = "denied"
        const val NOT_DETERMINED = "notDetermined"

        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 24 * HOUR_MS
        private const val STORE_TIMEOUT_MS = 30_000L

        /** Bounds on what the store hands back: 14 days of climbing never come near these. */
        private const val MAX_WORKOUTS = 500
        private const val MAX_ID_LENGTH = 128
        private const val MAX_SLEEP_SAMPLES = 5_000

        /** The window rule of [lastNightSleepHours], on plain samples. */
        fun sleepHours(samples: List<HealthSleepSample>, windowStart: Long, windowEnd: Long): Double? {
            val night = samples.take(MAX_SLEEP_SAMPLES).filter { it.endMs > windowStart && it.endMs <= windowEnd }
            val asleep = night.filter { it.stage == HealthTypes.STAGE_IN_BED || it.stage == HealthTypes.STAGE_ASLEEP }
                .map { SleepMath.Range(it.startMs, it.endMs) }
            val awake = night.filter { it.stage == HealthTypes.STAGE_AWAKE }.map { SleepMath.Range(it.startMs, it.endMs) }
            return SleepMath.hours(asleep, awake)
        }
    }
}
