package com.cruxcoach.android.data

import android.content.Context
import androidx.datastore.core.DataMigration
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
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cruxcoach_prefs",
    produceMigrations = { listOf(boardGradeScaleMigrationV1) },
)

/**
 * One-time remap of the persisted board-filter grade bounds after the unified
 * grade scale gained its two missing low Font grades — 4a (new index 0) and 5a
 * (new index 3). Every stored index shifts up: +1 for the 4a insert, and +1
 * more once at/above the old 5b position for the 5a insert. Gated by
 * [PreferenceKeys.BOARD_GRADE_SCALE_VERSION] so it runs exactly once.
 *
 * Only these two ints need touching — profile grades are persisted as Font
 * strings and re-derive their index via [com.cruxcoach.util.GradeConverter.gradeToIndex],
 * which is stable across the reindex.
 */
private val boardGradeScaleMigrationV1 = object : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        (currentData[PreferenceKeys.BOARD_GRADE_SCALE_VERSION] ?: 0) < 1

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        currentData[PreferenceKeys.BOARD_MIN_GRADE]?.let {
            prefs[PreferenceKeys.BOARD_MIN_GRADE] = remapOldGradeIndexToV1(it)
        }
        currentData[PreferenceKeys.BOARD_MAX_GRADE]?.let {
            prefs[PreferenceKeys.BOARD_MAX_GRADE] = remapOldGradeIndexToV1(it)
        }
        prefs[PreferenceKeys.BOARD_GRADE_SCALE_VERSION] = 1
        return prefs
    }

    override suspend fun cleanUp() {}
}

/** Old unified index (0..22, floor 4b) → new unified index (0..24, floor 4a). */
internal fun remapOldGradeIndexToV1(old: Int): Int =
    (old + if (old >= 2) 2 else 1).coerceIn(0, 24)

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

/** Controls whether opening a climb immediately updates a connected board. */
enum class BoardSendMode {
    AUTOMATIC,
    EXPLICIT;

    companion object {
        fun fromWire(value: String?): BoardSendMode =
            entries.firstOrNull { it.name == value } ?: AUTOMATIC
    }
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

enum class HistoryRetention(val days: Int) {
    OFF(0),
    DAYS_30(30),
    DAYS_90(90),
    DAYS_365(365),
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

        // Aurora family (Tension/Grasshopper/Decoy/Touchstone): the shared
        // non-Kilter Aurora scheme — start green, hands blue, finish red, feet
        // magenta (per the boards' placement_roles.led_color / BoardSesh
        // hold-state palette). So iLL differs — see SOILL_* below.
        const val AURORA_START: Int = 0x1C   // Green (00FF00)
        const val AURORA_HAND: Int = 0x03    // Blue (0000FF)
        const val AURORA_FINISH: Int = 0xE0  // Red (FF0000)
        const val AURORA_FOOT: Int = 0xE3    // Magenta (FF00FF)

        // So iLL ships a distinct placement_roles palette (RE-verified): start
        // green (== AURORA_START), middle magenta, finish WHITE, foot cyan —
        // three of four differ from the shared Aurora scheme, so it gets its own
        // fallback used until its synced placement_roles override it.
        const val SOILL_HAND: Int = 0xE3     // Magenta (FF00FF)
        const val SOILL_FINISH: Int = 0xFF   // White (FFFFFF)
        const val SOILL_FOOT: Int = 0x1F     // Cyan (00FFFF)

        /**
         * The conventional LED colours for a board family (FEAT-031): each
         * board lights in its own usual scheme rather than Kilter's. Kilter
         * stays USER-configurable (callers should prefer the user's
         * [ledHoldColors] for Kilter); the Aurora family + MoonBoard use their
         * standard palettes. The authoritative per-board source is the
         * catalogue's placement_roles.led_color — once that is synced per board
         * it should override these conventional defaults.
         */
        fun standardFor(brand: BoardBrand): LedHoldColors = when (brand) {
            BoardBrand.KILTER -> kilterStandard()
            BoardBrand.TENSION, BoardBrand.GRASSHOPPER, BoardBrand.DECOY,
            BoardBrand.TOUCHSTONE ->
                LedHoldColors(AURORA_START, AURORA_HAND, AURORA_FINISH, AURORA_FOOT)
            BoardBrand.SOILL ->
                LedHoldColors(start = AURORA_START, hand = SOILL_HAND, finish = SOILL_FINISH, foot = SOILL_FOOT)
            // MoonBoard's classic 3-colour scheme (green start, blue hands,
            // red top); no distinct foot role, so feet reuse the hand colour.
            BoardBrand.MOONBOARD ->
                LedHoldColors(start = 0x1C, hand = 0x03, finish = 0xE0, foot = 0x03)
            else -> LedHoldColors()
        }

