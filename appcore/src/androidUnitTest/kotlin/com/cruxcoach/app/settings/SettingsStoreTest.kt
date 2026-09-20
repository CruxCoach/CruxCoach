package com.cruxcoach.app.settings

import com.cruxcoach.app.logbook.MapKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsStoreTest {

    @Test
    fun `defaults match the Android preference defaults`() {
        val s = SettingsStore(MapKeyValueStore()).snapshot
        assertEquals(DarkModeSetting.SYSTEM, s.darkMode)
        assertEquals(GradeScale.FRENCH, s.gradeScale)
        assertEquals("kilter", s.boardBrand)
        assertEquals(1, s.boardLayoutId)
        assertEquals(10, s.boardProductSizeId)
        assertEquals(BoardSendMode.AUTOMATIC, s.singleConnectionSendMode)
        assertEquals(BoardSendMode.EXPLICIT, s.multiConnectionSendMode)
        assertEquals(0, s.bleAutoDisconnectSeconds)
        assertEquals(MoonBoardLedMode.BELOW, s.moonBoardLedMode)
        assertEquals(180, s.restTimerDurationSeconds)
        assertEquals(false, s.restTimerAutoStart)
        assertEquals(5f, s.routeFrameSpeed)
        assertTrue(s.routeUseSetterSpeed)
        assertTrue(s.routeCountdown)
        assertEquals(5, s.routeCountdownSeconds)
        assertEquals(SyncInterval.MANUAL, s.syncInterval)
        assertEquals(HistoryRetention.DAYS_30, s.historyRetention)
        assertTrue(s.announcementsEnabled && s.announcementTips)
        // Never asked is distinct from "declined".
        assertNull(s.crashReportOptIn)
        assertNull(s.ledColorStart)
        assertEquals(false, s.onboardingCompleted)
        assertNull(s.lastSeenAppVersionCode)
    }

    @Test
    fun `values use the Android key names and spellings`() {
        val kv = MapKeyValueStore()
        val store = SettingsStore(kv)
        store.setDarkMode(DarkModeSetting.DARK)
        store.setGradeScale(GradeScale.V_SCALE)
        store.setKeepScreenOn(true)
        store.setBoard("tension", 8, 17)
        store.setBleAutoDisconnectSeconds(90)
        store.setRestTimerDurationSeconds(99_999)
        store.setHistoryRetention(HistoryRetention.DAYS_365)
        store.setSyncInterval(SyncInterval.WEEKLY)
        store.setLedColor(SettingsStore.LedRole.HAND, 255)
        store.setCrashReportOptIn(false)

        assertEquals("DARK", kv.map["dark_mode"])
        assertEquals("V_SCALE", kv.map["grade_scale"])
        assertEquals("true", kv.map["keep_screen_on"])
        assertEquals("tension", kv.map["board_brand"])
        assertEquals("8", kv.map["board_layout_id"])
        assertEquals("17", kv.map["board_product_size_id"])
        assertEquals("90", kv.map["ble_auto_disconnect_seconds"])
        assertEquals("3600", kv.map["rest_timer_duration_seconds"])
        assertEquals("365", kv.map["climb_history_retention_days"])
        assertEquals("WEEKLY", kv.map["sync_interval"])
        assertEquals("255", kv.map["led_color_hand"])
        assertEquals("false", kv.map["crash_report_opt_in"])

        val s = store.snapshot
        assertEquals(DarkModeSetting.DARK, s.darkMode)
        assertEquals(3600, s.restTimerDurationSeconds)
        assertEquals(255, s.ledColorHand)
        assertEquals(false, s.crashReportOptIn)

        store.setLedColor(SettingsStore.LedRole.HAND, null)
        assertNull(store.snapshot.ledColorHand)
        assertTrue(!kv.map.containsKey("led_color_hand"))
    }

    @Test
    fun `legacy keys still decide when the current one is absent`() {
        val legacy = SettingsStore(
            MapKeyValueStore(
                mapOf(
                    "board_send_mode" to "EXPLICIT",
                    "ble_auto_disconnect_minutes" to "5",
                    "moonboard_leds_above_holds" to "true",
                )
            )
        ).snapshot
        assertEquals(BoardSendMode.EXPLICIT, legacy.singleConnectionSendMode)
        assertEquals(BoardSendMode.EXPLICIT, legacy.multiConnectionSendMode)
        assertEquals(300, legacy.bleAutoDisconnectSeconds)
        assertEquals(MoonBoardLedMode.BOTH, legacy.moonBoardLedMode)

        // A per-capacity choice beats the legacy key.
        val explicitSingle = SettingsStore(
            MapKeyValueStore(
                mapOf("board_send_mode" to "EXPLICIT", "single_connection_board_send_mode" to "AUTOMATIC")
            )
        ).snapshot
        assertEquals(BoardSendMode.AUTOMATIC, explicitSingle.singleConnectionSendMode)
        assertEquals(BoardSendMode.EXPLICIT, explicitSingle.multiConnectionSendMode)

        // Writing the mode drops the superseded boolean, as Android does.
        val kv = MapKeyValueStore(mapOf("moonboard_leds_above_holds" to "true"))
        SettingsStore(kv).setMoonBoardLedMode(MoonBoardLedMode.ABOVE)
        assertEquals("ABOVE", kv.map["moonboard_led_mode"])
        assertTrue(!kv.map.containsKey("moonboard_leds_above_holds"))
    }

    @Test
    fun `corrupt values fall back instead of throwing`() {
        val s = SettingsStore(
            MapKeyValueStore(
                mapOf(
                    "dark_mode" to "PURPLE",
                    "grade_scale" to "",
                    "board_layout_id" to "not-a-number",
                    "route_frame_speed_f" to "fast",
                    "climb_history_retention_days" to "7",
                    "keep_screen_on" to "yes",
                )
            )
        ).snapshot
        assertEquals(DarkModeSetting.SYSTEM, s.darkMode)
        assertEquals(GradeScale.FRENCH, s.gradeScale)
        assertEquals(1, s.boardLayoutId)
        assertEquals(5f, s.routeFrameSpeed)
        // An unknown retention window is not one of the offered ones.
        assertEquals(HistoryRetention.DAYS_30, s.historyRetention)
        assertEquals(false, s.keepScreenOn)
    }

    @Test
    fun `per-identity settings are scoped by the pubkey prefix`() {
        val kv = MapKeyValueStore()
        val alice = "a".repeat(64)
        val bob = "b".repeat(64)
        val store = SettingsStore(kv, alice)
        store.setOnboardingCompleted(true)
        store.setKeyBackedUp(true)
        store.setLastSeenAppVersionCode(223)
        store.setKilterSyncEnabled(true)
        store.setLeaderboardDisplayName("Alice")

        assertEquals("true", kv.map["key/${"a".repeat(16)}/onboarding_completed"])
        assertEquals("223", kv.map["key/${"a".repeat(16)}/last_seen_app_version_code"])
        assertTrue(!kv.map.containsKey("onboarding_completed"))
        assertEquals("Alice", store.snapshot.leaderboardDisplayName)
        assertEquals("a".repeat(16), store.snapshot.identityPrefix)

        store.setIdentity(bob)
        val fresh = store.snapshot
        assertEquals(false, fresh.onboardingCompleted)
        assertEquals(false, fresh.keyBackedUp)
        assertNull(fresh.lastSeenAppVersionCode)
        assertEquals("", fresh.leaderboardDisplayName)
        assertEquals(false, fresh.kilterSyncEnabled)

        store.setIdentity(alice)
        assertTrue(store.snapshot.onboardingCompleted)
        assertTrue(store.snapshot.kilterSyncEnabled)
    }

    @Test
    fun `state flow publishes every change`() {
        val store = SettingsStore(MapKeyValueStore())
        val seen = mutableListOf<DarkModeSetting>()
        seen.add(store.state.value.darkMode)
        store.setDarkMode(DarkModeSetting.LIGHT)
        seen.add(store.state.value.darkMode)
        store.setDarkMode(DarkModeSetting.DARK)
        seen.add(store.state.value.darkMode)
        assertEquals(listOf(DarkModeSetting.SYSTEM, DarkModeSetting.LIGHT, DarkModeSetting.DARK), seen)
    }
}
