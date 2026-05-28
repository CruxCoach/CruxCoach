package com.cruxcoach.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cruxcoach.android.notification.AnnouncementTagParser
import com.cruxcoach.android.nostr.SignerMode
import com.cruxcoach.domain.board.HoldRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "cruxcoach_prefs")

/**
 * Per-key DataStore keys — preferences that belong to a specific Nostr identity.
 * These are stored in a separate DataStore file per pubkey prefix.
 */
object KeyScopedKeys {
    val NOSTR_SYNC_CURSOR = longPreferencesKey("nostr_sync_cursor")
    val NOSTR_SYNC_RECOVERY_VERSION = intPreferencesKey("nostr_sync_recovery_version")
    val KEY_BACKED_UP = booleanPreferencesKey("key_backed_up")
    val AUTO_PUBLISH_ASCENTS = booleanPreferencesKey("auto_publish_ascents")
    val LEADERBOARD_DISPLAY_NAME = stringPreferencesKey("leaderboard_display_name")
    val KILTER_SYNC_ENABLED = booleanPreferencesKey("kilter_sync_enabled")
    val KILTER_PUSH_ENABLED = booleanPreferencesKey("kilter_push_enabled")
    val KILTER_LAST_SYNC = stringPreferencesKey("kilter_last_sync")
    // Climb-publishing flags (separate from ascent push so users can opt
    // in/out independently — and so non-Kilter-users don't get pinged).
    val KILTER_CLIMB_PUBLISH_ENABLED = booleanPreferencesKey("kilter_climb_publish_enabled")
    // Cursor for the live community-climb Nostr subscription. Holds the
    // largest event.created_at we've persisted; subsequent subscribes use
    // it as the `since` filter so we don't re-process the historical tail.
    val COMMUNITY_CLIMB_SINCE = longPreferencesKey("community_climb_since")
    // Last seen Blossom-manifest `created_at` (epoch seconds). Written by
    // BoardSyncManager on every successful manifest fetch. Used by
    // CommunityClimbSubscriber to seed its cursor on first run so a fresh
    // install doesn't pull the entire historical Nostr tail (the cron has
    // already merged everything older into the blob).
    val BLOSSOM_MANIFEST_CREATED_AT = longPreferencesKey("blossom_manifest_created_at")
    // True once the user has seen + dismissed the "set up your profile?"
    // dialog that fires on first publish without a Kind-0 profile. Per-
    // identity (key-scoped) so a re-imported nsec gets the prompt again.
    val PROFILE_HINT_DISMISSED = booleanPreferencesKey("profile_hint_dismissed")
    val SIGNER_MODE = stringPreferencesKey("signer_mode")
    val AMBER_PUBKEY = stringPreferencesKey("amber_pubkey")
    val AMBER_PACKAGE_NAME = stringPreferencesKey("amber_package_name")
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

    // Highest BuildConfig.VERSION_CODE this identity has already
    // acknowledged "what's new" dialogs for. Per-identity (not global)
    // because most announced features are per-identity opt-ins (e.g.
    // FEAT-002 backup): switching identity should re-prompt so the user
    // can decide independently for the new identity. Null = either fresh
    // install / fresh identity, or upgrade from a version that predates
    // this mechanism — the WhatsNewViewModel distinguishes via
    // ONBOARDING_COMPLETED.
    val LAST_SEEN_APP_VERSION_CODE = intPreferencesKey("last_seen_app_version_code")
}

enum class GradeScale(val label: String) {
    V_SCALE("V-Scale"),
    FRENCH("Fontainebleau")
}

enum class SyncInterval(@androidx.annotation.StringRes val labelRes: Int) {
    // labelRes points to a localized string resource so both the German
    // and English (system-fallback) locales render correctly. Pre-fix
    // these were hardcoded German ("Taeglich"/"Woechentlich"/"Manuell")
    // shown verbatim to every user regardless of locale.
    DAILY(com.cruxcoach.android.R.string.sync_interval_daily),
    WEEKLY(com.cruxcoach.android.R.string.sync_interval_weekly),
    MANUAL(com.cruxcoach.android.R.string.sync_interval_manual),
}

enum class DarkModeSetting(val label: String) {
    SYSTEM("System"),
    LIGHT("Hell"),
    DARK("Dunkel")
}

/**
 * Configurable LED colors for each hold role on the board.
 * Default values use CruxCoach branded colors.
 */
data class LedHoldColors(
    val start: Int = CRUXCOACH_START,
    val hand: Int = CRUXCOACH_HAND,
    val finish: Int = CRUXCOACH_FINISH,
    val foot: Int = CRUXCOACH_FOOT
) {
    companion object {
        // CruxCoach standard preset (default): start=magenta,
        // hand=blue, top/finish=green, foot=red. Byte values must
        // exist in RGB332_PALETTE so the settings row shows a named
        // color instead of "Benutzerdefiniert" / "Custom".
        const val CRUXCOACH_START: Int = 0xE3    // Magenta (FF00FF)
        const val CRUXCOACH_HAND: Int = 0x03     // Blue (0000FF)
        const val CRUXCOACH_FINISH: Int = 0x1C   // Green (00FF00)
        const val CRUXCOACH_FOOT: Int = 0xE0     // Red (FF0000)

        // Official Kilter Board preset (from placement_roles.led_color DB)
        const val KILTER_START: Int = 0x1C   // Green (00FF00)
        const val KILTER_HAND: Int = 0x1F    // Cyan (00FFFF)
        const val KILTER_FINISH: Int = 0xE3  // Magenta (FF00FF)
        const val KILTER_FOOT: Int = 0xF4    // Orange (FFA500)

        fun kilterStandard() = LedHoldColors(
            start = KILTER_START,
            hand = KILTER_HAND,
            finish = KILTER_FINISH,
            foot = KILTER_FOOT
        )
    }
    fun toRoleColorMap(): Map<Int, Int> = mapOf(
        HoldRole.START to start,
        HoldRole.HAND to hand,
        HoldRole.FINISH to finish,
        HoldRole.FOOT to foot,
        HoldRole.ROUTE_START to start,
        HoldRole.ROUTE_HAND to hand,
        HoldRole.ROUTE_FINISH to finish,
        HoldRole.ROUTE_FOOT to foot
    )

    fun colorForRole(roleId: Int): Int = when (HoldRole.normalize(roleId)) {
        HoldRole.START -> start
        HoldRole.HAND -> hand
        HoldRole.FINISH -> finish
        HoldRole.FOOT -> foot
        else -> 0xFF
    }
}

