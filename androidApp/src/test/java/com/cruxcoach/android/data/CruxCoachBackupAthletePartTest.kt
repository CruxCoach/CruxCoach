package com.cruxcoach.android.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.data.AthleteSnapshot
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.UnitSystem
import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.data.SecureDatabaseTransactionRunner
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BodyStatRepository
import com.cruxcoach.data.repository.ClimbRepositoryImpl
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.data.repository.PlanRepository
import com.cruxcoach.data.repository.UserRepository
import com.cruxcoach.data.repository.WorkoutRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.model.ClimbLog
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The athlete database (training, body, nutrition) is a separate file next to
 * the logbook. When it will not open or write, a restore still brings the
 * logbook back and says what is missing, instead of reporting the whole import
 * as failed after the logbook was already written.
 */
class CruxCoachBackupAthletePartTest {

    private class Device(val driver: SqlDriver, val db: SecureDatabase) {
        val personal = PersonalBoardRepositoryImpl(db)
        val workouts = WorkoutRepositoryImpl(db)
        val climbs = ClimbRepositoryImpl(db)
        val txn = SecureDatabaseTransactionRunner(db)
    }

    private lateinit var source: Device
    private lateinit var target: Device

    private fun newDevice(): Device {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SecureDatabase.Schema.create(driver)
        return Device(driver, SecureDatabase(driver))
    }

    @BeforeTest
    fun setUp() {
        source = newDevice()
        target = newDevice()
    }

    @AfterTest
    fun tearDown() {
        source.driver.close()
        target.driver.close()
    }

    private val userRepo: UserRepository = mockk(relaxed = true) { every { getActiveProfile() } returns null }

    private val weight = AthleteSnapshot(measurements = listOf(BodyMeasurement("2026-10-20", BodyMetric.WEIGHT.key, 68.5, "kg", 1L)))

    private fun export(athlete: (() -> AthleteSnapshot)?): String = CruxCoachBackup.export(
        categories = CruxCoachBackup.Category.entries.toSet(),
        userRepository = userRepo,
        bodyStatRepository = mockk<BodyStatRepository>(relaxed = true) { every { getAll() } returns emptyList() },
        workoutRepository = source.workouts,
        climbRepository = source.climbs,
        planRepository = mockk<PlanRepository>(relaxed = true),
        personalBoardRepo = source.personal,
        boardRepository = mockk<BoardRepository>(relaxed = true),
        exportedAt = "2026-10-25T12:00:00Z",
        nostrPubkey = null,
        athleteSnapshot = athlete,
    )

    private fun import(json: String, athleteRestore: (AthleteSnapshot, Boolean) -> Int): CruxCoachBackup.ImportResult =
        CruxCoachBackup.import(
            jsonString = json,
            selectedCategories = CruxCoachBackup.Category.entries.toSet(),
            userRepository = userRepo,
            bodyStatRepository = mockk<BodyStatRepository>(relaxed = true),
            workoutRepository = target.workouts,
            climbRepository = target.climbs,
            planRepository = mockk<PlanRepository>(relaxed = true) { every { getAllPlans(any()) } returns emptyList() },
            personalBoardRepo = target.personal,
            boardRepository = mockk<BoardRepository>(relaxed = true),
            transactionRunner = target.txn,
            athleteRestore = athleteRestore,
        )

    @Test
    fun `a training database that will not write leaves the restored logbook in and says so`() {
        source.climbs.insertClimb(ClimbLog(date = "2026-10-20", grade = "V5", attempts = 2, notes = "board"))
        val json = export { weight }

        val result = import(json) { _, _ -> throw IllegalStateException("file is not a database") }

        assertTrue(result.athleteFailed)
        assertEquals(0, result.athleteRows)
        assertEquals(1, result.climbLogs)
        assertEquals(1, target.climbs.getAll().size, "the logbook is restored")
    }

    @Test
    fun `a working training database gets its part`() {
        val json = export { weight }
        var restored: AthleteSnapshot? = null

        val result = import(json) { part, _ -> restored = part; part.measurements.size }

        assertFalse(result.athleteFailed)
        assertEquals(1, result.athleteRows)
        assertEquals(68.5, restored?.measurements?.single()?.value)
    }

