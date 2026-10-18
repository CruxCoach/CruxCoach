package com.cruxcoach.app.kilter

import com.cruxcoach.app.community.CommunityPublisher
import com.cruxcoach.app.community.PublishFailure
import com.cruxcoach.app.ui.Subscription
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.CommunityClimbRow
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.KilterGradeMapper
import com.cruxcoach.domain.community.ClimbEditorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Port of Android's `OwnKilterClimbPublisher`: publishes a climb the user SET
 * in the Kilter app to the CruxCoach community.
 *
 * Authorship gate: only when the climb's recorded `kilter_author_uuid` equals
 * the connected account's user uuid — identity, never a display name. A climb
 * the user merely logged is never publishable here. The climb keeps its Kilter
 * uuid: the row is converted in place and published through the ordinary
 * community publisher; the adoption marks it Kilter-synced, as it already
 * lives there.
 */
class OwnKilterClimbPublisher(
    private val boardRepository: BoardRepository,
    private val account: KilterClimbAccount,
    private val communityPublisher: CommunityPublisher,
    private val pubkeyProvider: () -> String?,
    private val clockSeconds: () -> Long,
) {
    /** published | notAuthor | noNostrIdentity | alreadyPublished | failed */
    class Outcome(val code: String)

    /** Own-authored and not yet on the community: the detail and logbook offer "publish as mine". */
    fun isPublishableAsMine(uuid: String): Boolean {
        val connected = account.userUuid().takeIf { it.isNotBlank() } ?: return false
        val canonical = canonicalUuid(uuid) ?: return false
        val row = runCatching { boardRepository.getOwnAuthoredClimbRow(canonical, connected) }.getOrNull() ?: return false
        return !row.isCommunityPublished()
    }

    /**
     * Converts and publishes. [setterGradeId] null falls back to the stored
     * grade, then the editor default, as on Android's one-tap surfaces; the
     * resolved grade is written locally so the climb shows it.
     */
    suspend fun publish(uuid: String, setterGradeId: Int? = null): Outcome {
        val connected = account.userUuid().takeIf { it.isNotBlank() } ?: return Outcome("notAuthor")
        val canonical = canonicalUuid(uuid) ?: return Outcome("notAuthor")
        val row = runCatching { boardRepository.getOwnAuthoredClimbRow(canonical, connected) }.getOrNull()
            ?: return Outcome("notAuthor")
        if (row.isCommunityPublished()) return Outcome("alreadyPublished")
        val pubkey = pubkeyProvider()?.takeIf { it.isNotBlank() } ?: return Outcome("noNostrIdentity")

        val adopted = runCatching {
            boardRepository.adoptKilterClimbAsCommunity(canonical, connected, pubkey, clockSeconds())
        }.getOrDefault(false)
        if (!adopted) return Outcome("failed")

        // The event needs angle and grade; a curated row may have no graded stat.
        val stats = runCatching { boardRepository.getClimbStatsForUuid(canonical) }.getOrNull()
        val angle = stats?.first ?: 40
        val grade = setterGradeId ?: stats?.second ?: KilterGradeMapper.DEFAULT_SETTER_GRADE_ID
        runCatching {
            boardRepository.upsertClimbStat(
                climbUuid = canonical,
                angle = angle.toLong(),
                displayDifficulty = grade.toDouble(),
                difficultyAverage = grade.toDouble(),
                qualityAverage = null,
                ascensionistCount = null,
                benchmarkDifficulty = null,
                faUsername = null,
                faAt = null,
                officialKilterDifficulty = null,
            )
        }
        val state = ClimbEditorState(
            selectedHolds = BoardClimbParser.parseFrames(row.framesText).associate { it.placementId to it.roleId },
            name = row.name,
            description = row.description,
            setterGradeId = grade,
            angle = angle,
        )
        val context = runCatching { boardRepository.getClimbPublishContext(canonical) }.getOrNull()
        val brand = BoardBrand.fromWire(context?.boardBrand ?: row.boardBrand)
        return try {
            val result = communityPublisher.publish(
                uuid = canonical,
                layoutId = context?.layoutId ?: row.layoutId,
                boardBrand = brand,
                state = state.copy(boardBrand = brand.wireValue),
                sizeLabel = context?.sizeLabel ?: "",
            )
            Outcome(if (result.failure == PublishFailure.NONE) "published" else "failed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome("failed")
        }
    }

    /** Format-blind: the DB mixes dashed-lower and nodash-UPPER spellings. */
    private fun canonicalUuid(uuid: String): String? {
        if (uuid.isBlank()) return null
        return runCatching { boardRepository.findClimbCanonicalUuid(uuid) }.getOrNull() ?: uuid
    }

    private fun CommunityClimbRow.isCommunityPublished(): Boolean =
        !nostrEventId.isNullOrBlank() || syncStatus == "published_nostr"
}

/**
 * The climb detail's "Publish as mine" (Android `canPublishAsMine`,
 * `publishOwnClimb`, `ownPublishFeedback`). [feedback]: none | published |
 * noNostrIdentity | notAuthor | alreadyPublished | failed.
 */
class OwnKilterClimbState(
    val uuid: String,
    val canPublishAsMine: Boolean,
    val inProgress: Boolean,
    val feedback: String,
)

class OwnKilterClimbModel internal constructor(
    private val publisher: OwnKilterClimbPublisher,
    main: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val state = MutableStateFlow(OwnKilterClimbState("", false, false, "none"))

    val currentState: OwnKilterClimbState get() = state.value

    fun watch(onState: (OwnKilterClimbState) -> Unit): Subscription {
        val job = scope.launch { state.collect { onState(it) } }
        return Subscription { job.cancel() }
    }

    /** Re-evaluates the gate for the climb on screen (the detail pages through climbs). */
    fun load(uuid: String) {
        state.value = OwnKilterClimbState(uuid, false, false, "none")
        scope.launch {
            val publishable = withContext(io) { runCatching { publisher.isPublishableAsMine(uuid) }.getOrDefault(false) }
            state.update { if (it.uuid == uuid) OwnKilterClimbState(uuid, publishable, it.inProgress, it.feedback) else it }
        }
    }

    fun publish() {
        val current = state.value
        if (current.uuid.isEmpty() || current.inProgress) return
        state.value = OwnKilterClimbState(current.uuid, current.canPublishAsMine, true, "none")
        scope.launch {
            val code = try {
                withContext(io) { publisher.publish(current.uuid) }.code
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "failed"
            }
            val stillPublishable = withContext(io) {
                runCatching { publisher.isPublishableAsMine(current.uuid) }.getOrDefault(false)
            }
            state.update {
                if (it.uuid == current.uuid) OwnKilterClimbState(current.uuid, stillPublishable, false, code) else it
            }
        }
    }

    fun consumeFeedback() = state.update { OwnKilterClimbState(it.uuid, it.canPublishAsMine, it.inProgress, "none") }

    fun close() = scope.cancel()
}
