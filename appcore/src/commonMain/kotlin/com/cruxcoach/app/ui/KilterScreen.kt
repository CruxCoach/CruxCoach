package com.cruxcoach.app.ui

import com.cruxcoach.app.kilter.KilterApi
import com.cruxcoach.app.kilter.KilterCircuitImporter
import com.cruxcoach.app.kilter.KilterFailure
import com.cruxcoach.app.kilter.KilterLogImporter
import com.cruxcoach.app.kilter.KilterNotUploadedEntry
import com.cruxcoach.app.kilter.KilterNotUploadedReason
import com.cruxcoach.app.kilter.KilterTokens
import com.cruxcoach.app.kilter.KilterUploadEngine
import com.cruxcoach.app.kilter.KilterUploadReason
import com.cruxcoach.app.kilter.KilterUploadStatus
import com.cruxcoach.app.kilter.KilterUploadTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One line of the upload status: an Android string key, shown as a plural of
 * [count] when [plural], from `IOS.strings` when [iosOnly].
 */
class KilterStatusLineUi(val key: String, val count: Int, val plural: Boolean, val iosOnly: Boolean)

/** One entry Kilter did not take, for the list the user can open and report. */
class KilterNotUploadedRowUi(
    val logUuid: String,
    /** Empty when the catalogue does not know the climb ("kilter_upload_unknown_climb"). */
    val climbName: String,
    val angle: Int,
    /** dd.mm.yyyy, as Android's list shows it. */
    val date: String,
    val isAscent: Boolean,
    /** notOnKilter | retryLater | conflict | invalidDate */
    val reason: String,
    /** The id Kilter refused, else the logbook's climb id: what the maintainer looks the climb up by. */
    val reportId: String,
)

data class KilterScreenState(
    val signedIn: Boolean,
    val userUuid: String,
    val busy: Boolean,
    /** idle | signingIn | importing | pushing | done | pushed */
    val phase: String,
    /** none | invalidCredentials | throttled | offline | timeout | serverError | malformedResponse | notSignedIn */
    val failure: String,
    val retryAfterSeconds: Long,
    val imported: Int,
    val alreadyPresent: Int,
    val unknownClimb: Int,
    /** Kilter circuits written as climb lists by the last import (Android `kilter_import_part_circuits`). */
    val circuits: Int = 0,
    /** A run (manual or automatic) holds the upload. */
    val uploading: Boolean = false,
    /** False until the first upload run of this identity: "kilter_upload_unchecked". */
    val uploadChecked: Boolean = false,
    /** What the last run did, as Android's status block words it. */
    val uploadLines: List<KilterStatusLineUi> = emptyList(),
    /** The last run failed or Kilter refused the session: shown as a problem, with a bug report action. */
    val uploadProblem: Boolean = false,
    /** Kilter refused the session: the action is a new sign-in rather than another upload. */
    val uploadNeedsLogin: Boolean = false,
    /** Entries Kilter did not take exist: "kilter_upload_show_entries". */
    val uploadShowEntries: Boolean = false,
    /** Aurora-imported entries wait for the opt-in: "kilter_upload_imported_action". */
    val uploadShowImported: Boolean = false,
    /** idle | loading | loaded | failed */
    val notUploadedState: String = "idle",
    val notUploaded: List<KilterNotUploadedRowUi> = emptyList(),
    /** Secret-free upload history, frozen when the report form opens. */
    val diagnostics: String = "",
    /** "0.2.4 (code)", for the report header. */
    val appVersion: String = "",
    /** "Sync continuously" was chosen (Android `kilter_sync_enabled`): the start syncs the logbook. */
    val syncContinuously: Boolean = false,
    /** "Upload to Kilter" (Android `kilter_push_enabled`): the start's sync also uploads. */
    val pushEnabled: Boolean = false,
    /** ISO instant of the last sync that moved something, "" before the first. */
    val lastSync: String = "",
    /** The last import was "Import once": the sign-in is gone, the summary stays. */
    val importedOnce: Boolean = false,
)

