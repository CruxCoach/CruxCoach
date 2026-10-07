package com.cruxcoach.android.athlete

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.athlete.data.AthleteRepository
import com.cruxcoach.athlete.model.*
import com.cruxcoach.db.athlete.AthleteDatabase
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AthleteRepositoryTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AthleteDatabase.Schema.create(it) }
    private var now = 1_000L
    private val repo = AthleteRepository(AthleteDatabase(driver), Dispatchers.Unconfined) { now++ }

    @AfterTest fun tearDown() = driver.close()

    private fun set(id: String, workout: String, slug: String, index: Int, done: Long?, reps: Int? = 5) =
        ExerciseSet(id, workout, slug, blockIndex = 0, setIndex = index, reps = reps, completedAt = done)

    @Test
    fun `profile defaults until saved and keeps unknown-free round trip`() {
        assertEquals(AthleteProfile(), repo.profile())
        repo.updateProfile { it.copy(weeklyGoal = 4, fuelEnabled = true) }
        assertEquals(4, repo.profile().weeklyGoal)
        assertTrue(repo.profile().fuelEnabled)
    }

    @Test
    fun `planned sets are not history until completed`() {
        repo.saveWorkout(Workout("w1", 10, null, "2026-10-04", updatedAt = 10))
        repo.saveSet(set("s1", "w1", "pull.pull_up", 0, done = 50))
        repo.saveSet(set("s2", "w1", "pull.pull_up", 1, done = null))
        assertEquals(listOf("s1"), repo.history("pull.pull_up").map { it.id })
        assertEquals("w1", repo.openWorkout()?.id)
        repo.finishWorkout("w1", endedAt = 100, sessionRpe = 7, notes = null)
        assertNull(repo.openWorkout())
        assertEquals(listOf("w1"), repo.recentWorkouts(5).map { it.id })
    }

    @Test
    fun `one value per metric and day`() {
        repo.saveMeasurement(BodyMeasurement("2026-10-04", "weight", 70.0, "kg", 1))
        repo.saveMeasurement(BodyMeasurement("2026-10-04", "weight", 70.4, "kg", 2))
        assertEquals(listOf(70.4), repo.series("weight").map { it.value })
    }

    @Test
    fun `legacy body stats are imported once and never overwrite`() {
        repo.saveMeasurement(BodyMeasurement("2026-09-01", "weight", 71.0, "kg", 1))
        val legacy = listOf(
            AthleteRepository.LegacyBodyStat("2026-09-01", "weight", 99.0, "kg"),
            AthleteRepository.LegacyBodyStat("2026-09-02", "body fat", 12.0, "%"),
            AthleteRepository.LegacyBodyStat("2026-09-02", "forearm_circumference", 30.0, "cm"),
        )
        assertEquals(2, repo.importLegacyBodyStats(legacy))
        assertEquals(0, repo.importLegacyBodyStats(legacy))
        assertEquals(71.0, repo.series("weight").single().value)
        assertEquals(12.0, repo.latest(BodyMetric.BODY_FAT.key)?.value)
        assertEquals(30.0, repo.latest(BodyMetric.FOREARM.key)?.value)
    }

    @Test
    fun `snapshot restores into an empty database and re-importing changes nothing`() {
        repo.updateProfile { it.copy(weeklyGoal = 5) }
        repo.saveWorkout(Workout("w1", 10, 20, "2026-10-04", updatedAt = 10))
        repo.saveSet(set("s1", "w1", "finger.one_arm_pickup", 0, done = 15).copy(side = Side.RIGHT, loadKg = 30.0))
        repo.saveMeasurement(BodyMeasurement("2026-10-04", "weight", 70.0, "kg", 1))
        repo.saveFoodLog(FoodLogEntry("f1", "2026-10-04", 5, Meal.LUNCH, name = "Bowl", proteinG = 35.0))
        repo.addHydration("2026-10-04", 500)
        repo.saveInjury(Injury("i1", InjuryRegion.FINGER, InjurySide.LEFT, severity = 4, startedOn = "2026-10-01"))
        repo.setFavorite("pull.pull_up", true)
        val snapshot = repo.snapshot()

        val otherDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AthleteDatabase.Schema.create(it) }
        val other = AthleteRepository(AthleteDatabase(otherDriver), Dispatchers.Unconfined) { 0L }
        other.restore(snapshot, includeProfile = true)
        other.restore(snapshot, includeProfile = true)
        val restored = other.snapshot()
        assertEquals(5, restored.profile?.weeklyGoal)
        assertEquals(snapshot.workouts, restored.workouts)
        assertEquals(snapshot.sets, restored.sets)
        assertEquals(snapshot.measurements, restored.measurements)
        assertEquals(snapshot.foodLog, restored.foodLog)
        assertEquals(snapshot.hydration, restored.hydration)
        assertEquals(snapshot.injuries, restored.injuries)
        assertEquals(setOf("pull.pull_up"), other.favorites())
        otherDriver.close()
    }

    @Test
    fun `recipes travel with the backup and stay editable`() {
        val recipe = com.cruxcoach.athlete.logic.Recipe("Porridge", portions = 2, ingredients = listOf(
            com.cruxcoach.athlete.logic.Recipe.Ingredient("oats", "Oats", 80.0, kcalPer100 = 370.0, proteinPer100 = 13.0),
            com.cruxcoach.athlete.logic.Recipe.Ingredient("milk", "Milk", 300.0, kcalPer100 = 64.0, proteinPer100 = 3.3),
        ))
        repo.saveRecipe("r1", recipe)
        val snapshot = repo.snapshot()
        assertEquals(recipe, snapshot.recipes["r1"])

        val otherDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AthleteDatabase.Schema.create(it) }
        val other = AthleteRepository(AthleteDatabase(otherDriver), Dispatchers.Unconfined) { 0L }
        other.restore(snapshot, includeProfile = true)
        assertEquals(recipe, other.recipe("r1"))
        assertTrue(other.foodItems().any { it.id == "r1" })
        // Only the nutrition part of a backup carries them as well.
        assertEquals(recipe, snapshot.onlyFuel().recipes["r1"])
        otherDriver.close()
    }

    @Test
    fun `a first start in US units still takes the backup's settings`() {
        repo.updateProfile { it.copy(weeklyGoal = 5, proteinPerKg = 1.8) }
        val snapshot = repo.snapshot()

        val otherDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AthleteDatabase.Schema.create(it) }
        val other = AthleteRepository(AthleteDatabase(otherDriver), Dispatchers.Unconfined) { 0L }
        // What AthleteService.ensureReady saves on a US phone before anything else happens.
        other.saveProfile(com.cruxcoach.athlete.logic.Units.withUnits(AthleteProfile(), UnitSystem.IMPERIAL))
        other.restore(snapshot, includeProfile = true)
        assertEquals(5, other.profile().weeklyGoal)
        assertEquals(1.8, other.profile().proteinPerKg)
        otherDriver.close()
    }

    @Test
    fun `restore without profile keeps local settings`() {
        repo.updateProfile { it.copy(weeklyGoal = 2) }
        repo.restore(com.cruxcoach.athlete.data.AthleteSnapshot(profile = AthleteProfile(weeklyGoal = 6)), includeProfile = false)
        assertEquals(2, repo.profile().weeklyGoal)
    }

    @Test
    fun `schema 2 database migrates to 3 and keeps coach data in the backup`() {
        // A feature build up to 1000020 had schema 2: no coach tables yet.
        val old = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AthleteDatabase.Schema.create(it) }
        old.execute(null, "DROP TABLE climbing_day", 0)
        old.execute(null, "DROP TABLE suggestion_event", 0)
        AthleteDatabase.Schema.migrate(old, 2, 3)
        val migrated = AthleteRepository(AthleteDatabase(old), Dispatchers.IO) { 1_000L }
        migrated.saveClimbingDay(ClimbingDayEntry("c1", "2026-10-04", ClimbingDayKind.OUTDOOR, 120, ClimbIntensity.HARD))
        migrated.logSuggestionEvent(SuggestionEvent("e1", "2026-10-04", 5L, SuggestionEventKind.NEXT,
            slugs = listOf("pull.pull_up"), feedback = SuggestionFeedback.NO_TIME))
        assertEquals(listOf("c1"), migrated.climbingDaysSince("2026-10-01").map { it.id })
        assertEquals(SuggestionFeedback.NO_TIME, migrated.suggestionEventsSince(0).single().feedback)

        val snapshot = migrated.snapshot()
        assertEquals(1, snapshot.climbingDays.size)
        assertEquals(1, snapshot.suggestionEvents.size)
        // Restoring into a fresh database brings both back; a second restore adds nothing.
        repo.restore(snapshot, includeProfile = false)
        repo.restore(snapshot, includeProfile = false)
        assertEquals(1, repo.allClimbingDays().size)
        assertEquals(1, repo.allSuggestionEvents().size)
        old.close()
    }
}
