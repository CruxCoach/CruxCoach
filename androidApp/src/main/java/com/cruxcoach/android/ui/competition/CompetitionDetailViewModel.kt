package com.cruxcoach.android.ui.competition

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.competition.CompetitionClimbResolver
import com.cruxcoach.android.competition.CompetitionIntentPublisher
import com.cruxcoach.android.competition.CompetitionPaymentFlow
import com.cruxcoach.android.competition.CompetitionRelayClient
import com.cruxcoach.android.competition.CompetitionHostPublisher
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.payment.NostrProfileManager
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardImage
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.competition.CompetitionClimb
import com.cruxcoach.domain.competition.CompetitionPrize
import com.cruxcoach.domain.competition.CompetitionPrizeClaim
import com.cruxcoach.domain.competition.CompetitionProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    private val payments: CompetitionPaymentFlow,
    private val signer: NostrSigner,
    private val profiles: NostrProfileManager,
    private val boardRepository: BoardRepository,
    private val preferences: UserPreferences,
    private val hostPublisher: CompetitionHostPublisher,
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

    sealed interface Cleanup {
        data object Idle : Cleanup
        data object Working : Cleanup
        data class Sent(val tombstoneAccepted: Int, val deletionAccepted: Int, val attempted: Int) : Cleanup
        data class Failed(val reason: String) : Cleanup
    }

    private val _cleanup = MutableStateFlow<Cleanup>(Cleanup.Idle)
    val cleanup: StateFlow<Cleanup> = _cleanup.asStateFlow()

    val myPubkey: String get() = signer.getPublicKeyHex()
    val isAuthority: Boolean get() = client.snapshot.value.competition?.authority == myPubkey

    data class Ui(
        val snapshot: CompetitionRelayClient.Snapshot,
        val myPubkey: String,
        val suggestedDisplayName: String = "",
        val catalogue: CatalogueState = CatalogueState.Loading,
        val gradeScale: GradeScale = GradeScale.FRENCH,
        val connectedRelays: Int = 0,
    ) {
        val me get() = snapshot.state?.participants?.firstOrNull { it.pubkey == myPubkey }
        val currentClimber: String?
            get() = snapshot.state?.let { s ->
                if (s.cursor < 0) null else s.order.getOrNull(s.cursor)
            }
        val isMyTurn: Boolean get() = me != null && currentClimber == me!!.pubkey
        val nextClimber: String?
            get() = snapshot.state?.let { state ->
                if (state.cursor < 0) null else state.order.getOrNull(state.cursor + 1)
            }
        val personalCue get() = CompetitionLivePolicy.personalCue(snapshot.state, myPubkey)
        val queue get() = CompetitionLivePolicy.queue(snapshot.state)
        val rotation get() = CompetitionLivePolicy.rotation(snapshot.competition, snapshot.state, me)
        val deferAvailability get() = CompetitionLivePolicy.defer(snapshot.state, snapshot.competition, me, myPubkey)

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

        fun attemptsLeftFor(climbId: String): Int {
            val rules = snapshot.competition?.rules ?: return 0
            val record = me?.climb(climbId)
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
            get() = deferAvailability.allowed

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

    private val suggestedDisplayName = MutableStateFlow("")

    /** Local catalogue detail for one protocol option, already board-checked. */
    data class CatalogueEntry(
        val option: CompetitionClimb,
        val climb: ClimbWithStats,
        val boardSize: BoardSize? = null,
        val placements: Map<Int, BoardPlacement> = emptyMap(),
        val boardImages: List<BoardImage> = emptyList(),
        val holds: List<BoardHold> = emptyList(),
    )

    sealed interface CatalogueState {
        data object Loading : CatalogueState
        data class Ready(
            val entries: Map<String, CatalogueEntry>,
            /** Options deliberately omitted because they do not match the competition board. */
            val incompatibleCount: Int = 0,
            val missingCount: Int = 0,
        ) : CatalogueState
        data class Unavailable(val reason: Reason) : CatalogueState

        enum class Reason { BOARD_CONFIGURATION, CATALOGUE_NOT_DOWNLOADED }
    }

    private val catalogue = MutableStateFlow<CatalogueState>(CatalogueState.Loading)

    private val transportSnapshot = combine(client.snapshot, client.connectedRelayCount) { snapshot, connected ->
        snapshot to connected
    }

    val ui: StateFlow<Ui> = combine(
        transportSnapshot,
        MutableStateFlow(myPubkey),
        suggestedDisplayName,
        catalogue,
        preferences.gradeScale,
    ) { transported, pubkey, displayName, localCatalogue, gradeScale ->
        Ui(transported.first, pubkey, displayName, localCatalogue, gradeScale, transported.second)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        Ui(CompetitionRelayClient.Snapshot(loading = true), myPubkey),
    )

    init {
        // The competition nickname remains editable, but starting with the
        // user's cached Nostr profile avoids a leaderboard full of hex
        // prefixes. Do not trigger an unrelated public-relay lookup merely
        // because somebody opened a loopback competition.
        viewModelScope.launch {
            val profile = runCatching { profiles.getProfileFromCache(myPubkey) }.getOrNull()
            suggestedDisplayName.value = profile?.displayName.orEmpty().take(48)
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis() / 1000
            if (client.load(organizerPubkey, compId, now)) {
                client.follow { System.currentTimeMillis() / 1000 }.collect { /* state flows out via snapshot */ }
            }
        }
        viewModelScope.launch {
            client.snapshot
                .map { snapshot -> snapshot.competition?.let { "${it.compId}:${it.revision}" to it } }
                .distinctUntilChanged { old, new -> old?.first == new?.first }
                .collectLatest { keyed ->
                    val competition = keyed?.second
                    if (competition == null) {
                        catalogue.value = CatalogueState.Loading
                    } else {
                        catalogue.value = loadCatalogue(competition)
                    }
                }
        }
    }

    /**
     * Resolve only rows that match brand + layout + angle + physical size.
     * A same-name climb on another board is never a useful fallback in a comp.
     */
    private suspend fun loadCatalogue(competition: com.cruxcoach.domain.competition.Competition): CatalogueState =
        withContext(Dispatchers.IO) {
            val board = competition.raw["board"] as? JsonObject
                ?: return@withContext CatalogueState.Unavailable(CatalogueState.Reason.BOARD_CONFIGURATION)
            val brand = (board["brand"] as? JsonPrimitive)?.content?.lowercase()
                ?: return@withContext CatalogueState.Unavailable(CatalogueState.Reason.BOARD_CONFIGURATION)
            val layoutId = (board["layout_id"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?: return@withContext CatalogueState.Unavailable(CatalogueState.Reason.BOARD_CONFIGURATION)
            val configuredAngle = (board["angle"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?: return@withContext CatalogueState.Unavailable(CatalogueState.Reason.BOARD_CONFIGURATION)
            val sizeName = (board["size"] as? JsonPrimitive)?.content.orEmpty()
            val boardBrand = BoardBrand.fromWire(brand)
            if (boardBrand.wireValue != brand) {
                return@withContext CatalogueState.Unavailable(CatalogueState.Reason.BOARD_CONFIGURATION)
            }

            val size = if (boardBrand == BoardBrand.MOONBOARD) null else {
                val wanted = normalizeSize(sizeName)
                val allowed = boardRepository.getProductSizesForLayout(layoutId, brand).toSet()
                boardRepository.getSelectableProductSizesForBrand(brand)
                    .firstOrNull { it.id.toInt() in allowed && normalizeSize(it.name) == wanted }
                    ?: return@withContext CatalogueState.Unavailable(CatalogueState.Reason.BOARD_CONFIGURATION)
            }
            val options = (competition.climbs + competition.climbPool).distinctBy { it.id }
            val entries = linkedMapOf<String, CatalogueEntry>()
            var missing = 0
            var incompatible = 0
            val rows = boardRepository.getClimbsByUuidsForBoard(
                uuids = options.filter { it.angle == configuredAngle }.map { it.climbUuid },
                angle = configuredAngle,
                boardBrand = brand,
                layoutId = layoutId,
                selProductSizeId = size?.id?.toInt() ?: 0,
            ).associateBy { normalizeUuid(it.uuid) }
            val placements = size?.let {
                boardRepository.getPlacementsForLayout(it.id.toInt(), layoutId, brand)
                    .associateBy { placement -> placement.placementId.toInt() }
            }.orEmpty()
            val images = size?.let {
                boardRepository.getBoardImages(it.id.toInt(), layoutId, brand)
            }.orEmpty()

            options.forEach { option ->
                if (option.angle != configuredAngle) {
                    incompatible++
                    return@forEach
                }
                val row = rows[normalizeUuid(option.climbUuid)]
                if (row == null) {
                    missing++
                    return@forEach
                }
                if (row.boardBrand.lowercase() != brand || row.layoutId.toInt() != layoutId ||
                    (size != null && !boardRepository.canRenderClimbOnSize(row.uuid, size.id.toInt(), brand))
                ) {
                    incompatible++
                    return@forEach
                }
                val holds = BoardClimbParser.parseFrames(row.frames)
                entries[option.id] = CatalogueEntry(
                    option = option,
                    climb = row,
                    boardSize = size,
                    placements = placements,
                    boardImages = images,
                    holds = holds,
                )
            }
            if (entries.isEmpty() && missing > 0 && incompatible == 0) {
                CatalogueState.Unavailable(CatalogueState.Reason.CATALOGUE_NOT_DOWNLOADED)
            } else {
                CatalogueState.Ready(entries, incompatible, missing)
            }
        }

    private fun normalizeSize(value: String): String = value
        .lowercase().replace('×', 'x').replace(" ", "").replace("ft", "")

    private fun normalizeUuid(value: String): String = value.lowercase().replace("-", "")

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

    // ── organizer decisions ──

    fun hostLifecycle(status: String) = hostAct("lifecycle", JsonObject(mapOf(
        "status" to JsonPrimitive(status), "at" to JsonPrimitive(System.currentTimeMillis() / 1000),
    )))

    fun cleanupCompetition() {
        if (_cleanup.value is Cleanup.Working) return
        _cleanup.value = Cleanup.Working
        viewModelScope.launch {
            _cleanup.value = hostPublisher.deleteCompetition().fold(
                onSuccess = { Cleanup.Sent(it.tombstoneAccepted, it.deletionAccepted, it.attempted) },
                onFailure = { Cleanup.Failed(it.message ?: "cleanup_failed") },
            )
        }
    }

    fun hostRegistration(pubkey: String, decision: String, intentId: String? = null, division: String? = null, display: String? = null) {
        val participant = client.snapshot.value.state?.participant(pubkey)
        hostAct("registration_decision", JsonObject(buildMap {
            put("pubkey", JsonPrimitive(pubkey)); put("decision", JsonPrimitive(decision))
            (division ?: participant?.division)?.takeIf(String::isNotBlank)?.let { put("division", JsonPrimitive(it)) }
            (display ?: participant?.display)?.takeIf(String::isNotBlank)?.let { put("display", JsonPrimitive(it)) }
            intentId?.let { put("intent_id", JsonPrimitive(it)) }
        }), subjects = listOf(pubkey))
    }

    fun hostCheckIn(pubkey: String, checkedIn: Boolean, intentId: String? = null) = hostAct("checkin", JsonObject(buildMap {
        put("pubkey", JsonPrimitive(pubkey)); put("state", JsonPrimitive(if (checkedIn) "checked_in" else "no_show"))
        intentId?.let { put("intent_id", JsonPrimitive(it)) }
    }), subjects = listOf(pubkey))

    fun hostWithdraw(pubkey: String, intentId: String) = hostRegistration(pubkey, "withdrawn", intentId)

    fun hostDefer(pubkey: String, decision: String, intentId: String) = hostAct("defer_decision", JsonObject(mapOf(
        "pubkey" to JsonPrimitive(pubkey), "decision" to JsonPrimitive(decision),
        "intent_id" to JsonPrimitive(intentId),
    )), subjects = listOf(pubkey))

    fun hostSeed() {
        val order = client.snapshot.value.state?.participants.orEmpty()
            .filter { it.registration == "accepted" && it.checkin == "checked_in" && it.result == "active" }
            .map { it.pubkey }.sorted()
        hostAct("queue", JsonObject(mapOf("action" to JsonPrimitive("seed"), "order" to kotlinx.serialization.json.JsonArray(order.map(::JsonPrimitive)))))
    }

    fun hostQueue(action: String, index: Int? = null, climbId: String? = null) = hostAct("queue", JsonObject(buildMap {
        put("action", JsonPrimitive(action)); index?.let { put("index", JsonPrimitive(it)) }; climbId?.let { put("climb_id", JsonPrimitive(it)) }
    }))

    fun hostAttempt(pubkey: String, climbId: String, outcome: String, intentId: String? = null) {
        val used = client.snapshot.value.state?.participant(pubkey)?.climb(climbId)?.attemptsUsed ?: 0
        hostAct("attempt_result", JsonObject(buildMap {
            put("pubkey", JsonPrimitive(pubkey)); put("climb_id", JsonPrimitive(climbId))
            put("outcome", JsonPrimitive(outcome)); put("attempt_no", JsonPrimitive(used + 1))
            intentId?.let { put("intent_id", JsonPrimitive(it)) }
        }), subjects = listOf(pubkey))
    }

    fun hostAnnounce(text: String) {
        if (text.isNotBlank()) hostAct("announcement", JsonObject(mapOf("text" to JsonPrimitive(text.trim()))))
    }

    private fun hostAct(op: String, data: JsonObject, subjects: List<String> = emptyList()) {
        if (_action.value is Action.Working) return
        _action.value = Action.Working
        viewModelScope.launch {
            _action.value = when (val result = hostPublisher.append(op, data, subjects = subjects)) {
                is CompetitionHostPublisher.Result.Published -> Action.Sent(result.accepted, result.attempted)
                is CompetitionHostPublisher.Result.Failed -> Action.Failed(result.reason)
            }
        }
    }

    // ── paying the entry fee ──

    /** Where the payment attempt has got to. Nothing here is a payment itself. */
    sealed interface Payment {
        data object Idle : Payment
        data object Working : Payment
        data class Ready(val invoice: CompetitionPaymentFlow.Invoice) : Payment
        data class Failed(val code: String, val amountSats: Long) : Payment
    }

    private val _payment = MutableStateFlow<Payment>(Payment.Idle)
    val payment: StateFlow<Payment> = _payment.asStateFlow()

    /**
     * Ask the organizer's endpoint for an invoice for this entry.
     *
     * Safe to press twice: the zap request carries the registration's own
     * nonce and the payment claim reuses its nonce, so a retry replaces the
     * previous attempt rather than producing a second one.
     */
    fun requestInvoice() {
        if (_payment.value is Payment.Working) return
        _payment.update { Payment.Working }
        viewModelScope.launch {
            val snapshot = client.snapshot.value
            val competition = snapshot.competition
            if (competition == null) {
                _payment.update { Payment.Failed("not_loaded", 0) }
                return@launch
            }
            val result = payments.requestInvoice(
                competition = competition,
                organizerPubkey = organizerPubkey,
                relays = competition.relays,
            )
            _payment.update {
                when (result) {
                    is CompetitionPaymentFlow.Result.Ready -> Payment.Ready(result.invoice)
                    is CompetitionPaymentFlow.Result.Failed -> Payment.Failed(result.code, result.amountSats)
                }
            }
        }
    }

    fun clearPayment() = _payment.update { Payment.Idle }

    // ── claiming a prize ──

    /** Where a prize claim has got to. Nothing here moves money. */
    sealed interface PrizeClaim {
        data object Idle : PrizeClaim
        data object Working : PrizeClaim
        data object Sent : PrizeClaim
        data class Failed(val code: String) : PrizeClaim
    }

    private val _prizeClaim = MutableStateFlow<PrizeClaim>(PrizeClaim.Idle)
    val prizeClaim: StateFlow<PrizeClaim> = _prizeClaim.asStateFlow()

    /**
     * Claim a prize the standings say is yours.
     *
     * Checked here before anything is sent — the winner is holding the phone
     * and can fix a bad destination, where an organizer refusing it later is a
     * message they may never see. The body is encrypted to the organizer, so
     * the destination reaches them and nobody else.
     */
    fun claimPrize(prizeId: String, payoutKind: String, destination: String) {
        if (_prizeClaim.value is PrizeClaim.Working) return
        _prizeClaim.update { PrizeClaim.Working }
        viewModelScope.launch {
            val snapshot = client.snapshot.value
            val competition = snapshot.competition
            val prize = competition?.prizes?.firstOrNull { it.id == prizeId }
            // The same hash the website binds a claim to: the reduced state's
            // own, so a correction that moves the standings invalidates claims
            // made against the old ones.
            val resultsHash = snapshot.state?.stateHash()
            if (competition == null || prize == null || resultsHash == null) {
                _prizeClaim.update { PrizeClaim.Failed("not_loaded") }
                return@launch
            }

            val now = System.currentTimeMillis() / 1000
            when (val check = CompetitionPrizeClaim.validateClaimInput(prize, payoutKind, destination, now)) {
                is CompetitionPrizeClaim.Check.Failed -> {
                    _prizeClaim.update { PrizeClaim.Failed(check.error) }
                    return@launch
                }
                CompetitionPrizeClaim.Check.Ok -> Unit
            }

            val body = CompetitionPrizeClaim.buildClaimBody(
                compId = competition.compId,
                prizeId = prizeId,
                resultsHash = resultsHash,
                payoutKind = payoutKind,
                destination = destination,
            )
            val ciphertext: String? = runCatching {
                signer.signer.nip44Encrypt(body, competition.authority)
            }.getOrNull()
            if (ciphertext == null) {
                // A signer that cannot encrypt cannot send a claim privately,
                // and sending it in the clear is not an acceptable fallback.
                _prizeClaim.update { PrizeClaim.Failed("no_encryption") }
                return@launch
            }

            val result = intents.claimPrize(competition, organizerPubkey, prizeId, ciphertext)
            _prizeClaim.update {
                when (result) {
                    is CompetitionIntentPublisher.Result.Published -> PrizeClaim.Sent
                    is CompetitionIntentPublisher.Result.Failed -> PrizeClaim.Failed(result.reason)
                }
            }
        }
    }

    /** Tell the organizer the money arrived. Optional, and says so. */
    fun acknowledgePrize(prizeId: String) = act {
        val competition = client.snapshot.value.competition ?: return@act null
        intents.acknowledgePrize(competition, organizerPubkey, prizeId)
    }

    fun clearPrizeClaim() = _prizeClaim.update { PrizeClaim.Idle }

    /** The prizes this person is standing at, once the results are final. */
    fun claimablePrizes(): List<CompetitionPrize> {
        val snapshot = client.snapshot.value
        val competition = snapshot.competition ?: return emptyList()
        if (snapshot.state?.status != "finished") return emptyList()
        return competition.prizes.filter { prize ->
            CompetitionPrizeClaim.eligibleWinner(snapshot.standings, prize)?.pubkey == myPubkey
        }
    }

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
