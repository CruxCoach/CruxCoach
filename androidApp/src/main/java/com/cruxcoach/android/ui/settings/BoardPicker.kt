package com.cruxcoach.android.ui.settings

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.data.BoardSyncManager
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.data.AuroraBoardSelector
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.data.QuantumCatalogueSync
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.QuantumBoardModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "BoardPickerVM"

/**
 * Reactive state shared by every board picker (FEAT-031). Derived straight from
 * the persisted board prefs + DB sizes, so every picker call site (Settings,
 * Filter, Onboarding, sync card) shows identical selection + options and can
 * never drift apart.
 */
data class BoardPickerState(
    /** False until the real board prefs have loaded. The dialog must not seed
     *  its (unkeyed) remembered selection from the placeholder default, or it
     *  would always open on Kilter regardless of the actual active board. */
    val loaded: Boolean = false,
    val moonBoardHoldSets: Map<MoonBoardVariant, List<Long>> = emptyMap(),
    val moonBoardHasHoldSetData: Boolean? = null,
    val initialBrand: String = BoardBrand.KILTER.wireValue,
    val productSizes: List<BoardSize> = BoardConstants.KILTER_KNOWN_SIZES,
    val selectedKilterSizeId: Int = 0,
    val selectedMoonBoardVariant: MoonBoardVariant? = null,
    /** Active layout_id — seeds the Aurora variant selection (FEAT-031). */
    val selectedAuroraLayoutId: Int = 0,
    /** Wire values of Aurora brands whose catalogue is already loaded — hides
     *  the "we'll download …" hint for boards the user already has (FEAT-031). */
    val loadedAuroraBrands: Set<String> = emptySet(),
    /** Active product_size_id — seeds the Aurora size tier so re-opening the
     *  picker shows the current size selected (FEAT-031). */
    val selectedAuroraProductSizeId: Int = 0,
    /** Picker-ready, deduped product sizes per Aurora brand (keyed by wire
     *  value), so the picker can offer a size tier for ANY interactive board
     *  once its catalogue is loaded — variant boards (Tension/Decoy) and
     *  single-layout boards (Grasshopper/So iLL) alike (FEAT-031). */
    val auroraBrandSizes: Map<String, List<BoardSize>> = emptyMap(),
)

/**
 * Single source of truth for the board picker. All four pickers use this same
 * VM + the shared selection actions, so their state is identical by
 * construction and selecting in one is reflected everywhere via the prefs.
 */