        // Previously-shipped CruxCoach DEFAULT presets, by release:
        //   v0.1.0–0.1.1: start=Orange, hand=Blue, finish=Magenta, foot=Teal (0x1D)
        //   v0.1.2–0.1.4: same, but foot=Mint Green (0x1E) — the named-palette fix
        // The current default (the CRUXCOACH_* constants above) replaced these
        // in 0.2.0. Used solely by the one-time default-color migration
        // (UserPreferences.migrateLegacyLedDefaultsIfNeeded) to recognise a
        // user sitting on an OLD default preset — as opposed to a deliberate
        // custom choice or the Kilter preset — and move them onto the new
        // default. All three sets are mutually disjoint, so an exact full-tuple
        // match can't false-positive on a customiser or a Kilter-preset user.
        val LEGACY_CRUXCOACH_DEFAULTS: List<LedHoldColors> = listOf(
            LedHoldColors(start = 0xEC, hand = 0x03, finish = 0xE3, foot = 0x1D),
            LedHoldColors(start = 0xEC, hand = 0x03, finish = 0xE3, foot = 0x1E),
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
        HoldRole.ROUTE_FOOT to foot,
        // Aurora-family role codes (Tension/Grasshopper/Decoy/So iLL/Touchstone:
        // 1=start, 2=middle/hand, 3=finish, 4=foot). Included so this
        // placement_roles-absent fallback map colours Aurora holds directly
        // instead of missing every key and dropping to the Kilter palette.
        1 to start, 2 to hand, 3 to finish, 4 to foot,
    )

    fun colorForRole(roleId: Int): Int = when (HoldRole.roleClass(roleId)) {
        HoldRole.START -> start
        HoldRole.HAND -> hand
        HoldRole.FINISH -> finish
        HoldRole.FOOT -> foot
        else -> 0xFF
    }
}

/**
 * Stable descriptor for reconnecting to a controller without discovering it again.
 * RSSI and runtime capacity-probe results are deliberately excluded because they
 * only describe the scan/connection in which the controller was observed.
 */
data class RememberedBoardController(
    val displayName: String,
    val serial: String,
    val apiLevel: Int,
    val address: String,
    val boardBrand: BoardBrand,
    /**
     * True once this controller was observed advertising while connected.
     * Null means "never established" — not "single client". Lets a reconnect
     * report a verified connection capacity without running a scan.
     */
    val advertisesWhileConnected: Boolean? = null,
)

/** Shared preference keys — device-level settings, same across all Nostr identities. */
object PreferenceKeys {
    // FEAT-044 CruxRelay: one-time board-sharing disclosure (global Bluetooth
    // name change + non-affiliation). App-scoped ON PURPOSE — the disclosure
    // is about the device, not the Nostr identity.
    val RELAY_DISCLOSURE_SEEN = booleanPreferencesKey("relay_disclosure_seen")
    val BOARD_PRODUCT_SIZE_ID = intPreferencesKey("board_product_size_id")
    val BOARD_LAYOUT_ID = intPreferencesKey("board_layout_id")
    /** Active board brand — "kilter" | "moonboard" (FEAT-027). */
    val BOARD_BRAND = stringPreferencesKey("board_brand")
    val SYNC_INTERVAL = stringPreferencesKey("sync_interval")
    val CLIMB_HISTORY_RETENTION_DAYS = intPreferencesKey("climb_history_retention_days")
    val LAST_SYNC_TIMESTAMP = stringPreferencesKey("last_sync_timestamp")
    /** SHA-256 of the last peer snapshot successfully imported. Cleared by
     * every other catalogue mutation so it is safe as a no-download gate. */
    val LAST_LOCAL_SHARE_SNAPSHOT_SHA256 =
        stringPreferencesKey("last_local_share_snapshot_sha256")
    val GRADE_SCALE = stringPreferencesKey("grade_scale")
    val LED_COLOR_START = intPreferencesKey("led_color_start")
    val LED_COLOR_HAND = intPreferencesKey("led_color_hand")
    val LED_COLOR_FINISH = intPreferencesKey("led_color_finish")
    val LED_COLOR_FOOT = intPreferencesKey("led_color_foot")
    // One-time guard for the 0.2.0 LED default-color migration
    // (migrateLegacyLedDefaultsIfNeeded). Set once on the first 0.2.0 launch
    // so a later user who deliberately recreates an old default tuple is not
    // disturbed. Never read by UI.
    val LED_DEFAULTS_MIGRATED = booleanPreferencesKey("led_defaults_migrated_v020")
    val BLE_AUTO_DISCONNECT_MINUTES = intPreferencesKey("ble_auto_disconnect_minutes")
    // Seconds-precision successor to BLE_AUTO_DISCONNECT_MINUTES. Read
    // by bleAutoDisconnectSeconds, which transparently migrates the
    // older minutes key on first read if the new key is absent.
    val BLE_AUTO_DISCONNECT_SECONDS = intPreferencesKey("ble_auto_disconnect_seconds")
    // Legacy global key retained as a read-only migration fallback for the two
    // capacity-specific modes below.
    val BOARD_SEND_MODE = stringPreferencesKey("board_send_mode")
    val SINGLE_CONNECTION_BOARD_SEND_MODE =
        stringPreferencesKey("single_connection_board_send_mode")
    val MULTI_CONNECTION_BOARD_SEND_MODE =
        stringPreferencesKey("multi_connection_board_send_mode")
    fun lastUsedBoardAddress(brand: BoardBrand) =
        stringPreferencesKey("last_used_board_address_${brand.wireValue}")
    fun lastUsedBoardDisplayName(brand: BoardBrand) =
        stringPreferencesKey("last_used_board_display_name_${brand.wireValue}")
    fun lastUsedBoardSerial(brand: BoardBrand) =
        stringPreferencesKey("last_used_board_serial_${brand.wireValue}")
    fun lastUsedBoardApiLevel(brand: BoardBrand) =
        intPreferencesKey("last_used_board_api_level_${brand.wireValue}")

