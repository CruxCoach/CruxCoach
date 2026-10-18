package com.cruxcoach.app.health

import com.cruxcoach.app.athlete.AthleteTestKit
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.HydrationEntry
import com.cruxcoach.athlete.model.Meal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Port of Android's HealthConnectExporterTest: what CruxCoach would write to Apple Health, and that it writes
 * nothing without access (FEAT-069). The record assertions are Android's; the store-side cases run against
 * [FakeHealthStore], which applies HealthKit's sync-id and version rule.
 */
class HealthExportTest {

    private val zone = TimeZone.currentSystemDefault()
    private val loggedAt = 1_791_400_000_000
    /** The day of [loggedAt] wherever the test runs, so the entry counts as logged on its own day. */
    private val loggedDay = Instant.fromEpochMilliseconds(loggedAt).toLocalDateTime(zone).date.toString()
    private val entry = FoodLogEntry(id = "e1", day = loggedDay, loggedAt = loggedAt, meal = Meal.POST_TRAINING,
        name = "Skyr", amountG = 250.0, kcal = 157.5, proteinG = 27.5, carbsG = 10.0)
    private val far = Long.MAX_VALUE

    private val kit = AthleteTestKit()
    private val keyValues = MemoryHealthKeyValues()

    @AfterTest
    fun tearDown() = kit.close()

    private fun sync(store: HealthStore?, now: Long = 5_000L) =
        HealthSync(kit.service, { store }, keyValues, Dispatchers.Unconfined, { zone }, { now })

    @Test
    fun `a logged food becomes a nutrition record with a stable id`() {
        val record = HealthExport.nutrition(entry, version = 7, zone = zone, nowMs = far)
        assertEquals("cruxcoach-food-e1", record.clientRecordId)
        assertEquals(7L, record.version)
        assertTrue(record.manualEntry)
        assertEquals("Skyr", record.name)
        assertEquals("snack", record.mealCode)
        assertEquals(157.5, record.kcal!!, 1e-6)
        assertEquals(27.5, record.proteinG!!, 1e-9)
        assertEquals(10.0, record.carbsG!!, 1e-9)
        // Not logged: not written as zero.
        assertNull(record.fatG)
        assertEquals(entry.loggedAt, record.startMs)
        // HealthKit gets one sample per logged quantity, each with its own sync id; no fat sample.
        assertEquals(
            listOf("cruxcoach-food-e1.dietaryEnergyConsumed", "cruxcoach-food-e1.dietaryProtein", "cruxcoach-food-e1.dietaryCarbohydrates"),
            record.samples().map { it.syncId },
        )
        assertTrue(record.samples().all { it.version == 7L && it.foodName == "Skyr" && it.endMs - it.startMs == 60_000L })
    }

    @Test
    fun `an entry logged for an earlier day lands on that day`() {
        // Tuesday 08:15, logging Monday's dinner.
        val tuesdayMorning = LocalDate(2026, 10, 6).atTime(8, 15).toInstant(zone).toEpochMilliseconds()
        val record = HealthExport.nutrition(FoodLogEntry(id = "e1", day = "2026-10-05", loggedAt = tuesdayMorning, meal = Meal.DINNER,
            name = "Skyr", amountG = 250.0, kcal = 157.5, proteinG = 27.5, carbsG = 10.0), 1, zone, far)
        val at = Instant.fromEpochMilliseconds(record.startMs).toLocalDateTime(zone)
        assertEquals(LocalDate(2026, 10, 5), at.date)
        assertEquals(LocalTime(8, 15), at.time)
        assertEquals("dinner", record.mealCode)
        // Logged on its own day: the logging time as it is.
        val sameDay = HealthExport.timeOf("2026-10-06", tuesdayMorning, zone, far)
        assertEquals(tuesdayMorning, sameDay)
        val water = HealthExport.hydration(HydrationEntry("w2", "2026-10-05", tuesdayMorning, 500), version = 1, zone = zone, nowMs = far)
        assertEquals(LocalDate(2026, 10, 5), Instant.fromEpochMilliseconds(water.startMs).toLocalDateTime(zone).date)
        // Never in the future: a day ahead of "now" is clamped to now.
        assertEquals(1_000L, HealthExport.timeOf("2026-10-07", tuesdayMorning, zone, 1_000L))
    }

