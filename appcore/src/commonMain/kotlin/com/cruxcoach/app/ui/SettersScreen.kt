package com.cruxcoach.app.ui

import com.cruxcoach.app.community.SetterDetailPresenter
import com.cruxcoach.app.community.SetterDetailState
import com.cruxcoach.app.community.SettersListPresenter
import com.cruxcoach.app.community.SettersListState
import com.cruxcoach.app.logbook.BoardStatsComputer
import com.cruxcoach.app.settings.GradeScale
import com.cruxcoach.data.repository.SetterClimbEntry

class SetterRowUi(
    val pubkey: String,
    val displayName: String,
    val climbCount: Long,
)

class SettersScreenState(
    val isLoading: Boolean,
    val setters: List<SetterRowUi>,
    val failed: Boolean,
)

class SetterClimbRowUi(
    val uuid: String,
    val name: String,
    val angle: Int,
    val grade: String,
    val quality: String,
    val sends: Long,
)

class SetterDetailScreenState(
    val pubkey: String,
    val displayName: String,
    val about: String,
    val pictureUrl: String,
    val lightningAddress: String,
    val climbs: List<SetterClimbRowUi>,
    val isLoading: Boolean,
    val failed: Boolean,
)

/** Swift-facing list of community setters on the active board. */
class SettersScreenModel(private val presenter: SettersListPresenter) {

    val currentState: SettersScreenState get() = map(presenter.state.value)

    fun watch(onState: (SettersScreenState) -> Unit): Subscription {
        val watch = presenter.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun refresh() = presenter.refresh()
    fun close() = presenter.close()

    private fun map(state: SettersListState) = SettersScreenState(
        isLoading = state.isLoading,
        setters = state.setters.map {
            SetterRowUi(
                pubkey = it.pubkey,
                displayName = it.displayName?.takeIf { name -> name.isNotBlank() }
                    ?: "npub:${it.pubkey.take(16)}",
                climbCount = it.climbCount,
            )
        },
        failed = state.failed,
    )
}

/** Swift-facing page for one setter. */
class SetterDetailScreenModel(
    private val presenter: SetterDetailPresenter,
    /** "FRENCH" or "V_SCALE", as `SettingsModel.gradeScale` spells it. */
    gradeScale: String,
) {
    private val scale = if (gradeScale == "V_SCALE") GradeScale.V_SCALE else GradeScale.FRENCH

    val currentState: SetterDetailScreenState get() = map(presenter.state.value)

    fun watch(onState: (SetterDetailScreenState) -> Unit): Subscription {
        val watch = presenter.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun refresh() = presenter.refresh()
    fun close() = presenter.close()

    private fun map(state: SetterDetailState) = SetterDetailScreenState(
        pubkey = state.pubkey,
        displayName = state.displayName,
        about = state.about,
        pictureUrl = state.pictureUrl,
        lightningAddress = state.lightningAddress,
        climbs = state.climbs.map { row(it) },
        isLoading = state.isLoading,
        failed = state.failed,
    )

    private fun row(entry: SetterClimbEntry) = SetterClimbRowUi(
        uuid = entry.uuid,
        name = entry.name,
        angle = entry.angle,
        grade = entry.difficultyAverage
            ?.let { BoardStatsComputer.formatDifficulty(it, scale) } ?: "",
        quality = entry.qualityAverage?.let { formatOneDecimal(it) } ?: "",
        sends = entry.ascensionistCount,
    )

    private fun formatOneDecimal(value: Double): String =
        com.cruxcoach.util.formatDecimal(value, 1)
}