    /**
     * Which MoonBoard hold sets the user has actually mounted, as a CSV of set
     * ids (FEAT-049). Keyed PER LAYOUT — someone may own a Masters 2019 and
     * meet a 2017 at a gym, and the id spaces are disjoint per layout, so one
     * board's selection can never be read as another's.
     *
     * Absent means "every set", not "no sets". That keeps the filter off for
     * everyone who never opens the picker, which is the pre-FEAT-049
     * behaviour, and it makes the upgrade a no-op.
     */
    fun moonBoardHoldSets(layoutId: Long) =
        stringPreferencesKey("moonboard_hold_sets_$layoutId")

    /**
     * Set once a controller has been *observed* to keep advertising while
     * connected, i.e. proven to accept more than one client.
     *
     * Absent while nothing has been observed, true once a controller was seen
     * advertising, false once a completed scan saw none.
     *
     * It used to be write-once-true, on the reasoning that absence of an
     * advertisement can be a missed window while its presence is proof. That
     * holds for a scan that could not run — but the probe already distinguishes
     * NOT_OBSERVED ("scanned, saw nothing") from INCONCLUSIVE ("could not
     * measure"), and only the second is silence. Treating both as silence made
     * "accepts several clients" permanent: a controller that was swapped, or a
     * simulator switched back to single-client, could never be corrected, and
     * the app went on offering multi-client behaviour that the board no longer
     * had.
     *
     * A reconnect without scan permission still writes nothing.
     */
    fun lastUsedBoardAdvertisesWhileConnected(brand: BoardBrand) =
        booleanPreferencesKey("last_used_board_multi_client_${brand.wireValue}")
    val BOARD_ANGLE = intPreferencesKey("board_angle")
    val BOARD_MIN_GRADE = intPreferencesKey("board_min_grade")
    val BOARD_MAX_GRADE = intPreferencesKey("board_max_grade")
    // Schema version for the board-filter grade indices. Bumped to 1 when the
    // unified scale gained 4a + 5a (see boardGradeScaleMigrationV1).
    val BOARD_GRADE_SCALE_VERSION = intPreferencesKey("board_grade_scale_version")
    val BOARD_MIN_ASCENSIONISTS = intPreferencesKey("board_min_ascensionists")
    val BOARD_SORT_FIELD = stringPreferencesKey("board_sort_field")
    val BOARD_SORT_DIRECTION = stringPreferencesKey("board_sort_direction")
    val BOARD_STATUS_FILTER = stringPreferencesKey("board_status_filter")
    val BOARD_CLIMB_TYPE = stringPreferencesKey("board_climb_type")
    val BOARD_BENCHMARK_ONLY = booleanPreferencesKey("board_benchmark_only")
    val BOARD_ORIGIN_FILTER = stringPreferencesKey("board_origin_filter")
    val BOARD_MY_CLIMBS_ONLY = booleanPreferencesKey("board_my_climbs_only")
    // "Nur unbewertete (Projekte)" browse mode — list shows ONLY ungraded
    // climbs while set; persisted like every other browse filter.
    val BOARD_UNGRADED_ONLY = booleanPreferencesKey("board_ungraded_only")
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
    val LAST_CLIMB_PROJECTION_SURVIVES_DISCONNECT =
        booleanPreferencesKey("last_climb_projection_survives_disconnect")
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
    // egym-Wellpass-only filter (FEAT-015 Phase 2). Off = no Wellpass gate.
    val MAP_FILTER_WELLPASS_ONLY = booleanPreferencesKey("map_filter_wellpass_only")
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
    /** Ungraded-only ("Projekte") browse mode. Defaults to false so fresh
     *  installs and pre-existing prefs open on the normal catalogue view. */
    val ungradedOnly: Boolean = false,
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
            minGrade = prefs[PreferenceKeys.BOARD_MIN_GRADE] ?: 0,   // 0 = 4a (floor)
            maxGrade = prefs[PreferenceKeys.BOARD_MAX_GRADE] ?: 16,  // 16 = 7c
            minAscensionists = prefs[PreferenceKeys.BOARD_MIN_ASCENSIONISTS] ?: 0,
            gradeScale = try { GradeScale.valueOf(prefs[PreferenceKeys.GRADE_SCALE] ?: GradeScale.FRENCH.name) } catch (_: IllegalArgumentException) { GradeScale.FRENCH },
            sortField = prefs[PreferenceKeys.BOARD_SORT_FIELD] ?: "ASCENSIONISTS",
            sortDirection = prefs[PreferenceKeys.BOARD_SORT_DIRECTION] ?: "DESC",
            statusFilter = prefs[PreferenceKeys.BOARD_STATUS_FILTER] ?: "ALL",
            climbType = prefs[PreferenceKeys.BOARD_CLIMB_TYPE] ?: "BOULDER",
            benchmarkOnly = prefs[PreferenceKeys.BOARD_BENCHMARK_ONLY] ?: false,
            originFilter = prefs[PreferenceKeys.BOARD_ORIGIN_FILTER] ?: "ALL",
            myClimbsOnly = prefs[PreferenceKeys.BOARD_MY_CLIMBS_ONLY] ?: false,
            ungradedOnly = prefs[PreferenceKeys.BOARD_UNGRADED_ONLY] ?: false,
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

