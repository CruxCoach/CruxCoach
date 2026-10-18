package com.cruxcoach.app.health

import com.cruxcoach.app.athlete.AthleteTestKit
import com.cruxcoach.app.ui.HealthScreenModel
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.ClimbingDaySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store-driven paths of [HealthSync] and the settings card (Android `HealthConnectSource.syncClimbing`,
 * `lastNightSleepHours` and `HealthConnectSettingsViewModel`) against [FakeHealthStore] and a real database.
 * Everything runs on the test thread (Unconfined), so the JDBC driver never sees a second thread.
 */
class HealthSyncTest {

    private lateinit var kit: AthleteTestKit
    private lateinit var store: FakeHealthStore
    private lateinit var health: HealthSync
    private val zone = TimeZone.currentSystemDefault()
    private val h = 3_600_000L

    @BeforeTest
    fun setUp() {
        kit = AthleteTestKit()
        runBlocking { kit.service.ensureReady() }
        store = FakeHealthStore()
        health = HealthSync(kit.service, { store }, MemoryHealthKeyValues(), Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() = kit.close()

    private fun yesterdayAt(hour: Int): Long =
        LocalDateTime(kit.service.today().minus(DatePeriod(days = 1)), LocalTime(hour, 0)).toInstant(zone).toEpochMilliseconds()

    private fun readOn(on: Boolean = true) = kit.repo.updateProfile { it.copy(coach = it.coach.copy(healthConnectEnabled = on)) }

    @Test
    fun `nothing is read while the switch is off`() = runBlocking {
        store.workouts += HealthWorkoutSample("w1", yesterdayAt(18), yesterdayAt(19), "")
        assertEquals(0, health.syncClimbing())
        assertEquals(0, store.workoutQueries)
        assertNull(health.lastSync.value)
        assertNull(health.lastNightSleepHours(kit.service.today()))
    }

    @Test
    fun `climbing workouts become climbing days once and a hand-logged day is left alone`() = runBlocking {
        readOn()
        store.workouts += HealthWorkoutSample("w1", yesterdayAt(18), yesterdayAt(19) + h / 2, "")
        // Too short to be a climbing day.
        store.workouts += HealthWorkoutSample("w2", yesterdayAt(12), yesterdayAt(12) + 5 * 60_000L, "")
        assertEquals(1, health.syncClimbing())
        val entry = kit.repo.climbingDayByExternalId("w1")!!
        assertEquals(90, entry.minutes)
        assertEquals(ClimbingDayKind.OTHER, entry.kind)
        assertEquals(ClimbingDaySource.HEALTH_CONNECT, entry.source)
        assertNull(kit.repo.climbingDayByExternalId("w2"))

        // A second sync finds the same workout: no duplicate.
        assertEquals(0, health.syncClimbing())
        assertEquals(1, kit.repo.climbingDaysSince(kit.service.today().minus(DatePeriod(days = 13)).toString()).size)
        assertEquals(0, health.lastSync.value!!.added)
        assertEquals(0, health.lastSync.value!!.updated)

        // A workout on a day the athlete logged by hand is skipped.
        val twoDaysAgo = kit.service.today().minus(DatePeriod(days = 2))
        kit.service.saveClimbingDay(ClimbingDayEntry(id = "m1", day = twoDaysAgo.toString(), kind = ClimbingDayKind.GYM_BOULDER,
            minutes = 60, intensity = ClimbIntensity.HARD, source = ClimbingDaySource.MANUAL, externalId = null, note = null, updatedAt = 0))
        val start = LocalDateTime(twoDaysAgo, LocalTime(18, 0)).toInstant(zone).toEpochMilliseconds()
        store.workouts += HealthWorkoutSample("w3", start, start + h, "")
        assertEquals(0, health.syncClimbing())
        assertEquals(1, health.lastSync.value!!.skippedManual)
        assertNull(kit.repo.climbingDayByExternalId("w3"))
    }

    @Test
    fun `a failed read is reported as failed and changes nothing`() = runBlocking {
        readOn()
        store.readOk = false
        store.workouts += HealthWorkoutSample("w1", yesterdayAt(18), yesterdayAt(19), "")
        assertEquals(0, health.syncClimbing())
        assertTrue(health.lastSync.value!!.failed)
        assertNull(kit.repo.climbingDayByExternalId("w1"))
        assertNull(health.lastNightSleepHours(kit.service.today()))
    }

    @Test
    fun `last night's sleep comes from the samples inside the window`() = runBlocking {
        readOn()
        store.sleep += HealthSleepSample(yesterdayAt(23), yesterdayAt(23) + 7 * h, "asleep")
        store.sleep += HealthSleepSample(yesterdayAt(23) + 2 * h, yesterdayAt(23) + 2 * h + h / 2, "awake")
        assertEquals(6.5, health.lastNightSleepHours(kit.service.today()))
        readOn(false)
        assertNull(health.lastNightSleepHours(kit.service.today()))
    }

    @Test
    fun `the card asks for access once and a build without the entitlement says so`() = runBlocking {
        store.asked = false
        store.writeAccess = "notDetermined"
        val presenter = HealthSettingsPresenter(health, kit.service, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), Dispatchers.Unconfined)
        val screen = HealthScreenModel(presenter)
        presenter.refresh().join()
        assertTrue(screen.currentState.available)
        assertFalse(screen.currentState.accessFailed)
        assertFalse(screen.currentState.canSync)

        presenter.setReadEnabled(true).join()
        assertEquals(listOf(HealthTypes.READ to emptyList<String>()), store.authorizationRequests)
        assertTrue(kit.repo.profile().coach.healthConnectEnabled)
        assertTrue(screen.currentState.readAsked)
        assertTrue(screen.currentState.canSync)
        assertTrue(screen.currentState.hasLastSync)
        assertFalse(screen.currentState.lastSyncFailed)
        // Turning it off and on again does not ask a second time.
        presenter.setReadEnabled(false).join()
        presenter.setReadEnabled(true).join()
        assertEquals(1, store.authorizationRequests.size)

        // Sending denied in the Health app: no second sheet (HealthKit would not show one), the card says so.
        store.writeAccess = "denied"
        presenter.refresh().join()
        presenter.setExportEnabled(true).join()
        assertTrue(kit.repo.profile().healthConnectExport)
        assertEquals(1, store.authorizationRequests.size)
        assertTrue(screen.currentState.writeDenied)

        // A build without the HealthKit entitlement: the request fails and the card is unusable, not "on".
        val noEntitlement = FakeHealthStore(authorizationAnswer = "failed", asked = false)
        val failing = HealthSettingsPresenter(
            HealthSync(kit.service, { noEntitlement }, MemoryHealthKeyValues(), Dispatchers.Unconfined),
            kit.service, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), Dispatchers.Unconfined,
        )
        val failingScreen = HealthScreenModel(failing)
        failing.refresh().join()
        assertTrue(failingScreen.currentState.accessFailed)
        failing.setReadEnabled(true).join()
        assertTrue(failingScreen.currentState.accessFailed)
        assertFalse(failingScreen.currentState.canSync)
        assertFalse(failingScreen.currentState.needsAccess)
        assertEquals(0, noEntitlement.workoutQueries)
        presenter.close()
        failing.close()
    }

    @Test
    fun `without HealthKit the card is unavailable and nothing is asked`() = runBlocking {
        store.available = false
        val presenter = HealthSettingsPresenter(health, kit.service, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), Dispatchers.Unconfined)
        val screen = HealthScreenModel(presenter)
        presenter.refresh().join()
        presenter.setReadEnabled(true).join()
        assertFalse(screen.currentState.available)
        assertFalse(screen.currentState.canSync)
        assertTrue(store.authorizationRequests.isEmpty())
        assertEquals(0, store.workoutQueries)
        presenter.close()
    }
}
