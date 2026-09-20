package com.cruxcoach.app.settings

import com.cruxcoach.app.platform.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DarkModeSetting { SYSTEM, LIGHT, DARK }

enum class SyncInterval { DAILY, WEEKLY, MANUAL }

enum class BoardSendMode { AUTOMATIC, EXPLICIT }

enum class MoonBoardLedMode { BELOW, ABOVE, BOTH }

/** Every setting that has an effect on iOS, read in one pass. */
data class SettingsSnapshot(
    val darkMode: DarkModeSetting = DarkModeSetting.SYSTEM,
    val gradeScale: GradeScale = GradeScale.FRENCH,
    val keepScreenOn: Boolean = false,
    val boardBrand: String = DEFAULT_BOARD_BRAND,
    val boardLayoutId: Int = KILTER_ORIGINAL_LAYOUT,
    val boardProductSizeId: Int = KILTER_DEFAULT_SIZE,
    val singleConnectionSendMode: BoardSendMode = BoardSendMode.AUTOMATIC,
    val multiConnectionSendMode: BoardSendMode = BoardSendMode.EXPLICIT,
    /** 0 = never disconnect automatically. */
    val bleAutoDisconnectSeconds: Int = 0,
    val moonBoardLedMode: MoonBoardLedMode = MoonBoardLedMode.BELOW,
    /** Null = the built-in palette for that role. */
    val ledColorStart: Int? = null,
    val ledColorHand: Int? = null,
    val ledColorFinish: Int? = null,
    val ledColorFoot: Int? = null,
    val restTimerDurationSeconds: Int = 180,
    val restTimerAutoStart: Boolean = false,
    val routeFrameSpeed: Float = 5f,
    val routeUseSetterSpeed: Boolean = true,
    val routeCountdown: Boolean = true,
    val routeCountdownSeconds: Int = 5,
    val routeAutoLoop: Boolean = false,
    val syncInterval: SyncInterval = SyncInterval.MANUAL,
    val historyRetention: HistoryRetention = HistoryRetention.DAYS_30,
    val announcementsEnabled: Boolean = true,
    val announcementReleases: Boolean = true,
    val announcementIssues: Boolean = true,
    val announcementTips: Boolean = true,
    val announcementGeneral: Boolean = true,
    /** Null = the user has never been asked. */
    val crashReportOptIn: Boolean? = null,
    // Key-scoped (per identity); defaults apply to an identity with no history.
    val identityPrefix: String = "",
    val onboardingCompleted: Boolean = false,
    val lastSeenAppVersionCode: Int? = null,
    val keyBackedUp: Boolean = false,
    val autoPublishAscents: Boolean = false,
    val leaderboardDisplayName: String = "",
    val profileHintDismissed: Boolean = false,
    val kilterSyncEnabled: Boolean = false,
    val kilterPushEnabled: Boolean = false,
    val kilterClimbPublishEnabled: Boolean = false,
    val kilterLastSync: String? = null,
)

const val DEFAULT_BOARD_BRAND = "kilter"
const val KILTER_ORIGINAL_LAYOUT = 1
const val KILTER_DEFAULT_SIZE = 10

/**
 * Typed settings over [KeyValueStore], on Android's key names, value spellings
 * and defaults, so a backup written by either app restores into the other.
 *
 * DataStore is typed while [KeyValueStore] holds strings: ints and longs are
 * decimal text, booleans "true"/"false", floats the Kotlin `toString()` form
 * and enums their `name`. Unparseable values fall back to the default rather
 * than throwing — a corrupt preference must not break the app.
 *
 * Per-identity settings live in a separate DataStore file on Android
 * (`cruxcoach_prefs_<pubkey[0..16]>`). Here they share one store and carry the
 * same 16-hex prefix in the key: `key/<prefix>/<name>`.
 */