    val historyRetention: Flow<HistoryRetention> = dataStore.data.map { prefs ->
        val days = prefs[PreferenceKeys.CLIMB_HISTORY_RETENTION_DAYS] ?: HistoryRetention.DAYS_30.days
        HistoryRetention.entries.firstOrNull { it.days == days } ?: HistoryRetention.DAYS_30
    }

    val lastSyncTimestamp: Flow<String?> = dataStore.data.map { prefs ->
        prefs[PreferenceKeys.LAST_SYNC_TIMESTAMP]
    }

    val lastLocalShareSnapshotSha256: Flow<String?> = dataStore.data.map { prefs ->
        prefs[PreferenceKeys.LAST_LOCAL_SHARE_SNAPSHOT_SHA256]
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

    // ── MoonBoard hold sets (FEAT-049) ─────────────────────────
    // The second axis next to the variant: WHICH of the variant's hold sets
    // are physically mounted. MoonBoard has no product size to derive this
    // from the way Kilter does, so it is user-owned and stored per layout.

    /**
     * The hold sets mounted on [variant], as a live flow. Emits the variant's
     * FULL set universe when nothing is stored — an absent preference means
     * "complete setup", which is what a bundle buyer has and what every
     * existing install gets on upgrade.
     *
     * A stored value that resolves to nothing (empty string, ids from another
     * board, hand-edited rubbish) is read the same lenient way. Reading it as
     * "no sets mounted" would hide the entire catalogue behind a filter the
     * user never set.
     */
    fun moonBoardHoldSets(variant: MoonBoardVariant): Flow<List<Long>> =
        dataStore.data.map { prefs ->
            resolveMoonBoardHoldSets(prefs[PreferenceKeys.moonBoardHoldSets(variant.layoutId)], variant)
        }

    /** Single-read counterpart to [moonBoardHoldSets], for the browse query's
     *  one-shot mask computation. */
    suspend fun getMoonBoardHoldSets(variant: MoonBoardVariant): List<Long> =
        resolveMoonBoardHoldSets(
            dataStore.data.first()[PreferenceKeys.moonBoardHoldSets(variant.layoutId)],
            variant,
        )

    /**
     * Persist the mounted hold sets for [variant]. Selecting every set — the
     * "complete setup" line — simply stores every id; there is no separate
     * flag for it, so the summary follows from the stored list alone.
     *
     * An empty [setIds] is refused rather than stored: at least one set has to
     * stay selected for the board to have any climbs at all, and the read path
     * would silently re-expand it to "all" anyway.
     */
    suspend fun setMoonBoardHoldSets(variant: MoonBoardVariant, setIds: Collection<Long>) {
        val universe = MoonBoardHoldSets.setIdsFor(variant)
        val kept = universe.filter { it in setIds }
        if (kept.isEmpty()) return
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.moonBoardHoldSets(variant.layoutId)] = kept.joinToString(",")
        }
    }

