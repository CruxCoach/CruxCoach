package com.cruxcoach.android.ui.whatsnew

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.data.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.cruxcoach.android.util.safeLaunch
import javax.inject.Inject

/**
 * One entry in the "what's new on upgrade" registry. The host shows each
 * entry exactly once, gated by [sinceVersionCode] vs.
 * [UserPreferences.lastSeenAppVersionCode].
 *
 * Adding an announcement for a future release:
 *   1) Add a [WhatsNewItem] with the release's BuildConfig.VERSION_CODE.
 *   2) Register it in [WhatsNewItems.registry] (keep ascending order).
 *   3) Add a renderer branch in [WhatsNewHost]'s `when`.
 */
data class WhatsNewItem(
    val id: String,
    val sinceVersionCode: Int,
)

object WhatsNewItems {
    /** FEAT-002 — encrypted Nostr/Blossom backups, default off. */
    val NOSTR_BACKUP = WhatsNewItem(id = "nostr-backup", sinceVersionCode = 4)

    /** FEAT-005 — Aurora JSON import (0.1.4). Discovery surface for
     *  users upgrading from 0.1.3 who already have the Aurora email
     *  export sitting in their Downloads. */
    val AURORA_JSON_IMPORT = WhatsNewItem(id = "aurora-json-import", sinceVersionCode = 5)

    /** Consolidated board-support announcement (MoonBoard + the Aurora family;
     *  the board-locations map + find-your-gym picker folded in as a one-line
     *  hint — see the whatsnew_moonboard_* strings).
     *
     *  Superseded by [RELEASE_021]: the 0.2.0 recap now rides along as that
     *  dialog's hint line instead of being a popup of its own. Declaration +
     *  dialog kept for reference, no longer registered. */
    val MOONBOARD_SUPPORT = WhatsNewItem(id = "moonboard-support", sinceVersionCode = 7)

    /** 0.2.1 highlights (zone search, Kilter circuits import, share links,
     *  cross-board lists), with the 0.2.0 board-support recap folded in as a
     *  hint line.
     *
     *  This single vc7 entry serves double duty on purpose. A 0.2.0 bug made
     *  the upgrade popup dismissible by a stray scrim tap / back press, which
     *  marked it seen (fixed in 0.2.1), so an unknown share of 0.1.4→0.2.0
     *  upgraders never actually read the board-support announcement. Everyone
     *  arriving on 0.2.1 with lastSeen ≤ 6 gets exactly ONE popup — 0.2.1
     *  headlines up front, the missed 0.2.0 boards as the hint — whether they
     *  came from 0.2.0 (lastSeen=6) or straight from 0.1.4 (lastSeen=5). A
     *  separate recap item would double-fire for the direct-upgrade cohort. */
    val RELEASE_021 = WhatsNewItem(id = "release-0.2.1", sinceVersionCode = 7)

    /** 0.2.2 highlights: playable lists, unified board delivery and optional OTA. */
    val RELEASE_022 = WhatsNewItem(id = "release-0.2.2", sinceVersionCode = 8)

    /** 0.2.3 — competitions (FEAT-058). The feature is reached from the logo
     *  menu in the board browser, which is a place nobody would look without
     *  being told, so it needs a discovery surface of its own. */
    val RELEASE_023 = WhatsNewItem(id = "release-0.2.3", sinceVersionCode = 13)

    /** FEAT-015 — Board Locations Map (0.2.0). Headline feature; users
     *  upgrading from 0.1.4 have no other entry point to discover the
     *  new map icon in the BoardBrowser search header. */
    val BOARD_LOCATIONS_MAP = WhatsNewItem(id = "board-locations-map", sinceVersionCode = 6)

    /** FEAT-007 Phase 1 — Find-your-gym board picker (0.2.0). Lives one
     *  tap deeper in *Settings → Board-Größe → Ändern* so it needs an
     *  explicit discovery surface. */
    val GYM_BOARD_PICKER = WhatsNewItem(id = "gym-board-picker", sinceVersionCode = 6)

    /** FEAT-031 — Aurora-family boards (0.2.0): Tension, Grasshopper, Decoy,
     *  So iLL and Touchstone become selectable, browsable + LED-send alongside
     *  Kilter. Discovery surface so upgrading users find them in the Settings
     *  board picker. Same 0.2.0 (versionCode 6) batch. */
    val AURORA_BOARDS = WhatsNewItem(id = "aurora-boards", sinceVersionCode = 6)

    // The 0.2.0 board batch shows a SINGLE popup on upgrade. A 0.1.4 -> 0.2.0
    // upgrade previously fired all four vc6 dialogs back-to-back (MoonBoard,
    // Aurora boards, board-locations map, find-your-gym picker) — far too many
    // popups. RELEASE_021 renders a single CONSOLIDATED dialog (0.2.1
    // headlines up front, the 0.2.0 board recap as a one-line hint), so the
    // vc6 items and MOONBOARD_SUPPORT are intentionally NOT registered here.
    // Their declarations + dialogs are kept (still referenced by WhatsNewHost)
    // for reference / future reuse. (RELEASE_021 sits at vc7 and deliberately
    // re-surfaces the 0.2.0 boards for users who missed that popup because of
    // the dismiss bug fixed in 0.2.1 — see its KDoc.)
    val registry: List<WhatsNewItem> = listOf(
        NOSTR_BACKUP,
        AURORA_JSON_IMPORT,
        RELEASE_021,
        RELEASE_022,
        RELEASE_023,
    )
}

