package com.cruxcoach.android.ui.competition

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.competition.CompetitionClimbResolver
import com.cruxcoach.android.competition.CompetitionIntentPublisher
import com.cruxcoach.android.competition.CompetitionRelayClient
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.domain.competition.CompetitionClimb
import com.cruxcoach.domain.competition.CompetitionProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One competition, live.
 *
 * The screen it feeds has to answer four questions without being asked: whose
 * turn is it, how many are before me, how many attempts have I left, and where
 * do I stand. Everything here exists to answer one of those.
 *
 * Actions are idempotent by construction — an intent reuses its nonce, so a
 * retry replaces the earlier request instead of adding a second one — which is
 * what lets them survive process death and a flaky relay.
 */
@HiltViewModel
class CompetitionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val client: CompetitionRelayClient,
    private val intents: CompetitionIntentPublisher,
    private val climbs: CompetitionClimbResolver,
    private val signer: NostrSigner,
) : ViewModel() {

    private val organizerPubkey: String = savedStateHandle["organizerPubkey"] ?: ""
    private val compId: String = savedStateHandle["compId"] ?: ""

    /** What the last action did, so the screen can say so instead of guessing. */
    sealed interface Action {
        data object Idle : Action
        data object Working : Action
        data class Sent(val accepted: Int, val attempted: Int) : Action
        data class Failed(val reason: String) : Action
    }

    private val _action = MutableStateFlow<Action>(Action.Idle)
    val action: StateFlow<Action> = _action.asStateFlow()

    val myPubkey: String get() = signer.getPublicKeyHex()

    data class Ui(
        val snapshot: CompetitionRelayClient.Snapshot,
        val myPubkey: String,
    ) {
        val me get() = snapshot.state?.participants?.firstOrNull { it.pubkey == myPubkey }
        val currentClimber: String?
            get() = snapshot.state?.let { s ->
                if (s.cursor < 0) null else s.order.getOrNull(s.cursor)
            }
        val isMyTurn: Boolean get() = me != null && currentClimber == me!!.pubkey

        /** How many climbers are ahead of me in this round, or null when I am not in it. */
        val climbersBefore: Int?
            get() {
                val state = snapshot.state ?: return null
                val index = state.order.indexOf(myPubkey)
                if (index < 0) return null
                val cursor = if (state.cursor < 0) 0 else state.cursor
                return (index - cursor).coerceAtLeast(0)
            }

        val attemptsLeft: Int
            get() {
                val state = snapshot.state ?: return 0
                val rules = snapshot.competition?.rules ?: return 0
                val record = me?.climb(state.currentClimbId)
                if (record?.outcome == "top") return 0
                return (rules.attemptsPerClimb - (record?.attemptsUsed ?: 0)).coerceAtLeast(0)
            }

        val defersLeft: Int
            get() {
                val rules = snapshot.competition?.rules ?: return 0
                val mine = me ?: return 0
                return (rules.deferBudgetPerRound - mine.defersUsedThisRound).coerceAtLeast(0)
            }

        /**
         * Whether a defer control should EXIST. A disabled button that never
         * explains itself is a worse answer than no button plus a sentence.
         */
        val canDefer: Boolean
            get() {
                val state = snapshot.state ?: return false
                val rules = snapshot.competition?.rules ?: return false
                val mine = me ?: return false
                return state.status == "running" && isMyTurn && defersLeft > 0 &&
                    mine.consecutiveDefers < rules.maxConsecutiveDefers
            }

        /**
         * Whether this climber may act right now.
         *
         * Every condition the reducer would apply to their next attempt,
         * checked before a control is drawn. An attempt the reducer is going to
         * reject is worse than no button at all, because they walk away
         * believing it counted.
         */
        fun mayAct(nowSeconds: Long): Boolean {
            val state = snapshot.state ?: return false
            val competition = snapshot.competition ?: return false
            val mine = me ?: return false
            if (state.status != "running" || state.paused) return false
            if (!isMyTurn) return false
            if (mine.registration != "accepted") return false
            if (mine.checkin != "checked_in") return false
            if (mine.result != "active") return false
            if (competition.feeMsat > 0 && mine.payment != "settled") return false
            val rest = competition.rules.minRestSec
            if (rest > 0 && mine.lastAttemptAt > 0 && nowSeconds - mine.lastAttemptAt < rest) return false
            return true
        }

        /** Seconds of rest still owed before this climber may go again. */
        fun restSecondsLeft(nowSeconds: Long): Long {
            val competition = snapshot.competition ?: return 0
            val mine = me ?: return 0
            val rest = competition.rules.minRestSec
            if (rest <= 0 || mine.lastAttemptAt <= 0) return 0
            return (mine.lastAttemptAt + rest - nowSeconds).coerceAtLeast(0)
        }

        /** One climb this person runs, with what is left on it. */
        data class Remaining(
            val climb: CompetitionClimb,
            val attemptsLeft: Int,
            val outcome: String,
        )

        /**
         * The climbs this person may still attempt.
         *
         * Under participant choice that is the set they hold, never the whole
         * pool: the reducer refuses an attempt on somebody else's climb, so a
         * control offering one would only produce a rejection.
         */
        val remainingClimbs: List<Remaining>
            get() {
                val competition = snapshot.competition ?: return emptyList()
                val mine = me ?: return emptyList()
                val attemptsPerClimb = competition.rules.attemptsPerClimb
                return competition.climbsFor(mine.selections).mapNotNull { climb ->
                    val record = mine.climb(climb.id)
                    if (record?.outcome == "top") return@mapNotNull null
                    val left = (attemptsPerClimb - (record?.attemptsUsed ?: 0)).coerceAtLeast(0)
                    if (left == 0) null else Remaining(climb, left, record?.outcome ?: "none")
                }
            }

        /** Pool climbs nobody holds yet — what may still be picked. */
        val freePoolClimbs: List<CompetitionClimb>
            get() {
                val competition = snapshot.competition ?: return emptyList()
                val state = snapshot.state ?: return emptyList()
                if (competition.rules.selectionUniqueness != "unique_per_competition") {
                    return competition.climbPool
                }
                return competition.climbPool.filter { state.claims[it.id] == null }
            }

        /** How many more climbs this person still has to pick. */
        val climbsStillToPick: Int
            get() {
                val competition = snapshot.competition ?: return 0
                return (competition.rules.climbCount - (me?.selections?.size ?: 0)).coerceAtLeast(0)
            }

        val picksOwnClimbs: Boolean
            get() = snapshot.competition?.rules?.climbSource == "participant_choice"

        fun secondsToDeadline(nowSeconds: Long): Long? {
            val state = snapshot.state ?: return null
            if (state.cursor < 0 || state.turnDeadlineAt == 0L) return null
            return (state.turnDeadlineAt - nowSeconds).coerceAtLeast(0)
        }
    }

    val ui: StateFlow<Ui> = combine(client.snapshot, MutableStateFlow(myPubkey)) { snapshot, pubkey ->
        Ui(snapshot, pubkey)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        Ui(CompetitionRelayClient.Snapshot(loading = true), myPubkey),
    )

    init {
        viewModelScope.launch {
            val now = System.currentTimeMillis() / 1000
            if (client.load(organizerPubkey, compId, now)) {
                client.follow { System.currentTimeMillis() / 1000 }.collect { /* state flows out via snapshot */ }
            }
        }
    }

    fun register(
        division: String,
        display: String,
        waiverAccepted: Boolean,
        selections: List<String> = emptyList(),
    ) = act {
        val competition = client.snapshot.value.competition ?: return@act null
        intents.register(competition, organizerPubkey, division, display, waiverAccepted, selections.sorted())
    }

    /**
     * Report an attempt on one of my own climbs.
     *
     * A report, not a result: the authority's entry is what scores. The screen
     * says so, because someone who thinks their top is banked and finds it is
     * not has been misled by us.
     */
    fun reportAttempt(climbId: String, outcome: String) = act {
        val snapshot = client.snapshot.value
        val competition = snapshot.competition ?: return@act null
        val mine = snapshot.state?.participants?.firstOrNull { it.pubkey == myPubkey } ?: return@act null
        val used = mine.climb(climbId)?.attemptsUsed ?: 0
        intents.reportAttempt(competition, organizerPubkey, climbId, outcome, used + 1)
    }

    fun withdraw() = act {
        val competition = client.snapshot.value.competition ?: return@act null
        intents.withdraw(competition, organizerPubkey)
    }

    fun requestCheckIn() = act {
        val competition = client.snapshot.value.competition ?: return@act null
        intents.requestCheckIn(competition, organizerPubkey)
    }

    fun requestDefer() = act {
        val snapshot = client.snapshot.value
        val competition = snapshot.competition ?: return@act null
        val state = snapshot.state ?: return@act null
        intents.requestDefer(competition, organizerPubkey, state.currentClimbId, state.turnDeadlineAt)
    }

    private fun act(work: suspend () -> CompetitionIntentPublisher.Result?) {
        if (_action.value is Action.Working) return
        _action.update { Action.Working }
        viewModelScope.launch {
            when (val result = work()) {
                null -> _action.update { Action.Failed("not_loaded") }
                is CompetitionIntentPublisher.Result.Published ->
                    _action.update { Action.Sent(result.accepted, result.attempted) }
                is CompetitionIntentPublisher.Result.Failed ->
                    _action.update { Action.Failed(result.reason) }
            }
        }
    }

    fun clearAction() = _action.update { Action.Idle }

    /** What happened the last time somebody asked to see a climb on the board. */
    private val _climbOpen = MutableStateFlow<CompetitionClimbResolver.Result?>(null)
    val climbOpen: StateFlow<CompetitionClimbResolver.Result?> = _climbOpen.asStateFlow()

    /**
     * Ask for a competition climb on the board.
     *
     * Resolved against what this phone holds before anything navigates: a climb
     * whose board has not been downloaded, or that no size we have can draw,
     * produces a sentence and a way to fix it rather than an empty board.
     */
    fun openClimb(climbId: String) {
        val competition = client.snapshot.value.competition ?: return
        val climb = competition.climb(climbId) ?: return
        _climbOpen.update { climbs.resolve(competition, climb) }
    }

    /** Try again — the catalogue may have been downloaded since. */
    fun retryOpenClimb(climbId: String) = openClimb(climbId)

    fun clearClimbOpen() = _climbOpen.update { null }

    /** The share link for this competition, for the system share sheet. */
    fun shareLink(host: String): String? {
        val snapshot = client.snapshot.value
        val competition = snapshot.competition ?: return null
        val organizer = snapshot.organizerPubkey ?: return null
        return com.cruxcoach.android.competition.CompetitionShareLink.httpsLink(
            naddr = CompetitionNaddr.encode(organizer, competition.compId),
            host = host,
        )
    }
}

/**
 * naddr for a competition.
 *
 * The shared encoder rather than Quartz's, so the same code runs in a JVM unit
 * test and is pinned by the cross-client vectors.
 */
object CompetitionNaddr {
    fun encode(organizerPubkey: String, compId: String): String =
        com.cruxcoach.android.competition.CompetitionShareLink.naddr(organizerPubkey, compId)
}