/** Shared preference keys — device-level settings, same across all Nostr identities. */
object PreferenceKeys {
    val BOARD_PRODUCT_SIZE_ID = intPreferencesKey("board_product_size_id")
    val BOARD_LAYOUT_ID = intPreferencesKey("board_layout_id")
    /** Active board brand — "kilter" | "moonboard" (FEAT-027). */
    val BOARD_BRAND = stringPreferencesKey("board_brand")
    val SYNC_INTERVAL = stringPreferencesKey("sync_interval")
    val LAST_SYNC_TIMESTAMP = stringPreferencesKey("last_sync_timestamp")
    val GRADE_SCALE = stringPreferencesKey("grade_scale")
    val LED_COLOR_START = intPreferencesKey("led_color_start")
    val LED_COLOR_HAND = intPreferencesKey("led_color_hand")
    val LED_COLOR_FINISH = intPreferencesKey("led_color_finish")
    val LED_COLOR_FOOT = intPreferencesKey("led_color_foot")
    val BLE_AUTO_DISCONNECT_MINUTES = intPreferencesKey("ble_auto_disconnect_minutes")
    // Seconds-precision successor to BLE_AUTO_DISCONNECT_MINUTES. Read
    // by bleAutoDisconnectSeconds, which transparently migrates the
    // older minutes key on first read if the new key is absent.
    val BLE_AUTO_DISCONNECT_SECONDS = intPreferencesKey("ble_auto_disconnect_seconds")
    val BOARD_ANGLE = intPreferencesKey("board_angle")
    val BOARD_MIN_GRADE = intPreferencesKey("board_min_grade")
    val BOARD_MAX_GRADE = intPreferencesKey("board_max_grade")
    val BOARD_MIN_ASCENSIONISTS = intPreferencesKey("board_min_ascensionists")
    val BOARD_SORT_FIELD = stringPreferencesKey("board_sort_field")
    val BOARD_SORT_DIRECTION = stringPreferencesKey("board_sort_direction")
    val BOARD_STATUS_FILTER = stringPreferencesKey("board_status_filter")
    val BOARD_CLIMB_TYPE = stringPreferencesKey("board_climb_type")
    val BOARD_BENCHMARK_ONLY = booleanPreferencesKey("board_benchmark_only")
    val BOARD_ORIGIN_FILTER = stringPreferencesKey("board_origin_filter")
    val BOARD_MY_CLIMBS_ONLY = booleanPreferencesKey("board_my_climbs_only")
    val ROUTE_FRAME_SPEED = floatPreferencesKey("route_frame_speed_f")
    // Auto-Note: when true, publishing a Kind-30078 climb also sends a
    // public Kind-1 note linking to it. Default false; the editor exposes
    // a per-publish checkbox that's pre-populated from this flag.
    val AUTO_NOTE_ENABLED = booleanPreferencesKey("auto_note_enabled")
    val ROUTE_USE_SETTER_SPEED = booleanPreferencesKey("route_use_setter_speed")
    val ROUTE_COUNTDOWN = booleanPreferencesKey("route_countdown")
    val ROUTE_COUNTDOWN_SECONDS = intPreferencesKey("route_countdown_seconds")
    val ROUTE_AUTO_LOOP = booleanPreferencesKey("route_auto_loop")
    val REST_TIMER_DURATION_SECONDS = intPreferencesKey("rest_timer_duration_seconds")
    val REST_TIMER_AUTO_START = booleanPreferencesKey("rest_timer_auto_start")
    val NEARBY_CLIMB_SHARING = booleanPreferencesKey("nearby_climb_sharing")
    val ALLOW_REMOTE_DISCONNECT = booleanPreferencesKey("allow_remote_disconnect")
    val EASTER_ANIMATIONS_UNLOCKED = booleanPreferencesKey("easter_animations_unlocked")
    val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    val DARK_MODE = stringPreferencesKey("dark_mode")
    val SESSION_DISPLAY_NAME = stringPreferencesKey("session_display_name")
    val LAST_CLIMB_UUID = stringPreferencesKey("last_climb_uuid")
    val LAST_CLIMB_ANGLE = intPreferencesKey("last_climb_angle")
    val LAST_CLIMB_TIMESTAMP = longPreferencesKey("last_climb_timestamp")
    val CRASH_REPORT_OPT_IN = booleanPreferencesKey("crash_report_opt_in")
    val LAST_ANNOUNCEMENT_CHECK = stringPreferencesKey("last_announcement_check")
    val ANNOUNCEMENTS_ENABLED = booleanPreferencesKey("announcements_enabled")
    val ANNOUNCEMENT_CAT_RELEASE = booleanPreferencesKey("announcement_cat_release")
    val ANNOUNCEMENT_CAT_ISSUE = booleanPreferencesKey("announcement_cat_issue")
    val ANNOUNCEMENT_CAT_TIP = booleanPreferencesKey("announcement_cat_tip")
    val ANNOUNCEMENT_CAT_GENERAL = booleanPreferencesKey("announcement_cat_general")
    val APP_LAUNCH_COUNT = intPreferencesKey("app_launch_count")

    // FEAT-001: NIP-65 relay discovery
    val NIP65_DISCOVERY_ENABLED = booleanPreferencesKey("nip65_discovery_enabled")
    val NIP65_RESOLVED_RELAYS = stringPreferencesKey("nip65_resolved_relays")

    // FEAT-006: Map filter chips
    // Replaces the older PUBLIC_ONLY filter — StoreRocket's "access"
    // metadata was unreliable (commercial gyms mis-flagged as private).
    // Layout-based filter is sourced from hangtime/PowerSync and trustworthy.
    val MAP_FILTER_SHOW_ORIGINAL = booleanPreferencesKey("map_filter_show_original")
    val MAP_FILTER_SHOW_HOMEWALLS = booleanPreferencesKey("map_filter_show_homewalls")
    val MAP_FILTER_MATCHES_MY_BOARD = booleanPreferencesKey("map_filter_matches_my_board")

