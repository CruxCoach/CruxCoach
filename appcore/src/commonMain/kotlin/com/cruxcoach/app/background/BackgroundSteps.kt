package com.cruxcoach.app.background

import com.cruxcoach.app.community.CommunityPublisher
import com.cruxcoach.app.kilter.KilterUploadRetryScheduler
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.KilterGradeMapper
import com.cruxcoach.domain.community.ClimbEditorState

// ── Kilter: the follow-up chain of an upload run ──────────────────────────

/**
 * The Kilter upload's next automatic run, on disk. Android hands it to WorkManager, which runs
 * it while the app is closed; the iOS timers die with the process, so without this a run that
 * stopped offline would wait for the next "Sync now". [PersistentKilterRetrySchedule] writes it,
 * the background refresh starts the follow-up that fell due ([KilterFollowUpStep]).
 */
class KilterFollowUpStore(
    private val keyValues: KeyValueStore,
    identityPrefix: String,
    private val clockMs: () -> Long,
) {
    private val retryKey = "bg_kilter_retry_${identityPrefix}"
    private val wakeKey = "bg_kilter_wake_${identityPrefix}"

    internal fun now(): Long = clockMs()

    internal fun hasRetry(): Boolean = keyValues.getString(retryKey) != null

    internal fun putRetry(attempt: Int, dueMs: Long) = keyValues.putString(retryKey, "$attempt:$dueMs")

    internal fun putWake(dueMs: Long) = keyValues.putString(wakeKey, "0:$dueMs")

    internal fun clear() {
        keyValues.putString(retryKey, null)
        keyValues.putString(wakeKey, null)
    }

    /** The attempt of the follow-up due at [nowMs] (0 for a wake-up run), or -1 when none is due. */
    fun dueAttempt(nowMs: Long): Int {
        val retry = parse(keyValues.getString(retryKey))
        if (retry != null && retry.second <= nowMs) return retry.first
        val wake = parse(keyValues.getString(wakeKey))
        if (wake != null && wake.second <= nowMs) return 0
        return -1
    }

    /**
     * Takes the due follow-up off disk before it runs: the run records its own next one, and a
     * chain that has run out must not be started again at every refresh.
     */
    fun take(attempt: Int) {
        if (attempt > 0) keyValues.putString(retryKey, null) else keyValues.putString(wakeKey, null)
    }

    private fun parse(value: String?): Pair<Int, Long>? {
        val parts = value?.split(':') ?: return null
        if (parts.size != 2) return null
        val attempt = parts[0].toIntOrNull() ?: return null
        val due = parts[1].toLongOrNull() ?: return null
        return attempt to due
    }
}

/**
 * The engine's follow-up scheduler: the in-process timers ([inner]) plus the same follow-up on
 * disk. Same rules as the timers: [schedule] with `replace = false` leaves a chain under way as
 * it is, [cancel] drops both the chain and the wake-up.
 */
class PersistentKilterRetrySchedule(
    private val inner: KilterUploadRetryScheduler,
    private val store: KilterFollowUpStore,
) : KilterUploadRetryScheduler {
    override fun schedule(attempt: Int, replace: Boolean) {
        inner.schedule(attempt, replace)
        val delayMin = KilterUploadRetryScheduler.DELAYS_MIN.getOrNull(attempt - 1) ?: return
        if (!replace && store.hasRetry()) return
        store.putRetry(attempt, store.now() + delayMin * 60_000L)
    }

    override fun wakeAfter(delayMs: Long) {
        inner.wakeAfter(delayMs)
        store.putWake(store.now() + delayMs.coerceAtLeast(0))
    }

    override fun cancel() {
        inner.cancel()
        store.clear()
    }
}

/**
 * Port of Android's `KilterUploadRetryWorker`: the follow-up run that fell due while the app was
 * not running. No new upload is started here — iOS has no "push automatically" switch, so only
 * a chain that a run (the user's "Sync now") left behind goes on.
 */