    /**
     * Tick or untick ONE set for [variant], deriving the new selection inside a
     * single store edit. Returns false when the toggle was refused because it
     * would have left nothing selected (edge case 1); the stored value is then
     * untouched.
     *
     * Why not read the selection, flip it and call [setMoonBoardHoldSets]: two
     * taps landing before the store's flow has emitted back would both read the
     * same list and each write a full replacement derived from it, so the second
     * write silently restores the set the first one removed (edge case 11). Here
     * the read and the write are one `edit` block, and DataStore serialises
     * edits — the second toggle starts from the first one's result even if the
     * flow has not caught up. That is also why this belongs in the store rather
     * than behind a ViewModel mutex: the value on disk is the shared truth, and
     * a second ViewModel or a later caller gets the same guarantee for free.
     */
    suspend fun toggleMoonBoardHoldSet(variant: MoonBoardVariant, setId: Long): Boolean {
        val universe = MoonBoardHoldSets.setIdsFor(variant)
        val key = PreferenceKeys.moonBoardHoldSets(variant.layoutId)
        var accepted = true
        dataStore.edit { prefs ->
            val current = resolveMoonBoardHoldSets(prefs[key], variant).toMutableSet()
            if (setId in current) current -= setId else current += setId
            val kept = universe.filter { it in current }
            if (kept.isEmpty()) {
                accepted = false
                return@edit
            }
            prefs[key] = kept.joinToString(",")
        }
        return accepted
    }

    private fun resolveMoonBoardHoldSets(stored: String?, variant: MoonBoardVariant): List<Long> {
        val universe = MoonBoardHoldSets.setIdsFor(variant)
        if (stored.isNullOrBlank()) return universe
        val selected = stored.split(',').mapNotNullTo(mutableSetOf()) { it.trim().toLongOrNull() }
        val kept = universe.filter { it in selected }
        return kept.ifEmpty { universe }
    }

