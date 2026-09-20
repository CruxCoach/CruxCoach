package com.cruxcoach.app.notify

import com.cruxcoach.app.logbook.MapKeyValueStore
import com.cruxcoach.app.settings.SettingsStore
import com.cruxcoach.app.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class RecordingScheduler : NotificationScheduler {
    class Request(val id: String, val afterSeconds: Int, val titleKey: String, val bodyKey: String)

    val scheduled = mutableListOf<Request>()
    val cancelled = mutableListOf<String>()
    var granted = true

    override fun schedule(id: String, afterSeconds: Int, titleKey: String, bodyKey: String) {
        scheduled += Request(id, afterSeconds, titleKey, bodyKey)
    }

    override fun cancel(id: String) {
        cancelled += id
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) = onResult(granted)
}

class NotifyRestTimerTest {
    private val kv = MapKeyValueStore()
    private val settings = SettingsStore(kv)
    private val clock = FixedClock(1_000)
    private val scheduler = RecordingScheduler()
    private val notifier = RestTimerNotifier(scheduler, settings, clock)

    @Test
    fun `a rest schedules one notification for its end instant`() {
        notifier.restChanged(1_180)

        val request = scheduler.scheduled.single()
        assertEquals(RestTimerNotifier.REST_ID, request.id)
        assertEquals(180, request.afterSeconds)
        assertEquals("notification_rest_timer_title", request.titleKey)
        assertEquals("notification_rest_timer_message", request.bodyKey)
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test
    fun `repeating the same end instant does not re-schedule`() {
        notifier.restChanged(1_180)
        // The player emits state every tick; each emission carries the same end.
        repeat(5) {
            clock.seconds += 1
            notifier.restChanged(1_180)
        }
        assertEquals(1, scheduler.scheduled.size)
        // A re-armed rest with a new end does replace the pending request.
        notifier.restChanged(1_400)
        assertEquals(2, scheduler.scheduled.size)
        assertEquals(1_400 - clock.seconds, scheduler.scheduled.last().afterSeconds.toLong())
    }

    @Test
    fun `leaving the rest cancels the pending notification exactly once`() {
        notifier.restChanged(1_180)
        notifier.restChanged(0)
        assertEquals(listOf(RestTimerNotifier.REST_ID), scheduler.cancelled)
        // Nothing pending, so climbing state changes stay silent.
        notifier.restChanged(0)
        notifier.cancel()
        assertEquals(1, scheduler.cancelled.size)
    }

    @Test
    fun `an end instant that has passed is never scheduled`() {
        notifier.restChanged(1_000)
        notifier.restChanged(900)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `a rest that expired while the app was suspended cancels rather than fires late`() {
        notifier.restChanged(1_180)
        assertEquals(1, scheduler.scheduled.size)
        // The app comes back after the rest was already over.
        clock.seconds = 1_500
        notifier.restChanged(1_180)
        assertEquals(1, scheduler.scheduled.size)
        assertEquals(listOf(RestTimerNotifier.REST_ID), scheduler.cancelled)
    }

    @Test
    fun `auto-start answers the Android rest-timer preference`() {
        assertEquals(0, notifier.autoStartSeconds())

        settings.setRestTimerAutoStart(true)
        assertEquals(180, notifier.autoStartSeconds())

        settings.setRestTimerDurationSeconds(90)
        assertEquals(90, notifier.autoStartSeconds())
        assertEquals("90", kv.map["rest_timer_duration_seconds"])

        settings.setRestTimerAutoStart(false)
        assertEquals(0, notifier.autoStartSeconds())
    }

    @Test
    fun `permission is a value, never an exception`() {
        assertFalse(notifier.permitted)
        var seen: Boolean? = null
        notifier.requestPermission { seen = it }
        assertEquals(true, seen)
        assertTrue(notifier.permitted)

        scheduler.granted = false
        notifier.requestPermission { seen = it }
        assertEquals(false, seen)
        assertFalse(notifier.permitted)
    }

    @Test
    fun `a host without notifications is inert`() {
        val none = NoNotifications()
        var seen: Boolean? = null
        none.requestPermission { seen = it }
        assertEquals(false, seen)
        none.schedule("x", 10, "t", "b")
        none.cancel("x")
    }
}
