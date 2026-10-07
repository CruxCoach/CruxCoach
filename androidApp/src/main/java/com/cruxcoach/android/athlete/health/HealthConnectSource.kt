package com.cruxcoach.android.athlete.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.ClimbingDaySource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.toJavaLocalDate
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Whether Health Connect can be used on this device. */
enum class HealthConnectAvailability { AVAILABLE, NEEDS_INSTALL_OR_UPDATE, UNSUPPORTED }

/** Outcome of the last climbing sync, shown in the settings card. */
data class HealthSyncResult(
    val atMillis: Long,
    val added: Int = 0,
    val updated: Int = 0,
    /** Sessions left out because the athlete already logged that day by hand. */
    val skippedManual: Int = 0,
    val failed: Boolean = false,
)

/**
 * The app's one Health Connect layer, on the Jetpack client so it works on
 * Android 9–13 (Health Connect app) and 14+ (built in). This class reads
 * (FEAT-071): last night's sleep for the check-in and climbing sessions from
 * watches and other apps as climbing days; [HealthConnectExporter] writes
 * logged meals and water (FEAT-069) through the same access helpers. Every
 * call is guarded so a missing provider, an old provider or revoked
 * permissions never crash anything. Reading runs only when the athlete turned
 * it on ([com.cruxcoach.athlete.model.CoachProfile.healthConnectEnabled]).
 */