    // Multi-select set filters — empty value (or unset key) means "no
    // filter on this dimension". Stored as comma-separated strings so
    // we don't need a Proto DataStore migration. Sets are typically
    // small (a few countries / size labels) so CSV is fine.
    val MAP_FILTER_COUNTRIES = stringPreferencesKey("map_filter_countries")
    val MAP_FILTER_ACCESS_TYPES = stringPreferencesKey("map_filter_access_types")
    val MAP_FILTER_ADJUSTABILITIES = stringPreferencesKey("map_filter_adjustabilities")
    val MAP_FILTER_SIZE_IDS = stringPreferencesKey("map_filter_size_ids")
    // Board family filter (BoardBrand.wireValue CSV). Empty = all brands.
    val MAP_FILTER_BRANDS = stringPreferencesKey("map_filter_brands")
}

/**
 * Snapshot of all board browser filter preferences from a single DataStore read.
 * Avoids 10 separate Flow subscriptions (~90ms saved on cold start).
 */
data class BoardFilterSnapshot(
    val angle: Int,
    val layoutId: Int,
    val minGrade: Int,
    val maxGrade: Int,
    val minAscensionists: Int,
    val gradeScale: GradeScale,
    val sortField: String,
    val sortDirection: String,
    val statusFilter: String,
    val climbType: String,
    val benchmarkOnly: Boolean,
    val originFilter: String,
    val myClimbsOnly: Boolean,
    /** Active board brand — "kilter" | "moonboard" (FEAT-027). */
    val boardBrand: String = "kilter",
)

