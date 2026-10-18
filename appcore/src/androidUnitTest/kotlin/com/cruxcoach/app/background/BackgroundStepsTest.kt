package com.cruxcoach.app.background

import com.cruxcoach.app.AppConfig
import com.cruxcoach.app.kilter.KilterUploadRetryScheduler
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.notify.NotificationRoutes
import com.cruxcoach.app.notify.NotificationScheduler
import com.cruxcoach.app.ui.TrText
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.community.ClimbEditorState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Posted(val id: String, val title: TrText, val body: TrText, val route: String)

private class RecordingNotifications : NotificationScheduler {
    val posted = mutableListOf<Posted>()
    override fun schedule(id: String, afterSeconds: Int, titleKey: String, bodyKey: String) { error("plain schedule unused") }
    override fun scheduleText(id: String, afterSeconds: Int, title: TrText, body: TrText, route: String) {
        posted.add(Posted(id, title, body, route))
    }
    override fun cancel(id: String) = Unit
    override fun requestPermission(onResult: (Boolean) -> Unit) = onResult(true)
}

private class FakeNotes : MaintainerNotes {
    var events: List<NostrEvent> = emptyList()
    val asked = mutableListOf<Long>()
    override suspend fun since(sinceSeconds: Long): List<NostrEvent> {
        asked.add(sinceSeconds)
        return events.filter { it.createdAt >= sinceSeconds }
    }
}

private class FakeInbox : AnnouncementInbox {
    val rows = LinkedHashMap<String, StoredAnnouncement>()
    val createdAt = HashMap<String, Long>()
    override fun contains(id: String) = rows.containsKey(id)
    override fun insert(id: String, content: String, category: String, priority: String, createdAtSeconds: Long) {
        rows[id] = StoredAnnouncement(id, content, category)
        createdAt[id] = createdAtSeconds
    }
    override fun all(): List<StoredAnnouncement> = rows.values.toList()
}

/** The relay side of the maintainer inbox: what a fetch since a time would store. */
private class FakeReplies : DevReplies {
    val onRelays = mutableListOf<Pair<Long, ReceivedReply>>()
    val stored = LinkedHashMap<String, ReceivedReply>()
    val asked = mutableListOf<Long>()
    override suspend fun fetch(sinceSeconds: Long) {
        asked.add(sinceSeconds)
        for ((at, reply) in onRelays) if (at >= sinceSeconds) stored.putIfAbsent(reply.id, reply)
    }
    override fun received(): List<ReceivedReply> = stored.values.toList()
}

private fun note(id: String, createdAt: Long, content: String, category: String?, pubkey: String = AppConfig.MAINTAINER_PUBKEY, labelled: Boolean = true) =
    NostrEvent(
        id = id, pubkey = pubkey, createdAt = createdAt, kind = 1,
        tags = buildList {
            if (labelled) add(listOf("L", AppConfig.ANNOUNCE_NAMESPACE))
            if (category != null) add(listOf("l", category, AppConfig.ANNOUNCE_NAMESPACE))
        },
        content = content, sig = "00",
    )

class NotificationPollStepTest {
    private val keyValues = MemoryKeyValues()
    private val notes = FakeNotes()
    private val inbox = FakeInbox()
    private val replies = FakeReplies()
    private val notifications = RecordingNotifications()
    private var switches = AnnouncementSwitches(enabled = true, release = true, issue = true, tip = true, general = true)
    private var nowMs = 1_800_000_000_000L
    private val nowS get() = nowMs / 1000

    private val step = NotificationPollStep(
        notes, inbox, replies, notifications, keyValues, { switches }, { "de" },
        AppConfig.MAINTAINER_PUBKEY, "abcd", { nowMs },
    )

    @Test
    fun `announcements switched off ask no relay at all`() = runTest {
        switches = AnnouncementSwitches(enabled = false, release = true, issue = true, tip = true, general = true)
        notes.events = listOf(note("a1", nowS - 10, "x", "release"))
        step.run()
        assertTrue(notes.asked.isEmpty(), "the switch is the privacy boundary for unattended traffic")
        assertTrue(inbox.rows.isEmpty())
        assertTrue(notifications.posted.isEmpty())
        // The reply leg is not behind that switch (Android polls DMs regardless).
        assertEquals(1, replies.asked.size)
    }