@Singleton
class HealthConnectSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: AthleteService,
) {

    private val _lastSync = MutableStateFlow<HealthSyncResult?>(null)
    val lastSync: StateFlow<HealthSyncResult?> = _lastSync.asStateFlow()

    fun availability(): HealthConnectAvailability = availabilityOf(context)

    /** The permission dialog of Health Connect; launch it with [PERMISSIONS] or [HealthConnectExporter.PERMISSIONS]. */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    suspend fun grantedPermissions(): Set<String> = guarded(emptySet()) { client ->
        client.permissionController.getGrantedPermissions()
    }

    suspend fun hasAllPermissions(): Boolean = grantedPermissions().containsAll(PERMISSIONS)

    /** On only when the athlete turned it on and the provider is there. */
    suspend fun enabled(): Boolean = withContext(Dispatchers.IO) {
        runCatching { service.ensureReady(); service.repo.profile().coach.healthConnectEnabled }.getOrDefault(false) &&
            availability() == HealthConnectAvailability.AVAILABLE
    }

    /**
     * Hours slept last night: sleep sessions that end between 15:00 yesterday
     * and 15:00 today, merged, minus awake stages. Null when off, without
     * permission or without data — never a guess.
     */
    suspend fun lastNightSleepHours(today: LocalDate): Double? {
        if (!enabled()) return null
        return guarded<Double?>(null) { client ->
            if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in client.permissionController.getGrantedPermissions()) {
                return@guarded null
            }
            val zone = ZoneId.systemDefault()
            val windowEnd = today.toJavaLocalDate().atTime(LocalTime.of(15, 0)).atZone(zone).toInstant()
            val windowStart = windowEnd.minus(Duration.ofHours(24))
            // Sessions may start before the window (went to bed at 22:00), so read a wider range
            // and keep the ones that end inside it.
            val records = client.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(windowStart.minus(Duration.ofHours(12)), windowEnd)),
            ).records.filter { it.endTime > windowStart && it.endTime <= windowEnd }
            SleepMath.hours(records.map { r ->
                SleepMath.Session(r.startTime, r.endTime, r.stages.filter { it.stage in AWAKE_STAGES }.map { it.startTime to it.endTime })
            })
        }
    }

    /**
     * Imports climbing sessions of the last [days] days as climbing days.
     * Never duplicates (the record id is kept), never touches an entry the
     * athlete made by hand, and leaves out a session on a day the athlete
     * already logged by hand. Returns the number of new entries.
     */
    suspend fun syncClimbing(days: Int = 14): Int = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext 0
        val result = guarded<HealthSyncResult?>(null) { client ->
            if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) !in client.permissionController.getGrantedPermissions()) {
                return@guarded null
            }
            service.ensureReady()
            val repo = service.repo
            val zone = ZoneId.systemDefault()
            val today = service.today()
            val from = today.minus(DatePeriod(days = days - 1))
            val start = from.toJavaLocalDate().atStartOfDay(zone).toInstant()
            val records = client.readRecords(
                ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(start, Instant.now())),
            ).records
            val existing = repo.climbingDaysSince(from.toString())
            val manualDays = existing.filter { it.source == ClimbingDaySource.MANUAL }.map { it.day }.toSet()
            var added = 0
            var updated = 0
            var skipped = 0
            for (r in records) {
                val kind = HealthConnectMapping.kindFor(r.exerciseType, r.title, r.notes) ?: continue
                val day = r.startTime.atZone(zone).toLocalDate().toString()
                val minutes = Duration.between(r.startTime, r.endTime).toMinutes().toInt()
                if (minutes < HealthConnectMapping.MIN_MINUTES) continue
                val externalId = r.metadata.id
                val known = repo.climbingDayByExternalId(externalId)
                if (known == null && day in manualDays) { skipped++; continue }
                if (known != null && known.source != ClimbingDaySource.HEALTH_CONNECT) continue
                val entry = ClimbingDayEntry(
                    id = known?.id ?: repo.newId(),
                    day = day,
                    kind = kind,
                    minutes = minutes.coerceAtMost(HealthConnectMapping.MAX_MINUTES),
                    intensity = HealthConnectMapping.intensityFor(r.title, r.notes),
                    source = ClimbingDaySource.HEALTH_CONNECT,
                    externalId = externalId,
                    note = r.title?.take(120),
                    updatedAt = System.currentTimeMillis(),
                )
                if (known == null) {
                    repo.saveClimbingDay(entry); added++
                } else if (known.day != entry.day || known.minutes != entry.minutes || known.kind != entry.kind) {
                    // Keep an intensity the athlete may have corrected in the app.
                    repo.saveClimbingDay(entry.copy(intensity = known.intensity)); updated++
                }
            }
            HealthSyncResult(System.currentTimeMillis(), added, updated, skipped)
        }
        _lastSync.value = result ?: HealthSyncResult(System.currentTimeMillis(), failed = true)
        result?.added ?: 0
    }

    /** Opens Health Connect's own settings (manage or revoke access). */
    fun settingsIntent(): Intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)

    /** Store page of the provider (Android 9–13, or when an update is needed). */
    fun installIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
        setPackage("com.android.vending")
        data = Uri.parse("market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding")
        putExtra("overlay", true)
        putExtra("callerId", context.packageName)
    }

    private suspend fun <T> guarded(fallback: T, block: suspend (HealthConnectClient) -> T): T = guarded(context, fallback, block)

    companion object {
        private const val TAG = "HealthConnectSource"
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

        /** Read access for the coach: sleep and exercise sessions. */
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        )

        fun availabilityOf(context: Context): HealthConnectAvailability = runCatching {
            when (HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE)) {
                HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.NEEDS_INSTALL_OR_UPDATE
                // Android 9–13 get Health Connect as an app from the store.
                else -> if (android.os.Build.VERSION.SDK_INT < 34) HealthConnectAvailability.NEEDS_INSTALL_OR_UPDATE
                    else HealthConnectAvailability.UNSUPPORTED
            }
        }.getOrDefault(HealthConnectAvailability.UNSUPPORTED)

        /**
         * Runs [block] with a client, or returns [fallback] when Health Connect is
         * missing or the call fails (revoked access, provider gone, …).
         */
        suspend fun <T> guarded(context: Context, fallback: T, block: suspend (HealthConnectClient) -> T): T = withContext(Dispatchers.IO) {
            try {
                if (HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE) != HealthConnectClient.SDK_AVAILABLE) return@withContext fallback
                block(HealthConnectClient.getOrCreate(context, PROVIDER_PACKAGE))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // SecurityException (revoked), RemoteException, IllegalState (provider gone) …
                Log.w(TAG, "Health Connect call failed: ${e.javaClass.simpleName}")
                fallback
            }
        }

        private val AWAKE_STAGES = setOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED)
    }
}

