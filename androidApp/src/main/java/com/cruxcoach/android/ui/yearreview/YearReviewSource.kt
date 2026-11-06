package com.cruxcoach.android.ui.yearreview

import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.yearreview.TimedSession
import com.cruxcoach.domain.yearreview.YearReviewComputer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * Everything the year in review (#64) reads, from the board logbook and the
 * training data on this phone. Nothing is written here.
 */
@Singleton
class YearReviewSource @Inject constructor(
    private val personalBoardRepo: PersonalBoardRepository,
    private val athleteService: AthleteService,
) {
    /** The review's clock: real time, unless a test moves it (see [YearReviewTestDate]). */
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    /** Read on every call, so a time zone change on the phone moves "today" at once. */
    val zone: TimeZone get() = TimeZone.currentSystemDefault()

    fun today(): LocalDate =
        YearReviewTestDate.today ?: Instant.fromEpochMilliseconds(clock()).toLocalDateTime(zone).date

    suspend fun load(): YearReviewComputer.Input = withContext(Dispatchers.IO) {
        val athlete = athleteService.repo
        YearReviewComputer.Input(
            entries = personalBoardRepo.getUserLogbookAllLight(),
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