/**
 * Kilter account screen: sign in, pull the account's ascents into the logbook,
 * upload this device's entries, sign out. The password is passed straight to
 * [KilterApi] and never stored or held in this state.
 *
 * Uploading stays an explicit tap on iOS (the app has no background trigger
 * for it); the engine's own follow-up runs continue a started upload while the
 * app is open. Runs go through [runScope], which outlives this model's scope:
 * a run cut off mid-request would lose Kilter's answer.
 */
class KilterScreenModel(
    private val api: KilterApi,
    private val tokens: KilterTokens,
    private val importer: KilterLogImporter,
    private val engine: KilterUploadEngine,
    private val runScope: CoroutineScope,
    private val appVersion: String,
    main: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private var job: Job? = null

    /**
     * Circuits → climb lists after each log import (Android's sync engine runs
     * `KilterCircuitImporter` with every import). Set once by the composition root.
     */
    internal var circuitImporter: KilterCircuitImporter? = null

    /** Own logged/authored Kilter climbs into the board DB before the logs land (Android's backfill). */
    internal var climbBackfiller: com.cruxcoach.app.kilter.KilterClimbBackfiller? = null

    /**
     * The identity's Kilter choices (`kilter_sync_enabled`, `kilter_push_enabled`, `kilter_last_sync`),
     * set once by the composition root; without it the import-mode choice is not offered.
     */
    internal var syncSettings: com.cruxcoach.app.settings.SettingsStore? = null
        set(value) {
            field = value
            _state.update { withSyncSettings(it) }
        }

    private fun withSyncSettings(state: KilterScreenState): KilterScreenState {
        val snapshot = syncSettings?.snapshot ?: return state
        return state.copy(
            syncContinuously = snapshot.kilterSyncEnabled,
            pushEnabled = snapshot.kilterPushEnabled,
            lastSync = snapshot.kilterLastSync ?: "",
        )
    }

    private val _state = MutableStateFlow(
        KilterScreenState(
            signedIn = tokens.isSignedIn,
            userUuid = tokens.userUuid(),
            busy = false,
            phase = "idle",
            failure = "none",
            retryAfterSeconds = 0,
            imported = 0,
            alreadyPresent = 0,
            unknownClimb = 0,
            appVersion = appVersion,
        )
    )
    val state = _state.asStateFlow()

    val currentState: KilterScreenState get() = _state.value

    init {
        // Automatic runs finish without this screen: their status still shows.
        scope.launch {
            combine(engine.uploadStatus, engine.sessionExpired) { status, expired -> status to expired }
                .collect { (status, expired) -> _state.update { withUpload(it, status, expired) } }
        }
    }

    fun watch(onState: (KilterScreenState) -> Unit): Subscription {
        val collector = scope.launch { _state.collect { onState(it) } }
        return Subscription { collector.cancel() }
    }

    fun signIn(email: String, password: String) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.update { it.copy(busy = true, phase = "signingIn", failure = "none") }
            val outcome = withContext(io) { api.signIn(email.trim(), password) }
            if (outcome.failure == KilterFailure.NONE) engine.clearSessionExpired()
            _state.update {
                it.copy(
                    busy = false,
                    phase = "idle",
                    signedIn = tokens.isSignedIn,
                    userUuid = outcome.userUuid.ifEmpty { tokens.userUuid() },
                    failure = code(outcome.failure),
                    retryAfterSeconds = outcome.retryAfterSeconds,
                )
            }
        }
    }

    /** Downloads the account's ascents and merges them into the logbook. */
    fun importLogs() = runImport(mode = MODE_PLAIN)

    /**
     * Android's "Import once": the logbook comes in, then the sign-in is revoked at Kilter and
     * forgotten here (the user uuid stays, so own authored climbs remain claimable) and continuous
     * sync is off. A failed import keeps the sign-in, so the user can try again.
     */
    fun importOnce() = runImport(mode = MODE_ONCE)

    /** Android's "Sync continuously": the logbook comes in and every app start syncs it again. */
    fun importContinuously() = runImport(mode = MODE_CONTINUOUS)

    /**
     * Android's "Upload to Kilter" switch. Switching it on starts an upload of the pending sends
     * and attempts at once; afterwards each start's sync uploads too.
     */
    fun setPushEnabled(enabled: Boolean) {
        val settings = syncSettings ?: return
        settings.setKilterPushEnabled(enabled)
        _state.update { withSyncSettings(it) }
        if (enabled) startUpload(enableImported = false, trigger = KilterUploadTrigger.ENABLED)
    }

    private fun runImport(mode: Int) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.update { it.copy(busy = true, phase = "importing", failure = "none", importedOnce = false) }
            val fetched = withContext(io) { api.fetchLogs() }
            if (fetched.failure != KilterFailure.NONE) {
                _state.update {
                    it.copy(busy = false, phase = "idle", failure = code(fetched.failure), signedIn = tokens.isSignedIn)
                }
                return@launch
            }
            // Best-effort, as on Android: a failed backfill never stops the log import.
            climbBackfiller?.let { backfiller ->
                withContext(io) {
                    try {
                        backfiller.backfillOwnClimbs()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            val summary = withContext(io) { importer.import(fetched.logs) }
            // Best-effort and non-fatal, as on Android: the token was refreshed by the log fetch.
            val circuits = circuitImporter?.let { withContext(io) { it.importCircuits() } } ?: 0
            val settings = syncSettings
            if (mode != MODE_PLAIN && settings != null) {
                settings.setKilterLastSync(kotlin.time.Clock.System.now().toString())
                if (mode == MODE_ONCE) {
                    // Revoked at Kilter before it is forgotten here, so the 30-day refresh token
                    // cannot outlive the "import once" choice; the user uuid stays.
                    withContext(io) { api.signOut() }
                    engine.clearSessionExpired()
                }
                settings.setKilterSyncEnabled(mode == MODE_CONTINUOUS)
            }
            _state.update {
                withSyncSettings(it.copy(
                    busy = false,
                    phase = "done",
                    imported = summary.imported,
                    alreadyPresent = summary.alreadyPresent,
                    unknownClimb = summary.unknownClimb,
                    circuits = circuits,
                    signedIn = tokens.isSignedIn,
                    importedOnce = mode == MODE_ONCE && settings != null,
                ))
            }
        }
    }

    /**
     * Sends this device's own ascents and attempts that Kilter does not have
     * yet ("Sync now"). The outcome is the engine's status, shown by the
     * status block; it is not repeated as a screen failure.
     */
    fun pushLogs() = startUpload(enableImported = false, trigger = KilterUploadTrigger.MANUAL)

    /** Opt in to uploading Aurora-imported entries (they are matched against Kilter first), then upload. */
    fun uploadImported() = startUpload(enableImported = true, trigger = KilterUploadTrigger.MANUAL)

    private fun startUpload(enableImported: Boolean, trigger: KilterUploadTrigger) {
        if (job?.isActive == true) return
        job = scope.launch {
            updateUpload { it.copy(busy = true, phase = "pushing", failure = "none", uploading = true) }
            try {
                // The run outlives this screen; an opt-in reaches a run already
                // in flight only through the next one, which waits for it.
                runScope.async {
                    if (enableImported) engine.setImportedUploadEnabled(true)
                    engine.upload(trigger)
                }.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The engine reports its own failures as status; this is a programming error.
            } finally {
                updateUpload { it.copy(busy = false, phase = "pushed", uploading = false, signedIn = tokens.isSignedIn) }
            }
        }
    }

    /** [change], then the status block recomputed for it (it depends on [KilterScreenState.uploading]). */
    private fun updateUpload(change: (KilterScreenState) -> KilterScreenState) = _state.update {
        withUpload(change(it), engine.uploadStatus.value, engine.sessionExpired.value)
    }

    /**
     * Kilter refused the session: forget it here (Kilter already did) and
     * show the sign-in form. Held-back entries and the history stay.
     */
    fun signInAgain() {
        if (job?.isActive == true) return
        tokens.clear()
        _state.update { it.copy(signedIn = false, phase = "idle", failure = "none") }
    }

    /** Loads the entries Kilter did not take, for the list the user opened. */
    fun loadNotUploaded() {
        _state.update { it.copy(notUploadedState = "loading", notUploaded = emptyList()) }
        scope.launch {
            val rows = try {
                withContext(io) { engine.notUploadedEntries() }.map(::row)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _state.update {
                if (rows == null) it.copy(notUploadedState = "failed")
                else it.copy(notUploadedState = "loaded", notUploaded = rows)
            }
        }
    }

    /** Freezes the diagnostics preview when the report form opens; background runs cannot replace it. */
    fun loadDiagnostics() {
        scope.launch {
            val text = try {
                engine.diagnosticSnapshot()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
            _state.update { it.copy(diagnostics = text) }
        }
    }

    /**
     * The bug report as Android sends it to the maintainer: title, [body],
     * the generalized device line, the app version and, when chosen, the
     * diagnostics frozen by [loadDiagnostics]. [deviceLine] comes from Swift
     * (OS major version and language only).
     */
    fun bugReportText(title: String, body: String, deviceLine: String, attachDiagnostics: Boolean): String =
        composeBugReport(title, body, deviceLine, appVersion, _state.value.diagnostics.takeIf { attachDiagnostics })

    fun signOut() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.update { it.copy(busy = true) }
            withContext(io) {
                api.signOut()
                engine.clearUploadLedger()
            }
            engine.clearSessionExpired()
            // Android's disconnect ends continuous sync with the session.
            syncSettings?.setKilterSyncEnabled(false)
            _state.update {
                withSyncSettings(it.copy(busy = false, signedIn = false, phase = "idle", failure = "none", imported = 0, alreadyPresent = 0, unknownClimb = 0, circuits = 0))
            }
        }
    }

    fun dismissFailure() = _state.update { it.copy(failure = "none") }

    fun close() = scope.cancel()

    private fun code(failure: KilterFailure): String = when (failure) {
        KilterFailure.NONE -> "none"
        KilterFailure.INVALID_CREDENTIALS -> "invalidCredentials"
        KilterFailure.THROTTLED -> "throttled"
        KilterFailure.OFFLINE -> "offline"
        KilterFailure.TIMEOUT -> "timeout"
        KilterFailure.SERVER_ERROR -> "serverError"
        KilterFailure.MALFORMED_RESPONSE -> "malformedResponse"
        KilterFailure.NOT_SIGNED_IN -> "notSignedIn"
        KilterFailure.CONFLICT -> "conflict"
    }

    companion object {
        private const val MODE_PLAIN = 0
        private const val MODE_ONCE = 1
        private const val MODE_CONTINUOUS = 2

        /** Port of Android's `KilterLogbookSyncStatus` decisions; the push switch is always on here. */
        internal fun withUpload(state: KilterScreenState, status: KilterUploadStatus?, expired: Boolean): KilterScreenState {
            val needsLogin = expired || status?.reason == KilterUploadReason.AUTHENTICATION
            val active = !state.uploading
            return state.copy(
                uploadChecked = status != null,
                uploadLines = when {
                    needsLogin -> listOf(KilterStatusLineUi("kilter_upload_auth", 0, plural = false, iosOnly = false))
                    status == null -> listOf(KilterStatusLineUi("kilter_upload_unchecked", 0, plural = false, iosOnly = false))
                    else -> statusLines(status)
                },
                uploadProblem = active && (needsLogin || status?.failed == true),
                uploadNeedsLogin = needsLogin,
                uploadShowEntries = (status?.notUploaded ?: 0) > 0,
                uploadShowImported = !needsLogin && (status?.heldImported ?: 0) > 0,
            )
        }

        /** Port of Android's `KilterUploadStatus.localized`: one key per line. */
        internal fun statusLines(s: KilterUploadStatus): List<KilterStatusLineUi> {
            val kept = s.notUploaded + s.heldImported
            val reasonKey = when (s.reason) {
                KilterUploadReason.NONE -> when {
                    s.pending > 0 -> "kilter_upload_pending"
                    kept > 0 -> "kilter_upload_done_except"
                    else -> "kilter_upload_done"
                }
                KilterUploadReason.DISABLED -> "kilter_upload_disabled"
                KilterUploadReason.AUTHENTICATION -> "kilter_upload_auth"
                KilterUploadReason.WALL_CONTEXT -> "kilter_upload_wall"
                KilterUploadReason.NETWORK -> "kilter_upload_network"
                KilterUploadReason.TIMEOUT -> "kilter_upload_timeout"
                KilterUploadReason.HTTP -> "kilter_upload_http"
                KilterUploadReason.CONFLICT -> "kilter_upload_conflict"
                KilterUploadReason.INTERNAL -> "kilter_upload_internal"
            }
            return buildList {
                add(KilterStatusLineUi(reasonKey, 0, plural = false, iosOnly = false))
                // iOS cannot create a wall: say how the account gets one.
                if (s.reason == KilterUploadReason.WALL_CONTEXT) add(KilterStatusLineUi("kilter_upload_wall_hint", 0, plural = false, iosOnly = true))
                if (s.nextRetry > 0) add(KilterStatusLineUi("kilter_upload_auto_retry", 0, plural = false, iosOnly = false))
                fun count(key: String, n: Int) { if (n > 0) add(KilterStatusLineUi(key, n, plural = true, iosOnly = false)) }
                count("kilter_upload_rejected", s.rejectedByKilter)
                count("kilter_upload_unconfirmed", s.unconfirmed)
                count("kilter_upload_invalid", s.rejectedInvalid)
                count("kilter_upload_conflicts", s.rejectedConflict)
                count("kilter_upload_held_imported", s.heldImported)
            }
        }

        internal fun row(entry: KilterNotUploadedEntry) = KilterNotUploadedRowUi(
            logUuid = entry.logUuid,
            climbName = entry.climbName?.takeIf { it.isNotBlank() } ?: "",
            angle = entry.angle,
            date = entry.climbedAt.take(10).split('-').takeIf { it.size == 3 }
                ?.let { (y, m, d) -> "$d.$m.$y" } ?: entry.climbedAt.take(10),
            isAscent = entry.isAscent,
            reason = when (entry.reason) {
                KilterNotUploadedReason.NOT_ON_KILTER -> "notOnKilter"
                KilterNotUploadedReason.RETRY_LATER -> "retryLater"
                KilterNotUploadedReason.CONFLICT -> "conflict"
                KilterNotUploadedReason.INVALID_DATE -> "invalidDate"
            },
            reportId = entry.wireId ?: entry.climbUuid,
        )

        /** Android's `DevContactViewModel.sendBugReport` content; the labels are the maintainer's format. */
        internal fun composeBugReport(
            title: String,
            description: String,
            deviceLine: String,
            appVersion: String,
            diagnostics: String?,
        ): String = buildString {
            append(title.trim())
            append("\n\nBeschreibung:\n")
            append(description.trim())
            append("\n\n---\n")
            append(deviceLine)
            append("\nApp ").append(appVersion)
            if (!diagnostics.isNullOrBlank()) {
                append("\n\nKilter upload diagnostics:\n")
                append(diagnostics)
            }
        }
    }
}
