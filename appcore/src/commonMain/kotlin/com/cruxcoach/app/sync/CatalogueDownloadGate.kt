package com.cruxcoach.app.sync

import com.cruxcoach.app.platform.ConnectivityMonitor
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.settings.safeGet
import com.cruxcoach.app.settings.safePut
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The link a download would use now: Android's `isWifiConnected` / `NetworkType.UNMETERED`.
 * On iOS "expensive" (cellular, a phone's hotspot) and "constrained" (Low Data Mode) are metered.
 */
interface NetworkCost {
    /** [UNKNOWN] until the system's first answer, then [NONE], [METERED] or [UNMETERED]. */
    val kind: StateFlow<String>

    companion object {
        const val UNKNOWN = "unknown"
        const val NONE = "none"
        const val METERED = "metered"
        const val UNMETERED = "unmetered"
    }
}

data class CatalogueGateState(
    /** Android's "Download over mobile data?" dialog (`showMeteredConfirmDialog`). */
    val meteredConfirm: Boolean = false,
    /** Android's "No network connection" dialog (`showNetworkDialog`). */
    val noNetwork: Boolean = false,
    /** Android's `waitingForUnmeteredNetwork`: "Queued — starts automatically on WiFi". */
    val waitingForWifi: Boolean = false,
)

/**
 * Port of `BoardSyncManager.startApiSync` / `confirmMeteredSync` / `dismissMeteredConfirm`: a
 * download the user asks for off Wi-Fi is not blocked but asked about (~130 MB of mobile data);
 * automatic downloads never use mobile data. A start queued for Wi-Fi (Android: a WorkManager job
 * with `NetworkType.UNMETERED`) begins as soon as an unmetered link is there, also after a
 * relaunch; on iOS only while the app runs, which the hint says.
 *
 * [onUnmetered] runs whenever an unmetered link appears and nothing is queued: the composition
 * root hands it the due-check of the automatic refresh, Android's periodic worker whose
 * UNMETERED constraint lets it run once Wi-Fi is back.
 */
class CatalogueDownloadGate(
    private val controller: CatalogueSyncController,
    private val connectivity: ConnectivityMonitor,
    private val network: NetworkCost,
    private val keyValues: KeyValueStore,
    scope: CoroutineScope,
    private val onUnmetered: () -> Unit = {},
) {
    private val _state = MutableStateFlow(CatalogueGateState(waitingForWifi = readQueue().isNotEmpty()))
    val state: StateFlow<CatalogueGateState> = _state.asStateFlow()

    /** The brands the open metered dialog is about. */
    private var pending: List<BoardBrand> = emptyList()
    private var queueAfterMeteredDismiss = false

    init {
        scope.launch {
            var lastKind: String? = null
            combine(network.kind, controller.state) { kind, sync -> kind to sync.running }
                .distinctUntilChanged()
                .collect { (kind, running) ->
                    val becameUnmetered = kind == NetworkCost.UNMETERED && lastKind != NetworkCost.UNMETERED
                    lastKind = kind
                    if (kind != NetworkCost.UNMETERED || running) return@collect
                    when {
                        readQueue().isNotEmpty() -> startQueued()
                        // Only when the link turns unmetered, not after every run that ends on Wi-Fi.
                        becameUnmetered -> onUnmetered()
                    }
                }
        }
    }

    /** The link's kind, for screens that show the "No WiFi" banner as it changes. */
    val networkKind: StateFlow<String> get() = network.kind

    /** Whether the current link is metered while online: Android's "No WiFi" banner. */
    val isMeteredNow: Boolean
        get() = connectivity.isOnline.value && network.kind.value == NetworkCost.METERED

    /**
     * A user-triggered download (Android `startApiSync`). [queueWhenOffline]: the onboarding's
     * start, which waits for Wi-Fi when offline or when the mobile-data question is declined.
     */
    fun request(brands: List<BoardBrand>, queueWhenOffline: Boolean) {
        val wanted = brands.filter { it.isInteractive }.distinct()
        if (wanted.isEmpty() || controller.state.value.running) return
        val kind = network.kind.value
        if (!connectivity.isOnline.value || kind == NetworkCost.NONE) {
            if (queueWhenOffline) queue(wanted) else _state.update { it.copy(noNetwork = true) }
            return
        }
        // Not known to be unmetered (mobile data, Low Data Mode, or no answer yet): ask, never assume Wi-Fi.
        if (kind != NetworkCost.UNMETERED) {
            pending = wanted
            queueAfterMeteredDismiss = queueWhenOffline
            _state.update { it.copy(meteredConfirm = true) }
            return
        }
        begin(wanted)
    }

    /** "Use mobile data": the one-time consent for this download (Android `confirmMeteredSync`). */
    fun confirmMetered() {
        val brands = pending
        pending = emptyList()
        queueAfterMeteredDismiss = false
        _state.update { it.copy(meteredConfirm = false) }
        if (brands.isEmpty() || controller.state.value.running) return
        if (!connectivity.isOnline.value) {
            _state.update { it.copy(noNetwork = true) }
            return
        }
        begin(brands)
    }

    /** "Cancel": the onboarding's start then waits for Wi-Fi (Android `dismissMeteredConfirm`). */
    fun dismissMetered() {
        val queueForWifi = queueAfterMeteredDismiss
        val brands = pending
        pending = emptyList()
        queueAfterMeteredDismiss = false
        _state.update { it.copy(meteredConfirm = false) }
        if (queueForWifi && brands.isNotEmpty()) queue(brands)
    }

    fun dismissNoNetwork() = _state.update { it.copy(noNetwork = false) }

    private fun begin(brands: List<BoardBrand>) {
        // Android's claimSyncSlot clears the waiting flag: a run that starts supersedes the queue.
        clearQueue()
        controller.start(brands)
    }

    private fun queue(brands: List<BoardBrand>) {
        val merged = (readQueue() + brands).distinct()
        keyValues.safePut(KEY_WIFI_QUEUE, merged.joinToString(",") { it.wireValue })
        _state.update { it.copy(waitingForWifi = true) }
        // Already on Wi-Fi and idle (the dialog was declined just as Wi-Fi came back): start now.
        if (network.kind.value == NetworkCost.UNMETERED && !controller.state.value.running) startQueued()
    }

    private fun startQueued() {
        val queued = readQueue()
        if (queued.isEmpty() || !connectivity.isOnline.value) return
        begin(queued)
    }

    private fun clearQueue() {
        keyValues.safePut(KEY_WIFI_QUEUE, null)
        _state.update { it.copy(waitingForWifi = false) }
    }

    private fun readQueue(): List<BoardBrand> =
        keyValues.safeGet(KEY_WIFI_QUEUE)?.split(',')?.mapNotNull { BoardBrand.fromWireOrNull(it.trim()) }
            ?.filter { it.isInteractive }?.distinct() ?: emptyList()

    companion object {
        /** The brands a start queued for Wi-Fi covers, comma separated (iOS only). */
        const val KEY_WIFI_QUEUE = "catalogue_wifi_queue"
    }
}
