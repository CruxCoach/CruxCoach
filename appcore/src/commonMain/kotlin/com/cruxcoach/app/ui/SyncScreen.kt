package com.cruxcoach.app.ui

import com.cruxcoach.app.platform.ConnectivityMonitor
import com.cruxcoach.app.setup.BoardOptions
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
    val catalogueRevision: Int,
)

/** Board-catalogue download screen (Android: onboarding step 1 and "Sync board data"). */
class SyncScreenModel(
    private val controller: CatalogueSyncController,
    private val connectivity: ConnectivityMonitor,
    main: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)

    val brandWires: List<String> = BoardOptions.downloadableBrands.map { it.wireValue }

    val currentState: SyncScreenState get() = map(controller.state.value, connectivity.isOnline.value)

    fun watch(onState: (SyncScreenState) -> Unit): Subscription {
        val job = scope.launch {
            combine(controller.state, connectivity.isOnline) { sync, online -> map(sync, online) }
                .collect { onState(it) }
        }
        return Subscription { job.cancel() }
    }

    private fun map(sync: com.cruxcoach.app.sync.CatalogueSyncState, online: Boolean): SyncScreenState {
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
            catalogueRevision = sync.catalogueRevision,
        )
    }

    fun start(brandWires: List<String>) {
        val brands = brandWires.mapNotNull { BoardBrand.fromWireOrNull(it) }
        if (brands.isNotEmpty()) controller.start(brands)
    }

    fun cancel() = controller.cancel()

    fun close() = scope.cancel()
}
