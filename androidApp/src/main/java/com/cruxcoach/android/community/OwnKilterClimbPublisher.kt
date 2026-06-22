package com.cruxcoach.android.community

import android.util.Log
import com.cruxcoach.android.data.kilter.KilterTokenStore
import com.cruxcoach.android.nostr.NostrIdentity
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.CommunityClimbRow
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.KilterGradeMapper
import com.cruxcoach.domain.community.ClimbEditorState
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OwnClimbPublisher"

/**
 * Publishes a user's OWN Kilter-authored climb to the CruxCoach community.
 *
 * AUTHORSHIP GATE (compliance + correct attribution): the action exists
 * ONLY for climbs whose recorded `kilter_author_uuid` equals the CONNECTED
 * Kilter account's userUuid ([KilterTokenStore.getUserUuid]). A climb the
 * user merely logged — but someone else set — is never publishable here:
 * publishing it would misattribute the setter's work and redistribute
 * third-party content without consent. Identity comparison only, never a
 * display-name match. Curated rows (author unknown, `kilter_author_uuid`
 * NULL) are equally excluded.
 *
 * IDENTITY: the climb KEEPS its original Kilter uuid. The existing local
 * row is converted IN PLACE to a community climb
 * ([BoardRepository.adoptKilterClimbAsCommunity]) and then routed through
 * the EXISTING [CommunityClimbPublisher] so the Kind-30078 event, the
 * `markClimbPublishInFlight`/`markClimbPublishedNostr` bookkeeping and the
 * retry-worker queue are all reused unchanged. The adoption stamps
 * `kilter_status='synced'` so the publisher's best-effort Kilter leg sees
 * an occupied slot and skips — the climb already lives on Kilter natively.
 */