    @Test
    fun `a new announcement is stored and notified in the app language`() = runTest {
        notes.events = listOf(note("a1", nowS - 100, "🇬🇧 New version\n\n🇩🇪 Neue Version", "release"))
        val result = step.run()

        assertTrue(result.ok)
        assertEquals(1, result.notified)
        assertEquals(nowS - NotificationPollStep.DEFAULT_ANNOUNCEMENT_WINDOW_S, notes.asked.single(), "first check: the last day")
        assertEquals(nowS - 100, inbox.createdAt["a1"], "seconds, as the iOS inbox shows them")
        val posted = notifications.posted.single()
        assertEquals("cc-announcement-a1", posted.id)
        assertEquals("notification_announcement_release", posted.title.key)
        assertEquals("Neue Version", posted.body.args.single().text)
        assertEquals(NotificationRoutes.ANNOUNCEMENTS, posted.route)
        assertEquals((nowS - 100).toString(), keyValues.getString(NotificationPollStep.KEY_LAST_ANNOUNCEMENT_CHECK))
    }

    @Test
    fun `nothing new posts no notification and the check moves on`() = runTest {
        notes.events = listOf(note("a1", nowS - 100, "tip", "tip"))
        step.run()
        assertEquals(1, notifications.posted.size)

        nowMs += 20 * 60_000L
        val again = step.run()
        assertEquals(0, again.notified)
        assertEquals(1, notifications.posted.size, "an announcement already stored is never notified again")
        assertEquals(nowS - 20 * 60 - 100, notes.asked.last(), "the next check starts at the newest seen")
    }

    @Test
    fun `only the maintainer's labelled notes count`() = runTest {
        notes.events = listOf(
            note("other", nowS - 50, "fake", "release", pubkey = "f".repeat(64)),
            note("plain", nowS - 40, "just a note", null, labelled = false),
        )
        step.run()
        assertTrue(inbox.rows.isEmpty())
        assertTrue(notifications.posted.isEmpty())
    }

    @Test
    fun `a category switched off is stored but not notified`() = runTest {
        switches = AnnouncementSwitches(enabled = true, release = true, issue = true, tip = false, general = true)
        notes.events = listOf(note("t1", nowS - 30, "tip", "tip"), note("g1", nowS - 20, "hello", null))
        step.run()
        assertEquals(setOf("t1", "g1"), inbox.rows.keys)
        val posted = notifications.posted.single()
        assertEquals("cc-announcement-g1", posted.id)
        assertEquals("", posted.title.key, "a general announcement is titled with the app name")
        assertEquals("CruxCoach", posted.title.args.single().text)
    }

    @Test
    fun `the first reply poll is a silent baseline and later replies notify`() = runTest {
        replies.onRelays.add(nowS - 30L * 86_400 to ReceivedReply("old", "chat", "an old answer"))
        val first = step.run()
        assertEquals(0, first.notified)
        assertTrue(replies.stored.containsKey("old"), "the history is still stored")
        assertEquals(nowS - NotificationPollStep.INITIAL_REPLY_WINDOW_S, replies.asked.single(), "a year back at most")
        assertTrue(notifications.posted.isEmpty())

        nowMs += 20 * 60_000L
        val long = "x".repeat(150)
        replies.onRelays.add(nowS - 5 to ReceivedReply("r1", "bug-report", long))
        val second = step.run()
        assertEquals(1, second.notified)
        val posted = notifications.posted.single()
        assertEquals("cc-reply-r1", posted.id)
        assertEquals("notification_reply_bug", posted.title.key)
        assertEquals(100, posted.body.args.single().text.length)
        assertEquals(NotificationRoutes.DEV_CHAT, posted.route)
        // Since: the last poll's start minus 60 s minus the NIP-59 two-day window.
        val firstStart = nowS - 20 * 60
        assertEquals(firstStart - NotificationPollStep.CURSOR_SAFETY_S - NotificationPollStep.NIP59_RANDOM_WINDOW_S, replies.asked.last())

        nowMs += 20 * 60_000L
        assertEquals(0, step.run().notified, "a reply already notified stays quiet")
    }

