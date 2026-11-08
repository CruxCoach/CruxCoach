package com.cruxcoach.android.ui.yearreview

import app.cash.sqldelight.Query
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.data.repository.ClimbRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.db.athlete.AthleteDatabase
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.yearreview.TaggedSend
import com.cruxcoach.domain.yearreview.TimedSession
import com.cruxcoach.domain.yearreview.YearReviewComputer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * Everything the year in review (#64) reads, from the board logbook and the
 * training data on this phone. Nothing is written here. Spray walls are not read
 * yet: they join with the logbook statistics, and the intro says so.
 */
@Singleton
class YearReviewSource @Inject constructor(
    private val personalBoardRepo: PersonalBoardRepository,
    private val athleteService: AthleteService,
    private val climbRepository: ClimbRepository,
    private val secureDb: SecureDatabase,
    // Opened on first use, not with the start screen.
    private val athleteDb: dagger.Lazy<AthleteDatabase>,
) {
    /** The review's clock: real time, unless a test moves it (see [YearReviewTestDate]). */
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    /** Read on every call, so a time zone change on the phone moves "today" at once. */
    val zone: TimeZone get() = TimeZone.currentSystemDefault()

    fun today(): LocalDate =
        YearReviewTestDate.today ?: Instant.fromEpochMilliseconds(clock()).toLocalDateTime(zone).date

    private val writeCount = AtomicLong()

    // SQLDelight calls a query's listeners after every write to its tables, in a transaction once it is done.
    private val listening = lazy {
        val listener = Query.Listener { writeCount.incrementAndGet() }
        secureDb.ascentsQueries.countUserLogbook().addListener(listener) // sends and attempts
        secureDb.climbLogsQueries.getAll().addListener(listener)
        athleteDb.get().coachQueries.getAllClimbingDays().addListener(listener)
    }

    /**
     * Goes up with every write to what decides whether a year reaches the full
     * review: board sends and attempts, climbing days and climbs logged by hand.
     * Counts from the first [load]; a read that starts at the same count is current.
     */
    fun writes(): Long = writeCount.get()

    suspend fun load(): YearReviewComputer.Input = withContext(Dispatchers.IO) {
        listening.value
        val athlete = athleteService.repo
        val handLogged = climbRepository.getAll()
        YearReviewComputer.Input(
            // Sends with their problem's frames, for the holds touched; the light logbook has the attempts.
            entries = personalBoardRepo.getUserAscentsAll() + personalBoardRepo.getUserLogbookAllLight().filter { !it.isSend },
            climbingDays = athlete.allClimbingDays(),
            timedSessions = personalBoardRepo.getAllBoardSessions().map {
                TimedSession(
                    startedAt = it.startedAt,
                    activeSeconds = (it.totalDurationSeconds - it.pauseDurationSeconds).coerceAtLeast(0),
                    ended = !it.endedAt.isNullOrEmpty(),
                )
            },
            workouts = athlete.workoutsBetween("0000-01-01", "9999-12-31"),
            benchmarks = athlete.allBenchmarks(),
            injuries = athlete.allInjuries(),
            taggedSends = handLogged.filter { it.sent && it.holdTypes.isNotEmpty() }.map { TaggedSend(it.date, it.holdTypes) },
            handLoggedDays = handLogged.map { it.date },
        )
    }
}

/**
 * A pretend date for checking the review on a phone before December: feature
 * builds only, kept in memory until the app closes. Release builds always use
 * the real date.
 */
object YearReviewTestDate {
    val available: Boolean get() = BuildConfig.DEBUG

    @Volatile var today: LocalDate? = null
        get() = field.takeIf { available }
}