class SettingsStore(
    private val store: KeyValueStore,
    identityPubkeyHex: String = "",
) {
    private var identityPrefix: String = identityPubkeyHex.take(IDENTITY_PREFIX_LENGTH)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsSnapshot> = _state.asStateFlow()

    /** Swift observes this; every setter refreshes it. */
    val snapshot: SettingsSnapshot get() = _state.value

    /** Switches the identity whose scoped settings are read and written. */
    fun setIdentity(pubkeyHex: String) {
        identityPrefix = pubkeyHex.take(IDENTITY_PREFIX_LENGTH)
        refresh()
    }

    fun refresh() {
        _state.value = read()
    }

    // --- Global ---

    fun setDarkMode(value: DarkModeSetting) = put(DARK_MODE, value.name)
    fun setGradeScale(value: GradeScale) = put(SettingsKeys.GRADE_SCALE, value.name)
    fun setKeepScreenOn(value: Boolean) = put(KEEP_SCREEN_ON, value.toString())

    fun setBoard(brand: String, layoutId: Int, productSizeId: Int) {
        store.safePut(BOARD_BRAND, brand)
        store.safePut(BOARD_LAYOUT_ID, layoutId.toString())
        store.safePut(BOARD_PRODUCT_SIZE_ID, productSizeId.toString())
        refresh()
    }

    fun setSingleConnectionSendMode(value: BoardSendMode) = put(SINGLE_CONNECTION_SEND_MODE, value.name)
    fun setMultiConnectionSendMode(value: BoardSendMode) = put(MULTI_CONNECTION_SEND_MODE, value.name)

    fun setBleAutoDisconnectSeconds(seconds: Int) = put(BLE_AUTO_DISCONNECT_SECONDS, seconds.coerceAtLeast(0).toString())

    fun setMoonBoardLedMode(value: MoonBoardLedMode) {
        store.safePut(MOONBOARD_LED_MODE, value.name)
        // Android drops the superseded boolean on write; keep the store equivalent.
        store.safePut(MOONBOARD_LEDS_ABOVE_HOLDS, null)
        refresh()
    }

    /** Null restores that role's built-in colour. */
    fun setLedColor(role: LedRole, color: Int?) = put(
        when (role) {
            LedRole.START -> LED_COLOR_START
            LedRole.HAND -> LED_COLOR_HAND
            LedRole.FINISH -> LED_COLOR_FINISH
            LedRole.FOOT -> LED_COLOR_FOOT
        },
        color?.toString(),
    )

    fun setRestTimerDurationSeconds(seconds: Int) =
        put(REST_TIMER_DURATION_SECONDS, seconds.coerceIn(0, MAX_REST_SECONDS).toString())

    fun setRestTimerAutoStart(value: Boolean) = put(REST_TIMER_AUTO_START, value.toString())

    fun setRouteFrameSpeed(seconds: Float) = put(ROUTE_FRAME_SPEED, seconds.toString())
    fun setRouteUseSetterSpeed(value: Boolean) = put(ROUTE_USE_SETTER_SPEED, value.toString())
    fun setRouteCountdown(value: Boolean) = put(ROUTE_COUNTDOWN, value.toString())
    fun setRouteCountdownSeconds(seconds: Int) = put(ROUTE_COUNTDOWN_SECONDS, seconds.coerceAtLeast(0).toString())
    fun setRouteAutoLoop(value: Boolean) = put(ROUTE_AUTO_LOOP, value.toString())

    fun setSyncInterval(value: SyncInterval) = put(SYNC_INTERVAL, value.name)

    fun setHistoryRetention(value: HistoryRetention) =
        put(SettingsKeys.CLIMB_HISTORY_RETENTION_DAYS, value.days.toString())

    fun setAnnouncementsEnabled(value: Boolean) = put(ANNOUNCEMENTS_ENABLED, value.toString())
    fun setAnnouncementReleases(value: Boolean) = put(ANNOUNCEMENT_CAT_RELEASE, value.toString())
    fun setAnnouncementIssues(value: Boolean) = put(ANNOUNCEMENT_CAT_ISSUE, value.toString())
    fun setAnnouncementTips(value: Boolean) = put(ANNOUNCEMENT_CAT_TIP, value.toString())
    fun setAnnouncementGeneral(value: Boolean) = put(ANNOUNCEMENT_CAT_GENERAL, value.toString())

    fun setCrashReportOptIn(value: Boolean) = put(CRASH_REPORT_OPT_IN, value.toString())

    // --- Key-scoped ---

    fun setOnboardingCompleted(value: Boolean) = putScoped(ONBOARDING_COMPLETED, value.toString())
    fun setLastSeenAppVersionCode(code: Int) = putScoped(LAST_SEEN_APP_VERSION_CODE, code.toString())
    fun setKeyBackedUp(value: Boolean) = putScoped(KEY_BACKED_UP, value.toString())
    fun setAutoPublishAscents(value: Boolean) = putScoped(AUTO_PUBLISH_ASCENTS, value.toString())
    fun setLeaderboardDisplayName(name: String) = putScoped(LEADERBOARD_DISPLAY_NAME, name)
    fun setProfileHintDismissed(value: Boolean) = putScoped(PROFILE_HINT_DISMISSED, value.toString())
    fun setKilterSyncEnabled(value: Boolean) = putScoped(KILTER_SYNC_ENABLED, value.toString())
    fun setKilterPushEnabled(value: Boolean) = putScoped(KILTER_PUSH_ENABLED, value.toString())
    fun setKilterClimbPublishEnabled(value: Boolean) = putScoped(KILTER_CLIMB_PUBLISH_ENABLED, value.toString())
    fun setKilterLastSync(timestamp: String?) = putScoped(KILTER_LAST_SYNC, timestamp)

    /** The store key a per-identity setting occupies. */
    fun scopedKey(name: String): String =
        if (identityPrefix.isEmpty()) name else "$SCOPE_NAMESPACE/$identityPrefix/$name"

    private fun put(key: String, value: String?) {
        store.safePut(key, value)
        refresh()
    }

    private fun putScoped(key: String, value: String?) = put(scopedKey(key), value)

    private fun read(): SettingsSnapshot = SettingsSnapshot(
        darkMode = enumOr(DARK_MODE, DarkModeSetting.entries, DarkModeSetting.SYSTEM),
        gradeScale = store.readGradeScale(),
        keepScreenOn = boolOr(KEEP_SCREEN_ON, false),
        boardBrand = string(BOARD_BRAND) ?: DEFAULT_BOARD_BRAND,
        boardLayoutId = intOr(BOARD_LAYOUT_ID, KILTER_ORIGINAL_LAYOUT),
        boardProductSizeId = intOr(BOARD_PRODUCT_SIZE_ID, KILTER_DEFAULT_SIZE),
        // A per-capacity choice wins, then the legacy single key, then the default.
        singleConnectionSendMode = sendMode(SINGLE_CONNECTION_SEND_MODE, BoardSendMode.AUTOMATIC),
        multiConnectionSendMode = sendMode(MULTI_CONNECTION_SEND_MODE, BoardSendMode.EXPLICIT),
        bleAutoDisconnectSeconds = int(BLE_AUTO_DISCONNECT_SECONDS)
            ?: int(BLE_AUTO_DISCONNECT_MINUTES)?.times(60)
            ?: 0,
        moonBoardLedMode = MoonBoardLedMode.entries.firstOrNull { it.name == string(MOONBOARD_LED_MODE) }
            ?: if (boolOr(MOONBOARD_LEDS_ABOVE_HOLDS, false)) MoonBoardLedMode.BOTH else MoonBoardLedMode.BELOW,
        ledColorStart = int(LED_COLOR_START),
        ledColorHand = int(LED_COLOR_HAND),
        ledColorFinish = int(LED_COLOR_FINISH),
        ledColorFoot = int(LED_COLOR_FOOT),
        restTimerDurationSeconds = intOr(REST_TIMER_DURATION_SECONDS, 180),
        restTimerAutoStart = boolOr(REST_TIMER_AUTO_START, false),
        routeFrameSpeed = string(ROUTE_FRAME_SPEED)?.toFloatOrNull() ?: 5f,
        routeUseSetterSpeed = boolOr(ROUTE_USE_SETTER_SPEED, true),
        routeCountdown = boolOr(ROUTE_COUNTDOWN, true),
        routeCountdownSeconds = intOr(ROUTE_COUNTDOWN_SECONDS, 5),
        routeAutoLoop = boolOr(ROUTE_AUTO_LOOP, false),
        syncInterval = enumOr(SYNC_INTERVAL, SyncInterval.entries, SyncInterval.MANUAL),
        historyRetention = store.readHistoryRetention(),
        announcementsEnabled = boolOr(ANNOUNCEMENTS_ENABLED, true),
        announcementReleases = boolOr(ANNOUNCEMENT_CAT_RELEASE, true),
        announcementIssues = boolOr(ANNOUNCEMENT_CAT_ISSUE, true),
        announcementTips = boolOr(ANNOUNCEMENT_CAT_TIP, true),
        announcementGeneral = boolOr(ANNOUNCEMENT_CAT_GENERAL, true),
        crashReportOptIn = bool(CRASH_REPORT_OPT_IN),
        identityPrefix = identityPrefix,
        onboardingCompleted = scopedBool(ONBOARDING_COMPLETED) ?: false,
        lastSeenAppVersionCode = scopedString(LAST_SEEN_APP_VERSION_CODE)?.toIntOrNull(),
        keyBackedUp = scopedBool(KEY_BACKED_UP) ?: false,
        autoPublishAscents = scopedBool(AUTO_PUBLISH_ASCENTS) ?: false,
        leaderboardDisplayName = scopedString(LEADERBOARD_DISPLAY_NAME) ?: "",
        profileHintDismissed = scopedBool(PROFILE_HINT_DISMISSED) ?: false,
        kilterSyncEnabled = scopedBool(KILTER_SYNC_ENABLED) ?: false,
        kilterPushEnabled = scopedBool(KILTER_PUSH_ENABLED) ?: false,
        kilterClimbPublishEnabled = scopedBool(KILTER_CLIMB_PUBLISH_ENABLED) ?: false,
        kilterLastSync = scopedString(KILTER_LAST_SYNC),
    )

    private fun sendMode(key: String, default: BoardSendMode): BoardSendMode =
        BoardSendMode.entries.firstOrNull { it.name == string(key) }
            ?: BoardSendMode.entries.firstOrNull { it.name == string(BOARD_SEND_MODE) }
            ?: default

    private fun string(key: String): String? = store.safeGet(key)?.takeIf { it.isNotEmpty() }
    private fun int(key: String): Int? = string(key)?.toIntOrNull()
    private fun intOr(key: String, default: Int): Int = int(key) ?: default
    private fun bool(key: String): Boolean? = when (string(key)) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun boolOr(key: String, default: Boolean): Boolean = bool(key) ?: default
    private fun scopedString(key: String): String? = string(scopedKey(key))
    private fun scopedBool(key: String): Boolean? = bool(scopedKey(key))

    private fun <T : Enum<T>> enumOr(key: String, values: List<T>, default: T): T =
        values.firstOrNull { it.name == string(key) } ?: default

    enum class LedRole { START, HAND, FINISH, FOOT }

    companion object {
        const val IDENTITY_PREFIX_LENGTH = 16
        const val SCOPE_NAMESPACE = "key"
        const val MAX_REST_SECONDS = 3600

        const val DARK_MODE = "dark_mode"
        const val KEEP_SCREEN_ON = "keep_screen_on"
        const val BOARD_BRAND = "board_brand"
        const val BOARD_LAYOUT_ID = "board_layout_id"
        const val BOARD_PRODUCT_SIZE_ID = "board_product_size_id"
        const val BOARD_SEND_MODE = "board_send_mode"
        const val SINGLE_CONNECTION_SEND_MODE = "single_connection_board_send_mode"
        const val MULTI_CONNECTION_SEND_MODE = "multi_connection_board_send_mode"
        const val BLE_AUTO_DISCONNECT_SECONDS = "ble_auto_disconnect_seconds"
        const val BLE_AUTO_DISCONNECT_MINUTES = "ble_auto_disconnect_minutes"
        const val MOONBOARD_LED_MODE = "moonboard_led_mode"
        const val MOONBOARD_LEDS_ABOVE_HOLDS = "moonboard_leds_above_holds"
        const val LED_COLOR_START = "led_color_start"
        const val LED_COLOR_HAND = "led_color_hand"
        const val LED_COLOR_FINISH = "led_color_finish"
        const val LED_COLOR_FOOT = "led_color_foot"
        const val REST_TIMER_DURATION_SECONDS = "rest_timer_duration_seconds"
        const val REST_TIMER_AUTO_START = "rest_timer_auto_start"
        const val ROUTE_FRAME_SPEED = "route_frame_speed_f"
        const val ROUTE_USE_SETTER_SPEED = "route_use_setter_speed"
        const val ROUTE_COUNTDOWN = "route_countdown"
        const val ROUTE_COUNTDOWN_SECONDS = "route_countdown_seconds"
        const val ROUTE_AUTO_LOOP = "route_auto_loop"
        const val SYNC_INTERVAL = "sync_interval"
        const val ANNOUNCEMENTS_ENABLED = "announcements_enabled"
        const val ANNOUNCEMENT_CAT_RELEASE = "announcement_cat_release"
        const val ANNOUNCEMENT_CAT_ISSUE = "announcement_cat_issue"
        const val ANNOUNCEMENT_CAT_TIP = "announcement_cat_tip"
        const val ANNOUNCEMENT_CAT_GENERAL = "announcement_cat_general"
        const val CRASH_REPORT_OPT_IN = "crash_report_opt_in"

        // Key-scoped (per identity).
        const val ONBOARDING_COMPLETED = "onboarding_completed"
        const val LAST_SEEN_APP_VERSION_CODE = "last_seen_app_version_code"
        const val KEY_BACKED_UP = "key_backed_up"
        const val AUTO_PUBLISH_ASCENTS = "auto_publish_ascents"
        const val LEADERBOARD_DISPLAY_NAME = "leaderboard_display_name"
        const val PROFILE_HINT_DISMISSED = "profile_hint_dismissed"
        const val KILTER_SYNC_ENABLED = "kilter_sync_enabled"
        const val KILTER_PUSH_ENABLED = "kilter_push_enabled"
        const val KILTER_CLIMB_PUBLISH_ENABLED = "kilter_climb_publish_enabled"
        const val KILTER_LAST_SYNC = "kilter_last_sync"
    }
}
