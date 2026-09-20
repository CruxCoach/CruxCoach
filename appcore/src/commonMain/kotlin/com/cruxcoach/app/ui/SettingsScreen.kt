package com.cruxcoach.app.ui

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.render.LedHoldColors
import com.cruxcoach.app.render.Rgb332Palette
import com.cruxcoach.app.settings.BoardDownloadSelection
import com.cruxcoach.app.settings.DarkModeSetting
import com.cruxcoach.app.settings.GradeScale
import com.cruxcoach.app.settings.MoonBoardLedMode
import com.cruxcoach.app.settings.SettingsSnapshot
import com.cruxcoach.app.settings.SettingsStore
import com.cruxcoach.app.settings.SyncInterval
import com.cruxcoach.app.setup.BoardOptions
import com.cruxcoach.app.sync.CatalogueSyncController
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One LED role as the picker shows it. [byte] is the RGB332 value sent to the wall. */
class LedRoleUi(
    /** start | hand | finish | foot */
    val roleCode: String,
    val byte: Int,
    /** Opaque ARGB for the swatch. */
    val argb: Long,
    /** Android string-resource name of the palette entry, or `color_custom`. */
    val nameKey: String,
    /** True while the role still uses the built-in CruxCoach colour (key unset). */
    val isDefault: Boolean,
)

/** One entry of the hardware-safe RGB332 picker palette. */
class PaletteColorUi(val nameKey: String, val byte: Int, val argb: Long, val familyCode: String)

/** One board family in the two deletion dialogs and the catalogue list. */
class BrandChoiceUi(
    val brandWire: String,
    /** Product name, not translatable UI text. */
    val title: String,
    val catalogueInstalled: Boolean,
    val autoDownload: Boolean,
)

class SettingsScreenState(
    /** system | light | dark */
    val darkMode: String,
    /** french | vScale */
    val gradeScale: String,
    val keepScreenOn: Boolean,
    val boardBrandWire: String,
    val boardLayoutId: Int,
    val boardProductSizeId: Int,
    /** Product name of the active board, e.g. "Kilter Board"; empty when unknown. */
    val boardTitle: String,
    /** Layout (and size) of the active board; empty until its catalogue is installed. */
    val boardDetail: String,
    /** 0 = never disconnect automatically. */
    val bleAutoDisconnectSeconds: Int,
    /** below | above | both */
    val moonBoardLedMode: String,
    val ledColors: List<LedRoleUi>,
    val restTimerDurationSeconds: Int,
    val restTimerAutoStart: Boolean,
    /** daily | weekly | manual */
    val syncInterval: String,
    val brands: List<BrandChoiceUi>,
    val deletingCatalogue: Boolean,
    val deletingUserData: Boolean,
    /** "" | catalogueDeleted | userDataDeleted | deleteFailed */
    val lastResult: String,
)

/**
 * Settings, on Android's preference keys, value spellings and defaults so a
 * backup written by either app restores into the other.
 *
 * Only settings that have an effect on iOS are exposed; a switch that changes
 * nothing is worse than an absent one. What is deliberately missing is listed
 * in `docs` and in this screen's help text, not silently faked.
 */