class KilterFollowUpStep(
    private val schedule: KilterFollowUpStore,
    private val signedIn: () -> Boolean,
    /** Starts the run detached from this refresh: cut off mid-request it would lose Kilter's answer. */
    private val upload: suspend (attempt: Int) -> Unit,
    private val clockMs: () -> Long,
) : BackgroundStep {
    override val code: String = CODE

    override fun intervalMs(): Long = 0

    override fun retryAfterMs(): Long = 0

    override suspend fun enabled(trigger: String): Boolean =
        signedIn() && schedule.dueAttempt(clockMs()) >= 0

    override suspend fun run(): BackgroundStepResult {
        val attempt = schedule.dueAttempt(clockMs())
        if (attempt < 0) return BackgroundStepResult.OK
        schedule.take(attempt)
        // The engine records its outcome and schedules its own next follow-up.
        upload(attempt)
        return BackgroundStepResult.OK
    }

    companion object {
        const val CODE = "kilter_follow_up"
    }
}

// ── Community climbs: the Nostr publish retry ─────────────────────────────

class CommunityRetryItem(
    val uuid: String,
    val layoutId: Long,
    val brand: BoardBrand,
    val state: ClimbEditorState,
    val sizeLabel: String,
)

/** Climbs whose Nostr publish no relay accepted, for one identity. */
fun interface CommunityRetryQueue {
    fun awaiting(pubkey: String): List<CommunityRetryItem>
}

/** Publishes one again; true when a relay accepted it. */
fun interface CommunityRepublisher {
    suspend fun publish(item: CommunityRetryItem): Boolean
}

/**
 * Port of Android's `CommunityPublishRetryWorker` (every 6 hours, 30 minutes back-off): every
 * own climb that no relay accepted goes out again under its stable d-tag, so a relay that got
 * the first try just replaces it. One failing climb never stops the rest; the run counts as
 * failed only when every climb failed, as Android asks WorkManager for a retry then.
 */
class CommunityRetryStep(
    private val queue: CommunityRetryQueue,
    private val republisher: CommunityRepublisher,
    private val pubkey: () -> String?,
) : BackgroundStep {
    override val code: String = CODE

    override fun intervalMs(): Long = INTERVAL_MS

    override fun retryAfterMs(): Long = RETRY_MS

    override suspend fun enabled(trigger: String): Boolean = !pubkey().isNullOrBlank()

    override suspend fun run(): BackgroundStepResult {
        val key = pubkey()?.takeIf { it.isNotBlank() } ?: return BackgroundStepResult.OK
        val items = queue.awaiting(key)
        if (items.isEmpty()) return BackgroundStepResult.OK
        var failed = 0
        for (item in items) {
            val ok = try {
                republisher.publish(item)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!ok) failed++
        }
        return if (failed == items.size) BackgroundStepResult.FAILED else BackgroundStepResult.OK
    }

    companion object {
        const val CODE = "community_retry"
        const val INTERVAL_MS = 6L * 60 * 60 * 1000
        const val RETRY_MS = 30L * 60 * 1000
    }
}

/**
 * [CommunityRetryQueue] over the board database, as Android's worker rebuilds the editor state:
 * the stored frames, name and description, the stats' angle and grade (40° and the default
 * grade when a mid-flow failure lost them), and the climb's OWN brand, layout and size label,
 * never the active board's. The brand also goes into the state, which decides the bounds box.
 */