class UserPreferences(
    private val dataStore: DataStore<Preferences>,
    private val keyScoped: DataStore<Preferences>
) {

    /** Single-read snapshot of all board filter prefs (counterpart to [setBoardFilters]). */
    suspend fun getBoardFilterSnapshot(): BoardFilterSnapshot {
        val prefs = dataStore.data.first()
        return BoardFilterSnapshot(
            angle = prefs[PreferenceKeys.BOARD_ANGLE] ?: 40,
            layoutId = prefs[PreferenceKeys.BOARD_LAYOUT_ID] ?: BoardConstants.KILTER_ORIGINAL_LAYOUT,
            boardBrand = prefs[PreferenceKeys.BOARD_BRAND] ?: "kilter",
            minGrade = prefs[PreferenceKeys.BOARD_MIN_GRADE] ?: 0,
            maxGrade = prefs[PreferenceKeys.BOARD_MAX_GRADE] ?: 14,
            minAscensionists = prefs[PreferenceKeys.BOARD_MIN_ASCENSIONISTS] ?: 0,
            gradeScale = try { GradeScale.valueOf(prefs[PreferenceKeys.GRADE_SCALE] ?: GradeScale.FRENCH.name) } catch (_: IllegalArgumentException) { GradeScale.FRENCH },
            sortField = prefs[PreferenceKeys.BOARD_SORT_FIELD] ?: "ASCENSIONISTS",
            sortDirection = prefs[PreferenceKeys.BOARD_SORT_DIRECTION] ?: "DESC",
            statusFilter = prefs[PreferenceKeys.BOARD_STATUS_FILTER] ?: "ALL",
            climbType = prefs[PreferenceKeys.BOARD_CLIMB_TYPE] ?: "BOULDER",
            benchmarkOnly = prefs[PreferenceKeys.BOARD_BENCHMARK_ONLY] ?: false,
            originFilter = prefs[PreferenceKeys.BOARD_ORIGIN_FILTER] ?: "ALL",
            myClimbsOnly = prefs[PreferenceKeys.BOARD_MY_CLIMBS_ONLY] ?: false,
        )
    }

    val boardProductSizeId: Flow<Int> = dataStore.data.map { prefs ->
        prefs[PreferenceKeys.BOARD_PRODUCT_SIZE_ID] ?: BoardConstants.KILTER_DEFAULT_SIZE
    }

    /** True if the user has never explicitly chosen a board model. */
    val isBoardProductSizeDefault: Flow<Boolean> = dataStore.data.map { prefs ->
        !prefs.contains(PreferenceKeys.BOARD_PRODUCT_SIZE_ID)
    }

    val boardLayoutId: Flow<Int> = dataStore.data.map { prefs ->
        prefs[PreferenceKeys.BOARD_LAYOUT_ID] ?: BoardConstants.KILTER_ORIGINAL_LAYOUT
    }

    /**
     * Active board brand — "kilter" | "moonboard" (FEAT-027). Defaults to
     * "kilter": pre-0.2.0 installs have no MoonBoard concept and must keep
     * behaving exactly as before.
     */
    val boardBrand: Flow<String> = dataStore.data.map { prefs ->
        prefs[PreferenceKeys.BOARD_BRAND] ?: "kilter"
    }

    val syncInterval: Flow<SyncInterval> = dataStore.data.map { prefs ->
        val value = prefs[PreferenceKeys.SYNC_INTERVAL] ?: SyncInterval.MANUAL.name
        try { SyncInterval.valueOf(value) } catch (_: IllegalArgumentException) { SyncInterval.MANUAL }
    }

    val lastSyncTimestamp: Flow<String?> = dataStore.data.map { prefs ->
        prefs[PreferenceKeys.LAST_SYNC_TIMESTAMP]
    }

    val gradeScale: Flow<GradeScale> = dataStore.data.map { prefs ->
        val value = prefs[PreferenceKeys.GRADE_SCALE] ?: GradeScale.FRENCH.name
        try { GradeScale.valueOf(value) } catch (_: IllegalArgumentException) { GradeScale.FRENCH }
    }

    suspend fun setBoardProductSizeId(id: Int) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.BOARD_PRODUCT_SIZE_ID] = id
        }
    }

    suspend fun setBoardLayoutId(id: Int) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.BOARD_LAYOUT_ID] = id
        }
    }

    /** Mark the active board brand. The Kilter branch of the board picker
     *  uses this directly; the MoonBoard branch uses [setMoonBoardSelection]. */
    suspend fun setBoardBrand(brand: String) {
        dataStore.edit { prefs -> prefs[PreferenceKeys.BOARD_BRAND] = brand }
    }

    /**
     * Atomically select a MoonBoard variant as the active board: writes the
     * variant's [layoutId], marks the brand "moonboard", and pins the browse
     * angle to 40° — valid for every v0.2.0 MoonBoard variant — so the
     * browser shows climbs immediately.
     */
    suspend fun setMoonBoardSelection(layoutId: Int) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.BOARD_LAYOUT_ID] = layoutId
            prefs[PreferenceKeys.BOARD_BRAND] = "moonboard"
            prefs[PreferenceKeys.BOARD_ANGLE] = 40
        }
    }

    // FEAT-006: Map filter chips. Default shows commercial gyms (Original
    // layout) only — the "where can I go climb?" use case. Private
    // homewall installations (~5% of dataset, layout_id=8) are off by
    // default but can be opted in. Both flags can be toggled
    // independently — turning both off intentionally yields an empty
    // map (the user gets a visible "0 of 1080" footer to recover).
    val mapFilterShowOriginal: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PreferenceKeys.MAP_FILTER_SHOW_ORIGINAL] ?: true
    }

    val mapFilterShowHomewalls: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PreferenceKeys.MAP_FILTER_SHOW_HOMEWALLS] ?: false
    }

    val mapFilterMatchesMyBoard: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PreferenceKeys.MAP_FILTER_MATCHES_MY_BOARD] ?: false
    }

    suspend fun setMapFilterShowOriginal(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.MAP_FILTER_SHOW_ORIGINAL] = enabled
        }
    }

    suspend fun setMapFilterShowHomewalls(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.MAP_FILTER_SHOW_HOMEWALLS] = enabled
        }
    }

    private fun parseCsvSet(value: String?): Set<String> =
        value?.split(',')?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }?.toSet() ?: emptySet()

    val mapFilterCountries: Flow<Set<String>> = dataStore.data.map { prefs ->
        parseCsvSet(prefs[PreferenceKeys.MAP_FILTER_COUNTRIES])
    }

    val mapFilterAccessTypes: Flow<Set<String>> = dataStore.data.map { prefs ->
        parseCsvSet(prefs[PreferenceKeys.MAP_FILTER_ACCESS_TYPES])
    }

    val mapFilterAdjustabilities: Flow<Set<String>> = dataStore.data.map { prefs ->
        parseCsvSet(prefs[PreferenceKeys.MAP_FILTER_ADJUSTABILITIES])
    }

    /** Stored as CSV of integers; parsed to Set<Int>. */
    val mapFilterSizeIds: Flow<Set<Int>> = dataStore.data.map { prefs ->
        parseCsvSet(prefs[PreferenceKeys.MAP_FILTER_SIZE_IDS])
            .mapNotNull { it.toIntOrNull() }
            .toSet()
    }

    /** Board-family filter as BoardBrand wire values (e.g. "kilter",
     *  "moonboard"). Empty = show all brands. */
    val mapFilterBrands: Flow<Set<String>> = dataStore.data.map { prefs ->
        parseCsvSet(prefs[PreferenceKeys.MAP_FILTER_BRANDS])
    }

    suspend fun setMapFilterCountries(values: Set<String>) {
        dataStore.edit { it[PreferenceKeys.MAP_FILTER_COUNTRIES] = values.joinToString(",") }
    }

    suspend fun setMapFilterAccessTypes(values: Set<String>) {
        dataStore.edit { it[PreferenceKeys.MAP_FILTER_ACCESS_TYPES] = values.joinToString(",") }
    }

    suspend fun setMapFilterAdjustabilities(values: Set<String>) {
        dataStore.edit { it[PreferenceKeys.MAP_FILTER_ADJUSTABILITIES] = values.joinToString(",") }
    }

    suspend fun setMapFilterSizeIds(values: Set<Int>) {
        dataStore.edit { it[PreferenceKeys.MAP_FILTER_SIZE_IDS] = values.joinToString(",") }
    }

    suspend fun setMapFilterBrands(values: Set<String>) {
        dataStore.edit { it[PreferenceKeys.MAP_FILTER_BRANDS] = values.joinToString(",") }
    }

    /** Reset every map-side filter to its empty/default state in one transaction. */
    suspend fun resetMapFilters() {
        dataStore.edit { prefs ->
            prefs.remove(PreferenceKeys.MAP_FILTER_SHOW_ORIGINAL)
            prefs.remove(PreferenceKeys.MAP_FILTER_SHOW_HOMEWALLS)
            prefs.remove(PreferenceKeys.MAP_FILTER_MATCHES_MY_BOARD)
            prefs.remove(PreferenceKeys.MAP_FILTER_COUNTRIES)
            prefs.remove(PreferenceKeys.MAP_FILTER_ACCESS_TYPES)
            prefs.remove(PreferenceKeys.MAP_FILTER_ADJUSTABILITIES)
            prefs.remove(PreferenceKeys.MAP_FILTER_SIZE_IDS)
            prefs.remove(PreferenceKeys.MAP_FILTER_BRANDS)
        }
    }

    suspend fun setMapFilterMatchesMyBoard(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.MAP_FILTER_MATCHES_MY_BOARD] = enabled
        }
    }

    suspend fun setSyncInterval(interval: SyncInterval) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.SYNC_INTERVAL] = interval.name
        }
    }

    suspend fun setLastSyncTimestamp(timestamp: String?) {
        dataStore.edit { prefs ->
            if (timestamp != null) {
                prefs[PreferenceKeys.LAST_SYNC_TIMESTAMP] = timestamp
            } else {
                prefs.remove(PreferenceKeys.LAST_SYNC_TIMESTAMP)
            }
        }
    }

    val kilterSyncEnabled: Flow<Boolean> = keyScoped.data.map { prefs ->
        prefs[KeyScopedKeys.KILTER_SYNC_ENABLED] ?: false
    }

    val kilterPushEnabled: Flow<Boolean> = keyScoped.data.map { prefs ->
        prefs[KeyScopedKeys.KILTER_PUSH_ENABLED] ?: false
    }

    val kilterLastSync: Flow<String?> = keyScoped.data.map { prefs ->
        prefs[KeyScopedKeys.KILTER_LAST_SYNC]
    }

    /**
     * Whether to push newly created CruxCoach climbs into the official
     * Kilter database. Default `true` — the design goal is that every
     * CruxCoach-set climb also lives on Kilter (via the user's account
     * if logged in, or via the bundled fallback if enabled).
     */
    val kilterClimbPublishEnabled: Flow<Boolean> = keyScoped.data.map { prefs ->
        prefs[KeyScopedKeys.KILTER_CLIMB_PUBLISH_ENABLED] ?: true
    }

    suspend fun setKilterSyncEnabled(enabled: Boolean) {
        keyScoped.edit { prefs -> prefs[KeyScopedKeys.KILTER_SYNC_ENABLED] = enabled }
    }

    suspend fun setKilterPushEnabled(enabled: Boolean) {
        keyScoped.edit { prefs -> prefs[KeyScopedKeys.KILTER_PUSH_ENABLED] = enabled }
    }

    suspend fun setKilterClimbPublishEnabled(enabled: Boolean) {
        keyScoped.edit { prefs -> prefs[KeyScopedKeys.KILTER_CLIMB_PUBLISH_ENABLED] = enabled }
    }

    /**
     * Cursor for the live community-climb Nostr subscription. Returns
     * `null` until the first event has landed, then the max
     * `event.created_at` (epoch seconds) we've successfully upserted.
     */
    val communityClimbSince: Flow<Long?> = keyScoped.data.map { prefs ->
        prefs[KeyScopedKeys.COMMUNITY_CLIMB_SINCE]
    }

    suspend fun setCommunityClimbSince(epochSeconds: Long) {
        keyScoped.edit { prefs -> prefs[KeyScopedKeys.COMMUNITY_CLIMB_SINCE] = epochSeconds }
    }

    /**
     * Last `created_at` of a successfully-fetched Blossom manifest. Written
     * by BoardSyncManager. Read once by CommunityClimbSubscriber to seed
     * the live-sub cursor on a fresh install — see start() in that class.
     */
    val blossomManifestCreatedAt: Flow<Long?> = keyScoped.data.map { prefs ->
        prefs[KeyScopedKeys.BLOSSOM_MANIFEST_CREATED_AT]
    }

    suspend fun setBlossomManifestCreatedAt(epochSeconds: Long) {
        keyScoped.edit { prefs -> prefs[KeyScopedKeys.BLOSSOM_MANIFEST_CREATED_AT] = epochSeconds }
    }

    /** Whether the first-publish "set up your profile?" hint has been
     *  shown + dismissed for the current identity. Default false (= will
     *  show on next publish without a profile). */
    val profileHintDismissed: Flow<Boolean> = keyScoped.data.map { prefs ->
        prefs[KeyScopedKeys.PROFILE_HINT_DISMISSED] ?: false
    }

    suspend fun setProfileHintDismissed(value: Boolean) {
        keyScoped.edit { prefs -> prefs[KeyScopedKeys.PROFILE_HINT_DISMISSED] = value }
    }

    suspend fun setKilterLastSync(timestamp: String?) {
        keyScoped.edit { prefs ->
            if (timestamp != null) prefs[KeyScopedKeys.KILTER_LAST_SYNC] = timestamp
            else prefs.remove(KeyScopedKeys.KILTER_LAST_SYNC)
        }
    }

    suspend fun setGradeScale(scale: GradeScale) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.GRADE_SCALE] = scale.name
        }
    }

    val ledHoldColors: Flow<LedHoldColors> = combine(
        dataStore.data.map { it[PreferenceKeys.LED_COLOR_START] },
        dataStore.data.map { it[PreferenceKeys.LED_COLOR_HAND] },
        dataStore.data.map { it[PreferenceKeys.LED_COLOR_FINISH] },
        dataStore.data.map { it[PreferenceKeys.LED_COLOR_FOOT] }
    ) { start, hand, finish, foot ->
        LedHoldColors(
            start = start ?: LedHoldColors.CRUXCOACH_START,
            hand = hand ?: LedHoldColors.CRUXCOACH_HAND,
            finish = finish ?: LedHoldColors.CRUXCOACH_FINISH,
            foot = foot ?: LedHoldColors.CRUXCOACH_FOOT
        )
    }

    suspend fun setLedColor(roleId: Int, colorByte: Int) {
        dataStore.edit { prefs ->
            when (roleId) {
                HoldRole.START -> prefs[PreferenceKeys.LED_COLOR_START] = colorByte
                HoldRole.HAND -> prefs[PreferenceKeys.LED_COLOR_HAND] = colorByte
                HoldRole.FINISH -> prefs[PreferenceKeys.LED_COLOR_FINISH] = colorByte
                HoldRole.FOOT -> prefs[PreferenceKeys.LED_COLOR_FOOT] = colorByte
            }
        }
    }

    /**
     * BLE idle-disconnect timeout in seconds. New storage key since
     * 0.1.3; old installs had whole-minute granularity under
     * [PreferenceKeys.BLE_AUTO_DISCONNECT_MINUTES]. The fallback read
     * multiplies the legacy value by 60 so upgrading users keep their
     * chosen timeout to the second — the next write lands in the new
     * seconds key and the legacy entry eventually becomes dead bytes.
     */
    val bleAutoDisconnectSeconds: Flow<Int> = dataStore.data.map { prefs ->
        prefs[PreferenceKeys.BLE_AUTO_DISCONNECT_SECONDS]
            ?: ((prefs[PreferenceKeys.BLE_AUTO_DISCONNECT_MINUTES] ?: 1) * 60)
    }

    suspend fun setBleAutoDisconnectSeconds(seconds: Int) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.BLE_AUTO_DISCONNECT_SECONDS] = seconds
        }
    }

    suspend fun resetLedColors() {
        dataStore.edit { prefs ->
            prefs.remove(PreferenceKeys.LED_COLOR_START)
            prefs.remove(PreferenceKeys.LED_COLOR_HAND)
            prefs.remove(PreferenceKeys.LED_COLOR_FINISH)
            prefs.remove(PreferenceKeys.LED_COLOR_FOOT)
        }
    }

    suspend fun setKilterColors() {
        val kilter = LedHoldColors.kilterStandard()
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.LED_COLOR_START] = kilter.start
            prefs[PreferenceKeys.LED_COLOR_HAND] = kilter.hand
            prefs[PreferenceKeys.LED_COLOR_FINISH] = kilter.finish
            prefs[PreferenceKeys.LED_COLOR_FOOT] = kilter.foot
        }
    }

    // Board browser filter persistence
    val boardAngle: Flow<Int> = dataStore.data.map { it[PreferenceKeys.BOARD_ANGLE] ?: 40 }
    val boardMinGrade: Flow<Int> = dataStore.data.map { it[PreferenceKeys.BOARD_MIN_GRADE] ?: 0 }
    val boardMaxGrade: Flow<Int> = dataStore.data.map { it[PreferenceKeys.BOARD_MAX_GRADE] ?: 14 }
    val boardMinAscensionists: Flow<Int> = dataStore.data.map { it[PreferenceKeys.BOARD_MIN_ASCENSIONISTS] ?: 0 }
    val boardSortField: Flow<String> = dataStore.data.map { it[PreferenceKeys.BOARD_SORT_FIELD] ?: "ASCENSIONISTS" }
    val boardSortDirection: Flow<String> = dataStore.data.map { it[PreferenceKeys.BOARD_SORT_DIRECTION] ?: "DESC" }
    val boardStatusFilter: Flow<String> = dataStore.data.map { it[PreferenceKeys.BOARD_STATUS_FILTER] ?: "ALL" }
    val boardClimbType: Flow<String> = dataStore.data.map { it[PreferenceKeys.BOARD_CLIMB_TYPE] ?: "BOULDER" }
    val boardBenchmarkOnly: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.BOARD_BENCHMARK_ONLY] ?: false }

    /** Auto-Note global default (off). */
    val autoNoteEnabled: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.AUTO_NOTE_ENABLED] ?: false }
    suspend fun setAutoNoteEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[PreferenceKeys.AUTO_NOTE_ENABLED] = enabled }
    }

    suspend fun setBoardFilters(
        angle: Int, minGrade: Int, maxGrade: Int, minAscensionists: Int,
        sortField: String, sortDirection: String, statusFilter: String,
        climbType: String = "BOULDER", benchmarkOnly: Boolean = false,
        originFilter: String = "ALL",
        myClimbsOnly: Boolean = false,
    ) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.BOARD_ANGLE] = angle
            prefs[PreferenceKeys.BOARD_MIN_GRADE] = minGrade
            prefs[PreferenceKeys.BOARD_MAX_GRADE] = maxGrade
            prefs[PreferenceKeys.BOARD_MIN_ASCENSIONISTS] = minAscensionists
            prefs[PreferenceKeys.BOARD_SORT_FIELD] = sortField
            prefs[PreferenceKeys.BOARD_SORT_DIRECTION] = sortDirection
            prefs[PreferenceKeys.BOARD_STATUS_FILTER] = statusFilter
            prefs[PreferenceKeys.BOARD_CLIMB_TYPE] = climbType
            prefs[PreferenceKeys.BOARD_BENCHMARK_ONLY] = benchmarkOnly
            prefs[PreferenceKeys.BOARD_ORIGIN_FILTER] = originFilter
            prefs[PreferenceKeys.BOARD_MY_CLIMBS_ONLY] = myClimbsOnly
        }
    }

    // Route playback settings
    val routeFrameSpeed: Flow<Float> = dataStore.data.map { it[PreferenceKeys.ROUTE_FRAME_SPEED] ?: 5f }
    val routeUseSetterSpeed: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.ROUTE_USE_SETTER_SPEED] ?: true }
    val routeCountdown: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.ROUTE_COUNTDOWN] ?: true }
    val routeCountdownSeconds: Flow<Int> = dataStore.data.map { it[PreferenceKeys.ROUTE_COUNTDOWN_SECONDS] ?: 5 }
    val routeAutoLoop: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.ROUTE_AUTO_LOOP] ?: false }

    suspend fun setRouteFrameSpeed(seconds: Float) {
        dataStore.edit { it[PreferenceKeys.ROUTE_FRAME_SPEED] = seconds }
    }

    suspend fun setRouteUseSetterSpeed(enabled: Boolean) {
        dataStore.edit { it[PreferenceKeys.ROUTE_USE_SETTER_SPEED] = enabled }
    }

    suspend fun setRouteCountdown(enabled: Boolean) {
        dataStore.edit { it[PreferenceKeys.ROUTE_COUNTDOWN] = enabled }
    }

    suspend fun setRouteCountdownSeconds(seconds: Int) {
        dataStore.edit { it[PreferenceKeys.ROUTE_COUNTDOWN_SECONDS] = seconds }
    }

    suspend fun setRouteAutoLoop(enabled: Boolean) {
        dataStore.edit { it[PreferenceKeys.ROUTE_AUTO_LOOP] = enabled }
    }

    // Rest timer settings
    val restTimerDurationSeconds: Flow<Int> = dataStore.data.map {
        it[PreferenceKeys.REST_TIMER_DURATION_SECONDS] ?: 180 // default 3 min
    }
    val restTimerAutoStart: Flow<Boolean> = dataStore.data.map {
        it[PreferenceKeys.REST_TIMER_AUTO_START] ?: false
    }

    suspend fun setRestTimerDurationSeconds(seconds: Int) {
        dataStore.edit { it[PreferenceKeys.REST_TIMER_DURATION_SECONDS] = seconds }
    }

    suspend fun setRestTimerAutoStart(enabled: Boolean) {
        dataStore.edit { it[PreferenceKeys.REST_TIMER_AUTO_START] = enabled }
    }

    // Nearby climb sharing
    val nearbyClimbSharing: Flow<Boolean> = dataStore.data.map {
        it[PreferenceKeys.NEARBY_CLIMB_SHARING] ?: false
    }
    val allowRemoteDisconnect: Flow<Boolean> = dataStore.data.map {
        it[PreferenceKeys.ALLOW_REMOTE_DISCONNECT] ?: false
    }

    suspend fun setNearbyClimbSharing(enabled: Boolean) {
        dataStore.edit { it[PreferenceKeys.NEARBY_CLIMB_SHARING] = enabled }
    }

    suspend fun setAllowRemoteDisconnect(enabled: Boolean) {
        dataStore.edit { it[PreferenceKeys.ALLOW_REMOTE_DISCONNECT] = enabled }
    }

    val keepScreenOn: Flow<Boolean> = dataStore.data.map {
        it[PreferenceKeys.KEEP_SCREEN_ON] ?: false
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        dataStore.edit { it[PreferenceKeys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun isOnboardingCompleted(): Boolean =
        keyScoped.data.first()[KeyScopedKeys.ONBOARDING_COMPLETED] ?: false

    suspend fun setOnboardingCompleted(completed: Boolean) {
        keyScoped.edit { it[KeyScopedKeys.ONBOARDING_COMPLETED] = completed }
    }

    val darkMode: Flow<DarkModeSetting> = dataStore.data.map { prefs ->
        val value = prefs[PreferenceKeys.DARK_MODE] ?: DarkModeSetting.SYSTEM.name
        try { DarkModeSetting.valueOf(value) } catch (_: IllegalArgumentException) { DarkModeSetting.SYSTEM }
    }

    suspend fun setDarkMode(mode: DarkModeSetting) {
        dataStore.edit { it[PreferenceKeys.DARK_MODE] = mode.name }
    }

    val sessionDisplayName: Flow<String> = dataStore.data.map {
        it[PreferenceKeys.SESSION_DISPLAY_NAME] ?: ""
    }

    suspend fun setSessionDisplayName(name: String) {
        dataStore.edit { it[PreferenceKeys.SESSION_DISPLAY_NAME] = name }
    }

    // Persisted last climb (survives app restart)
    val lastClimbUuid: Flow<String?> = dataStore.data.map {
        it[PreferenceKeys.LAST_CLIMB_UUID]
    }
    val lastClimbAngle: Flow<Int> = dataStore.data.map {
        it[PreferenceKeys.LAST_CLIMB_ANGLE] ?: 0
    }
    val lastClimbTimestamp: Flow<Long> = dataStore.data.map {
        it[PreferenceKeys.LAST_CLIMB_TIMESTAMP] ?: 0L
    }

    suspend fun setLastClimb(uuid: String, angle: Int) {
        dataStore.edit {
            it[PreferenceKeys.LAST_CLIMB_UUID] = uuid
            it[PreferenceKeys.LAST_CLIMB_ANGLE] = angle
            it[PreferenceKeys.LAST_CLIMB_TIMESTAMP] = System.currentTimeMillis()
        }
    }


    val easterAnimationsUnlocked: Flow<Boolean> = dataStore.data.map {
        it[PreferenceKeys.EASTER_ANIMATIONS_UNLOCKED] ?: false
    }

    suspend fun setEasterAnimationsUnlocked(unlocked: Boolean) {
        dataStore.edit { it[PreferenceKeys.EASTER_ANIMATIONS_UNLOCKED] = unlocked }
    }

    /** null = user has never been asked yet, true/false = user's explicit choice */
    val crashReportOptIn: Flow<Boolean?> = dataStore.data.map {
        it[PreferenceKeys.CRASH_REPORT_OPT_IN]
    }

    suspend fun setCrashReportOptIn(enabled: Boolean) {
        dataStore.edit { it[PreferenceKeys.CRASH_REPORT_OPT_IN] = enabled }
    }

    val lastAnnouncementCheck: Flow<Long?> = dataStore.data.map {
        it[PreferenceKeys.LAST_ANNOUNCEMENT_CHECK]?.toLongOrNull()
    }

    suspend fun setLastAnnouncementCheck(timestamp: Long) {
        dataStore.edit { it[PreferenceKeys.LAST_ANNOUNCEMENT_CHECK] = timestamp.toString() }
    }

    /**
     * Persistent sync cursor (Unix seconds) for the Nostr DM relay subscription.
     * Latest `created_at` we successfully ingested. Survives app restarts and
     * any DB wipe so the relay subscription can resume from where it left off
     * instead of using a fixed time window.
     */
    val nostrSyncCursor: Flow<Long?> = keyScoped.data.map {
        it[KeyScopedKeys.NOSTR_SYNC_CURSOR]
    }

    suspend fun getNostrSyncCursor(): Long? =
        keyScoped.data.first()[KeyScopedKeys.NOSTR_SYNC_CURSOR]

    suspend fun setNostrSyncCursor(cursor: Long) {
        keyScoped.edit { it[KeyScopedKeys.NOSTR_SYNC_CURSOR] = cursor }
    }

    /**
     * Monotonically advance the cursor: write [candidate] only if it is
     * strictly greater than the on-disk value. The compare-and-write
     * happens inside the same `edit` block, which DataStore serializes
     * per instance, so concurrent advancers from the foreground
     * subscription and the background poll worker cannot regress the
     * cursor by writing a stale snapshot.
     *
     * Use this instead of the get+compare+set pattern for anything that
     * should only ever move forward. Full overwrites (e.g. explicit
     * reset to 0 on identity switch) should still go through
     * [setNostrSyncCursor].
     */
    suspend fun advanceNostrSyncCursor(candidate: Long) {
        keyScoped.edit { prefs ->
            val current = prefs[KeyScopedKeys.NOSTR_SYNC_CURSOR] ?: 0L
            if (candidate > current) prefs[KeyScopedKeys.NOSTR_SYNC_CURSOR] = candidate
        }
    }

    /**
     * Schema version of the Nostr sync recovery state. Bumping the constant in
     * code triggers a one-shot cursor reset on next app start so the relay
     * back-fills missing history (e.g. after the BoardDB → SecureDB split).
     */
    suspend fun getNostrRecoveryVersion(): Int =
        keyScoped.data.first()[KeyScopedKeys.NOSTR_SYNC_RECOVERY_VERSION] ?: 0

    suspend fun setNostrRecoveryVersion(version: Int) {
        keyScoped.edit { it[KeyScopedKeys.NOSTR_SYNC_RECOVERY_VERSION] = version }
    }

    val announcementsEnabled: Flow<Boolean> = dataStore.data.map {
        it[PreferenceKeys.ANNOUNCEMENTS_ENABLED] ?: true
    }

    suspend fun setAnnouncementsEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferenceKeys.ANNOUNCEMENTS_ENABLED] = enabled }
    }

    val announcementCatRelease: Flow<Boolean> = dataStore.data.map {
        it[PreferenceKeys.ANNOUNCEMENT_CAT_RELEASE] ?: true
    }
    val announcementCatIssue: Flow<Boolean> = dataStore.data.map {
        it[PreferenceKeys.ANNOUNCEMENT_CAT_ISSUE] ?: true
    }
    val announcementCatTip: Flow<Boolean> = dataStore.data.map {
        it[PreferenceKeys.ANNOUNCEMENT_CAT_TIP] ?: true
    }
    val announcementCatGeneral: Flow<Boolean> = dataStore.data.map {
        it[PreferenceKeys.ANNOUNCEMENT_CAT_GENERAL] ?: true
    }

    suspend fun setAnnouncementCategoryEnabled(category: String, enabled: Boolean) {
        dataStore.edit {
            val key = when (category) {
                AnnouncementTagParser.CATEGORY_RELEASE -> PreferenceKeys.ANNOUNCEMENT_CAT_RELEASE
                AnnouncementTagParser.CATEGORY_ISSUE -> PreferenceKeys.ANNOUNCEMENT_CAT_ISSUE
                AnnouncementTagParser.CATEGORY_TIP -> PreferenceKeys.ANNOUNCEMENT_CAT_TIP
                AnnouncementTagParser.CATEGORY_GENERAL -> PreferenceKeys.ANNOUNCEMENT_CAT_GENERAL
                else -> return@edit
            }
            it[key] = enabled
        }
    }

    val autoPublishAscents: Flow<Boolean> = keyScoped.data.map {
        it[KeyScopedKeys.AUTO_PUBLISH_ASCENTS] ?: false
    }

    suspend fun setAutoPublishAscents(enabled: Boolean) {
        keyScoped.edit { it[KeyScopedKeys.AUTO_PUBLISH_ASCENTS] = enabled }
    }

    val leaderboardDisplayName: Flow<String> = keyScoped.data.map {
        it[KeyScopedKeys.LEADERBOARD_DISPLAY_NAME] ?: ""
    }

    suspend fun setLeaderboardDisplayName(name: String) {
        keyScoped.edit { it[KeyScopedKeys.LEADERBOARD_DISPLAY_NAME] = name }
    }

    // Nostr key management
    val signerMode: Flow<SignerMode> = keyScoped.data.map { prefs ->
        val value = prefs[KeyScopedKeys.SIGNER_MODE] ?: SignerMode.LOCAL.name
        try { SignerMode.valueOf(value) } catch (_: IllegalArgumentException) { SignerMode.LOCAL }
    }

    suspend fun setSignerMode(mode: SignerMode) {
        keyScoped.edit { it[KeyScopedKeys.SIGNER_MODE] = mode.name }
    }

    val amberPubkey: Flow<String?> = keyScoped.data.map { it[KeyScopedKeys.AMBER_PUBKEY] }

    suspend fun setAmberPubkey(pubkey: String?) {
        keyScoped.edit {
            if (pubkey != null) it[KeyScopedKeys.AMBER_PUBKEY] = pubkey
            else it.remove(KeyScopedKeys.AMBER_PUBKEY)
        }
    }

    val amberPackageName: Flow<String?> = keyScoped.data.map { it[KeyScopedKeys.AMBER_PACKAGE_NAME] }

    suspend fun setAmberPackageName(name: String?) {
        keyScoped.edit {
            if (name != null) it[KeyScopedKeys.AMBER_PACKAGE_NAME] = name
            else it.remove(KeyScopedKeys.AMBER_PACKAGE_NAME)
        }
    }

    val keyBackedUp: Flow<Boolean> = keyScoped.data.map {
        it[KeyScopedKeys.KEY_BACKED_UP] ?: false
    }

    suspend fun setKeyBackedUp(backedUp: Boolean) {
        keyScoped.edit { it[KeyScopedKeys.KEY_BACKED_UP] = backedUp }
    }

    val appLaunchCount: Flow<Int> = dataStore.data.map {
        it[PreferenceKeys.APP_LAUNCH_COUNT] ?: 0
    }

    suspend fun incrementLaunchCount() {
        dataStore.edit {
            val current = it[PreferenceKeys.APP_LAUNCH_COUNT] ?: 0
            it[PreferenceKeys.APP_LAUNCH_COUNT] = current + 1
        }
    }

    val lastSeenAppVersionCode: Flow<Int?> = keyScoped.data.map {
        it[KeyScopedKeys.LAST_SEEN_APP_VERSION_CODE]
    }

    suspend fun setLastSeenAppVersionCode(versionCode: Int) {
        keyScoped.edit { it[KeyScopedKeys.LAST_SEEN_APP_VERSION_CODE] = versionCode }
    }

    /**
     * FEAT-001 kill-switch. Default `true`. When `false`, the resolver
     * short-circuits to `NostrConfig.DEFAULT_RELAYS` and no bootstrap fetch
     * runs. Not surfaced in the settings UI in 0.1.3 — flipped via dev
     * guidance or a follow-up patch if a bootstrap relay misbehaves.
     */
    val nip65DiscoveryEnabled: Flow<Boolean> = dataStore.data.map {
        it[PreferenceKeys.NIP65_DISCOVERY_ENABLED] ?: true
    }

    suspend fun isNip65DiscoveryEnabled(): Boolean =
        dataStore.data.first()[PreferenceKeys.NIP65_DISCOVERY_ENABLED] ?: true

    suspend fun setNip65DiscoveryEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferenceKeys.NIP65_DISCOVERY_ENABLED] = enabled }
    }
}
