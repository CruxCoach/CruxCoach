package com.cruxcoach.app.ui

import com.cruxcoach.app.platform.ConnectivityMonitor
import com.cruxcoach.app.setup.BoardOptions
import com.cruxcoach.app.sync.CatalogueAutoSync
import com.cruxcoach.app.sync.CatalogueDownloadGate
import com.cruxcoach.app.sync.CatalogueGateState
import com.cruxcoach.app.sync.CatalogueSyncController
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class SyncBrandRow(
    val brandWire: String,
    val title: String,
    /** idle | checking | downloading | verifying | importing | upToDate | done | failed */
    val phase: String,
    /** none | manifestUnavailable | … | cancelled */
    val failure: String,
    val receivedBytes: Long,
    val totalBytes: Long,
    val climbCount: Long,
    val installed: Boolean,
)

class SyncScreenState(
    val running: Boolean,
    val online: Boolean,
    val rows: List<SyncBrandRow>,
    val installedCount: Int,
    /** False until the first database read answered; the UI shows a spinner, not onboarding. */
    val installedKnown: Boolean,
    val catalogueRevision: Int,
    /** Android's "Download over mobile data?" dialog is open. */
    val meteredConfirm: Boolean,
    /** Android's "No network connection" dialog is open. */
    val noNetwork: Boolean,
    /** A start waits for Wi-Fi ("Queued — starts automatically on WiFi"). */
    val waitingForWifi: Boolean,
    /** Online over mobile data, a hotspot or Low Data Mode: Android's "No WiFi" banner. */
    val onMeteredNetwork: Boolean,
    /** Days without a successful automatic update once three cycles were missed; -1 otherwise. */
    val overdueDays: Int,
)

/** Board-catalogue download screen (Android: onboarding step 1 and "Sync board data"). */
class SyncScreenModel(
    private val controller: CatalogueSyncController,
    private val connectivity: ConnectivityMonitor,
    /** Android's mobile-data consent and Wi-Fi queue for user-started downloads. */
    private val gate: CatalogueDownloadGate,
    /** The automatic refresh, for Android's "not updated for N days" hint. */
    private val autoSync: CatalogueAutoSync,
    main: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)

    val brandWires: List<String> = BoardOptions.downloadableBrands.map { it.wireValue }

    val currentState: SyncScreenState get() = map(controller.state.value, connectivity.isOnline.value, gate.state.value)

    fun watch(onState: (SyncScreenState) -> Unit): Subscription {
        val job = scope.launch {
            combine(controller.state, connectivity.isOnline, gate.state, gate.networkKind) { sync, online, gateState, _ ->
                map(sync, online, gateState)
            }.collect { onState(it) }
        }
        return Subscription { job.cancel() }
    }

    private fun map(
        sync: com.cruxcoach.app.sync.CatalogueSyncState,
        online: Boolean,
        gateState: CatalogueGateState,
    ): SyncScreenState {
        val installed = sync.installedBrands.map { it.wireValue }.toSet()
        return SyncScreenState(
            running = sync.running,
            online = online,
            rows = BoardOptions.downloadableBrands.map { brand ->
                val brandState = sync.brands.firstOrNull { it.brand == brand }
                SyncBrandRow(
                    brandWire = brand.wireValue,
                    title = UiCodes.brandTitle(brand),
                    phase = brandState?.let { UiCodes.phase(it.phase) } ?: "idle",
                    failure = brandState?.let { UiCodes.syncFailure(it.failure) } ?: "none",
                    receivedBytes = brandState?.receivedBytes ?: 0L,
                    totalBytes = brandState?.totalBytes ?: 0L,
                    climbCount = brandState?.climbCount ?: 0L,
                    installed = brand.wireValue in installed,
                )
            },
            installedCount = installed.size,
            installedKnown = sync.installedKnown,
            catalogueRevision = sync.catalogueRevision,
            meteredConfirm = gateState.meteredConfirm,
            noNetwork = gateState.noNetwork,
            waitingForWifi = gateState.waitingForWifi,
            onMeteredNetwork = online && gate.isMeteredNow,
            overdueDays = if (sync.running) -1 else autoSync.overdueDays(),
        )
    }

    /** Starts at once, without the mobile-data question (callers that already asked). */
    fun start(brandWires: List<String>) {
        val brands = brandWires.mapNotNull { BoardBrand.fromWireOrNull(it) }
        if (brands.isNotEmpty()) controller.start(brands)
    }

    /** A user-started download: asks before mobile data, says when there is no network (Android `startApiSync`). */
    fun requestStart(brandWires: List<String>) =
        gate.request(brandWires.mapNotNull { BoardBrand.fromWireOrNull(it) }, queueWhenOffline = false)

    /** The onboarding's download: offline or declined over mobile data, it waits for Wi-Fi instead. */
    fun requestStartQueued(brandWires: List<String>) =
        gate.request(brandWires.mapNotNull { BoardBrand.fromWireOrNull(it) }, queueWhenOffline = true)

    /** "Use mobile data". */
    fun confirmMetered() = gate.confirmMetered()

    /** "Cancel" on the mobile-data question. */
    fun dismissMetered() = gate.dismissMetered()

    fun dismissNoNetwork() = gate.dismissNoNetwork()

    fun cancel() = controller.cancel()

    fun close() = scope.cancel()
}
