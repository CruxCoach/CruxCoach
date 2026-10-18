package com.cruxcoach.app.ui

import com.cruxcoach.app.coach.TrainingHistoryPresenter
import com.cruxcoach.app.coach.TrainingHistoryRunState
import com.cruxcoach.app.training.Tx
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** One past training; tapping it opens its summary. */
class TrainingHistoryRowUi(
    val workoutId: String,
    /** The built-in routine's name, the title, else "Training". */
    val title: TrText,
    /** "Oct 12, 2026 · 45 min · 12 sets". */
    val details: TrText,
)

class TrainingHistoryMonthUi(
    /** "yyyy-MM"; Swift shows it as "October 2026". */
    val month: String,
    val rows: List<TrainingHistoryRowUi>,
)

class TrainingHistoryScreenState(
    val loading: Boolean,
    val isEmpty: Boolean,
    /** Newest month first, each newest training first. */
    val months: List<TrainingHistoryMonthUi>,
)

/** The training history (Android `TrainingHistoryScreen`): the last 100 finished trainings by month. */
class TrainingHistoryScreenModel internal constructor(private val presenter: TrainingHistoryPresenter) {
    val currentState: TrainingHistoryScreenState get() = map(presenter.state.value)

    fun watch(onState: (TrainingHistoryScreenState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    fun close() = presenter.close()

    private var lastGood: TrainingHistoryScreenState? = null

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(s: TrainingHistoryRunState): TrainingHistoryScreenState = try {
        mapUnsafe(s).also { lastGood = it }
    } catch (e: Exception) {
        mapFailures++
        println("${this::class.simpleName}: state not mapped: ${e.message}")
        lastGood ?: mapUnsafe(TrainingHistoryRunState())
    }

    private fun mapUnsafe(s: TrainingHistoryRunState) = TrainingHistoryScreenState(
        loading = s.loading,
        isEmpty = s.months.isEmpty(),
        months = s.months.map { (month, rows) ->
            TrainingHistoryMonthUi(month, rows.map { row ->
                val w = row.workout
                val details = listOfNotNull(
                    TrText("", listOf(Tx.date(w.day)), -1, ""),
                    w.durationMinutes?.let { Tx.p("trw_minutes", it, Tx.i(it)) },
                    Tx.p("trw_sets_done", row.sets, Tx.i(row.sets)),
                )
                TrainingHistoryRowUi(w.id, Tx.workoutTitle(w), Tx.join(details))
            })
        },
    )
}