    @Test
    fun `water becomes a hydration record`() {
        val record = HealthExport.hydration(HydrationEntry("w1", "2026-10-07", 1_791_400_000_000, 250), version = 1, zone = zone, nowMs = far)
        assertEquals("cruxcoach-water-w1", record.clientRecordId)
        assertEquals(0.25, record.ml / 1000.0, 1e-9)
        assertEquals(listOf("cruxcoach-water-w1.dietaryWater"), record.samples().map { it.syncId })
    }

    @Test
    fun `the access types are the HealthKit types the host maps`() {
        assertEquals(
            setOf("dietaryEnergyConsumed", "dietaryProtein", "dietaryCarbohydrates", "dietaryFatTotal", "dietaryWater"),
            HealthTypes.WRITE.toSet(),
        )
        assertEquals(setOf("sleepAnalysis", "workouts"), HealthTypes.READ.toSet())
    }

    @Test
    fun `without Health or permission nothing is written and nothing throws`() = runBlocking {
        assertEquals("noAccess", sync(null).syncDay("2026-10-07", listOf(entry), emptyList()))
        val missing = FakeHealthStore(available = false)
        assertEquals("noAccess", sync(missing).syncDay("2026-10-07", listOf(entry), emptyList()))
        val denied = FakeHealthStore(writeAccess = "denied")
        assertEquals("noAccess", sync(denied).syncDay("2026-10-07", listOf(entry), emptyList()))
        val undecided = FakeHealthStore(writeAccess = "notDetermined")
        assertEquals("noAccess", sync(undecided).syncDay("2026-10-07", listOf(entry), emptyList()))
        assertTrue(missing.writeCalls.isEmpty() && denied.writeCalls.isEmpty() && undecided.writeCalls.isEmpty())
        assertTrue(keyValues.map.isEmpty())
    }

    @Test
    fun `a day is written once and changes replace or remove their samples`() = runBlocking {
        val store = FakeHealthStore()
        val glass = HydrationEntry("w1", loggedDay, loggedAt, 300)
        assertEquals("done", sync(store, now = 10).syncDay(loggedDay, listOf(entry), listOf(glass)))
        assertEquals(4, store.written.size)
        // The same day again: nothing to send.
        assertEquals("unchanged", sync(store, now = 20).syncDay(loggedDay, listOf(entry), listOf(glass)))
        assertEquals(1, store.writeCalls.size)

        // More kcal, protein removed: the energy sample is replaced, the protein sample deleted.
        val changed = entry.copy(kcal = 200.0, proteinG = null)
        assertEquals("done", sync(store, now = 30).syncDay(loggedDay, listOf(changed), listOf(glass)))
        assertEquals(200.0, store.written["cruxcoach-food-e1.dietaryEnergyConsumed"]!!.value, 1e-9)
        assertEquals(30L, store.written["cruxcoach-food-e1.dietaryEnergyConsumed"]!!.version)
        assertNull(store.written["cruxcoach-food-e1.dietaryProtein"])
        // The unchanged glass was not sent again.
        assertTrue(store.writeCalls.last().first.none { it.syncId.startsWith("cruxcoach-water-") })

        // The glass deleted in the app: its sample goes.
        assertEquals("done", sync(store, now = 40).syncDay(loggedDay, listOf(changed), emptyList()))
        assertNull(store.written["cruxcoach-water-w1.dietaryWater"])
        assertEquals(setOf("cruxcoach-food-e1.dietaryEnergyConsumed", "cruxcoach-food-e1.dietaryCarbohydrates"), store.written.keys)
    }

    @Test
    fun `a failed write is not remembered and is sent again`() = runBlocking {
        val store = FakeHealthStore().apply { writeAnswer = "failed" }
        assertEquals("failed", sync(store).syncDay(loggedDay, listOf(entry), emptyList()))
        assertTrue(store.written.isEmpty())
        assertTrue(keyValues.map.isEmpty())
        store.writeAnswer = "done"
        assertEquals("done", sync(store).syncDay(loggedDay, listOf(entry), emptyList()))
        assertEquals(3, store.written.size)
    }

    @Test
    fun `the export from the database runs only while it is switched on`() = runBlocking {
        kit.service.ensureReady()
        kit.repo.saveFoodLog(entry)
        val store = FakeHealthStore()
        assertEquals("noAccess", sync(store).exportDayIfEnabled(loggedDay))
        assertTrue(store.writeCalls.isEmpty())
        kit.repo.updateProfile { it.copy(healthConnectExport = true) }
        assertEquals("done", sync(store).exportDayIfEnabled(loggedDay))
        assertEquals(3, store.written.size)
    }
}