/** Merging of overlapping sleep sessions; pure for tests. */
object SleepMath {

    data class Session(val start: Instant, val end: Instant, val awake: List<Pair<Instant, Instant>> = emptyList())

    /** Total sleep in hours (one decimal), overlaps counted once; null without sessions. */
    fun hours(sessions: List<Session>): Double? {
        if (sessions.isEmpty()) return null
        val asleep = merge(sessions.map { it.start to it.end })
        val awake = merge(sessions.flatMap { it.awake })
        val asleepMs = asleep.sumOf { Duration.between(it.first, it.second).toMillis() }
        val awakeMs = awake.sumOf { (s, e) ->
            asleep.sumOf { (a, b) ->
                val from = maxOf(s, a); val to = minOf(e, b)
                if (to > from) Duration.between(from, to).toMillis() else 0L
            }
        }
        val hours = (asleepMs - awakeMs).coerceAtLeast(0L) / 3_600_000.0
        return if (hours <= 0.0) null else (hours * 10).roundToInt() / 10.0
    }

    private fun merge(ranges: List<Pair<Instant, Instant>>): List<Pair<Instant, Instant>> {
        val sorted = ranges.filter { it.second > it.first }.sortedBy { it.first }
        val out = mutableListOf<Pair<Instant, Instant>>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.second) out[out.size - 1] = last.first to maxOf(last.second, r.second)
            else out += r
        }
        return out
    }
}

/**
 * Which Health Connect exercise sessions are climbing, and what kind. Pure
 * so it can be tested: rock-climbing sessions always count; other types
 * only when the title or notes say climbing. The title decides between
 * gym bouldering, rope, another board and outdoor; without a hint the
 * session stays "other climbing".
 */
object HealthConnectMapping {

    /** Shorter sessions are warm-ups or mis-taps, not climbing days. */
    const val MIN_MINUTES = 10
    const val MAX_MINUTES = 8 * 60

    private val CLIMB_WORDS = listOf("klettern", "kletter", "climb", "boulder", "bouldern", "escalad", "arrampic", "bloc")
    private val BOARD_WORDS = listOf("kilter", "moonboard", "moon board", "tension", "board")
    private val ROPE_WORDS = listOf("seil", "rope", "lead", "vorstieg", "toprope", "top rope", "top-rope", "sport climb")
    private val OUTDOOR_WORDS = listOf("fels", "outdoor", "crag", "draußen", "draussen", "rock ", "gebiet", "font", "trad")
    private val GYM_WORDS = listOf("halle", "gym", "indoor", "boulder")

    fun kindFor(exerciseType: Int, title: String?, notes: String?): ClimbingDayKind? {
        val text = listOfNotNull(title, notes).joinToString(" ").lowercase()
        val climbingType = exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING
        if (!climbingType && CLIMB_WORDS.none { it in text }) return null
        return when {
            BOARD_WORDS.any { it in text } -> ClimbingDayKind.OTHER_BOARD
            ROPE_WORDS.any { it in text } && OUTDOOR_WORDS.none { it in text } -> ClimbingDayKind.GYM_ROPE
            OUTDOOR_WORDS.any { it in "$text " } -> ClimbingDayKind.OUTDOOR
            GYM_WORDS.any { it in text } -> ClimbingDayKind.GYM_BOULDER
            else -> ClimbingDayKind.OTHER
        }
    }

    /** Volume unless the athlete wrote otherwise in the title or notes. */
    fun intensityFor(title: String?, notes: String?): ClimbIntensity {
        val text = listOfNotNull(title, notes).joinToString(" ").lowercase()
        return when {
            listOf("limit", "projekt", "project", "max").any { it in text } -> ClimbIntensity.LIMIT
            listOf("hart", "hard", "intensiv", "intense").any { it in text } -> ClimbIntensity.HARD
            listOf("locker", "easy", "leicht", "technik", "technique", "recovery", "erholung").any { it in text } -> ClimbIntensity.LIGHT
            else -> ClimbIntensity.VOLUME
        }
    }
}