class SettingsScreenModel(
    private val settings: SettingsStore,
    /** The same store [settings] writes; `board_download_brands` is a set, which [SettingsStore] does not model. */
    private val keyValues: KeyValueStore,
    private val boardRepository: BoardRepository,
    private val personalRepository: PersonalBoardRepository,
    private val catalogueSync: CatalogueSyncController,
    /** Applied to the live BLE link the moment the idle timeout changes. */
    private val onAutoDisconnectSeconds: (Int) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private class Transient(
        val deletingCatalogue: Boolean = false,
        val deletingUserData: Boolean = false,
        val lastResult: String = "",
    )

    private val transient = MutableStateFlow(Transient())

    /** Board families a user can delete or download here, in Android's declaration order. */
    val brandWires: List<String> = deletableBrands.map { it.wireValue }

    val palette: List<PaletteColorUi> = Rgb332Palette.PALETTE.map {
        PaletteColorUi(it.nameKey, it.byte, it.argb, it.family.name.lowercase())
    }

    val currentState: SettingsScreenState
        get() = map(settings.snapshot, catalogueSync.installedBrands, transient.value)

    fun watch(onState: (SettingsScreenState) -> Unit): Subscription {
        val job = scope.launch {
            combine(settings.state, catalogueSync.state, transient) { snapshot, sync, busy ->
                map(snapshot, sync.installedBrands, busy)
            }.collect { onState(it) }
        }
        return Subscription { job.cancel() }
    }

    // ── Display ──────────────────────────────────────────────────────

    fun setDarkMode(code: String) {
        settings.setDarkMode(SettingsCodes.darkMode(code) ?: return)
    }

    fun setGradeScale(code: String) {
        settings.setGradeScale(SettingsCodes.gradeScale(code) ?: return)
    }

    fun setKeepScreenOn(enabled: Boolean) = settings.setKeepScreenOn(enabled)

    // ── Board & Bluetooth ────────────────────────────────────────────

    fun setBoard(brandWire: String, layoutId: Int, productSizeId: Int) {
        val brand = BoardBrand.fromWireOrNull(brandWire) ?: return
        settings.setBoard(brand.wireValue, layoutId, productSizeId)
    }

    fun setBleAutoDisconnectSeconds(seconds: Int) {
        val bounded = seconds.coerceIn(0, MAX_AUTO_DISCONNECT_SECONDS)
        settings.setBleAutoDisconnectSeconds(bounded)
        onAutoDisconnectSeconds(bounded)
    }

    fun setMoonBoardLedMode(code: String) {
        settings.setMoonBoardLedMode(SettingsCodes.ledMode(code) ?: return)
    }

    /** [byte] is an RGB332 value; anything outside a byte is ignored rather than truncated. */
    fun setLedColor(roleCode: String, byte: Int) {
        val role = SettingsCodes.ledRole(roleCode) ?: return
        if (byte !in 0..0xFF) return
        settings.setLedColor(role, byte)
    }

    /** Back to the built-in CruxCoach colours: Android removes the four keys. */
    fun resetLedColors() = SettingsStore.LedRole.entries.forEach { settings.setLedColor(it, null) }

    fun applyKilterLedColors() {
        val kilter = LedHoldColors.kilterStandard()
        settings.setLedColor(SettingsStore.LedRole.START, kilter.start)
        settings.setLedColor(SettingsStore.LedRole.HAND, kilter.hand)
        settings.setLedColor(SettingsStore.LedRole.FINISH, kilter.finish)
        settings.setLedColor(SettingsStore.LedRole.FOOT, kilter.foot)
    }

    // ── Timers ───────────────────────────────────────────────────────

    fun setRestTimerDurationSeconds(seconds: Int) = settings.setRestTimerDurationSeconds(seconds)

    fun setRestTimerAutoStart(enabled: Boolean) = settings.setRestTimerAutoStart(enabled)

    // ── Catalogues ───────────────────────────────────────────────────

    fun setSyncInterval(code: String) {
        settings.setSyncInterval(SettingsCodes.syncInterval(code) ?: return)
    }

    /** Which catalogues a foreground refresh may download (Android `board_download_brands`). */
    fun setAutoDownloadBrands(brandWires: List<String>) {
        BoardDownloadSelection.save(
            keyValues,
            brandWires.mapNotNull { BoardBrand.fromWireOrNull(it) },
        )
        settings.refresh()
    }

    // ── Deletion ─────────────────────────────────────────────────────

    /**
     * Drops the selected catalogues and their incremental sync markers, then
     * opts them out of future downloads — without the opt-out the next refresh
     * would silently undo the deletion (Android `BoardSyncManager.deleteBoardData`).
     */
    fun deleteCatalogueData(brandWires: List<String>) {
        val brands = brandWires.mapNotNull { BoardBrand.fromWireOrNull(it) }.filter { it.isInteractive }.distinct()
        if (brands.isEmpty()) return
        if (!claim(catalogue = true)) return
        scope.launch {
            val ok = try {
                withContext(ioDispatcher) {
                    if (brands.containsAll(deletableBrands)) {
                        boardRepository.deleteAllBoardData()
                    } else {
                        boardRepository.deleteBoardDataForBrands(brands.map { it.wireValue }.toSet())
                    }
                }
                BoardDownloadSelection.exclude(keyValues, brands)
                settings.refresh()
                catalogueSync.catalogueDeleted(brands)
                true
            } catch (e: Exception) {
                false
            }
            transient.value = Transient(lastResult = if (ok) RESULT_CATALOGUE else RESULT_FAILED)
        }
    }

    /**
     * Drops ascents, sessions and lists that belong to the selected boards.
     * A full selection keeps Android's historical whole-wipe, which also takes
     * brand-less sessions and list entries whose climb is no longer known.
     */
    fun deleteUserData(brandWires: List<String>) {
        val brands = brandWires.mapNotNull { BoardBrand.fromWireOrNull(it) }.filter { it.isInteractive }.distinct()
        if (brands.isEmpty()) return
        if (!claim(catalogue = false)) return
        scope.launch {
            val ok = try {
                withContext(ioDispatcher) {
                    if (brands.containsAll(deletableBrands)) {
                        personalRepository.deleteAllUserBoardData()
                    } else {
                        val wires = brands.map { it.wireValue }.toSet()
                        // List entries carry no brand column and live in the other
                        // database file, so each referenced climb's family is resolved
                        // through the board DB. A uuid the catalogue no longer knows
                        // stays put: it cannot be proven to belong to a chosen board.
                        val entryUuids = personalRepository.getAllListEntryClimbUuids()
                        val entryBrands = boardRepository.getClimbBrandsForUuids(entryUuids)
                        val doomedEntries = entryUuids.filter { entryBrands[it] in wires }
                        personalRepository.deleteUserBoardDataForBrands(wires, doomedEntries)
                    }
                }
                true
            } catch (e: Exception) {
                false
            }
            transient.value = Transient(lastResult = if (ok) RESULT_USER_DATA else RESULT_FAILED)
        }
    }

    fun dismissResult() = transient.update { Transient(it.deletingCatalogue, it.deletingUserData, "") }

    fun close() = scope.cancel()

    // ── internals ────────────────────────────────────────────────────

    /** One destructive run at a time; a second tap must not start a parallel delete. */
    private fun claim(catalogue: Boolean): Boolean {
        val current = transient.value
        if (current.deletingCatalogue || current.deletingUserData) return false
        transient.value = Transient(deletingCatalogue = catalogue, deletingUserData = !catalogue)
        return true
    }

    private fun map(
        snapshot: SettingsSnapshot,
        installed: List<BoardBrand>,
        busy: Transient,
    ): SettingsScreenState {
        val installedWires = installed.mapTo(mutableSetOf()) { it.wireValue }
        val autoDownload = BoardDownloadSelection.selected(keyValues).mapTo(mutableSetOf()) { it.wireValue }
        return SettingsScreenState(
            darkMode = SettingsCodes.darkMode(snapshot.darkMode),
            gradeScale = SettingsCodes.gradeScale(snapshot.gradeScale),
            keepScreenOn = snapshot.keepScreenOn,
            boardBrandWire = snapshot.boardBrand,
            boardLayoutId = snapshot.boardLayoutId,
            boardProductSizeId = snapshot.boardProductSizeId,
            boardTitle = BoardBrand.fromWireOrNull(snapshot.boardBrand)?.let { UiCodes.brandTitle(it) } ?: "",
            boardDetail = boardDetail(snapshot),
            bleAutoDisconnectSeconds = snapshot.bleAutoDisconnectSeconds,
            moonBoardLedMode = SettingsCodes.ledMode(snapshot.moonBoardLedMode),
            ledColors = ledRoles(snapshot),
            restTimerDurationSeconds = snapshot.restTimerDurationSeconds,
            restTimerAutoStart = snapshot.restTimerAutoStart,
            syncInterval = SettingsCodes.syncInterval(snapshot.syncInterval),
            brands = deletableBrands.map {
                BrandChoiceUi(
                    brandWire = it.wireValue,
                    title = UiCodes.brandTitle(it),
                    catalogueInstalled = it.wireValue in installedWires,
                    autoDownload = it.wireValue in autoDownload,
                )
            },
            deletingCatalogue = busy.deletingCatalogue,
            deletingUserData = busy.deletingUserData,
            lastResult = busy.lastResult,
        )
    }

    private fun ledRoles(snapshot: SettingsSnapshot): List<LedRoleUi> = listOf(
        role("start", snapshot.ledColorStart, LedHoldColors.CRUXCOACH_START),
        role("hand", snapshot.ledColorHand, LedHoldColors.CRUXCOACH_HAND),
        role("finish", snapshot.ledColorFinish, LedHoldColors.CRUXCOACH_FINISH),
        role("foot", snapshot.ledColorFoot, LedHoldColors.CRUXCOACH_FOOT),
    )

    private fun role(code: String, stored: Int?, fallback: Int): LedRoleUi {
        val byte = stored ?: fallback
        return LedRoleUi(code, byte, Rgb332Palette.toArgb(byte), Rgb332Palette.nameKey(byte), stored == null)
    }

    /** Empty while the brand's catalogue is absent: the layout names come from it. */
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
        /** The interactive boards, in declaration order — Android's two delete dialogs. */
        private val deletableBrands: List<BoardBrand> get() = BoardBrand.entries.filter { it.isInteractive }

        const val MAX_AUTO_DISCONNECT_SECONDS = 3600
        const val RESULT_CATALOGUE = "catalogueDeleted"
        const val RESULT_USER_DATA = "userDataDeleted"
        const val RESULT_FAILED = "deleteFailed"
    }
}

