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

    /** FEAT-027 — MoonBoard support (0.2.0). The release headline; the
     *  first thing an upgrading user should see, so it leads the 0.2.0
     *  (versionCode 6) batch. 0.1.5 never shipped standalone, so its map +
     *  picker entries also carry versionCode 6 and follow this one. */
    val MOONBOARD_SUPPORT = WhatsNewItem(id = "moonboard-support", sinceVersionCode = 6)

    /** FEAT-015 — Board Locations Map (0.2.0). Headline feature; users
     *  upgrading from 0.1.4 have no other entry point to discover the
     *  new map icon in the BoardBrowser search header. */
    val BOARD_LOCATIONS_MAP = WhatsNewItem(id = "board-locations-map", sinceVersionCode = 6)

    /** FEAT-007 Phase 1 — Find-your-gym board picker (0.2.0). Lives one
     *  tap deeper in *Settings → Board-Größe → Ändern* so it needs an
     *  explicit discovery surface. */
    val GYM_BOARD_PICKER = WhatsNewItem(id = "gym-board-picker", sinceVersionCode = 6)

    val registry: List<WhatsNewItem> = listOf(
        NOSTR_BACKUP,
        AURORA_JSON_IMPORT,
        MOONBOARD_SUPPORT,
        BOARD_LOCATIONS_MAP,
        GYM_BOARD_PICKER,
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
    vm: WhatsNewViewModel = hiltViewModel(),
) {
    val pending by vm.pending.collectAsState()
    val current = pending.firstOrNull() ?: return

    when (current.id) {
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
        else -> {
            // Unknown id (shouldn't happen unless registry/dispatch
            // diverge). Drop silently so the queue can advance.
            vm.dismissCurrent()
        }
    }
}