class BoardCommunityRetryQueue(
    private val boards: BoardRepository,
    /** The active board's size id: only a fallback for the size label. */
    private val activeSizeId: () -> Int,
) : CommunityRetryQueue {
    override fun awaiting(pubkey: String): List<CommunityRetryItem> {
        val rows = boards.getClimbsAwaitingNostrRetry(pubkey)
        if (rows.isEmpty()) return emptyList()
        val fallbackLabel = try { boards.getProductSize(activeSizeId())?.name } catch (e: Exception) { null }
        return rows.mapNotNull { row ->
            try {
                val stats = boards.getClimbStatsForUuid(row.uuid)
                val context = boards.getClimbPublishContext(row.uuid)
                val brand = BoardBrand.fromWire(context?.boardBrand)
                val holds = BoardClimbParser.parseFrames(row.framesText).associate { it.placementId to it.roleId }
                CommunityRetryItem(
                    uuid = row.uuid,
                    layoutId = context?.layoutId ?: row.layoutId,
                    brand = brand,
                    state = ClimbEditorState(
                        selectedHolds = holds,
                        boardBrand = brand.wireValue,
                        name = row.name,
                        description = row.description,
                        setterGradeId = stats?.second ?: KilterGradeMapper.DEFAULT_SETTER_GRADE_ID,
                        angle = stats?.first ?: DEFAULT_ANGLE,
                    ),
                    sizeLabel = context?.sizeLabel ?: fallbackLabel ?: "",
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private companion object {
        const val DEFAULT_ANGLE = 40
    }
}

/** [CommunityRepublisher] over the shared publisher; no announcement note on a retry. */
class PublisherCommunityRepublisher(private val publisher: CommunityPublisher) : CommunityRepublisher {
    override suspend fun publish(item: CommunityRetryItem): Boolean =
        publisher.publish(item.uuid, item.layoutId, item.brand, item.state, item.sizeLabel, null).isPublished
}

// ── Encrypted backup ──────────────────────────────────────────────────────

/**
 * Port of Android's `BackupSyncWorker`: a full backup when backups are on and the chosen
 * cadence ([KEY_BACKUP_INTERVAL], Android's `backup_interval`: DAILY, WEEKLY or MANUAL, MANUAL
 * by default so a fresh install never overwrites a cloud backup on its own) has passed.
 * Android backs off 30 minutes after a failure.
 */
class BackupStep(
    private val keyValues: KeyValueStore,
    private val backupEnabled: () -> Boolean,
    /** Runs a full backup stamped [exportedAt] (ISO instant); true when it completed. */
    private val backUp: suspend (exportedAt: String) -> Boolean,
    private val isoNow: () -> String,
) : BackgroundStep {
    override val code: String = CODE

    override fun intervalMs(): Long = when (interval()) {
        INTERVAL_DAILY -> DAY_MS
        INTERVAL_WEEKLY -> 7 * DAY_MS
        else -> Long.MAX_VALUE
    }

    override fun retryAfterMs(): Long = RETRY_MS

    override suspend fun enabled(trigger: String): Boolean = backupEnabled() && interval() != INTERVAL_MANUAL

    override suspend fun run(): BackgroundStepResult =
        if (backUp(isoNow())) BackgroundStepResult.OK else BackgroundStepResult.FAILED

    private fun interval(): String = when (val value = keyValues.getString(KEY_BACKUP_INTERVAL)) {
        INTERVAL_DAILY, INTERVAL_WEEKLY -> value
        else -> INTERVAL_MANUAL
    }

    companion object {
        const val CODE = "backup"
        const val KEY_BACKUP_INTERVAL = "backup_interval"
        const val INTERVAL_DAILY = "DAILY"
        const val INTERVAL_WEEKLY = "WEEKLY"
        const val INTERVAL_MANUAL = "MANUAL"
        const val RETRY_MS = 30L * 60 * 1000
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

// ── Board catalogues ──────────────────────────────────────────────────────

/**
 * Android's `BoardSyncWorker` cadence (`sync_interval`), foreground only: a catalogue download is
 * hundreds of megabytes and cannot finish in a background slot of half a minute. The refresh
 * keeps its own due marker ([com.cruxcoach.app.sync.CatalogueAutoSync]) and never throws.
 */
class CatalogueStep(
    /** Starts the due catalogue refreshes; the composition root runs it on the main thread. */
    private val refreshIfDue: suspend () -> List<String>,
) : BackgroundStep {
    override val code: String = CODE

    override fun intervalMs(): Long = 0

    override fun retryAfterMs(): Long = 0

    override suspend fun enabled(trigger: String): Boolean = trigger == BackgroundRefresh.TRIGGER_FOREGROUND

    override suspend fun run(): BackgroundStepResult {
        refreshIfDue()
        return BackgroundStepResult.OK
    }

    companion object {
        const val CODE = "catalogue"
    }
}

/**
 * Port of Android's `KilterPublishRetryWorker` (every 6 hours, 30 minutes back-off): own climbs
 * the Kilter portal has not taken yet go out again while "publish own climbs to Kilter" is on.
 * [pass] runs one retry pass and answers false where Android asks WorkManager for a retry.
 */
class KilterClimbPublishStep(
    private val publishEnabled: () -> Boolean,
    private val pass: suspend () -> Boolean,
) : BackgroundStep {
    override val code: String = CODE

    override fun intervalMs(): Long = INTERVAL_MS

    override fun retryAfterMs(): Long = RETRY_MS

    override suspend fun enabled(trigger: String): Boolean = publishEnabled()

    override suspend fun run(): BackgroundStepResult =
        if (pass()) BackgroundStepResult.OK else BackgroundStepResult.FAILED

    companion object {
        const val CODE = "kilter_climb_publish"
        const val INTERVAL_MS = 6L * 60 * 60 * 1000
        const val RETRY_MS = 30L * 60 * 1000
    }
}
