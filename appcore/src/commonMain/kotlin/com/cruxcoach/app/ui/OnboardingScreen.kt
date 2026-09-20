package com.cruxcoach.app.ui

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.settings.BoardDownloadSelection
import com.cruxcoach.app.settings.SettingsSnapshot
import com.cruxcoach.app.settings.SettingsStore
import com.cruxcoach.app.setup.BoardOptions
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class OnboardingScreenState(
    /** board | data */
    val step: String,
    val stepNumber: Int,
    val stepCount: Int,
    /** True once `onboarding_completed` is set for this identity; the host leaves the flow. */
    val completed: Boolean,
    val boardBrandWire: String,
    val boardLayoutId: Int,
    val boardProductSizeId: Int,
    /** Product name of the chosen board, e.g. "Kilter Board". */
    val boardTitle: String,
    /** Layout (and size); empty until that brand's catalogue is installed. */
    val boardDetail: String,
    /** Catalogues the user ticked for download in this flow. */
    val downloadBrandWires: List<String>,
    val brands: List<BrandChoiceUi>,
)

/**
 * First-run setup, a port of Android's `ui/onboarding/OnboardingScreen` +
 * `OnboardingViewModel`. Two steps, both skippable:
 *
 *  1. BOARD — connect the wall (optional), pick the board model, pick which
 *     catalogues to download.
 *  2. DATA  — bring existing data over (encrypted CruxCoach backup on iOS; the
 *     Kilter-account and MoonBoard-CSV importers are not ported).
 *
 * What it persists is exactly what Android persists: the board selection,
 * `board_download_brands` and the key-scoped `onboarding_completed`.
 *
 * The catalogue download itself is not duplicated here — the host drives the
 * existing [SyncScreenModel], the same one the settings "board data" screen uses.
 */
class OnboardingScreenModel(
    private val settings: SettingsStore,
    private val keyValues: KeyValueStore,
    private val boardRepository: BoardRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val step = MutableStateFlow(STEP_BOARD)
    private val selection = MutableStateFlow(initialDownloadSelection())

    val brandWires: List<String> = downloadable.map { it.wireValue }

    val currentState: OnboardingScreenState
        get() = map(settings.snapshot, step.value, selection.value)

    fun watch(onState: (OnboardingScreenState) -> Unit): Subscription {
        val job = scope.launch {
            combine(settings.state, step, selection) { snapshot, current, wires ->
                map(snapshot, current, wires)
            }.collect { onState(it) }
        }
        return Subscription { job.cancel() }
    }

    // ── Step 1: board ────────────────────────────────────────────────

    /**
     * Persists the chosen board immediately, as Android does, so every other
     * screen already sees it, and narrows the download selection to that board
     * — the overwhelmingly common case is one wall.
     */
    fun chooseBoard(brandWire: String, layoutId: Int, productSizeId: Int) {
        val brand = BoardBrand.fromWireOrNull(brandWire) ?: return
        settings.setBoard(brand.wireValue, layoutId, productSizeId)
        if (brand.isInteractive) selection.value = listOf(brand.wireValue)
    }

    fun toggleDownloadBrand(brandWire: String) {
        val brand = BoardBrand.fromWireOrNull(brandWire)?.takeIf { it.isInteractive } ?: return
        val current = selection.value
        selection.value = if (brand.wireValue in current) current - brand.wireValue else current + brand.wireValue
    }

    /** "All boards", or none when everything is already ticked. */
    fun toggleAllDownloadBrands() {
        val all = downloadable.map { it.wireValue }
        selection.value = if (selection.value.containsAll(all)) emptyList() else all
    }

    /** Writes the download consent and moves to step 2. The host starts the sync. */
    fun confirmDownloads() {
        persistSelection()
        step.value = STEP_DATA
    }

    // ── Navigation ───────────────────────────────────────────────────

    fun back() {
        step.value = STEP_BOARD
    }

    /**
     * Global skip. An untouched selection must not leave the legacy
     * "every board" default behind for a later refresh to act on, so skipping
     * before the download step writes an explicit empty selection.
     */
    fun skip() {
        if (!BoardDownloadSelection.isConfigured(keyValues)) {
            BoardDownloadSelection.save(keyValues, emptyList())
        }
        complete()
    }

    /** Finishes the flow from step 2. */
    fun finish() {
        persistSelection()
        complete()
    }

    fun close() = scope.cancel()

    // ── internals ────────────────────────────────────────────────────

    private fun complete() {
        settings.setOnboardingCompleted(true)
    }

    private fun persistSelection() {
        BoardDownloadSelection.save(keyValues, selection.value.mapNotNull { BoardBrand.fromWireOrNull(it) })
        settings.refresh()
    }

    /**
     * A first run suggests the active board only and records an explicit empty
     * consent, mirroring Android's `initialDownloadSelection`. A returning user
     * (or one who already chose) keeps their stored selection.
     */
    private fun initialDownloadSelection(): List<String> {
        val stored = BoardDownloadSelection.isConfigured(keyValues)
        if (stored || settings.snapshot.onboardingCompleted) {
            return BoardDownloadSelection.selected(keyValues).map { it.wireValue }
        }
        val active = BoardBrand.fromWireOrNull(settings.snapshot.boardBrand)?.takeIf { it.isInteractive }
        BoardDownloadSelection.save(keyValues, emptyList())
        return listOfNotNull(active?.wireValue)
    }

    private fun map(snapshot: SettingsSnapshot, current: String, wires: List<String>): OnboardingScreenState =
        OnboardingScreenState(
            step = current,
            stepNumber = if (current == STEP_DATA) 2 else 1,
            stepCount = 2,
            completed = snapshot.onboardingCompleted,
            boardBrandWire = snapshot.boardBrand,
            boardLayoutId = snapshot.boardLayoutId,
            boardProductSizeId = snapshot.boardProductSizeId,
            boardTitle = BoardBrand.fromWireOrNull(snapshot.boardBrand)?.let { UiCodes.brandTitle(it) } ?: "",
            boardDetail = boardDetail(snapshot),
            downloadBrandWires = wires,
            brands = downloadable.map {
                BrandChoiceUi(
                    brandWire = it.wireValue,
                    title = UiCodes.brandTitle(it),
                    catalogueInstalled = false,
                    autoDownload = it.wireValue in wires,
                )
            },
        )

    private fun boardDetail(snapshot: SettingsSnapshot): String {
        val brand = BoardBrand.fromWireOrNull(snapshot.boardBrand) ?: return ""
        val option = try {
            BoardOptions.forBrand(brand, boardRepository).firstOrNull {
                it.layoutId == snapshot.boardLayoutId &&
                    (it.productSizeId == snapshot.boardProductSizeId || it.productSizeId == 0)
            }
        } catch (e: Exception) {
            null
        } ?: return ""
        return if (option.sizeName.isEmpty()) option.layoutName else "${option.layoutName} · ${option.sizeName}"
    }

    companion object {
        const val STEP_BOARD = "board"
        const val STEP_DATA = "data"

        /** Only brands whose catalogue this app can install can be ticked here. */
        private val downloadable: List<BoardBrand> get() = BoardOptions.downloadableBrands
    }
}
