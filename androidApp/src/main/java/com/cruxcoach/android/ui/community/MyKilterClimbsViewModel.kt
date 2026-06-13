package com.cruxcoach.android.ui.community

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.community.OwnKilterClimbPublisher
import com.cruxcoach.android.community.isCommunityPublished
import com.cruxcoach.android.ui.board.OwnPublishFeedback
import com.cruxcoach.data.repository.BoardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of the "Meine Climbs" list: an own-AUTHORED Kilter climb with
 *  its community publish state. */
data class MyClimbItem(
    val uuid: String,
    val name: String,
    /** Angle for the detail-screen navigation (stats row, fallback 40°). */
    val angle: Int,
    val published: Boolean,
    /** True while a previous publish attempt sits in the retry queue. */
    val publishFailed: Boolean,
)

data class MyKilterClimbsState(
    val isLoading: Boolean = true,
    /** False when no Kilter account is connected — the screen explains
     *  that the list needs a connected account instead of showing the
     *  generic empty state. */
    val hasKilterConnection: Boolean = true,
    val climbs: List<MyClimbItem> = emptyList(),
    /** Uuid of an in-flight publish (disables all publish buttons). */
    val publishingUuid: String? = null,
    /** One-shot publish feedback (snackbar), shared with the other
     *  publish surfaces. */
    val feedback: OwnPublishFeedback? = null,
)

/**
 * "Meine Climbs": every Kilter climb the CONNECTED account authored
 * (kilter_author_uuid identity match — the same gate as the climb-detail
 * and logbook surfaces), each with its CruxCoach-community publish state
 * and a publish action for the not-yet-published ones.
 */
@HiltViewModel
class MyKilterClimbsViewModel @Inject constructor(
    private val ownClimbPublisher: OwnKilterClimbPublisher,
    private val boardRepository: BoardRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MyKilterClimbsState())
    val state: StateFlow<MyKilterClimbsState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val (connected, items) = withContext(Dispatchers.IO) {
                    val connected = ownClimbPublisher.hasConnectedKilterAccount()
                    val items = ownClimbPublisher.getOwnAuthoredClimbs().map { row ->
                        MyClimbItem(
                            uuid = row.uuid,
                            name = row.name,
                            angle = runCatching { boardRepository.getClimbStatsForUuid(row.uuid) }
                                .getOrNull()?.first ?: 40,
                            published = row.isCommunityPublished,
                            publishFailed = row.syncStatus == "failed",
                        )
                    }
                    connected to items
                }
                _state.update {
                    it.copy(isLoading = false, hasKilterConnection = connected, climbs = items)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "refresh failed", e)
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun publish(uuid: String) {
        if (_state.value.publishingUuid != null) return
        _state.update { it.copy(publishingUuid = uuid) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { ownClimbPublisher.publish(uuid) }
                    .onFailure { Log.w(TAG, "publish threw uuid=$uuid", it) }
                    .getOrNull()
            }
            val feedback = when (outcome) {
                is OwnKilterClimbPublisher.Outcome.Published -> OwnPublishFeedback.Published
                OwnKilterClimbPublisher.Outcome.NotAuthor -> OwnPublishFeedback.NotAuthor
                OwnKilterClimbPublisher.Outcome.NoNostrIdentity -> OwnPublishFeedback.NoNostrIdentity
                OwnKilterClimbPublisher.Outcome.AlreadyPublished -> OwnPublishFeedback.AlreadyPublished
                is OwnKilterClimbPublisher.Outcome.Failed, null -> OwnPublishFeedback.Failed
            }
            _state.update { it.copy(publishingUuid = null, feedback = feedback) }
            refresh()
        }
    }

    fun consumeFeedback() {
        _state.update { it.copy(feedback = null) }
    }

    companion object {
        private const val TAG = "MyKilterClimbsVM"
    }
}
