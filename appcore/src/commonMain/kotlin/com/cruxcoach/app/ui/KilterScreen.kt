package com.cruxcoach.app.ui

import com.cruxcoach.app.kilter.KilterApi
import com.cruxcoach.app.kilter.KilterFailure
import com.cruxcoach.app.kilter.KilterLogImporter
import com.cruxcoach.app.kilter.KilterTokens
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** Logs this device sent to the portal on the last push. */
    val uploaded: Int = 0,
    /** Own logs still waiting to go up. */
    val pendingUpload: Int = 0,
    /** True when no log upstream names a gym, wall and layout yet. */
    val missingWallContext: Boolean = false,
)

/**
 * Kilter account screen: sign in, pull the account's ascents into the logbook,
 * sign out. The password is passed straight to [KilterApi] and never stored or
 * held in this state.
 */
class KilterScreenModel(
    private val api: KilterApi,
    private val tokens: KilterTokens,
    private val importer: KilterLogImporter,
    /** Null leaves the screen import-only. */
    private val uploader: com.cruxcoach.app.kilter.KilterUploader? = null,
    main: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private var job: Job? = null

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
        )
    )
    val state = _state.asStateFlow()

    val currentState: KilterScreenState get() = _state.value

    fun watch(onState: (KilterScreenState) -> Unit): Subscription {
        val collector = scope.launch { _state.collect { onState(it) } }
        return Subscription { collector.cancel() }
    }

    fun signIn(email: String, password: String) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.update { it.copy(busy = true, phase = "signingIn", failure = "none") }
            val outcome = withContext(io) { api.signIn(email.trim(), password) }
            _state.update {
                it.copy(
                    busy = false,
                    phase = if (outcome.failure == KilterFailure.NONE) "idle" else "idle",
                    signedIn = tokens.isSignedIn,
                    userUuid = outcome.userUuid.ifEmpty { tokens.userUuid() },
                    failure = code(outcome.failure),
                    retryAfterSeconds = outcome.retryAfterSeconds,
                )
            }
        }
    }

    /** Downloads the account's ascents and merges them into the logbook. */
    fun importLogs() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.update { it.copy(busy = true, phase = "importing", failure = "none") }
            val fetched = withContext(io) { api.fetchLogs() }
            if (fetched.failure != KilterFailure.NONE) {
                _state.update {
                    it.copy(busy = false, phase = "idle", failure = code(fetched.failure), signedIn = tokens.isSignedIn)
                }
                return@launch
            }
            val summary = withContext(io) { importer.import(fetched.logs) }
            _state.update {
                it.copy(
                    busy = false,
                    phase = "done",
                    imported = summary.imported,
                    alreadyPresent = summary.alreadyPresent,
                    unknownClimb = summary.unknownClimb,
                )
            }
        }
    }

    /**
     * Sends this device's own ascents and attempts to the portal.
     *
     * Separate from the import and never automatic: writing into someone's
     * Kilter account is their decision, and Android gates it behind the same
     * explicit switch.
     */
    fun pushLogs() {
        val pusher = uploader ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            _state.update {
                it.copy(busy = true, phase = "pushing", failure = "none", missingWallContext = false)
            }
            val outcome = withContext(io) { pusher.push() }
            _state.update {
                it.copy(
                    busy = false,
                    phase = if (outcome.failure == KilterFailure.NONE) "pushed" else "idle",
                    failure = code(outcome.failure),
                    uploaded = outcome.uploaded,
                    pendingUpload = outcome.pending,
                    missingWallContext = outcome.missingWallContext,
                    signedIn = tokens.isSignedIn,
                )
            }
        }
    }

    fun signOut() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.update { it.copy(busy = true) }
            withContext(io) { api.signOut() }
            _state.update {
                it.copy(busy = false, signedIn = false, phase = "idle", failure = "none", imported = 0, alreadyPresent = 0, unknownClimb = 0)
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
}
