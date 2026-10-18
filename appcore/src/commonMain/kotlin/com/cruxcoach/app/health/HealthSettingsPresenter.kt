package com.cruxcoach.app.health

import com.cruxcoach.app.athlete.AthleteService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The settings card's state (Android `HealthConnectCardState`), with what HealthKit can and cannot tell. */
internal data class HealthSettingsState(
    val loading: Boolean = true,
    /** The store is attached and HealthKit exists on this device. */
    val available: Boolean = false,
    /** The last access request was refused by the store itself (no entitlement in this build, restricted device). */
    val accessFailed: Boolean = false,
    /** Coach reads sleep and climbing workouts (CoachProfile.healthConnectEnabled). */
    val readEnabled: Boolean = false,
    /** Nutrition writes meals and water (AthleteProfile.healthConnectExport). */
    val exportEnabled: Boolean = false,
    /** The athlete was asked for read access. Whether they allowed it HealthKit never says. */
    val readAsked: Boolean = false,
    /** authorized | denied | notDetermined, for all five write types. */
    val writeStatus: String = HealthSync.NOT_DETERMINED,
    val syncing: Boolean = false,
    val lastSync: HealthSyncResult? = null,
    val sleepHours: Double? = null,
    /** done | unchanged | noAccess | failed of the last export from this card; "" before one. */
    val lastExport: String = "",
)

/**
 * The card's logic (Android `HealthConnectSettingsViewModel`): one switch per direction, each with its own
 * access request, a manual sync, and the last result. Turning a switch on stores the wish and asks for
 * access when it was never asked; nothing is read or written while a switch is off.
 */
internal class HealthSettingsPresenter(
    private val sync: HealthSync,
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(HealthSettingsState())
    val state: StateFlow<HealthSettingsState> = _state.asStateFlow()

    init {
        scope.launch { sync.lastSync.collect { r -> _state.update { it.copy(lastSync = r) } } }
    }

    fun refresh(): Job = scope.launch { refreshNow() }

    private suspend fun refreshNow() {
        val profile = withContext(io) { runCatching { service.ensureReady(); service.repo.profile() }.getOrNull() }
        val available = sync.availableStore() != null
        val asked = if (available) sync.requestStatus(HealthTypes.READ, emptyList()) else HealthSync.FAILED
        val write = if (available) withContext(io) { sync.writeStatus() } else HealthSync.NOT_DETERMINED
        _state.update {
            it.copy(
                loading = false,
                available = available,
                readEnabled = profile?.coach?.healthConnectEnabled ?: false,
                exportEnabled = profile?.healthConnectExport ?: false,
                readAsked = asked == HealthSync.ASKED,
                // A store that cannot even say whether it asked is not usable in this build.
                accessFailed = it.accessFailed || (available && asked == HealthSync.FAILED),
                writeStatus = write,
            )
        }
    }

    /** Stores the wish; asks for read access the first time, then syncs (Android `setEnabled` + `onPermissionsResult`). */
    fun setReadEnabled(on: Boolean): Job = scope.launch {
        withContext(io) { runCatching { service.ensureReady(); service.repo.updateProfile { it.copy(coach = it.coach.copy(healthConnectEnabled = on)) } } }
        _state.update { it.copy(readEnabled = on) }
        if (!on || !_state.value.available) return@launch
        if (!_state.value.readAsked) {
            if (!ask(HealthTypes.READ, emptyList())) return@launch
        }
        syncNowInternal()
    }

    /** The nutrition export; asks for write access when it was never decided. */
    fun setExportEnabled(on: Boolean): Job = scope.launch {
        withContext(io) { runCatching { service.ensureReady(); service.repo.updateProfile { it.copy(healthConnectExport = on) } } }
        _state.update { it.copy(exportEnabled = on) }
        if (!on || !_state.value.available) return@launch
        if (_state.value.writeStatus == HealthSync.NOT_DETERMINED) ask(emptyList(), HealthTypes.WRITE)
        if (_state.value.writeStatus == HealthSync.AUTHORIZED) exportToday()
    }

    /** "Allow access": asks for what a switched-on direction still lacks. */
    fun permit(): Job = scope.launch {
        val s = _state.value
        val read = if (s.readEnabled && !s.readAsked) HealthTypes.READ else emptyList()
        val write = if (s.exportEnabled && s.writeStatus == HealthSync.NOT_DETERMINED) HealthTypes.WRITE else emptyList()
        if (read.isEmpty() && write.isEmpty()) return@launch
        if (ask(read, write) && _state.value.readEnabled) syncNowInternal()
    }

    /** Reads climbing and sleep when reading is on, and sends today's meals and water when the export is on. */
    fun syncNow(): Job = scope.launch { syncNowInternal() }

    private suspend fun syncNowInternal() {
        if (_state.value.syncing) return
        _state.update { it.copy(syncing = true) }
        try {
            val s = _state.value
            if (s.readEnabled) {
                sync.syncClimbing()
                val today = withContext(io) { service.today() }
                val sleep = sync.lastNightSleepHours(today)
                _state.update { it.copy(sleepHours = sleep) }
            }
            if (s.exportEnabled && s.writeStatus == HealthSync.AUTHORIZED) exportToday()
        } finally {
            _state.update { it.copy(syncing = false) }
        }
    }

    private suspend fun exportToday() {
        val today = withContext(io) { service.today() }.toString()
        val result = sync.exportDayIfEnabled(today)
        _state.update { it.copy(lastExport = result) }
    }

    /** True when the request went through; the state is re-read either way. */
    private suspend fun ask(read: List<String>, write: List<String>): Boolean {
        val result = sync.requestAccess(read, write)
        if (result != HealthSync.DONE) _state.update { it.copy(accessFailed = true) }
        else _state.update { it.copy(accessFailed = false) }
        refreshNow()
        return result == HealthSync.DONE
    }

    fun close() = scope.cancel()
}
