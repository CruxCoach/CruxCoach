package com.cruxcoach.android.athlete

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.athlete.data.AthleteRepository
import com.cruxcoach.athlete.model.*
import com.cruxcoach.db.athlete.AthleteDatabase
import com.cruxcoach.db.secure.SecureDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Set-to-set autoregulation and "it hurts" against the real service and database. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlayerAutoregulationTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var athleteDriver: AndroidSqliteDriver
    private lateinit var secureDriver: AndroidSqliteDriver
    private lateinit var repo: AthleteRepository
    private lateinit var service: AthleteService

    @Before
    fun setUp() {
        athleteDriver = AndroidSqliteDriver(AthleteDatabase.Schema, context, null)
        secureDriver = AndroidSqliteDriver(SecureDatabase.Schema, context, null)
        repo = AthleteRepository(AthleteDatabase(athleteDriver), Dispatchers.IO) { System.currentTimeMillis() }
        val boardRepo = mockk<com.cruxcoach.data.repository.PersonalBoardRepository>(relaxed = true)
        every { boardRepo.getActiveSession() } returns null
        service = AthleteService(
            repoLazy = { repo },
            catalogStore = ExerciseCatalogStore(context) { repo },
            climbingDays = ClimbingDaysReader(SecureDatabase(secureDriver)),
            bodyStatRepository = mockk(relaxed = true),
            sessionManager = BoardSessionManager(boardRepo, mockk(relaxed = true), mockk(relaxed = true)),
        )
        runBlocking { service.ensureReady() }
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
    }

    @After
    fun tearDown() {
        athleteDriver.close(); secureDriver.close()
    }

    private fun workSets(workoutId: String, slug: String) =
        repo.setsFor(workoutId).filter { it.exerciseSlug == slug && it.setType == SetType.WORK }.sortedBy { it.setIndex }

    @Test
    fun `no reserve on a set moves the next planned set once, also when asked again`() {
        val id = service.startWorkout(Routine("r", "R", items = listOf(RoutineItem("pull.weighted_pull_up", sets = 3, repsMin = 5, repsMax = 5))), null)
        val first = workSets(id, "pull.weighted_pull_up").first()
        val before = workSets(id, "pull.weighted_pull_up")[1]
        service.completeSetDetailed(first.copy(reps = first.targetReps ?: 5, rir = 0), startRest = false)
        val done = repo.setsFor(id).first { it.id == first.id }
        val adjustment = service.adjustUpcomingSets(id, done)
        assertNotNull(adjustment)
        val after = workSets(id, "pull.weighted_pull_up")[1]
        assertTrue(after.targetLoadKg != before.targetLoadKg || after.targetReps != before.targetReps, "$before → $after")
        // Asking again with what was applied changes nothing more.
        assertEquals(adjustment, service.adjustUpcomingSets(id, done, adjustment))
        assertEquals(after, workSets(id, "pull.weighted_pull_up")[1])
    }

    @Test
    fun `pain swap keeps done sets and puts a sparing exercise right after the block`() {
        val id = service.startWorkout(Routine("r", "R", items = listOf(
            RoutineItem("pull.pull_up", sets = 3, repsMin = 5, repsMax = 8),
            RoutineItem("core.hollow_hold", sets = 2, durationS = 20),
        )), null)
        val first = workSets(id, "pull.pull_up").first()
        service.completeSetDetailed(first.copy(reps = 6), startRest = false)
        val outcome = service.painStop(id, first.blockIndex, InjuryRegion.FINGER, null, 3, PainAction.SWAP)
        assertEquals(PainAction.SWAP, outcome.action)
        val newSlug = assertNotNull(outcome.newSlug)
        assertNotEquals("pull.pull_up", newSlug)
        val sets = repo.setsFor(id)
        assertEquals(listOf(first.id), sets.filter { it.exerciseSlug == "pull.pull_up" }.map { it.id })
        assertTrue(sets.filter { it.exerciseSlug == newSlug }.all { it.blockIndex == first.blockIndex + 1 && !it.isCompleted })
        assertTrue(sets.filter { it.exerciseSlug == "core.hollow_hold" }.all { it.blockIndex == first.blockIndex + 2 })
    }

    @Test
    fun `remembered strong pain becomes an injury that pauses climbing`() {
        val id = service.startWorkout(Routine("r", "R", items = listOf(RoutineItem("pull.pull_up", sets = 3, repsMin = 5, repsMax = 8))), null)
        val first = workSets(id, "pull.pull_up").first()
        val outcome = service.painStop(id, first.blockIndex, InjuryRegion.ELBOW, InjurySide.LEFT, 6, PainAction.END_EXERCISE, remember = true)
        assertNotNull(outcome.injuryId)
        assertTrue(repo.setsFor(id).none { it.exerciseSlug == "pull.pull_up" && !it.isCompleted })
        val injury = repo.activeInjuries().single()
        assertEquals(InjuryRegion.ELBOW, injury.region)
        assertTrue(injury.climbingPaused)
    }
}
