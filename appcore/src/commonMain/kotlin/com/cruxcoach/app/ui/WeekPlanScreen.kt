package com.cruxcoach.app.ui

import com.cruxcoach.app.library.WeekPlanPresenter
import com.cruxcoach.app.library.WeekPlanState
import com.cruxcoach.app.util.watch
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_REST

/** A plan entry the chooser offers. [entry] "" is a free day. */
class PlanOptionUi(val entry: String, val label: LibraryText, val selected: Boolean)

class PlanDayUi(
    /** 1 = Monday … 7 = Sunday. */
    val weekday: Int,
    val hasEntry: Boolean,
    val label: LibraryText,
    val proposalLabel: LibraryText,
    /** The options of the chooser dialog for this day, in Android's order. */
    val options: List<PlanOptionUi>,
)

class WeekPlanScreenState(
    val isLoading: Boolean,
    val failed: Boolean,
    val days: List<PlanDayUi>,
    val hasProposal: Boolean,
    /** The proposal equals the current week: nothing to apply. */
    val proposalSame: Boolean,
    val canUndo: Boolean,
    val sessionMinutes: Int,
    val minuteOptions: List<Int>,
)

/** The standard week (Android `WeekPlanScreen`). */
class WeekPlanScreenModel internal constructor(private val presenter: WeekPlanPresenter) {

    val currentState: WeekPlanScreenState get() = map(presenter.state.value)

    fun watch(onState: (WeekPlanScreenState) -> Unit): Subscription {
        val watch = presenter.state.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    /** [entry] "" = free day; otherwise an option's entry. */
    fun setDay(weekday: Int, entry: String) {
        if (weekday !in 1..7) return
        presenter.set(weekday, entry.ifEmpty { null })
    }

    fun setMinutes(minutes: Int) {
        if (minutes in WeekPlanPresenter.SESSION_MINUTES) presenter.setMinutes(minutes)
    }

    fun applyProposal() = presenter.applyProposal()
    fun undoProposal() = presenter.undoProposal()
    fun dropUndo() = presenter.dropUndo()
    fun close() = presenter.close()

    private fun map(s: WeekPlanState): WeekPlanScreenState {
        // null = free day; the other options are stored as their plan entry.
        val entries: List<String?> = listOf<String?>(null, PLAN_REST, PLAN_BOARD) +
            s.routines.map { it.id } + BuiltinRoutines.all.map { "builtin:" + it.builtinKey }
        return WeekPlanScreenState(
            isLoading = s.loading,
            failed = s.failed,
            days = (1..7).map { day ->
                val current = s.profile.weekPlan[day]
                PlanDayUi(
                    weekday = day,
                    hasEntry = current != null,
                    label = LibraryTexts.planEntry(current, s.routines),
                    proposalLabel = LibraryTexts.planEntry(s.proposal[day], s.routines),
                    options = entries.map { e -> PlanOptionUi(e ?: "", LibraryTexts.planEntry(e, s.routines), e == current) },
                )
            },
            hasProposal = s.proposal.isNotEmpty(),
            proposalSame = s.proposal == s.profile.weekPlan,
            canUndo = s.canUndo,
            sessionMinutes = s.profile.sessionMinutes,
            minuteOptions = WeekPlanPresenter.SESSION_MINUTES,
        )
    }
}
