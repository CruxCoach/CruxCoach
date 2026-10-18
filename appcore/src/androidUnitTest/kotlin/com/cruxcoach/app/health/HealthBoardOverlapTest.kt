package com.cruxcoach.app.health

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.AthleteTestKit
import com.cruxcoach.athlete.data.AthleteRepository
import com.cruxcoach.athlete.logic.BoardOverlap
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of Android's HealthBoardOverlapTest (holistic audit W3 / P0-3): a climbing workout recorded during a
 * board session is not counted twice — fake workouts go through the real import, board data through the real
 * board database. The service assertions are Android's; the sheet and list texts Android checks in Compose
 * are the climbing-day screen's (ported elsewhere), so "count anyway" is set through the service here.
 */
class HealthBoardOverlapTest {

    private lateinit var kit: AthleteTestKit
    private lateinit var repo: AthleteRepository
    private lateinit var service: AthleteService
    private lateinit var board: PersonalBoardRepositoryImpl
    private lateinit var health: HealthSync

    private val zone: TimeZone = TimeZone.currentSystemDefault()
    private val today: String get() = service.today().toString()

    @BeforeTest
    fun setUp() {
        kit = AthleteTestKit()
        repo = kit.repo
        service = kit.service
        runBlocking { service.ensureReady() }
        board = PersonalBoardRepositoryImpl(kit.secureDb)
        health = HealthSync(service, { null }, MemoryHealthKeyValues(), Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() = kit.close()

    private fun at(time: String): Long = LocalDateTime.parse("${today}T$time").toInstant(zone).toEpochMilliseconds()

    private fun boardSession(from: String, to: String) {
        board.insertBoardSession("${today}T$from", "${today}T$to", (at(to) - at(from)) / 1000, 0L, 0L, 0L)
    }

    private fun attempt(uuid: String, time: String) = board.insertAscent(
        uuid = uuid, climbUuid = "c-$uuid", angle = 40L, isMirror = false, attemptId = 0L, bidCount = 1L,
        quality = 3L, difficulty = 15L, isBenchmark = false, comment = null, climbedAt = "${today}T$time", synced = false,
        gymUuid = null, wallUuid = null, productLayoutUuid = null, climbName = uuid, difficultyAverage = 15.0,
        climbFrames = "p100r12", framesCount = 1L, boardBrand = "kilter", layoutId = 1L, externalId = null,
    )

    private fun watch(id: String, title: String, from: String, to: String) =
        HealthSession(id, climbingType = true, title = title, notes = null, startMs = at(from), endMs = at(to))

    private fun import(vararg sessions: HealthSession): HealthSyncResult =
        health.importSessions(sessions.toList(), service.today().minus(DatePeriod(days = 13)), zone)

    private fun todayActivity() = service.activities(days = 1)[today]!!

    @Test
    fun `a watch session during a board session counts only the minutes outside it`() {
        boardSession("18:00:00", "18:30:00")
        val result = import(watch("hc1", "Bouldern Halle", "18:00:00", "19:00:00"))
        assertEquals(1, result.added)
        assertEquals(1, result.boardOverlaps)
        val entry = repo.climbingDayByExternalId("hc1")!!
        // The entry keeps what the watch recorded; 30 of its minutes are the board session's.
        assertEquals(60, entry.minutes)
        assertEquals(BoardOverlap.Stored(30), service.boardOverlap(entry.id))
        // Board 30 + the 30 minutes after it – not 90.
        assertEquals(60, todayActivity().climbingMinutes)
    }

    @Test
    fun `another board on a board day is the board session and can be counted anyway`() {
        boardSession("18:00:00", "18:30:00")
        import(watch("hc2", "Board 40°", "17:55:00", "19:00:00"))
        val entry = repo.climbingDayByExternalId("hc2")!!
        assertEquals(ClimbingDayKind.OTHER_BOARD, entry.kind)
        assertEquals(BoardOverlap.Stored(65), service.boardOverlap(entry.id))
        val notTwice = todayActivity()
        assertEquals(30, notTwice.climbingMinutes)

        // "Count anyway" is the athlete's way out (Android: the sheet's switch and save).
        service.saveBoardOverlap(entry.id, countAnyway = true)
        assertEquals(true, service.boardOverlap(entry.id)?.countAnyway)
        val counted = todayActivity()
        assertEquals(30 + 65, counted.climbingMinutes)
        assertTrue(counted.fingerLoad > notTwice.fingerLoad)

        // A later sync looks again but keeps the athlete's choice; deleting the day takes the overlap along.
        import(watch("hc2", "Board 40°", "17:55:00", "19:00:00"))
        assertEquals(BoardOverlap.Stored(65, countAnyway = true), service.boardOverlap(entry.id))
        service.deleteClimbingDay(entry.id)
        assertNull(service.boardOverlap(entry.id))
    }

    @Test
    fun `logged attempts cover their stretch and are not counted twice`() {
        // No board session, only attempts 18:10–18:50: they cover 18:05–18:55, 50 of the watch's 60 minutes.
        listOf("18:10:00", "18:30:00", "18:50:00").forEachIndexed { i, t -> attempt("a$i", t) }
        import(watch("hc3", "Bouldern", "18:00:00", "19:00:00"))
        val entry = repo.climbingDayByExternalId("hc3")!!
        assertEquals(BoardOverlap.Stored(50), service.boardOverlap(entry.id))
        val day = todayActivity()
        // The logbook has no session time: the watch's 50 minutes stand in for it, so the energy keeps the hour …
        assertEquals(60, day.climbingMinutes)
        // … while the finger load counts the attempts and only the ten extra minutes.
        service.saveBoardOverlap(entry.id, countAnyway = true)
        assertTrue(todayActivity().fingerLoad > day.fingerLoad)
        service.saveBoardOverlap(entry.id, countAnyway = false)
        assertEquals(BoardOverlap.Stored(50), service.boardOverlap(entry.id))
    }

    @Test
    fun `a fully covered watch session keeps its load while the board session has no time of its own`() {
        // A board session 18:00–19:00 that recorded no active time (all paused) and no logbook rows yet.
        board.insertBoardSession("${today}T18:00:00", "${today}T19:00:00", 3600L, 3600L, 0L, 0L)
        import(watch("hc5", "Bouldern Halle", "18:00:00", "19:00:00"))
        val entry = repo.climbingDayByExternalId("hc5")!!
        assertEquals(BoardOverlap.Stored(60), service.boardOverlap(entry.id))
        val day = todayActivity()
        // The watch's hour is the board time, for the energy and for the load.
        assertEquals(60, day.climbingMinutes)
        assertTrue(day.fingerLoad > 0.0)
    }

    @Test
    fun `a watch session at another time of the day counts in full`() {
        boardSession("09:00:00", "10:00:00")
        val result = import(watch("hc4", "Bouldern Halle", "18:00:00", "19:30:00"))
        assertEquals(0, result.boardOverlaps)
        val entry = repo.climbingDayByExternalId("hc4")!!
        assertNull(service.boardOverlap(entry.id))
        assertEquals(60 + 90, todayActivity().climbingMinutes)
    }
}