    @Test
    fun `the reply cursor belongs to one identity`() = runTest {
        step.run()
        val other = NotificationPollStep(
            notes, inbox, replies, notifications, keyValues, { switches }, { "en" },
            AppConfig.MAINTAINER_PUBKEY, "ffff", { nowMs },
        )
        replies.onRelays.add(nowS - 5 to ReceivedReply("r1", "chat", "hi"))
        assertEquals(0, other.run().notified, "a new identity starts with its own silent baseline")
    }
}

class KilterFollowUpTest {
    private val keyValues = MemoryKeyValues()
    private var nowMs = 5_000_000L
    private val store = KilterFollowUpStore(keyValues, "id1", { nowMs })
    private val timers = mutableListOf<String>()
    private val inner = object : KilterUploadRetryScheduler {
        override fun schedule(attempt: Int, replace: Boolean) { timers.add("schedule:$attempt:$replace") }
        override fun wakeAfter(delayMs: Long) { timers.add("wake:$delayMs") }
        override fun cancel() { timers.add("cancel") }
    }
    private val schedule = PersistentKilterRetrySchedule(inner, store)

    @Test
    fun `a follow-up is kept on disk beside the timer and runs once when due`() = runTest {
        var signedIn = true
        val uploads = mutableListOf<Int>()
        val step = KilterFollowUpStep(store, { signedIn }, { uploads.add(it) }, { nowMs })

        schedule.schedule(1, replace = false)
        assertEquals(listOf("schedule:1:false"), timers, "the in-process timer still runs")
        assertFalse(step.enabled(BackgroundRefresh.TRIGGER_BACKGROUND), "2 minutes have not passed")

        nowMs += 2 * 60_000L
        assertTrue(step.enabled(BackgroundRefresh.TRIGGER_BACKGROUND))
        signedIn = false
        assertFalse(step.enabled(BackgroundRefresh.TRIGGER_BACKGROUND), "no Kilter session, nothing to continue")
        signedIn = true

        step.run()
        assertEquals(listOf(1), uploads)
        assertEquals(-1, store.dueAttempt(nowMs), "taken off disk: a chain that ran out is not restarted")
    }

    @Test
    fun `a chain under way is kept unless the chain itself moves it on`() {
        schedule.schedule(2, replace = true)
        nowMs += 1_000
        schedule.schedule(1, replace = false)
        nowMs += 5 * 60_000L
        assertEquals(2, store.dueAttempt(nowMs))

        schedule.schedule(3, replace = true)
        assertEquals(-1, store.dueAttempt(nowMs), "attempt 3 waits 15 minutes")
        nowMs += 15 * 60_000L
        assertEquals(3, store.dueAttempt(nowMs))
    }

    @Test
    fun `past the last delay nothing is kept and cancel drops chain and wake-up`() {
        schedule.schedule(KilterUploadRetryScheduler.DELAYS_MIN.size + 1, replace = true)
        assertEquals(-1, store.dueAttempt(Long.MAX_VALUE))

        schedule.wakeAfter(60_000)
        assertEquals(0, store.dueAttempt(nowMs + 60_000), "a wake-up run is attempt 0")
        schedule.schedule(1, replace = true)
        schedule.cancel()
        assertEquals(-1, store.dueAttempt(Long.MAX_VALUE))
        assertEquals("cancel", timers.last())
    }
}

class CommunityAndBackupStepTest {
    private fun item(uuid: String) = CommunityRetryItem(uuid, 1, BoardBrand.KILTER, ClimbEditorState(name = uuid, angle = 40), "12x12")

    @Test
    fun `one failing climb does not stop the rest and only all failing fails the run`() = runTest {
        val tried = mutableListOf<String>()
        var failing = setOf("b")
        val step = CommunityRetryStep(
            queue = { pubkey -> assertEquals("pk", pubkey); listOf(item("a"), item("b"), item("c")) },
            republisher = { tried.add(it.uuid); if (it.uuid == "c") error("relay crashed") else it.uuid !in failing },
            pubkey = { "pk" },
        )
        assertTrue(step.run().ok)
        assertEquals(listOf("a", "b", "c"), tried)

        failing = setOf("a", "b")
        assertFalse(step.run().ok, "every climb failed: retry after the back-off")
    }