@Singleton
class OwnKilterClimbPublisher @Inject constructor(
    private val boardRepository: BoardRepository,
    private val kilterTokenStore: KilterTokenStore,
    /** Interface (not the concrete NostrSigner): the Quartz-free identity
     *  facade keeps this class plain-JVM unit-testable. */
    private val nostrSigner: NostrIdentity,
    private val communityClimbPublisher: CommunityClimbPublisher,
) {
    sealed interface Outcome {
        data class Published(val nostrEventId: String) : Outcome

        /** Authorship gate failed: no connected Kilter account, unknown
         *  author (curated row), or a different author. The UI never
         *  offers the action in these cases — this is the backstop. */
        data object NotAuthor : Outcome

        /** No local Nostr identity (signer not initialised) — the user
         *  needs to set up their CruxCoach key before publishing. */
        data object NoNostrIdentity : Outcome

        data object AlreadyPublished : Outcome
        data class Failed(val message: String?) : Outcome
    }

    /** True when a Kilter account is connected (userUuid known) — without
     *  one the authorship gate can never open. */
    fun hasConnectedKilterAccount(): Boolean = kilterTokenStore.getUserUuid() != null

    /** True iff the connected Kilter account authored this climb. */
    fun isOwnAuthoredClimb(uuid: String): Boolean {
        val connected = kilterTokenStore.getUserUuid() ?: return false
        val canonical = canonicalUuid(uuid) ?: return false
        val author = runCatching { boardRepository.getClimbKilterAuthorUuid(canonical) }
            .getOrNull() ?: return false
        return author == connected
    }

    /**
     * Publishable-as-mine = own-authored AND not yet published to the
     * community ([CommunityClimbRow.nostrEventId] unset). A failed
     * publish stays publishable (retry).
     */
    fun isPublishableAsMine(uuid: String): Boolean {
        val connected = kilterTokenStore.getUserUuid() ?: return false
        val canonical = canonicalUuid(uuid) ?: return false
        val row = runCatching { boardRepository.getOwnAuthoredClimbRow(canonical, connected) }
            .getOrNull() ?: return false
        return !row.isCommunityPublished
    }

    /** Every climb the connected Kilter account authored (newest first).
     *  Empty when no Kilter account is connected. */
    fun getOwnAuthoredClimbs(): List<CommunityClimbRow> {
        // Blank (not just null) closes the gate: a session whose userUuid was
        // never persisted can surface "" — passing it to the query would
        // match foreign rows / nothing. KilterTokenStore.getUserUuid()
        // self-heals from the token's JWT sub before reaching here.
        val connected = kilterTokenStore.getUserUuid()?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return runCatching { boardRepository.getOwnAuthoredKilterClimbs(connected) }
            .getOrElse {
                Log.w(TAG, "getOwnAuthoredKilterClimbs failed", it)
                emptyList()
            }
    }

    /** Every CruxCoach climb authored under the local Nostr identity —
     *  drafts AND already-published — across ALL boards, newest first.
     *  Tombstoned (deleted) rows are excluded. Empty when no identity is
     *  set up yet. This is the CruxCoach-native half of "Meine Climbs";
     *  [getOwnAuthoredClimbs] supplies the Kilter-imported half. */
    fun getMyCruxCoachClimbs(): List<CommunityClimbRow> {
        val pubkey = runCatching { nostrSigner.getPublicKeyHex() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { boardRepository.getMyClimbs(pubkey) }
            .getOrElse {
                Log.w(TAG, "getMyClimbs failed", it)
                emptyList()
            }
            .filter { it.syncStatus != "deleted" }
    }

    /** True when a local Nostr identity exists — without it there are no
     *  CruxCoach-authored climbs and publishing is impossible. */
    fun hasNostrIdentity(): Boolean =
        runCatching { nostrSigner.getPublicKeyHex() }.getOrNull()?.isNotBlank() == true

    /**
     * Convert + publish. Mirrors the CommunityPublishRetryWorker's state
     * reconstruction (frames/name/description from the row, angle+grade
     * from climb_stats with the same fallbacks, brand/layout/size from
     * [BoardRepository.getClimbPublishContext]).
     *
     * [setterGradeId]: the difficulty to publish with. Pass it to ENFORCE a
     * real grade — the "Meine Climbs" hub supplies one (a grade picker for
     * the user's ungraded Kilter climbs) so a community climb is never
     * emitted with a silent default. Left null (the detail/logbook one-tap
     * surfaces), it falls back to the climb's stored grade, then the slider
     * default — preserving their existing behaviour. Whatever grade is
     * resolved is persisted to climb_stats so the published climb shows it
     * locally (the event carries it regardless, but the imported row was
     * ungraded).
     */
    suspend fun publish(uuid: String, setterGradeId: Int? = null): Outcome {
        val connected = kilterTokenStore.getUserUuid() ?: return Outcome.NotAuthor
        val canonical = canonicalUuid(uuid) ?: return Outcome.NotAuthor
        // Authorship-gated single-row lookup: null = no row / unknown
        // author / foreign author — all "not yours to publish".
        val row = runCatching { boardRepository.getOwnAuthoredClimbRow(canonical, connected) }
            .getOrNull() ?: return Outcome.NotAuthor
        if (row.isCommunityPublished) return Outcome.AlreadyPublished

        val pubkey = runCatching { nostrSigner.getPublicKeyHex() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return Outcome.NoNostrIdentity

        // IN-PLACE conversion, keeping the Kilter uuid. SQL-guarded against
        // foreign authors / rows owned by a different pubkey.
        val adopted = runCatching {
            boardRepository.adoptKilterClimbAsCommunity(
                uuid = canonical,
                kilterAuthorUuid = connected,
                pubkey = pubkey,
                adoptedAtEpochSeconds = System.currentTimeMillis() / 1000,
            )
        }.getOrElse {
            Log.w(TAG, "adoptKilterClimbAsCommunity threw uuid=$canonical", it)
            false
        }
        if (!adopted) return Outcome.Failed("provenance conversion refused")

        // Same angle/grade fallbacks as the retry worker: a curated row may
        // carry a NULL display_difficulty; the Kind-30078 event requires
        // both fields, so fall back rather than throw.
        val stats = runCatching { boardRepository.getClimbStatsForUuid(canonical) }.getOrNull()
        val angle = stats?.first ?: 40
        // Caller-supplied grade enforces a real difficulty; else the stored
        // grade, else the slider default (only reachable from the non-hub
        // one-tap surfaces).
        val grade = setterGradeId ?: stats?.second ?: KilterGradeMapper.DEFAULT_SETTER_GRADE_ID
        // Persist the resolved grade locally so browse/detail render it — the
        // imported Kilter row's climb_stats was ungraded (NULL difficulty).
        // display_difficulty + difficulty_average both carry the setter grade
        // until community votes accumulate, mirroring an editor-authored draft.
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
        }.onFailure { Log.w(TAG, "claim grade persist failed uuid=$canonical", it) }
        val state = ClimbEditorState(
            selectedHolds = parseHolds(row.framesText),
            name = row.name,
            description = row.description,
            setterGradeId = grade,
            angle = angle,
        )
        val ctx = runCatching { boardRepository.getClimbPublishContext(canonical) }.getOrNull()
        val boardBrand = BoardBrand.fromWire(ctx?.boardBrand ?: row.boardBrand)
        val layoutId = ctx?.layoutId ?: row.layoutId
        val sizeLabel = ctx?.sizeLabel ?: ""

        return try {
            val result = communityClimbPublisher.publish(
                uuid = canonical,
                layoutId = layoutId,
                boardBrand = boardBrand,
                state = state,
                sizeLabel = sizeLabel,
            )
            Outcome.Published(result.nostrEventId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The publisher already marked the row 'failed' (retry-worker
            // eligible) before throwing; surface the failure to the UI.
            Log.w(TAG, "own-climb publish failed uuid=$canonical", e)
            Outcome.Failed(e.message)
        }
    }

    /** Format-blind canonical uuid resolution (the DB mixes dashed-lower
     *  and nodash-UPPER spellings; surfaces may hold either). */
    private fun canonicalUuid(uuid: String): String? {
        if (uuid.isBlank()) return null
        return runCatching { boardRepository.findClimbCanonicalUuid(uuid) }.getOrNull() ?: uuid
    }

    private fun parseHolds(framesText: String): Map<Int, Int> =
        BoardClimbParser.parseFrames(framesText).associate { it.placementId to it.roleId }
}

/** Published to the community = a Kind-30078 event id is recorded (or the
 *  lifecycle column says so). A 'failed' row stays unpublished → retryable. */
val CommunityClimbRow.isCommunityPublished: Boolean
    get() = !nostrEventId.isNullOrBlank() || syncStatus == "published_nostr"

/** Format-blind uuid form for SET MEMBERSHIP only (ascents store BLE/API
 *  spellings — nodash-UPPER vs dashed-lower — that differ from the canonical
 *  board-DB row). Never write this form back to the DB. */
fun normalizeClimbUuid(uuid: String): String = uuid.lowercase().replace("-", "")
