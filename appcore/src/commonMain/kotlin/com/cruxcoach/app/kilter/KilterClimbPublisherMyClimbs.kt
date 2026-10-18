package com.cruxcoach.app.kilter

import com.cruxcoach.app.ui.GradeFormatter
import com.cruxcoach.app.ui.Subscription
import com.cruxcoach.app.ui.UiCodes
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.CommunityClimbRow
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.KilterGradeMapper
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
 * One row of "My climbs". [status]: draft | publishPending | published |
 * kilterUnclaimed. [setterGradeId] is -1 for an ungraded Kilter import (its
 * claim goes through the grade picker).
 */
class MyClimbRowUi(
    val uuid: String,
    val name: String,
    val angle: Int,
    val status: String,
    val brandWire: String,
    val brandTitle: String,
    val setterGradeId: Int,
)

/**
 * Android's "Meine Climbs" hub (`MyKilterClimbsViewModel`). Sections in
 * Android's order: drafts (with pending retries), published, Kilter imports
 * awaiting a claim. [feedback]: none | published | noNostrIdentity | notAuthor
 * | alreadyPublished | failed. [gradeDialogUuid] is non-empty while the grade
 * picker for an ungraded claim is open.
 */
class MyKilterClimbsState(
    val isLoading: Boolean,
    val hasKilterConnection: Boolean,
    val hasNostrIdentity: Boolean,
    val drafts: List<MyClimbRowUi>,
    val published: List<MyClimbRowUi>,
    val unclaimed: List<MyClimbRowUi>,
    val publishingUuid: String,
    val gradeDialogUuid: String,
    val feedback: String,
) {
    val isEmpty: Boolean get() = drafts.isEmpty() && published.isEmpty() && unclaimed.isEmpty()
}

class MyKilterClimbsModel internal constructor(
    private val own: OwnKilterClimbPublisher,
    private val boardRepository: BoardRepository,
    private val account: KilterClimbAccount,
    private val pubkeyProvider: () -> String?,
    private val grades: GradeFormatter,
    main: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val state = MutableStateFlow(
        MyKilterClimbsState(true, true, true, emptyList(), emptyList(), emptyList(), "", "", "none"),
    )

    val currentState: MyKilterClimbsState get() = state.value

    val minGradeId: Int get() = MIN_GRADE_ID
    val maxGradeId: Int get() = MAX_GRADE_ID
    val defaultGradeId: Int get() = KilterGradeMapper.DEFAULT_SETTER_GRADE_ID

    fun gradeLabel(gradeId: Int): String = grades.label(gradeId.toDouble())

    fun watch(onState: (MyKilterClimbsState) -> Unit): Subscription {
        val job = scope.launch { state.collect { onState(it) } }
        return Subscription { job.cancel() }
    }

    /** Re-reads both halves; the screen calls it on appear so an edit or delete elsewhere shows. */
    fun refresh() {
        scope.launch {
            val rows = try {
                withContext(io) { load() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            state.update { s ->
                if (rows == null) {
                    patched(s, isLoading = false)
                } else {
                    MyKilterClimbsState(
                        isLoading = false,
                        hasKilterConnection = account.userUuid().isNotBlank(),
                        hasNostrIdentity = !pubkeyProvider().isNullOrBlank(),
                        drafts = rows.filter { it.status == "draft" || it.status == "publishPending" },
                        published = rows.filter { it.status == "published" },
                        unclaimed = rows.filter { it.status == "kilterUnclaimed" },
                        publishingUuid = s.publishingUuid,
                        gradeDialogUuid = s.gradeDialogUuid,
                        feedback = s.feedback,
                    )
                }
            }
        }
    }

    /** A graded import publishes in one tap; an ungraded one asks for a grade first. */
    fun claim(uuid: String) {
        val s = state.value
        if (s.publishingUuid.isNotEmpty()) return
        val row = s.unclaimed.firstOrNull { it.uuid == uuid } ?: return
        if (row.setterGradeId < 0) {
            state.update { patched(it, gradeDialogUuid = uuid) }
        } else {
            publish(uuid, row.setterGradeId)
        }
    }

    fun confirmGrade(uuid: String, setterGradeId: Int) {
        state.update { patched(it, gradeDialogUuid = "") }
        publish(uuid, setterGradeId.coerceIn(MIN_GRADE_ID, MAX_GRADE_ID))
    }

    fun dismissGradeDialog() = state.update { patched(it, gradeDialogUuid = "") }

    fun consumeFeedback() = state.update { patched(it, feedback = "none") }

    fun close() = scope.cancel()

    private fun publish(uuid: String, setterGradeId: Int) {
        if (state.value.publishingUuid.isNotEmpty()) return
        state.update { patched(it, publishingUuid = uuid) }
        scope.launch {
            val code = try {
                withContext(io) { own.publish(uuid, setterGradeId) }.code
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "failed"
            }
            state.update { patched(it, publishingUuid = "", feedback = code) }
            refresh()
        }
    }

    /**
     * CruxCoach-native rows first: an adopted Kilter climb appears in both
     * queries, and its CruxCoach status is the authoritative one.
     */
    private fun load(): List<MyClimbRowUi> {
        val byUuid = LinkedHashMap<String, MyClimbRowUi>()
        val pubkey = pubkeyProvider()?.takeIf { it.isNotBlank() }
        if (pubkey != null) {
            for (row in boardRepository.getMyClimbs(pubkey).filter { it.syncStatus != "deleted" }) {
                byUuid.getOrPut(row.uuid.lowercase()) { item(row) }
            }
        }
        val connected = account.userUuid().takeIf { it.isNotBlank() }
        if (connected != null) {
            for (row in boardRepository.getOwnAuthoredKilterClimbs(connected)) {
                byUuid.getOrPut(row.uuid.lowercase()) { item(row) }
            }
        }
        return byUuid.values.toList()
    }

    private fun item(row: CommunityClimbRow): MyClimbRowUi {
        val stats = runCatching { boardRepository.getClimbStatsForUuid(row.uuid) }.getOrNull()
        val status = when {
            !row.nostrEventId.isNullOrBlank() || row.syncStatus == "published_nostr" -> "published"
            !row.createdByPubkey.isNullOrBlank() -> if (row.syncStatus == "failed") "publishPending" else "draft"
            else -> "kilterUnclaimed"
        }
        val brand = BoardBrand.fromWire(row.boardBrand)
        return MyClimbRowUi(
            uuid = row.uuid,
            name = row.name,
            angle = stats?.first ?: 40,
            status = status,
            brandWire = brand.wireValue,
            brandTitle = UiCodes.brandTitle(brand),
            setterGradeId = stats?.second ?: -1,
        )
    }

    private fun patched(
        s: MyKilterClimbsState,
        isLoading: Boolean = s.isLoading,
        publishingUuid: String = s.publishingUuid,
        gradeDialogUuid: String = s.gradeDialogUuid,
        feedback: String = s.feedback,
    ) = MyKilterClimbsState(
        isLoading, s.hasKilterConnection, s.hasNostrIdentity, s.drafts, s.published, s.unclaimed,
        publishingUuid, gradeDialogUuid, feedback,
    )

    private companion object {
        /** The creator's setter-grade range (KilterGradeMapper's table). */
        const val MIN_GRADE_ID = 10
        const val MAX_GRADE_ID = 34
    }
}