    @Test
    fun `no identity no community retry`() = runTest {
        val step = CommunityRetryStep({ error("must not list") }, { error("must not publish") }, { "" })
        assertFalse(step.enabled(BackgroundRefresh.TRIGGER_BACKGROUND))
        assertEquals(6L * 60 * 60 * 1000, step.intervalMs())
    }

    @Test
    fun `backup runs only when on and a cadence is chosen`() = runTest {
        val keyValues = MemoryKeyValues()
        var enabled = true
        val stamps = mutableListOf<String>()
        val step = BackupStep(keyValues, { enabled }, { stamps.add(it); true }, { "2026-10-18T09:00:00Z" })

        assertFalse(step.enabled(BackgroundRefresh.TRIGGER_BACKGROUND), "MANUAL by default, as Android")
        keyValues.putString(BackupStep.KEY_BACKUP_INTERVAL, "DAILY")
        assertTrue(step.enabled(BackgroundRefresh.TRIGGER_BACKGROUND))
        assertEquals(24L * 60 * 60 * 1000, step.intervalMs())
        keyValues.putString(BackupStep.KEY_BACKUP_INTERVAL, "WEEKLY")
        assertEquals(7L * 24 * 60 * 60 * 1000, step.intervalMs())
        keyValues.putString(BackupStep.KEY_BACKUP_INTERVAL, "HOURLY")
        assertFalse(step.enabled(BackgroundRefresh.TRIGGER_BACKGROUND), "an unknown value is MANUAL")

        keyValues.putString(BackupStep.KEY_BACKUP_INTERVAL, "DAILY")
        enabled = false
        assertFalse(step.enabled(BackgroundRefresh.TRIGGER_BACKGROUND))
        enabled = true
        assertTrue(step.run().ok)
        assertEquals(listOf("2026-10-18T09:00:00Z"), stamps)

        val failing = BackupStep(keyValues, { true }, { false }, { "t" })
        assertFalse(failing.run().ok)
    }

    @Test
    fun `catalogue refresh only on a return to the foreground`() = runTest {
        var calls = 0
        val step = CatalogueStep { calls++; emptyList() }
        assertFalse(step.enabled(BackgroundRefresh.TRIGGER_BACKGROUND))
        assertTrue(step.enabled(BackgroundRefresh.TRIGGER_FOREGROUND))
        step.run()
        assertEquals(1, calls)
    }

    @Test
    fun `settings model reads and writes Android's keys`() {
        val keyValues = MemoryKeyValues()
        val settings = com.cruxcoach.app.settings.SettingsStore(keyValues, "a".repeat(64))
        val model = BackgroundSettingsModel(settings, keyValues)
        val seen = mutableListOf<BackgroundSettingsState>()
        val sub = model.watch { seen.add(it) }

        assertTrue(model.currentState.announcementsEnabled, "on by default, as Android")
        assertEquals("MANUAL", model.currentState.backupInterval)
        model.setTips(false)
        model.setBackupInterval("WEEKLY")
        model.setBackupInterval("SOMETIMES")
        assertEquals("false", keyValues.getString("announcement_cat_tip"))
        assertEquals("WEEKLY", keyValues.getString(BackupStep.KEY_BACKUP_INTERVAL))
        assertFalse(seen.last().tips)
        assertEquals(3, seen.size, "initial state plus two changes; the bad value changes nothing")

        sub.cancel()
        model.setAnnouncementsEnabled(false)
        assertEquals(3, seen.size)
        assertFalse(BackgroundWiring.announcementSwitches(settings).enabled)
        assertNull(keyValues.getString("bg_refresh_ok_backup"))
    }

    @Test
    fun `the Kilter climb publish step follows the switch and Android's cadence`() = runTest {
        var on = false
        var passes = 0
        var answer = true
        val step = KilterClimbPublishStep({ on }) { passes++; answer }
        assertFalse(step.enabled("background"), "switch off: skipped, nothing recorded")
        on = true
        assertTrue(step.enabled("background"))
        assertTrue(step.run().ok)
        answer = false // every climb failed for a passing reason: Android's Result.retry()
        assertFalse(step.run().ok)
        assertEquals(2, passes)
        assertEquals(6L * 60 * 60 * 1000, step.intervalMs())
        assertEquals(30L * 60 * 1000, step.retryAfterMs())
        assertEquals("kilter_climb_publish", step.code)
    }
}