/** Lowercase codes for the settings enums Swift branches on. */
internal object SettingsCodes {
    fun darkMode(value: DarkModeSetting): String = when (value) {
        DarkModeSetting.SYSTEM -> "system"
        DarkModeSetting.LIGHT -> "light"
        DarkModeSetting.DARK -> "dark"
    }

    fun darkMode(code: String): DarkModeSetting? = when (code) {
        "system" -> DarkModeSetting.SYSTEM
        "light" -> DarkModeSetting.LIGHT
        "dark" -> DarkModeSetting.DARK
        else -> null
    }

    fun gradeScale(value: GradeScale): String = if (value == GradeScale.V_SCALE) "vScale" else "french"

    fun gradeScale(code: String): GradeScale? = when (code) {
        "french" -> GradeScale.FRENCH
        "vScale" -> GradeScale.V_SCALE
        else -> null
    }

    fun ledMode(value: MoonBoardLedMode): String = when (value) {
        MoonBoardLedMode.BELOW -> "below"
        MoonBoardLedMode.ABOVE -> "above"
        MoonBoardLedMode.BOTH -> "both"
    }

    fun ledMode(code: String): MoonBoardLedMode? = when (code) {
        "below" -> MoonBoardLedMode.BELOW
        "above" -> MoonBoardLedMode.ABOVE
        "both" -> MoonBoardLedMode.BOTH
        else -> null
    }

    fun syncInterval(value: SyncInterval): String = when (value) {
        SyncInterval.DAILY -> "daily"
        SyncInterval.WEEKLY -> "weekly"
        SyncInterval.MANUAL -> "manual"
    }

    fun syncInterval(code: String): SyncInterval? = when (code) {
        "daily" -> SyncInterval.DAILY
        "weekly" -> SyncInterval.WEEKLY
        "manual" -> SyncInterval.MANUAL
        else -> null
    }

    fun ledRole(code: String): SettingsStore.LedRole? = when (code) {
        "start" -> SettingsStore.LedRole.START
        "hand" -> SettingsStore.LedRole.HAND
        "finish" -> SettingsStore.LedRole.FINISH
        "foot" -> SettingsStore.LedRole.FOOT
        else -> null
    }
}