@HiltViewModel
class BoardPickerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences,
    private val boardRepository: BoardRepository,
    private val auroraBoardSelector: AuroraBoardSelector,
    private val quantumCatalogueSync: QuantumCatalogueSync,
    private val syncManager: BoardSyncManager,
) : ViewModel() {

    private val productSizes = MutableStateFlow(BoardConstants.KILTER_KNOWN_SIZES)
    private val loadedBrands = MutableStateFlow<Set<String>>(emptySet())
    private val auroraBrandSizes = MutableStateFlow<Map<String, List<BoardSize>>>(emptyMap())

    private val moonHoldSetData = MutableStateFlow<Boolean?>(null)
    private val moonHoldSets = combine(MoonBoardVariant.entries.map { variant ->
        userPreferences.moonBoardHoldSets(variant).map { variant to it }
    }) { it.toMap() }

    val state: StateFlow<BoardPickerState> = combine(
        userPreferences.boardBrand,
        userPreferences.boardLayoutId,
        userPreferences.boardProductSizeId,
        // Nested so the outer combine stays within the typed 4-arg overload.
        combine(productSizes, loadedBrands, auroraBrandSizes) { sizes, loaded, auroraSizes ->
            Triple(sizes, loaded, auroraSizes)
        },
    ) { brand, layoutId, sizeId, sizeData ->
        val (sizes, loaded, auroraSizes) = sizeData
        BoardPickerState(
            loaded = true,
            initialBrand = brand,
            productSizes = sizes,
            selectedKilterSizeId = sizeId,
            selectedMoonBoardVariant = MoonBoardVariant.fromBoardSelection(
                layoutId.toLong(), BoardBrand.fromWire(brand),
            ),
            selectedAuroraLayoutId = layoutId,
            loadedAuroraBrands = loaded,
            selectedAuroraProductSizeId = sizeId,
            auroraBrandSizes = auroraSizes,
        )
    }.let { boardState ->
        combine(boardState, moonHoldSets, moonHoldSetData) { board, holds, hasData ->
            board.copy(moonBoardHoldSets = holds, moonBoardHasHoldSetData = hasData)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), BoardPickerState())

    init {
        // Prefer the synced DB sizes (both Kilter products); fall back to the
        // bundled known-sizes constant when the catalogue isn't imported yet.
        viewModelScope.launch {
            val sizes = withContext(Dispatchers.IO) {
                boardRepository.getAllProductSizes(
                    BoardConstants.KILTER_PRODUCT_ID.toLong(), BoardBrand.KILTER.wireValue,
                ) + boardRepository.getAllProductSizes(
                    BoardConstants.KILTER_HOMEWALL_PRODUCT_ID.toLong(), BoardBrand.KILTER.wireValue,
                )
            }
            if (sizes.isNotEmpty()) productSizes.value = sizes
            // Which boards are actually loaded — hides the download hint for them.
            // A grouped COUNT over the full catalogue used to monopolize the
            // database connection for several seconds on slower devices.  We
            // only need presence here, and board_brand has an index, so one
            // early-exit EXISTS probe per known brand is both exact and cheap.
            val loaded = withContext(Dispatchers.IO) {
                BoardBrand.entries
                    .asSequence()
                    .filter { boardRepository.hasClimbsForBrand(it.wireValue) }
                    .map { it.wireValue }
                    .toSet()
            }
            loadedBrands.value = loaded
            // Picker-ready (deduped) product sizes for every interactive Aurora
            // board, so the size tier works for variant boards (Tension/Decoy)
            // AND single-layout boards (Grasshopper/So iLL/Touchstone). Keyed by
            // brand; the dialog narrows to the active variant's product when a
            // variant is selected. A LOADED board uses its catalogue sizes
            // (authoritative, drift-proof); an UN-synced board falls back to the
            // bundled sizes (BoardConstants.AURORA_BUNDLED_SIZES) so its options
            // show IMMEDIATELY, before the first sync — the bundle mirrors the
            // catalogue's deduped output, so the list doesn't change once synced.
            auroraBrandSizes.value = withContext(Dispatchers.IO) {
                buildMap {
                    BoardBrand.entries
                        .filter { it.usesAuroraProtocol && it != BoardBrand.KILTER }
                        .forEach { brand ->
                            val sizes = (if (brand.wireValue in loaded)
                                boardRepository.getSelectableProductSizesForBrand(brand.wireValue)
                            else
                                BoardConstants.auroraBundledSizes(brand))
                                // Hide known Aurora phantoms (e.g. Tension's
                                // bogus "12 high x 16 wide") from the catalogue;
                                // the bundle never contains them, so this is a
                                // no-op there but keeps both paths uniform.
                                .filterNot { BoardConstants.isExcludedAuroraSize(brand, it.id.toInt()) }
                            if (sizes.isNotEmpty()) put(brand.wireValue, sizes)
                        }
                }
            }
        }
    }

    fun refreshMoonBoardHoldSetData() {
        viewModelScope.launch {
            moonHoldSetData.value = withContext(Dispatchers.IO) { boardRepository.hasMoonBoardHoldSetMask() }
        }
    }

    suspend fun needsDownloadConsent(brand: BoardBrand): Boolean =
        brand !in userPreferences.boardDownloadBrands.first() && withContext(Dispatchers.IO) {
            !boardRepository.hasClimbsForBrand(brand.wireValue)
        }

    suspend fun enableDownloads(brand: BoardBrand) {
        userPreferences.includeBoardDownload(brand)
    }

    fun downloadSelectedBoard(brand: BoardBrand) = syncManager.loadBoardCatalogue(brand)

    fun selectKilter(sizeId: Int, fixedAngle: Int? = null) {
        viewModelScope.launch {
            val size = productSizes.value.firstOrNull { it.id.toInt() == sizeId }
            // Never persist an id outside the Kilter size list (e.g. a stale
            // Aurora id leaking through a caller): a nonexistent product_size
            // resolves to null everywhere — empty size label, fit filter
            // silently off, no board image. Fall back to the product default.
            val validSizeId = if (size != null) sizeId else BoardConstants.KILTER_DEFAULT_SIZE
            val layout = BoardConstants.layoutIdForProduct(
                size?.productId?.toInt() ?: BoardConstants.KILTER_PRODUCT_ID,
            )
            // Atomic brand+layout+size(+angle) write — see UserPreferences.setBoardSelection:
            // separate writes can flash a transient (kilter, stale-layout) tuple
            // through the board-flow collectors. fixedAngle is non-null only for a
            // fixed-angle gym wall, seeding the browse angle to its real value.
            userPreferences.setBoardSelection(BoardBrand.KILTER.wireValue, layout, validSizeId, fixedAngle)
        }
    }

    fun selectMoonBoard(variant: MoonBoardVariant, holdSetIds: List<Long>? = null) {
        viewModelScope.launch { userPreferences.setMoonBoardSelection(variant.layoutId.toInt(), holdSetIds) }
    }

    fun selectQuantum(model: QuantumBoardModel, deferDownload: Boolean = false) {
        viewModelScope.launch {
            userPreferences.setBoardSelection(
                BoardBrand.QUANTUM.wireValue,
                model.layoutId.toInt(),
                model.productSizeId.toInt(),
                null,
            )
            // Selection remains usable offline when already cached; a failed
            // refresh is non-destructive and can be retried from board sync.
            if (deferDownload || BoardBrand.QUANTUM !in userPreferences.boardDownloadBrands.first()) return@launch
            syncManager.state.first { !it.isSyncing }
            if (BoardBrand.QUANTUM !in userPreferences.boardDownloadBrands.first()) return@launch
            val result = quantumCatalogueSync.sync()
            if (result is QuantumCatalogueSync.Result.Failed) {
                Toast.makeText(context, R.string.quantum_sync_failed_generic, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun selectAurora(board: BoardBrand, variant: BoardConstants.AuroraVariant?, productSizeId: Int? = null, deferDownload: Boolean = false) {
        viewModelScope.launch {
            val status = try {
                if (!deferDownload) syncManager.state.first { !it.isSyncing }
                auroraBoardSelector.select(board, variant, productSizeId, allowDownload = !deferDownload).status
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Aurora board selection failed", e)
                AuroraBoardSelector.Status.FAILED
            }
            // Surface a failed catalogue sync. The picker dialog / gym sheet is
            // already closed when the sync resolves, so a toast is the one
            // feedback channel that reaches every call site: without it a
            // hardware selection could otherwise land on an unexplained
            // empty board when its catalogue download fails.
            if (!deferDownload && status == AuroraBoardSelector.Status.DOWNLOAD_DISABLED) {
                Toast.makeText(context, R.string.board_download_disabled, Toast.LENGTH_LONG).show()
            }
            if (status == AuroraBoardSelector.Status.FAILED) {
                Toast.makeText(context, R.string.aurora_sync_failed_generic, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * The one board picker, used by every call site (FEAT-031). Reads its state
 * from the shared [BoardPickerViewModel] so all pickers are identical and
 * always offer the same boards (Kilter, MoonBoard + the Aurora family).
 *
 * @param onSelected invoked after a board is confirmed — the host uses it to
 *        close the dialog; board content updates reactively via the prefs.
 */
@Composable
internal fun BoardPickerDialog(
    onDismiss: () -> Unit,
    onSelected: () -> Unit = {},
    onFindViaGym: (() -> Unit)? = null,
    prefill: BoardPickerPrefill? = null,
    mismatch: BoardConfigurationMismatch? = null,
    deferDownloads: Boolean = false,
    onBoardChosen: (BoardBrand) -> Unit = {},
    suggestedBrand: BoardBrand? = null,
) {
    val viewModel: BoardPickerViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.refreshMoonBoardHoldSetData() }
    // Wait for the real prefs before composing the dialog — it seeds its
    // selection once (unkeyed remember), so it must not see the placeholder.
    if (!state.loaded) return
    BoardDownloadRequestHost(viewModel, deferDownloads, onSelected = { brand ->
        onBoardChosen(brand)
        onSelected()
    }) { request ->
        BoardSelectionDialog(
            initialBrand = prefill?.brand?.wireValue ?: suggestedBrand?.wireValue ?: state.initialBrand,
            productSizes = state.productSizes,
            selectedKilterSizeId = state.selectedKilterSizeId,
            selectedMoonBoardVariant = state.selectedMoonBoardVariant,
            initialMoonBoardHoldSets = state.moonBoardHoldSets,
            moonBoardHasHoldSetData = state.moonBoardHasHoldSetData,
            selectedAuroraLayoutId = state.selectedAuroraLayoutId,
            selectedAuroraProductSizeId = state.selectedAuroraProductSizeId,
            loadedAuroraBrands = state.loadedAuroraBrands,
            auroraBrandSizes = state.auroraBrandSizes,
            frequency = BoardConstants.DEFAULT_SIZE_FREQUENCY,
            showAuroraBoards = true,
            prefill = prefill,
            mismatch = mismatch,
            onConfirmKilter = { size -> request(BoardBrand.KILTER) { viewModel.selectKilter(size) } },
            onConfirmMoonBoard = { variant, holds -> request(BoardBrand.MOONBOARD) { viewModel.selectMoonBoard(variant, holds) } },
            onConfirmQuantum = { model -> request(BoardBrand.QUANTUM) { viewModel.selectQuantum(model, deferDownloads) } },
            onConfirmAurora = { brand, variant, sizeId -> request(brand) { viewModel.selectAurora(brand, variant, sizeId, deferDownloads) } },
            onFindViaGym = onFindViaGym,
            onDismiss = onDismiss,
        )
    }
}

/** Shared by direct and gym pickers. The selection is applied only after consent. */
@Composable
internal fun BoardDownloadRequestHost(
    viewModel: BoardPickerViewModel,
    deferDownloads: Boolean,
    onSelected: (BoardBrand) -> Unit,
    content: @Composable ((BoardBrand, () -> Unit) -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<BoardBrand, () -> Unit>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val complete: (BoardBrand, () -> Unit, Boolean) -> Unit = { brand, apply, enabled ->
        apply()
        // Aurora/Quantum selection already loads its catalogue. Kilter and
        // MoonBoard selection only persists hardware preferences.
        if (enabled && (brand == BoardBrand.KILTER || brand == BoardBrand.MOONBOARD)) {
            viewModel.downloadSelectedBoard(brand)
        }
        onSelected(brand)
    }
    content { brand, apply ->
        if (!busy && pending == null) {
            busy = true
            scope.launch {
                try {
                    if (!deferDownloads && viewModel.needsDownloadConsent(brand)) {
                        failed = false
                        pending = brand to apply
                    } else complete(brand, apply, false)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed = true
                } finally {
                    busy = false
                }
            }
        }
    }
    if (failed && pending == null) {
        Text(stringResource(R.string.onboarding_download_selection_failed), color = MaterialTheme.colorScheme.error)
    }
    pending?.let { (brand, apply) ->
        AlertDialog(
            onDismissRequest = { if (!busy) pending = null },
            title = { Text(stringResource(R.string.board_picker_download_title, brand.displayName)) },
            text = { Text(stringResource(if (failed) R.string.onboarding_download_selection_failed else R.string.board_picker_download_description)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            viewModel.enableDownloads(brand)
                            complete(brand, apply, true)
                            pending = null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.board_picker_download_confirm)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pending = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

}
