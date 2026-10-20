package com.cruxcoach.android.athlete

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.data.AthleteRepository
import com.cruxcoach.athlete.data.AthleteSnapshot
import com.cruxcoach.athlete.logic.Comeback
import com.cruxcoach.athlete.logic.ComebackContent
import com.cruxcoach.athlete.logic.SideTarget
import com.cruxcoach.athlete.model.*
import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.db.athlete.AthleteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The comeback plan's data (owner 2026-10-20): stored with the injury and in its own diary table, migrated from
 * schema 5, and carried by backups only in the opt-in category "Verletzungen & Comeback-Tagebuch".
 */
class ComebackDataTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AthleteDatabase.Schema.create(it) }
    private var now = 1_000L
    private val repo = AthleteRepository(AthleteDatabase(driver), Dispatchers.Unconfined) { now++ }
    private val catalog = ExerciseCatalog.parse(File("src/main/assets/${ExerciseCatalogStore.ASSET}").readText())

    @AfterTest fun tearDown() = driver.close()

    private val today = LocalDate(2026, 10, 20)
    private fun injury() = Injury("i1", InjuryRegion.FINGER, InjurySide.LEFT, "Ring A2", 3, startedOn = "2026-10-10", updatedAt = 1,
        check = InjuryCheck(pop = InjuryPop.FELT, signs = setOf(WarningSign.POP_WITH_SWELLING), checked = ProfessionalCheck.IMAGING,
            finding = InjuryFinding.PULLEY_PARTIAL, fingers = setOf(Finger.RING)))

    @Test
    fun `the plan and the answers stay with the injury and the diary has a row per day`() {
        val started = Comeback.start(injury(), PlanSource.CRUXCOACH, 1, today, true, false, mapOf("HALF_CRIMP" to 30.0), now = 5)
        repo.saveInjury(started)
        val back = repo.injury("i1")!!
        assertEquals(started, back)
        assertEquals(InjuryFinding.PULLEY_PARTIAL, back.check!!.finding)
        assertEquals(1, back.activePlan!!.stage)
        repo.saveComebackEntry(ComebackEntry("i1", "2026-10-20", morningPain = 2, pressurePain = 3,
            sessions = listOf(ComebackSessionLog(ComebackSessionKind.TENDON, 1, "w1", 9)), updatedAt = 10))
        repo.saveComebackEntry(ComebackEntry("i1", "2026-10-20", morningPain = 1, updatedAt = 11))
        assertEquals(1, repo.comebackEntries("i1").single().morningPain)
        // Deleting the injury takes its diary along.
        repo.deleteInjury("i1")
        assertTrue(repo.comebackEntries("i1").isEmpty())
    }

    @Test
    fun `schema 5 database migrates to 6 and keeps its injuries`() {
        // Every build before the comeback plan had schema 5: no answers, no plan, no diary.
        val old = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AthleteDatabase.Schema.create(it) }
        old.execute(null, "DROP TABLE comeback_entry", 0)
        old.execute(null, "ALTER TABLE injury DROP COLUMN plan_json", 0)
        old.execute(null, "ALTER TABLE injury DROP COLUMN check_json", 0)
        old.execute(null, "INSERT INTO injury(id, region, side, detail, severity, climbing_paused, started_on, updated_at) " +
            "VALUES ('old', 'FINGER', 'RIGHT', 'A2', 4, 1, '2026-10-01', 5)", 0)
        AthleteDatabase.Schema.migrate(old, 5, 6)
        val migrated = AthleteRepository(AthleteDatabase(old), Dispatchers.Unconfined) { 1_000L }
        val kept = migrated.injury("old")!!
        assertEquals("A2", kept.detail); assertNull(kept.check); assertNull(kept.plan)
        migrated.saveInjury(Comeback.start(kept, PlanSource.OWN, 0, today, false, null, emptyMap(), 7))
        assertEquals(PlanSource.OWN, migrated.injury("old")!!.plan!!.source)
        migrated.saveComebackEntry(ComebackEntry("old", "2026-10-20", pressurePain = 4, updatedAt = 8))
        assertEquals(4, migrated.comebackEntries("old").single().pressurePain)
        old.close()
    }

    @Test
    fun `a value this build cannot read leaves the injury usable`() {
        repo.saveInjury(injury())
        driver.execute(null, "UPDATE injury SET plan_json = '{\"stage\":\"x\"}', check_json = 'nonsense' WHERE id = 'i1'", 0)
        val read = repo.injury("i1")!!
        assertNull(read.plan); assertNull(read.check)
        assertEquals("Ring A2", read.detail)
    }

    @Test
    fun `health data travels only in its own category`() {
        repo.saveInjury(Comeback.start(injury(), PlanSource.CRUXCOACH, 2, today, true, false, emptyMap(), now = 5))
        repo.saveComebackEntry(ComebackEntry("i1", "2026-10-20", morningPain = 2, updatedAt = 10))
        repo.saveWorkout(Workout("w1", 10, 20, "2026-10-20", updatedAt = 10))
        val full = repo.snapshot()
        assertEquals(2, full.healthRows)
        val training = full.onlyTraining()
        assertTrue(training.injuries.isEmpty() && training.comebackEntries.isEmpty(), "training carries no health data")
        val health = full.onlyHealth()
        assertEquals(1, health.injuries.size); assertEquals(1, health.comebackEntries.size)
        assertTrue(CruxCoachBackup.Category.HEALTH !in CruxCoachBackup.Category.DEFAULTS)

        // A backup with the category restores plan and diary on another phone, newest row wins.
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val text = json.encodeToString(AthleteSnapshot.serializer(), health)
        val otherDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AthleteDatabase.Schema.create(it) }
        val other = AthleteRepository(AthleteDatabase(otherDriver), Dispatchers.Unconfined) { 0L }
        other.restore(json.decodeFromString(AthleteSnapshot.serializer(), text), includeProfile = false)
        assertEquals(2, other.injury("i1")!!.plan!!.stage)
        assertEquals(2, other.comebackEntries("i1").single().morningPain)
        otherDriver.close()
    }

    @Test
    fun `an old build's backup without the new keys still parses`() {
        val old = """{"injuries":[{"id":"a","region":"FINGER","severity":3,"startedOn":"2026-10-01","updatedAt":1}]}"""
        val snapshot = Json { ignoreUnknownKeys = true }.decodeFromString(AthleteSnapshot.serializer(), old)
        assertNull(snapshot.injuries.single().plan)
        assertTrue(snapshot.comebackEntries.isEmpty())
    }

    @Test
    fun `every exercise of every stage is in the catalogue and fits how it is used`() {
        ComebackContent.all.forEach { ladder ->
            ladder.stages.forEach { stage ->
                stage.sessions.forEach { session ->
                    session.items.forEach { item ->
                        val def: ExerciseDefinition? = catalog[item.slug]
                        assertNotNull(def, "${ladder.id} stage ${stage.index}: ${item.slug} is not in the catalogue")
                        // A hand or side only where the exercise is one-sided.
                        if (item.side != SideTarget.BOTH) assertTrue(def.unilateral || !def.slug.startsWith("finger."),
                            "${item.slug} is for one side but not one-sided")
                        // Holds have a time, sets have repetitions.
                        assertTrue(item.durationS != null || item.repsMin != null, "${item.slug} without a dose")
                        if (item.loadPercent != null) assertNotNull(item.grip, "${item.slug}: a percentage needs its grip's reference")
                    }
                }
            }
        }
        // The comeback exercises stay out of suggestions but are in the catalogue with both languages.
        val comebackOnly = catalog.all.filter { ExerciseDefinition.TAG_COMEBACK in it.tags }
        assertEquals(7, comebackOnly.size, comebackOnly.map { it.slug }.toString())
        comebackOnly.forEach { assertTrue(it.i18n["de"]!!.steps.isNotEmpty() && it.i18n["en"]!!.steps.isNotEmpty(), it.slug) }
    }
}