/**
 * Computes which [WhatsNewItem]s to show on this app start and exposes
 * them as a queue. Logic:
 *
 *   - lastSeen == null && !onboardingCompleted -> fresh install, queue
 *     stays empty (onboarding handles the feature intro itself).
 *     [com.cruxcoach.android.ui.onboarding.OnboardingViewModel.completeOnboarding]
 *     writes lastSeen = current at the end so we never re-fire.
 *   - lastSeen == null && onboardingCompleted -> upgrade from a version
 *     before this mechanism existed: treat lastSeen as 0, queue all
 *     entries with sinceVersionCode <= current.
 *   - lastSeen < current -> normal upgrade: queue entries with
 *     sinceVersionCode in (lastSeen, current].
 *   - lastSeen >= current -> nothing to show.
 *
 * After the last item is dismissed, [setLastSeenAppVersionCode] persists
 * the current version so dialogs never re-fire.
 */
@HiltViewModel
class WhatsNewViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _pending = MutableStateFlow<List<WhatsNewItem>>(emptyList())
    val pending: StateFlow<List<WhatsNewItem>> = _pending.asStateFlow()

    init {
        viewModelScope.safeLaunch("WhatsNewViewModel") {
            val current = BuildConfig.VERSION_CODE
            val lastSeen = userPreferences.lastSeenAppVersionCode.first()
            val onboardingDone = userPreferences.isOnboardingCompleted()

            if (lastSeen == null && !onboardingDone) return@safeLaunch

            val effectiveLastSeen = lastSeen ?: 0
            val toShow = WhatsNewItems.registry
                .filter { it.sinceVersionCode in (effectiveLastSeen + 1)..current }
                .sortedBy { it.sinceVersionCode }

            if (toShow.isEmpty()) {
                // Monotonic: never lower the recorded watermark, otherwise a
                // QA downgrade or a hot-fix release with a lower versionCode
                // would re-trigger announcements the user already dismissed.
                if (lastSeen == null || lastSeen < current) {
                    userPreferences.setLastSeenAppVersionCode(current)
                }
            } else {
                _pending.value = toShow
            }
        }
    }

    fun dismissCurrent() {
        viewModelScope.launch {
            val remaining = _pending.value.drop(1)
            _pending.value = remaining
            if (remaining.isEmpty()) {
                val current = BuildConfig.VERSION_CODE
                val existing = userPreferences.lastSeenAppVersionCode.first()
                if (existing == null || existing < current) {
                    userPreferences.setLastSeenAppVersionCode(current)
                }
            }
        }
    }
}

/**
 * Composable host. Place once in the navigation tree (after the onboarding
 * gate is resolved) — it overlays whatever is on screen with the next
 * pending dialog. Drops to a no-op when the queue is empty, so it's
 * always cheap to keep mounted.
 */
@Composable
fun WhatsNewHost(
    onNavigateToKeyManagement: () -> Unit = {},
    onNavigateToAuroraMigration: () -> Unit = {},
    onNavigateToBoardMap: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToCompetitions: () -> Unit = {},
    vm: WhatsNewViewModel = hiltViewModel(),
) {
    val pending by vm.pending.collectAsState()
    val current = pending.firstOrNull() ?: return

    when (current.id) {
        WhatsNewItems.RELEASE_021.id ->
            Release021WhatsNewDialog(
                onDismiss = { vm.dismissCurrent() },
                onNavigateToSettings = onNavigateToSettings,
            )
        WhatsNewItems.RELEASE_022.id ->
            Release022WhatsNewDialog(
                onDismiss = { vm.dismissCurrent() },
                onNavigateToSettings = onNavigateToSettings,
            )
        WhatsNewItems.RELEASE_023.id ->
            Release023WhatsNewDialog(
                onDismiss = { vm.dismissCurrent() },
                onNavigateToCompetitions = onNavigateToCompetitions,
            )
        WhatsNewItems.MOONBOARD_SUPPORT.id ->
            MoonBoardWhatsNewDialog(
                onDismiss = { vm.dismissCurrent() },
                onNavigateToSettings = onNavigateToSettings,
            )
        WhatsNewItems.NOSTR_BACKUP.id ->
            NostrBackupWhatsNewDialog(
                onDismiss = { vm.dismissCurrent() },
                onNavigateToKeyManagement = onNavigateToKeyManagement,
            )
        WhatsNewItems.AURORA_JSON_IMPORT.id ->
            AuroraJsonImportWhatsNewDialog(
                onDismiss = { vm.dismissCurrent() },
                onNavigateToAuroraMigration = onNavigateToAuroraMigration,
            )
        WhatsNewItems.BOARD_LOCATIONS_MAP.id ->
            BoardLocationsMapWhatsNewDialog(
                onDismiss = { vm.dismissCurrent() },
                onNavigateToBoardMap = onNavigateToBoardMap,
            )
        WhatsNewItems.GYM_BOARD_PICKER.id ->
            GymBoardPickerWhatsNewDialog(
                onDismiss = { vm.dismissCurrent() },
                onNavigateToSettings = onNavigateToSettings,
            )
        WhatsNewItems.AURORA_BOARDS.id ->
            AuroraBoardsWhatsNewDialog(
                onDismiss = { vm.dismissCurrent() },
                onNavigateToSettings = onNavigateToSettings,
            )
        else -> {
            // Unknown id (shouldn't happen unless registry/dispatch
            // diverge). Drop silently so the queue can advance.
            vm.dismissCurrent()
        }
    }
}