    /**
     * Atomically set the active board's brand + layout + product size in a
     * single DataStore edit. The board-flow collectors combine these three
     * keys with `distinctUntilChanged`, so writing them separately can emit a
     * transient (new brand, stale layout) tuple that fires a query against a
     * mismatched (brand, layout) pair before it settles. Used by the Kilter
     * and Aurora picker paths (FEAT-031) — mirrors [setMoonBoardSelection]'s
     * one-edit atomicity.
     */
    suspend fun setBoardSelection(brand: String, layoutId: Int, productSizeId: Int? = null, angle: Int? = null) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.BOARD_BRAND] = brand
            prefs[PreferenceKeys.BOARD_LAYOUT_ID] = layoutId
            // Null size = "keep the current product size" (the map browse path
            // has a layout but not always a size); still one atomic edit.
            if (productSizeId != null) prefs[PreferenceKeys.BOARD_PRODUCT_SIZE_ID] = productSizeId
            // Non-null angle = a FIXED-angle wall reported by the gym pick → seed
            // the browse angle to the wall's real angle instead of the generic
            // 40° default. Adjustable walls pass null (angle stays the user's
            // per-session choice). Written in the same atomic edit.
            if (angle != null) prefs[PreferenceKeys.BOARD_ANGLE] = angle
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

    /** egym-Wellpass-only filter. Off (false) = no Wellpass gate. */
    val mapFilterWellpassOnly: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PreferenceKeys.MAP_FILTER_WELLPASS_ONLY] ?: false
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

    suspend fun setMapFilterWellpassOnly(enabled: Boolean) {
        dataStore.edit { it[PreferenceKeys.MAP_FILTER_WELLPASS_ONLY] = enabled }
    }

    /**
     * Atomically toggle [value] in the CSV string-set under [key]: the read,
     * modify, and write all happen inside one `dataStore.edit {}` transaction.
     * The previous read-then-set pattern in the ViewModel could lose an
     * update when two rapid taps both read the same pre-write snapshot and the
     * second `set` overwrote the first. Mirrors the atomic compare-and-write
     * precedent in `advanceNostrSyncCursor`.
     */
    private suspend fun toggleCsvSetMember(
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        value: String,
    ) {
        dataStore.edit { prefs ->
            val current = parseCsvSet(prefs[key])
            val next = if (value in current) current - value else current + value
            prefs[key] = next.joinToString(",")
        }
    }

    suspend fun toggleMapFilterCountry(code: String) =
        toggleCsvSetMember(PreferenceKeys.MAP_FILTER_COUNTRIES, code)

    suspend fun toggleMapFilterAccessType(key: String) =
        toggleCsvSetMember(PreferenceKeys.MAP_FILTER_ACCESS_TYPES, key)

    suspend fun toggleMapFilterAdjustability(key: String) =
        toggleCsvSetMember(PreferenceKeys.MAP_FILTER_ADJUSTABILITIES, key)

    suspend fun toggleMapFilterSizeId(sizeId: Int) =
        toggleCsvSetMember(PreferenceKeys.MAP_FILTER_SIZE_IDS, sizeId.toString())

    suspend fun toggleMapFilterBrand(key: String) =
        toggleCsvSetMember(PreferenceKeys.MAP_FILTER_BRANDS, key)

    /** Atomically toggle a whole group of members in the brand CSV set in one
     *  transaction — the "other boards" chip toggles all info-layer brands at
     *  once. Adds the group when not all present, otherwise removes it. */
    suspend fun toggleMapFilterBrandGroup(keys: Set<String>) {
        dataStore.edit { prefs ->
            val current = parseCsvSet(prefs[PreferenceKeys.MAP_FILTER_BRANDS])
            val next = if (keys.all { it in current }) current - keys else current + keys
            prefs[PreferenceKeys.MAP_FILTER_BRANDS] = next.joinToString(",")
        }
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
            prefs.remove(PreferenceKeys.MAP_FILTER_WELLPASS_ONLY)
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

    suspend fun setHistoryRetention(value: HistoryRetention) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.CLIMB_HISTORY_RETENTION_DAYS] = value.days
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

    suspend fun setLastLocalShareSnapshotSha256(sha256: String?) {
        dataStore.edit { prefs ->
            if (sha256 != null) {
                prefs[PreferenceKeys.LAST_LOCAL_SHARE_SNAPSHOT_SHA256] = sha256
            } else {
                prefs.remove(PreferenceKeys.LAST_LOCAL_SHARE_SNAPSHOT_SHA256)
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
     * Kilter database via the user's own account. Default `false` (opt-in):
     * climbs you create stay local unless you explicitly turn this on in
     * Settings, so nothing is written to Kilter without consent.
     */
    val kilterClimbPublishEnabled: Flow<Boolean> = keyScoped.data.map { prefs ->
        prefs[KeyScopedKeys.KILTER_CLIMB_PUBLISH_ENABLED] ?: false
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

    /**
     * Default AUTOMATIC: on a board only you can hold, opening a climb and
     * having it appear on the wall is the whole point, and nobody else is
     * affected by it.
     */
    val singleConnectionBoardSendMode: Flow<BoardSendMode> = dataStore.data.map { prefs ->
        BoardSendMode.fromWire(
            prefs[PreferenceKeys.SINGLE_CONNECTION_BOARD_SEND_MODE]
                ?: prefs[PreferenceKeys.BOARD_SEND_MODE]
        )
    }

    suspend fun setSingleConnectionBoardSendMode(mode: BoardSendMode) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.SINGLE_CONNECTION_BOARD_SEND_MODE] = mode.name
        }
    }

    /**
     * Default EXPLICIT: several people can be on this board at once, so every
     * send takes the wall away from whoever is on it. Swiping through a list
     * must not do that — the tap is the point at which the climber says they
     * actually want the wall.
     */
    val multiConnectionBoardSendMode: Flow<BoardSendMode> = dataStore.data.map { prefs ->
        prefs[PreferenceKeys.MULTI_CONNECTION_BOARD_SEND_MODE]?.let(BoardSendMode::fromWire)
            ?: prefs[PreferenceKeys.BOARD_SEND_MODE]?.let(BoardSendMode::fromWire)
            ?: BoardSendMode.EXPLICIT
    }

    suspend fun setMultiConnectionBoardSendMode(mode: BoardSendMode) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.MULTI_CONNECTION_BOARD_SEND_MODE] = mode.name
        }
    }

    /** Physical controller most recently connected successfully, per board family. */
    val lastUsedBoardAddresses: Flow<Map<BoardBrand, String>> = dataStore.data.map { prefs ->
        BoardBrand.entries
            .asSequence()
            .filter { it.isInteractive }
            .mapNotNull { brand ->
                prefs[PreferenceKeys.lastUsedBoardAddress(brand)]?.let { brand to it }
            }
            .toMap()
    }

    suspend fun setLastUsedBoardAddress(brand: BoardBrand, address: String) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.lastUsedBoardAddress(brand)] = address
        }
    }

    /**
     * Last successfully connected physical controller, per board family. A
     * complete descriptor lets Android reconnect by address without a BLE scan,
     * so legacy Android does not need location permission or location services.
     */
    val rememberedBoardControllers: Flow<Map<BoardBrand, RememberedBoardController>> =
        dataStore.data.map { prefs ->
            BoardBrand.entries
                .asSequence()
                .filter { it.isInteractive }
                .mapNotNull { brand ->
                    val address = prefs[PreferenceKeys.lastUsedBoardAddress(brand)]
                        ?.takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    val displayName = prefs[PreferenceKeys.lastUsedBoardDisplayName(brand)]
                        ?.takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    val apiLevel = prefs[PreferenceKeys.lastUsedBoardApiLevel(brand)]
                        ?.takeIf { it >= 0 }
                        ?: return@mapNotNull null
                    brand to RememberedBoardController(
                        displayName = displayName,
                        serial = prefs[PreferenceKeys.lastUsedBoardSerial(brand)].orEmpty(),
                        apiLevel = apiLevel,
                        address = address,
                        boardBrand = brand,
                        advertisesWhileConnected =
                            prefs[PreferenceKeys.lastUsedBoardAdvertisesWhileConnected(brand)],
                    )
                }
                .toMap()
        }

    suspend fun setRememberedBoardController(controller: RememberedBoardController) {
        dataStore.edit { prefs ->
            val brand = controller.boardBrand
            prefs[PreferenceKeys.lastUsedBoardAddress(brand)] = controller.address
            prefs[PreferenceKeys.lastUsedBoardDisplayName(brand)] = controller.displayName
            prefs[PreferenceKeys.lastUsedBoardSerial(brand)] = controller.serial
            prefs[PreferenceKeys.lastUsedBoardApiLevel(brand)] = controller.apiLevel
            // Deliberately never writes false or removes the key: this record is
            // rewritten on every successful connect, and a connect without scan
            // permission carries no observation. Clobbering here would discard a
            // capacity that was verified in an earlier, scan-capable session.
            if (controller.advertisesWhileConnected == true) {
                prefs[PreferenceKeys.lastUsedBoardAdvertisesWhileConnected(brand)] = true
            }
        }
    }

    /**
     * Records a controller-capacity observation for [brand] — positive by
     * default, negative when the probe completed a scan and saw nothing.
     *
     * Both directions are stored. Writing only the positive made "accepts
     * several clients" permanent, so a controller switched back to
     * single-client was misjudged for ever; see the key's own doc.
     *
     * Separate from [setRememberedBoardController] because the observation
     * arrives seconds after the connect that stored the record, from the
     * post-connect advertising probe.
     */
    suspend fun setRememberedBoardAdvertisesWhileConnected(
        brand: BoardBrand,
        observed: Boolean = true,
    ) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.lastUsedBoardAdvertisesWhileConnected(brand)] = observed
        }
    }

    /**
     * Forget what was observed about every controller's capacity.
     *
     * The probe then measures afresh on the next connect. Needed because the
     * stored verdict outlives the hardware it describes — a swapped gym
     * controller, or a board simulator moved between modes, otherwise keeps the
     * old answer for good.
     */
    suspend fun clearBoardCapacityObservations() {
        dataStore.edit { prefs ->
            BoardBrand.entries.forEach {
                prefs.remove(PreferenceKeys.lastUsedBoardAdvertisesWhileConnected(it))
            }
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

    /**
     * One-time 0.2.0 migration: move users sitting on a *previous* CruxCoach
     * default LED preset onto the current default, without disturbing custom
     * colors or the Kilter preset.
     *
     * The current default is never persisted — a fresh install, a never-
     * customised user, and anyone who tapped "reset" all have null LED keys
     * and therefore already render the current [LedHoldColors.CRUXCOACH_*]
     * colors via the [ledHoldColors] fallback. The only way the four keys can
     * be *present and equal an old default* is a user who explicitly set them
     * (e.g. manually dialled the old default back in). For exactly that case
     * we remove the four keys so the row rejoins the null-fallback on the
     * current default — matching every other "I'm on the default" user.
     *
     * Acts only on an exact full-tuple match against
     * [LedHoldColors.LEGACY_CRUXCOACH_DEFAULTS]; partial sets, genuine custom
     * colors, and the Kilter preset are left untouched (all disjoint from the
     * legacy tuples). Guarded by [PreferenceKeys.LED_DEFAULTS_MIGRATED] so it
     * runs exactly once — a post-0.2.0 user who deliberately recreates an old
     * tuple keeps it. Idempotent and safe to call on every cold start.
     */
    suspend fun migrateLegacyLedDefaultsIfNeeded() {
        dataStore.edit { prefs ->
            if (prefs[PreferenceKeys.LED_DEFAULTS_MIGRATED] == true) return@edit
            val start = prefs[PreferenceKeys.LED_COLOR_START]
            val hand = prefs[PreferenceKeys.LED_COLOR_HAND]
            val finish = prefs[PreferenceKeys.LED_COLOR_FINISH]
            val foot = prefs[PreferenceKeys.LED_COLOR_FOOT]
            if (start != null && hand != null && finish != null && foot != null) {
                val stored = LedHoldColors(start = start, hand = hand, finish = finish, foot = foot)
                if (stored in LedHoldColors.LEGACY_CRUXCOACH_DEFAULTS) {
                    prefs.remove(PreferenceKeys.LED_COLOR_START)
                    prefs.remove(PreferenceKeys.LED_COLOR_HAND)
                    prefs.remove(PreferenceKeys.LED_COLOR_FINISH)
                    prefs.remove(PreferenceKeys.LED_COLOR_FOOT)
                }
            }
            prefs[PreferenceKeys.LED_DEFAULTS_MIGRATED] = true
        }
    }

    // Board browser filter persistence
    val boardAngle: Flow<Int> = dataStore.data.map { it[PreferenceKeys.BOARD_ANGLE] ?: 40 }
    val boardMinGrade: Flow<Int> = dataStore.data.map { it[PreferenceKeys.BOARD_MIN_GRADE] ?: 0 }
    val boardMaxGrade: Flow<Int> = dataStore.data.map { it[PreferenceKeys.BOARD_MAX_GRADE] ?: 16 }
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
        ungradedOnly: Boolean = false,
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
            prefs[PreferenceKeys.BOARD_UNGRADED_ONLY] = ungradedOnly
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
    val lastClimbProjectionSurvivesDisconnect: Flow<Boolean> = dataStore.data.map {
        it[PreferenceKeys.LAST_CLIMB_PROJECTION_SURVIVES_DISCONNECT] ?: true
    }

    suspend fun setLastClimb(
        uuid: String,
        angle: Int,
        projectionSurvivesDisconnect: Boolean = true,
    ) {
        dataStore.edit {
            it[PreferenceKeys.LAST_CLIMB_UUID] = uuid
            it[PreferenceKeys.LAST_CLIMB_ANGLE] = angle
            it[PreferenceKeys.LAST_CLIMB_TIMESTAMP] = System.currentTimeMillis()
            it[PreferenceKeys.LAST_CLIMB_PROJECTION_SURVIVES_DISCONNECT] =
                projectionSurvivesDisconnect
        }
    }

    suspend fun clearLastClimb() {
        dataStore.edit {
            it.remove(PreferenceKeys.LAST_CLIMB_UUID)
            it.remove(PreferenceKeys.LAST_CLIMB_ANGLE)
            it.remove(PreferenceKeys.LAST_CLIMB_TIMESTAMP)
            it.remove(PreferenceKeys.LAST_CLIMB_PROJECTION_SURVIVES_DISCONNECT)
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

    // FEAT-044 CruxRelay: one-time sharing disclosure, app-scoped (§12).
    val relayDisclosureSeen: Flow<Boolean> = dataStore.data.map {
        it[PreferenceKeys.RELAY_DISCLOSURE_SEEN] ?: false
    }

    suspend fun setRelayDisclosureSeen() {
        dataStore.edit { it[PreferenceKeys.RELAY_DISCLOSURE_SEEN] = true }
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
