package com.cruxcoach.app.kilter

import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.community.ClimbEditorState
import kotlinx.coroutines.CancellationException

/**
 * What the creator's Kilter leg did after a successful Nostr publish: the
 * portal outcome (null when the leg did not run), and whether to nudge the
 * user to connect Kilter (publishing is on, but there is no login).
 */
class CreatorKilterResult(val outcome: KilterClimbPublishOutcome?, val nudgeToConnect: Boolean)

/** The Kilter step the climb editor runs after its Nostr publish. */
fun interface CreatorKilterLeg {
    suspend fun afterNostrPublish(
        uuid: String,
        layoutId: Long,
        brand: BoardBrand,
        state: ClimbEditorState,
        boardSize: BoardSize?,
        isEdit: Boolean,
    ): CreatorKilterResult
}

/**
 * Step 2 of Android's `CommunityClimbPublisher.publish`: best effort, after
 * Nostr accepted the climb. Kilter-only — MoonBoard and the Aurora family are
 * community-only and never pushed, mislabelled as Kilter, to the user's account.
 * A throw here is swallowed: the climb is already public on Nostr.
 */
class KilterClimbCreatorLeg(
    private val publisher: KilterClimbPublisher,
    private val account: KilterClimbAccount,
    private val boardRepository: BoardRepository,
    private val publishEnabled: () -> Boolean,
) : CreatorKilterLeg {
    override suspend fun afterNostrPublish(
        uuid: String,
        layoutId: Long,
        brand: BoardBrand,
        state: ClimbEditorState,
        boardSize: BoardSize?,
        isEdit: Boolean,
    ): CreatorKilterResult {
        if (!brand.supportsOfficialAppPublish || !publishEnabled()) return CreatorKilterResult(null, false)
        val hadLogin = account.isSignedIn()
        return try {
            // The portal wants hole ids, not placement ids (BoardClimbParser.encodeClimbConcat).
            val pidToHoleId = runCatching { boardRepository.getAllPlacements() }.getOrDefault(emptyList())
                .associate { it.placementId.toInt() to it.holeId }
            val concat = BoardClimbParser.encodeClimbConcat(
                state.selectedHolds.entries.sortedBy { it.key }.map { BoardHold(it.key, it.value) },
                pidToHoleId,
            )
            val prior = boardRepository.getKilterPublishState(uuid)?.status
            // 'rejected' is terminal for the queue, but an edit may fix exactly what
            // Kilter refused: re-open the lane so the claim can take the slot.
            if (isEdit && prior == "rejected") {
                boardRepository.markKilterPublishFailed(uuid, "re-eligible: user edit after rejection")
            }
            val outcome = if (isEdit && prior == "synced") {
                publisher.update(uuid, layoutId, state, boardSize, concat)
            } else {
                publisher.publish(uuid, layoutId, state, boardSize, concat)
            }
            val nudge = outcome.code == "skipped" && outcome.reason == "no-kilter-login" && !hadLogin
            CreatorKilterResult(outcome, nudge)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CreatorKilterResult(null, false)
        }
    }
}
