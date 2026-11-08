package com.cruxcoach.android.ui.yearreview

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.domain.yearreview.YearReview
import com.cruxcoach.domain.yearreview.YearReviewComputer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject
import kotlin.time.Instant

/** When the year-in-review card invites: in December the running year "so far", in January the finished one. */
object YearReviewWindow {
    fun invitedYear(today: LocalDate): Int? = when (today.month.number) {
        12 -> today.year
        1 -> today.year - 1
        else -> null
    }
}

/** The full-screen review of one year. */
@HiltViewModel
class YearReviewViewModel @Inject constructor(
    private val source: YearReviewSource,
    private val userPreferences: UserPreferences,
    private val athleteService: AthleteService,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val year: Int = 0,
        val review: YearReview? = null,
        val gradeScale: GradeScale = GradeScale.FRENCH,
        /** The community display name, for the share image; empty when none is set. */
        val displayName: String = "",
        val training: TrainingContext = TrainingContext(null, null, null),
        /** The logbook could not be read; the screen says so instead of showing an empty year. */
        val failed: Boolean = false,
    )

    private val _state = MutableStateFlow(State(year = savedStateHandle.get<Int>(ARG_YEAR) ?: source.today().year))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    /** After a failed load. */
    fun retry() {
        _state.update { it.copy(loading = true, failed = false) }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val scale = userPreferences.gradeScale.first()
                val name = runCatching { userPreferences.leaderboardDisplayName.first() }.getOrDefault("")
                val input = source.load()
                val training = withContext(Dispatchers.IO) {
                    runCatching {
                        // The catalogue loads on first use; until then it is empty and no exercise has a name.
                        TrainingContext(athleteService.catalogStore.ensureLoaded(), athleteService.repo.profile(), athleteService.currentBodyweight())
                    }.getOrDefault(TrainingContext(null, null, null))
                }
                val review = withContext(Dispatchers.Default) {
                    YearReviewComputer.compute(
                        input, _state.value.year, source.today(), source.zone,
                        gradeLabel = { GradeDisplayHelper.formatDifficulty(it, scale) },
                    )
                }
                _state.update { it.copy(loading = false, review = review, gradeScale = scale, displayName = name.trim(), training = training) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "year review failed", e)
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    companion object {
        const val ARG_YEAR = "year"
        private const val TAG = "YearReviewVM"
    }
}

/**
 * The entries into the review: the card in December and January, and the year
 * picker in the statistics with its short "your year so far" below the minimum.
 */
@HiltViewModel
class YearReviewEntryViewModel @Inject constructor(
    private val source: YearReviewSource,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    data class State(
        val today: LocalDate? = null,
        /** Years with a full review, newest first: what the statistics offer to open. */
        val years: List<Int> = emptyList(),
        /** The year the card invites to, when it should show now. */
        val cardYear: Int? = null,
        val selectedYear: Int? = null,
        val selected: YearReview? = null,
        /**
         * The running year while it is below the minimum and an earlier year has a full
         * review: the statistics show how far it got below that year's button.
         */
        val current: YearReview? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var input: YearReviewComputer.Input? = null
    private var cardStarted = false
    private var statsStarted = false
    private val cardCheck = MutableStateFlow(0)

    /**
     * For the card, each time its screen resumes and at midnight: the date may have
     * moved into December, or into January, while the app was open. Reads the
     * logbook only in December and January and while the card is not hidden, so the
     * start screen stays light all year: again on a new day, and in December while
     * the card waits for the minimum, since a new climbing day may have reached it.
     * The statistics beside it read again on a new day, also with the card hidden.
     */
    fun checkCard() {
        cardCheck.value++
        if (cardStarted) return
        cardStarted = true
        viewModelScope.launch {
            combine(userPreferences.yearReviewHiddenYears, cardCheck) { hidden, _ -> hidden }.collect { hidden ->
                val today = source.today()
                val year = YearReviewWindow.invitedYear(today)
                val newDay = _state.value.today != today
                when {
                    year == null || year in hidden -> {
                        _state.update { it.copy(cardYear = null) }
                        if (statsStarted && newDay) load()
                    }
                    // In January the invited year is over: new climbing days cannot open it.
                    input == null || newDay || (_state.value.cardYear == null && year == today.year) -> load()
                    else -> refreshCard(hidden)
                }
            }
        }
    }

    /** How long until the next local midnight, when the card checks again while it stays on screen. */
    fun millisToNextDay(): Long {
        val zone = source.zone
        val now = Instant.fromEpochMilliseconds(source.clock())
        val midnight = now.toLocalDateTime(zone).date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
        return (midnight - now).inWholeMilliseconds.coerceAtLeast(0) + 1_000
    }

    /** For the year picker in the statistics, each time it opens; it reads again on a new day. */
    fun startStats() {
        if (statsStarted && _state.value.today == source.today()) return
        statsStarted = true
        reload()
    }

    /** Reads the logbook again, e.g. after the test date moved. */
    fun reload() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        try {
            val loaded = source.load()
            input = loaded
            val today = source.today()
            val years = withContext(Dispatchers.Default) { YearReviewComputer.yearsWithReview(loaded, today, source.zone) }
            // The running year below the minimum, beside the earlier years' reviews; empty, it says nothing.
            val current = if (statsStarted && years.isNotEmpty() && today.year !in years) {
                withContext(Dispatchers.Default) { YearReviewComputer.compute(loaded, today.year, today, source.zone) }.takeIf { !it.isEmpty() }
            } else {
                null
            }
            _state.update { it.copy(today = today, years = years, current = current) }
            refreshCard(userPreferences.yearReviewHiddenYears.first())
            // The card alone needs no year picked; without any full review the current year shows how far it got.
            if (statsStarted) {
                select(
                    _state.value.selectedYear?.takeIf { it in years }
                        ?: YearReviewWindow.invitedYear(today)?.takeIf { it in years }
                        ?: years.firstOrNull()
                        ?: today.year,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "year review entry failed", e)
        }
    }

    private var selecting: Job? = null

    /** The newest choice wins: a reload's pick never overwrites a chip tapped after it. */
    fun select(year: Int) {
        val loaded = input ?: return
        selecting?.cancel()
        selecting = viewModelScope.launch {
            val review = withContext(Dispatchers.Default) { YearReviewComputer.compute(loaded, year, source.today(), source.zone) }
            _state.update { it.copy(selectedYear = year, selected = review) }
        }
    }

    fun hideCard(year: Int) {
        viewModelScope.launch { userPreferences.hideYearReviewCard(year) }
    }

    /** Feature builds only: pretend today is [day], or the real date for null. */
    fun setTestDate(day: LocalDate?) {
        if (!YearReviewTestDate.available) return
        YearReviewTestDate.today = day
        reload()
    }

    private suspend fun refreshCard(hidden: Set<Int>) {
        val loaded = input ?: return
        val today = source.today()
        val year = YearReviewWindow.invitedYear(today)?.takeIf { it !in hidden }
        val show = year != null && withContext(Dispatchers.Default) {
            YearReviewComputer.compute(loaded, year, today, source.zone).hasEnoughData
        }
        _state.update { it.copy(cardYear = if (show) year else null) }
    }

    private companion object {
        const val TAG = "YearReviewEntryVM"
    }
}
