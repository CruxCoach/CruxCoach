package com.cruxcoach.android.athlete.sim

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.athlete.ClimbingDaysReader
import com.cruxcoach.android.athlete.ExerciseCatalogStore
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.athlete.data.AthleteRepository
import com.cruxcoach.db.athlete.AthleteDatabase
import com.cruxcoach.db.secure.SecureDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.atStartOfDayIn
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The training simulation (issue #59): synthetic athletes run day by day through the real training service,
 * and automatic checks look for what goes wrong ([Checks]). The fast subset runs with the unit tests; the full
 * run of every persona is started with `SIM_FULL=1` and writes its reports to `build/training-simulation`
 * (see `docs/en/TRAINING_SIMULATION.md`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TrainingSimulationTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    /** One persona in a fresh database, as a new install would start. */
    private fun simulate(persona: Persona): SimRun {
        val athleteDriver = AndroidSqliteDriver(AthleteDatabase.Schema, context, null)
        val secureDriver = AndroidSqliteDriver(SecureDatabase.Schema, context, null)
        try {
            val clock = SimClock()
            val repo = AthleteRepository(AthleteDatabase(athleteDriver), Dispatchers.IO) { clock.nowMs }
            val boardRepo = mockk<com.cruxcoach.data.repository.PersonalBoardRepository>(relaxed = true)
            every { boardRepo.getActiveSession() } returns null
            val service = AthleteService(
                repoLazy = { repo },
                catalogStore = ExerciseCatalogStore(context) { repo },
                climbingDays = ClimbingDaysReader(SecureDatabase(secureDriver)),
                bodyStatRepository = mockk(relaxed = true),
                sessionManager = BoardSessionManager(boardRepo, mockk(relaxed = true), mockk(relaxed = true)),
            )
            service.clock = { clock.nowMs }
            val sim = Simulator(service, clock, persona)
            clock.nowMs = kotlinx.datetime.LocalDate.parse("2026-01-05").atStartOfDayIn(service.zone).toEpochMilliseconds() + 7 * 3_600_000L
            runBlocking { service.ensureReady() }
            return sim.run()
        } finally {
            athleteDriver.close(); secureDriver.close()
        }
    }

    private fun write(name: String, text: String) {
        val dir = File("build/training-simulation").apply { mkdirs() }
        File(dir, name).writeText(text)
    }

    /**
     * CI: five personas, three weeks each. The safety and exercise-choice checks must hold; everything else is reported.
     */
    @Test
    fun `fast subset keeps the safety rules`() {
        val runs = Persona.FAST.map { p -> simulate(p).let { it to Checks.run(it) } }
        runs.forEach { (r, flags) -> write("fast-${r.persona.id}.md", SimReport.persona(r, flags)) }
        val safety = runs.flatMap { (r, flags) -> flags.filter { it.check.group == "Safety" }.map { r.persona.id to it } }
        assertTrue("safety flags: $safety", safety.isEmpty())
        // Well-known exercises first (issue #68): the exercise-choice checks hold too.
        val choice = runs.flatMap { (r, flags) -> flags.filter { it.check.group == "Choice" }.map { r.persona.id to it } }
        assertTrue("exercise choice flags: $choice", choice.isEmpty())
        runs.forEach { (r, _) -> assertTrue("${r.persona.id} never trained", r.days.any { it.trained }) }
        runs.filter { (r, _) -> r.persona.events.any { it is Event.Injury && it.comeback } }.forEach { (r, _) ->
            assertTrue("${r.persona.id} did no comeback session", r.days.any { it.comeback?.sessions?.isNotEmpty() == true })
        }
    }

    /** Every persona for its whole run; reports only, started with SIM_FULL=1. */
    @Test
    fun `full run`() {
        assumeTrue("set SIM_FULL=1 for the full simulation", System.getenv("SIM_FULL") == "1")
        val only = System.getenv("SIM_ONLY")?.split(',')?.map { it.trim() }?.toSet()
        val runs = Persona.ALL.filter { only == null || it.id in only }.map { p ->
            val r = simulate(p)
            val flags = Checks.run(r)
            write("${p.id}.md", SimReport.persona(r, flags))
            write("${p.id}-days.md", SimReport.days(r))
            r to flags
        }
        write("overview.md", SimReport.overview(runs))
    }
}
