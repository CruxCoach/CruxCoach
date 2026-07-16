package com.cruxcoach.android.ui.community

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.community.OwnKilterClimbPublisher
import com.cruxcoach.android.community.isCommunityPublished
import com.cruxcoach.android.ui.board.OwnPublishFeedback
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.CommunityClimbRow
import com.cruxcoach.domain.board.BoardBrand
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import com.cruxcoach.android.util.safeLaunch

/** Status bucket of a "Meine Climbs" row — drives both the section it
 *  lands in and the trailing affordance (badge vs claim/publish button). */
enum class MyClimbStatus {
    /** CruxCoach-authored, not yet published — a local draft. */
    DRAFT,

    /** Published to the CruxCoach community (Kind-30078 recorded). */
    PUBLISHED,

    /** CruxCoach-authored, a previous publish attempt failed — the retry
     *  worker will pick it up again. */
    PUBLISH_PENDING,

    /** Imported from Kilter and authored by the connected account, but
     *  not yet published on CruxCoach — claimable via native publish. */
    KILTER_UNCLAIMED,
}

/** One row of the "Meine Climbs" hub: a climb the user authored (Kilter
 *  import or CruxCoach-native), with its lifecycle status + board. */
data class MyClimbItem(
    val uuid: String,
    val name: String,
    /** Angle for the detail-screen navigation (stats row, fallback 40°). */
    val angle: Int,
    val status: MyClimbStatus,
    /** Board the climb belongs to — shown as a badge so the cross-board
     *  hub stays legible. */
    val boardBrand: BoardBrand,
    /** Setter grade id, or null when the imported climb is ungraded. A
     *  KILTER_UNCLAIMED row without a grade routes the claim through the
     *  grade picker instead of a one-tap publish (difficulty is a must-have). */
    val setterGradeId: Int? = null,
)

data class MyKilterClimbsState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    /** False when no Kilter account is connected — gates the empty-state
     *  copy and the meaning of an empty "imported" section. */
    val hasKilterConnection: Boolean = true,
    /** False when no local Nostr identity exists — without it there can be
     *  no CruxCoach drafts/published climbs. */
    val hasNostrIdentity: Boolean = true,
    val climbs: List<MyClimbItem> = emptyList(),
    /** Uuid of an in-flight publish (disables all publish buttons). */
    val publishingUuid: String? = null,
    /** Non-null while the grade picker is open for an ungraded Kilter climb
     *  the user chose to claim — difficulty must be set before it publishes. */
    val gradeDialogUuid: String? = null,
    /** One-shot publish feedback (snackbar), shared with the other
     *  publish surfaces. */
    val feedback: OwnPublishFeedback? = null,
)

/**
 * "Meine Climbs": the single hub for every climb the user authored —
 * CruxCoach-native ones (drafts + published, across all boards, by Nostr
 * identity) UNION the Kilter climbs the connected account authored
 * (kilter_author_uuid match). Each row is classified into a [MyClimbStatus]
 * so drafts, published climbs, and not-yet-claimed Kilter imports are
 * clearly separated; the imports offer a native-publish ("claim") action.
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
        viewModelScope.safeLaunch(TAG) {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            try {
                val result = withContext(Dispatchers.IO) {
                    val connected = ownClimbPublisher.hasConnectedKilterAccount()
                    val hasNostr = ownClimbPublisher.hasNostrIdentity()

                    // CruxCoach-native climbs carry the authoritative draft /
                    // published status, so they are mapped FIRST and win on a
                    // uuid collision. A Kilter climb the user already adopted +
                    // published has BOTH a kilter_author_uuid and a
                    // created_by_pubkey, so it surfaces in both queries — the
                    // dedup below keeps only the CruxCoach classification.
                    val byUuid = LinkedHashMap<String, MyClimbItem>()
                    for (row in ownClimbPublisher.getMyCruxCoachClimbs()) {
                        val key = row.uuid.lowercase()
                        if (key !in byUuid) byUuid[key] = row.toItem()
                    }
                    for (row in ownClimbPublisher.getOwnAuthoredClimbs()) {
                        val key = row.uuid.lowercase()
                        if (key !in byUuid) byUuid[key] = row.toItem()
                    }
                    Triple(connected, hasNostr, byUuid.values.toList())
                }
                _state.update {
                    it.copy(
                        isLoading = false,
                        loadFailed = false,
                        hasKilterConnection = result.first,
                        hasNostrIdentity = result.second,
                        climbs = result.third,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "refresh failed", e)
                _state.update { it.copy(isLoading = false, loadFailed = true) }
            }
        }
    }

    /** Classify a raw row into a hub item. Published wins over everything;
     *  a CruxCoach-authored (pubkey-stamped) unpublished row is a draft (or
     *  a pending retry); anything else here is a Kilter import awaiting a
     *  claim. */
    private fun CommunityClimbRow.toItem(): MyClimbItem {
        val stats = runCatching { boardRepository.getClimbStatsForUuid(uuid) }.getOrNull()
        val status = when {
            isCommunityPublished -> MyClimbStatus.PUBLISHED
            !createdByPubkey.isNullOrBlank() ->
                if (syncStatus == "failed") MyClimbStatus.PUBLISH_PENDING else MyClimbStatus.DRAFT
            else -> MyClimbStatus.KILTER_UNCLAIMED
        }
        return MyClimbItem(
            uuid = uuid,
            name = name,
            angle = stats?.first ?: 40,
            status = status,
            boardBrand = BoardBrand.fromWire(boardBrand),
            setterGradeId = stats?.second,
        )
    }

    /**
     * Claim (= publish to the community) an imported Kilter climb. Difficulty
     * is a must-have: a graded climb publishes in one tap with its grade; an
     * ungraded one opens the grade picker first so it never goes out with a
     * silent default.
     */
    fun claim(item: MyClimbItem) {
        if (_state.value.publishingUuid != null) return
        if (item.setterGradeId == null) {
            _state.update { it.copy(gradeDialogUuid = item.uuid) }
        } else {
            doPublish(item.uuid, item.setterGradeId)
        }
    }

    /** Grade chosen in the picker → publish with it. */
    fun confirmGrade(uuid: String, setterGradeId: Int) {
        _state.update { it.copy(gradeDialogUuid = null) }
        doPublish(uuid, setterGradeId)
    }

    fun dismissGradeDialog() {
        _state.update { it.copy(gradeDialogUuid = null) }
    }

    private fun doPublish(uuid: String, setterGradeId: Int) {
        if (_state.value.publishingUuid != null) return
        _state.update { it.copy(publishingUuid = uuid) }
        viewModelScope.safeLaunch(TAG) {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    ownClimbPublisher.publish(uuid, setterGradeId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "publish threw", e)
                    null
                }
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