    @Test
    fun `an export without a training database has no athlete part and still carries the logbook`() {
        source.climbs.insertClimb(ClimbLog(date = "2026-10-21", grade = "V3", attempts = 1, notes = "warm-up"))
        val json = export(null)

        assertFalse(json.contains("\"athlete\": {"), "no athlete part without a database")
        val result = import(json) { _, _ -> error("nothing to restore") }
        assertFalse(result.athleteFailed)
        assertEquals(1, result.climbLogs)
    }

    // ── A backup written by a newer version (values this one does not know) ──

    private val newer = AthleteSnapshot(
        profile = AthleteProfile(units = UnitSystem.IMPERIAL, equipment = setOf(EquipmentV2.HANGBOARD, EquipmentV2.MAT)),
        climbingDays = listOf(
            ClimbingDayEntry("d1", "2026-10-20", ClimbingDayKind.GYM_BOULDER, 90, ClimbIntensity.HARD),
            ClimbingDayEntry("d2", "2026-10-21", ClimbingDayKind.OUTDOOR, 120, ClimbIntensity.VOLUME),
        ),
        measurements = weight.measurements,
    )

    /** The export with [edit] applied to its athlete part, as a later version might write it. */
    private fun exportEdited(edit: (JsonObject) -> JsonObject): String {
        val root = Json.parseToJsonElement(export { newer }).jsonObject
        return JsonObject(root + ("athlete" to edit(root.getValue("athlete").jsonObject))).toString()
    }

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

    private fun restoredFrom(json: String): Pair<CruxCoachBackup.ImportResult, AthleteSnapshot?> {
        var part: AthleteSnapshot? = null
        val result = import(json) { p, _ -> part = p; p.climbingDays.size + p.measurements.size }
        return result to part
    }

    @Test
    fun `a climbing day of a kind this version does not know is left out, the others restore`() {
        source.climbs.insertClimb(ClimbLog(date = "2026-10-20", grade = "V5", attempts = 2, notes = "board"))
        val json = exportEdited { athlete ->
            val days = athlete.getValue("climbingDays").jsonArray
            val ice = days[0].jsonObject.with("kind", JsonPrimitive("ICE_CLIMBING"))
            athlete.with("climbingDays", JsonArray(listOf(ice, days[1])))
        }

        val (result, part) = restoredFrom(json)

        assertFalse(result.athleteFailed)
        assertEquals(1, result.climbLogs, "the logbook restores")
        assertEquals(listOf("d2"), part?.climbingDays?.map { it.id })
        assertEquals(1, part?.measurements?.size)
    }

    @Test
    fun `new equipment is dropped from the set and a new unit system falls back to its default`() {
        val json = exportEdited { athlete ->
            val profile = athlete.getValue("profile").jsonObject
                .with("units", JsonPrimitive("SOMETHING_NEW"))
                .with("equipment", JsonArray(listOf(JsonPrimitive("HANGBOARD"), JsonPrimitive("SPRAY_WALL_2030"))))
            athlete.with("profile", profile)
        }

        val profile = assertNotNull(restoredFrom(json).second?.profile)

        assertEquals(UnitSystem.METRIC, profile.units)
        assertEquals(setOf(EquipmentV2.HANGBOARD), profile.equipment)
    }

    @Test
    fun `an athlete part that still cannot be read is skipped and the logbook restores`() {
        source.climbs.insertClimb(ClimbLog(date = "2026-10-22", grade = "V4", attempts = 3, notes = "moon"))
        val json = exportEdited { athlete -> athlete.with("climbingDays", JsonPrimitive("not a list")) }

        val (result, part) = restoredFrom(json)

        assertEquals(1, result.climbLogs)
        assertTrue(part == null || part.climbingDays.isEmpty())
    }

    @Test
    fun `a current backup reads exactly as written`() {
        val (_, part) = restoredFrom(export { newer })

        assertEquals(newer.climbingDays, part?.climbingDays)
        assertEquals(newer.profile, part?.profile)
        assertNull(Json.parseToJsonElement(export(null)).jsonObject["athlete"]?.let { it as? JsonObject })
    }
}
