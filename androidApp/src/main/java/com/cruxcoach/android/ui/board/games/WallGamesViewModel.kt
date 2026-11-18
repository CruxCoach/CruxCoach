package com.cruxcoach.android.ui.board.games

import com.cruxcoach.android.led.AnimationPauseOwner
import com.cruxcoach.android.led.LedAnimationEngine
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.brand
import com.cruxcoach.data.repository.getClimbByUuidAnySpelling
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.games.EnduranceChallenge
import com.cruxcoach.domain.board.games.FloorIsLava
import com.cruxcoach.domain.board.games.GAME_COUNTDOWN_SECONDS
import com.cruxcoach.domain.board.games.GAME_LEVELS
import com.cruxcoach.domain.board.games.GameTimeline
import com.cruxcoach.domain.board.games.HoldsThief
import com.cruxcoach.domain.board.games.TraverseGame
import com.cruxcoach.domain.board.games.Knot
import com.cruxcoach.domain.board.games.KnotCall
import com.cruxcoach.domain.board.games.KnotColour
import com.cruxcoach.domain.board.games.KnotDraw
import com.cruxcoach.domain.board.games.KnotHolds
import com.cruxcoach.domain.board.games.KnotLayout
import com.cruxcoach.domain.board.games.KnotSpacing
import com.cruxcoach.domain.board.games.WallRows
import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.board.MoonBoardFrameEncoder
import com.cruxcoach.android.data.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class WallGame {
    LAVA, ENDURANCE, THIEF, TRAVERSE, KNOT;

    /** Endurance and traverse play a chosen climb; the others use the whole wall. */
    val needsClimb: Boolean get() = this == ENDURANCE || this == TRAVERSE
}

/** Why the wall does not show what the screen shows. */
enum class WallSendProblem {
    NOT_CONNECTED, OTHER_BOARD, SESSION, CLEANING, FAILED,
    /** More holds than the MoonBoard lights at once ([WallGameHolds.moonBoardFits]): nothing was sent. */
    TOO_MANY_HOLDS,
}

/** Why a chosen climb cannot be played. */
enum class ClimbProblem { NOT_FOUND, OTHER_BOARD, NO_HOLDS }

data class GameClimb(
    val uuid: String,
    val name: String,
    val angle: Int,
    val holds: List<BoardHold>,
    /** Holds of the climb that are not on the active board (another size). */
    val offBoard: Int,
)

data class RecentClimb(val uuid: String, val name: String, val angle: Int)

/** Knot's settings and its round. */
data class KnotState(
    /** Players in the game; more than [maxClimbers] wait in line. */
    val players: Int = 1,
    /** Names as typed; an empty one shows as "Player n". */
    val names: List<String> = List(Knot.MAX_PLAYERS) { "" },
    /** The next call comes when someone below taps, without a timer. */
    val byTap: Boolean = false,
    val spacing: KnotSpacing = KnotSpacing.CLOSE,
    /** The spacings to choose from: playable on this wall for [atWall] climbers ([Knot.layout] not null) and reaching further than a closer one. */
    val spacings: Set<KnotSpacing> = emptySet(),
    /** Playable spacings that reach no further than a closer one here (solo, or the MoonBoard's three colours). */
    val sameReach: Set<KnotSpacing> = emptySet(),
    /** How far the colours really are in [layout], from the middle of a climber's part. */
    val reachM: Double? = null,
    /** Spots of each colour every climber has within reach. */
    val holds: KnotHolds = KnotHolds.NORMAL,
    /** The steps that fit the wall at this spacing and stay within the board's limit. */
    val holdsOffered: Set<KnotHolds> = emptySet(),
    /** Steps that would light more holds than the board takes in the app. */
    val holdsTooMany: Set<KnotHolds> = emptySet(),
    /** Each climber's part of the field, from and to in metres along the wall, left to right. */
    val parts: List<Pair<Double, Double>> = emptyList(),
    /** The field is drawn from the catalogue's ratings, so "Draw again" gives another one. */
    val drawn: Boolean = false,
    /** The field is being worked out for the settings shown. */
    val pending: Boolean = false,
    /** The colours the board lights for Knot; null when it can't name them. */
    val colours: List<KnotColour>? = null,
    /** Climbers this board fits at once; 0 without colours. */
    val maxClimbers: Int = 0,
    /** Where the field's spots sit for [atWall] climbers; null when the wall has no field for them. */
    val layout: KnotLayout? = null,
    val safetySeen: Boolean = true,
    val run: KnotRun = KnotRun(),
) {
    /** Climbers at the wall at once: every player when they fit, else as many as fit. */
    val atWall: Int get() = minOf(players, maxClimbers.coerceAtLeast(1))

    /** The board's angle when it is steep enough for the hint. */
    fun steepAngle(board: WallGameBoard?): Int? = board?.angle?.takeIf { it > Knot.STEEP_DEGREES }

    /** Where the field will light, in the colours' own order, before a round shuffles them. */
    fun previewSpots(): Map<Int, KnotColour>? = layout?.let { l -> colours?.let { l.field(it).spots } }
}

data class WallGamesState(
    val loading: Boolean = true,
    val board: WallGameBoard? = null,
    val unavailable: WallGameBoardLoad? = null,
    val game: WallGame = WallGame.LAVA,
    val level: Int = DEFAULT_LEVEL,
    val traverseDifficulty: Double = DEFAULT_TRAVERSE_DIFFICULTY,
    val thiefDensity: Int = HoldsThief.DEFAULT_DENSITY,
    val thiefRoute: List<BoardHold> = emptyList(),
    /** True once the hold-thief climb was put on the wall; later changes follow it there. */
    val thiefLive: Boolean = false,
    val stealCount: Int = DEFAULT_STEAL,
    val climb: GameClimb? = null,
    val climbProblem: ClimbProblem? = null,
    val recentClimbs: List<RecentClimb> = emptyList(),
    val run: GameRun = GameRun(),
    val sendProblem: WallSendProblem? = null,
    val soundOn: Boolean = true,
    val knot: KnotState = KnotState(),
) {
    val running: Boolean get() = run.phase == GamePhase.COUNTDOWN || run.phase == GamePhase.RUNNING || knot.run.active

    /** What the board preview shows. */
    val shownHolds: List<BoardHold>
        get() = when {
            run.phase != GamePhase.IDLE -> run.lit
            game == WallGame.THIEF -> thiefRoute
            game.needsClimb -> climb?.holds.orEmpty()
            else -> emptyList()
        }

    val canStart: Boolean
        get() = board != null && !running && when (game) {
            WallGame.LAVA -> !board.rows.isEmpty()
            WallGame.THIEF -> thiefRoute.isNotEmpty()
            WallGame.ENDURANCE, WallGame.TRAVERSE -> climb != null && climb.holds.size > climb.offBoard
            WallGame.KNOT -> knot.layout != null && !knot.pending
        }

    companion object {
        const val DEFAULT_LEVEL = 3
        const val DEFAULT_TRAVERSE_DIFFICULTY = 2.5
        const val DEFAULT_STEAL = 3
        const val MAX_STEAL = 20
        const val RECENT_LIMIT = 20
    }
}

/**
 * Wall games on the active board: floor is lava, endurance, hold thief and
 * traverse. Each step of a game goes to the wall as one complete frame
 * through the board's ordinary send path; nothing is logged and nothing
 * enters the projection history.
 */
@HiltViewModel
class WallGamesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val loader: WallGameBoardLoader,
    private val boardRepository: BoardRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    private val ble: BoardBleConnection,
    private val sessionQueueManager: SessionQueueManager,
    private val ledEffects: LedAnimationEngine,
    private val userPreferences: UserPreferences,
    private val voice: KnotVoice,
) : ViewModel(), WallGamesActions {

    private val mutableState = MutableStateFlow(WallGamesState())
    val state: StateFlow<WallGamesState> = mutableState.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = ble.connectionState

    private val sounds = GameSounds()
    private var thiefSeed: Long = Random.nextLong()
    private val stealRandom = Random(Random.nextLong())

    private val sender = WallGameSender(viewModelScope, ::transmit)
    private val knotRunner = KnotRunner(
        scope = viewModelScope,
        now = SystemClock::elapsedRealtime,
        onWall = ::showKnot,
        onCall = ::announce,
        onBeep = { if (mutableState.value.soundOn) sounds.play(it) },
        onStepIn = ::announceStepIn,
    )
    /** What Knot last put on the wall, to put back after a reconnect. */
    private var knotWall: Map<Int, KnotColour> = emptyMap()
    private var knotBigHolds: Set<Int>? = null
    /** Hand holds rated from the catalogue; with them each game draws a fresh field, without them it is always the same. */
    private var knotHandGrades: Map<Int, Double> = emptyMap()
    private val knotSeeds = Random(Random.nextLong())
    /** The draw of the next game's field, which the preview shows, and the spots of the last one, which it avoids. */
    private var knotSeed = knotSeeds.nextLong()
    private var knotLastSpots: Set<Int> = emptySet()
    /** Works out Knot's field for the settings shown; a newer change cancels it. */
    private var knotJob: Job? = null
    private val runner = WallGameRunner(
        scope = viewModelScope,
        now = SystemClock::elapsedRealtime,
        onFrame = ::show,
        onBeep = { if (mutableState.value.soundOn) sounds.play(it) },
    )

    /** The climb this screen was opened with ("Play as a game"), if any. */
    private val startClimbUuid: String? = savedStateHandle["climbUuid"]
    private val startAngle: Int = savedStateHandle["angle"] ?: -1
    private var loadJob: Job? = null

    init {
        // Every game step is a frame of its own: while this screen is open the
        // wall belongs to the game, not to a chosen LED effect.
        ledEffects.pause(AnimationPauseOwner.WALL_GAME)
        loadBoard()
        viewModelScope.launch { runner.run.collect { run -> mutableState.update { it.copy(run = run) } } }
        viewModelScope.launch { knotRunner.run.collect { run -> mutableState.update { it.copy(knot = it.knot.copy(run = run)) } } }
        viewModelScope.launch {
            userPreferences.knotSafetySeen.collect { seen -> mutableState.update { it.copy(knot = it.knot.copy(safetySeen = seen)) } }
        }
        viewModelScope.launch {
            // After a reconnect the wall shows nothing of ours: put the current frame back.
            var previous = ble.connectionState.value
            ble.connectionState.collect { now ->
                if (now == ConnectionState.CONNECTED && previous == ConnectionState.CONNECTING) {
                    sender.forget()
                    val s = mutableState.value
                    when {
                        s.running && s.run.phase == GamePhase.RUNNING -> show(s.run.lit)
                        s.game == WallGame.THIEF && s.thiefLive -> show(s.thiefRoute)
                        s.game == WallGame.KNOT && s.knot.run.active -> showKnot(knotWall)
                    }
                }
                if (now == ConnectionState.DISCONNECTED) {
                    mutableState.update { it.copy(sendProblem = null) }
                }
                previous = now
            }
        }
    }

    /**
     * Back on the screen without a board, e.g. from the catalogues the "no hold positions" state
     * opens: try again, so the downloaded holds show without leaving the games.
     */
    fun reloadIfMissing() {
        if (mutableState.value.board != null || loadJob?.isActive == true) return
        mutableState.update { it.copy(loading = true) }
        loadBoard()
    }

    private fun loadBoard() {
        loadJob = viewModelScope.launch {
            val load = try {
                withContext(Dispatchers.IO) { loader.load() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "Loading the board for games failed: ${e.javaClass.simpleName}")
                WallGameBoardLoad.NoHolds
            }
            val board = (load as? WallGameBoardLoad.Ready)?.board
            mutableState.update {
                val density = board?.let { b -> thiefStartDensity(b, it.thiefDensity) } ?: it.thiefDensity
                it.copy(
                    loading = false,
                    board = board,
                    unavailable = load.takeIf { board == null },
                    thiefDensity = density,
                    thiefRoute = board?.let { b -> HoldsThief.route(b.rows, density, thiefSeed) }.orEmpty(),
                )
            }
            refreshKnot()
            if (board != null && mutableState.value.game == WallGame.KNOT) prepareKnot()
            if (board != null) {
                if (startClimbUuid != null) {
                    mutableState.update { it.copy(game = WallGame.ENDURANCE) }
                    chooseClimb(startClimbUuid, startAngle)
                }
                observeRecentClimbs(board)
            }
        }
    }

    private fun observeRecentClimbs(board: WallGameBoard) {
        viewModelScope.launch {
            personalBoardRepo.observeClimbHistory().collect { entries ->
                val recent = entries
                    .filter { BoardBrand.fromWire(it.boardBrand) == board.brand && it.layoutId == board.layoutId }
                    .distinctBy { it.climbUuid }
                    .take(WallGamesState.RECENT_LIMIT)
                    .map { RecentClimb(it.climbUuid, it.climbName, it.angle) }
                mutableState.update { it.copy(recentClimbs = recent) }
            }
        }
    }

    override fun selectGame(game: WallGame) {
        if (mutableState.value.running) return
        // A finished game's last state (all the lava) should not linger.
        if (mutableState.value.run.phase == GamePhase.FINISHED) show(emptyList())
        runner.reset()
        knotRunner.reset()
        mutableState.update { it.copy(game = game, thiefLive = false, sendProblem = null) }
        if (game == WallGame.KNOT) prepareKnot()
    }

    override fun setLevel(level: Int) {
        if (mutableState.value.running) return
        mutableState.update { it.copy(level = level.coerceIn(GAME_LEVELS)) }
    }

    override fun setTraverseDifficulty(difficulty: Double) {
        if (mutableState.value.running) return
        mutableState.update { it.copy(traverseDifficulty = difficulty.coerceIn(0.0, TraverseGame.MAX_DIFFICULTY)) }
    }

    override fun setSoundOn(on: Boolean) {
        mutableState.update { it.copy(soundOn = on) }
        if (!on) voice.stop()
    }

    fun chooseClimb(uuid: String, angle: Int) {
        if (mutableState.value.running) return
        val board = mutableState.value.board ?: return
        viewModelScope.launch {
            val climb = withContext(Dispatchers.IO) {
                runCatching { boardRepository.getClimbByUuidAnySpelling(uuid, angle) }.getOrNull()
            }
            val holds = climb?.let { BoardClimbParser.parseMultiFrames(it.frames).firstOrNull() }.orEmpty()
            val problem = when {
                climb == null -> ClimbProblem.NOT_FOUND
                climb.brand != board.brand || climb.layoutId != board.layoutId -> ClimbProblem.OTHER_BOARD
                holds.none { board.rows.rowOf(it.placementId) != null } -> ClimbProblem.NO_HOLDS
                else -> null
            }
            mutableState.update {
                it.copy(
                    climb = if (problem == null && climb != null) GameClimb(
                        uuid = climb.uuid,
                        name = climb.name,
                        angle = angle,
                        holds = holds,
                        offBoard = holds.count { h -> board.rows.rowOf(h.placementId) == null },
                    ) else null,
                    climbProblem = problem,
                )
            }
        }
    }

    // ── Hold thief ─────────────────────────────────────────────────────

    /**
     * The hold thief's first share: on a MoonBoard the highest whose climb one frame of the box
     * carries, so the first climb lights (the default share never fits there); else [current].
     */
    private fun thiefStartDensity(board: WallGameBoard, current: Int): Int {
        val variant = board.moonVariant ?: return current
        return HoldsThief.DENSITIES.firstOrNull { density ->
            WallGameHolds.moonBoardFits(HoldsThief.route(board.rows, density, thiefSeed), variant, board.moonLedMode)
        } ?: HoldsThief.DENSITIES.last()
    }

    override fun setThiefDensity(density: Int) {
        val board = mutableState.value.board ?: return
        mutableState.update { it.copy(thiefDensity = density, thiefRoute = HoldsThief.route(board.rows, density, thiefSeed)) }
        followThief()
    }

    override fun newThiefClimb() {
        val board = mutableState.value.board ?: return
        thiefSeed = Random.nextLong()
        mutableState.update { it.copy(thiefRoute = HoldsThief.route(board.rows, it.thiefDensity, thiefSeed)) }
        followThief()
    }

    override fun setStealCount(count: Int) {
        mutableState.update { it.copy(stealCount = count.coerceIn(1, WallGamesState.MAX_STEAL)) }
    }

    override fun stealHolds() {
        mutableState.update { it.copy(thiefRoute = HoldsThief.steal(it.thiefRoute, it.stealCount, stealRandom)) }
        followThief()
    }

    /** Puts the hold-thief climb on the wall; from now on its changes follow. */
    fun lightThiefClimb() {
        mutableState.update { it.copy(thiefLive = true) }
        sender.forget()
        show(mutableState.value.thiefRoute)
    }

    private fun followThief() {
        val s = mutableState.value
        if (s.game == WallGame.THIEF && s.thiefLive) show(s.thiefRoute)
    }

    // ── Knot ───────────────────────────────────────────────────────────

    /**
     * Knot's colours and climbers for [players] on [board] at once, and its field off the main thread: the settings show
     * the tap straight away, the field and the preview follow. A newer change cancels the older one.
     */
    private fun refreshKnot(
        players: Int = mutableState.value.knot.players,
        spacing: KnotSpacing = mutableState.value.knot.spacing,
        holds: KnotHolds = mutableState.value.knot.holds,
        then: () -> Unit = {},
    ) {
        val board = mutableState.value.board
        val colours = board?.let { Knot.colours(it.brand) }
        val unit = board?.let { Knot.metresPerUnit(it.brand) }
        knotJob?.cancel()
        if (board == null || colours == null || unit == null) {
            mutableState.update { it.copy(knot = it.knot.copy(colours = null, maxClimbers = 0, layout = null, spacings = emptySet(), pending = false)) }
            return
        }
        val max = Knot.maxClimbers(board.rows, unit)
        val count = players.coerceIn(Knot.PLAYERS)
        mutableState.update { it.copy(knot = it.knot.copy(colours = colours, maxClimbers = max, players = count, spacing = spacing, holds = holds, pending = true)) }
        val fitsBoard = knotFitsBoard(board)
        val input = KnotInput(board.rows, minOf(count, max.coerceAtLeast(1)), colours, unit, spacing, holds, knotBigHolds.orEmpty(),
            knotHandGrades.takeIf { it.isNotEmpty() }?.let { KnotDraw(knotSeed, it, knotLastSpots) }, fitsBoard)
        knotJob = viewModelScope.launch {
            val field = withContext(Dispatchers.Default) { knotField(input) }
            mutableState.update {
                it.copy(knot = it.knot.copy(spacing = field.spacing, spacings = field.spacings, sameReach = field.sameReach,
                    holds = field.holds, holdsOffered = field.holdsOffered, holdsTooMany = field.holdsTooMany,
                    layout = field.layout, reachM = field.reachM, parts = field.parts, drawn = input.draw != null, pending = false))
            }
            then()
        }
    }

    override fun setKnotSpacing(spacing: KnotSpacing) {
        if (mutableState.value.knot.run.phase !in KNOT_SETUP_PHASES) return
        refreshKnot(spacing = spacing)
    }

    override fun setKnotHolds(holds: KnotHolds) {
        if (mutableState.value.knot.run.phase !in KNOT_SETUP_PHASES) return
        refreshKnot(holds = holds)
    }

    /** A slider was let go: with the field on, the wall takes the field the preview shows. */
    override fun knotSettingsSettled() {
        if (mutableState.value.knot.run.phase != KnotPhase.POSITIONS) return
        viewModelScope.launch {
            knotJob?.join()
            relightKnot()
        }
    }

    /** Another field for the same settings, away from the last game's holds; with the field on, on the wall too. */
    override fun knotDrawAgain() {
        if (mutableState.value.knot.run.phase !in KNOT_SETUP_PHASES) return
        knotSeed = knotSeeds.nextLong()
        // Without a catalogue there is only the one field of the nearest holds.
        refreshKnot(then = { if (mutableState.value.knot.run.phase == KnotPhase.POSITIONS) relightKnot() })
    }

    /** The lit field again, as the settings now give it, in the round's colours. */
    private fun relightKnot() {
        val s = mutableState.value
        val run = s.knot.run
        val field = run.field ?: return
        val layout = s.knot.layout ?: return
        if (run.phase != KnotPhase.POSITIONS) return
        val lit = layout.field(field.colours)
        if (lit == field) return
        sender.forget()
        knotRunner.light(lit, run.climbers.size, singleFoot(s, layout, field.colours))
    }

    /** Per part, the round's colours with only one foot spot within reach. */
    private fun singleFoot(s: WallGamesState, layout: KnotLayout, order: List<KnotColour>): List<Set<KnotColour>> {
        val board = s.board ?: return emptyList()
        val unit = Knot.metresPerUnit(board.brand) ?: return emptyList()
        return Knot.singleFootColours(board.rows, layout, unit, s.knot.spacing).map { slots -> slots.mapTo(mutableSetOf()) { order[it] } }
    }

    /** Whether a field fits what the board takes in the app: one frame on the MoonBoard. */
    private fun knotFitsBoard(board: WallGameBoard): (KnotLayout) -> Boolean {
        val variant = board.moonVariant
        if (board.brand != BoardBrand.MOONBOARD || variant == null) return { true }
        val colours = Knot.colours(board.brand) ?: return { true }
        return { layout -> MoonBoardFrameEncoder.fits(Knot.moonBoardFrames(layout.field(colours).spots), variant, board.moonLedMode) }
    }

    /** Looks once for the board's big holds. */
    private fun prepareKnot() {
        val board = mutableState.value.board ?: return
        if (knotBigHolds != null) return
        knotBigHolds = emptySet()
        viewModelScope.launch {
            val (big, grades) = withContext(Dispatchers.IO) {
                val started = SystemClock.elapsedRealtime()
                val grades = runCatching { loader.knotHandGrades(board) }.getOrDefault(emptyMap())
                Log.i(TAG, "Knot grades: ${grades.size} hand holds rated from the catalogue in ${SystemClock.elapsedRealtime() - started} ms")
                runCatching { loader.knotBigHolds(board) }.getOrDefault(emptySet()) to grades
            }
            if (big.isEmpty() && grades.isEmpty()) return@launch
            knotBigHolds = big
            knotHandGrades = grades
            refreshKnot()
        }
    }

    override fun setKnotPlayers(players: Int) {
        if (mutableState.value.running) return
        refreshKnot(players = players)
    }

    override fun setKnotName(climber: Int, name: String) {
        if (climber !in 0 until Knot.MAX_PLAYERS) return
        mutableState.update { s ->
            s.copy(knot = s.knot.copy(names = s.knot.names.mapIndexed { i, n -> if (i == climber) name.take(NAME_LIMIT) else n }))
        }
    }

    override fun setKnotByTap(byTap: Boolean) {
        if (mutableState.value.running) return
        mutableState.update { it.copy(knot = it.knot.copy(byTap = byTap)) }
    }

    override fun knotNextCall() = knotRunner.next()

    override fun knotSafetySeen() {
        viewModelScope.launch { runCatching { userPreferences.setKnotSafetySeen() } }
        mutableState.update { it.copy(knot = it.knot.copy(safetySeen = true)) }
    }

    /** Everyone has taken their start: the countdown, then the calls. */
    override fun startKnotCalls() {
        val s = mutableState.value
        knotRunner.start(if (s.knot.byTap) 0 else Knot.callMs(s.level), knotSeeds.nextLong())
    }

    override fun knotOut(climber: Int) {
        knotRunner.out(climber)
        // The call of a climber who is out is no longer worth hearing.
        if (mutableState.value.knot.run.phase == KnotPhase.FINISHED) voice.stop()
    }

    private fun lightKnot(s: WallGamesState) {
        val layout = s.knot.layout ?: return
        val colours = s.knot.colours ?: return
        sender.forget()
        val order = Knot.colourOrder(colours, knotSeeds.nextLong())
        knotRunner.light(layout.field(order), s.knot.players, singleFoot(s, layout, order))
        Log.i(TAG, "Knot game: ${layout.spots.size} spots, ${(layout.spots intersect knotLastSpots).size} of them also in the last game")
        // The next game gets a fresh field, away from this one's holds.
        knotLastSpots = layout.spots
        knotSeed = knotSeeds.nextLong()
        refreshKnot()
    }

    /** A short beep, so the climbers listen, then the call. The sound switch silences both. */
    private fun announce(call: KnotCall) {
        if (!mutableState.value.soundOn) return
        voice.say(call, solo = mutableState.value.knot.run.climbers.size <= 1, afterMs = CALL_AFTER_BEEP_MS) {
            sounds.play(GameBeep.COUNTDOWN)
        }
    }

    /** The next in line: a beep, then "Player 5, to the wall", after any call still being spoken. */
    private fun announceStepIn(player: Int) {
        if (!mutableState.value.soundOn) return
        voice.sayToWall(player, afterMs = CALL_AFTER_BEEP_MS) { sounds.play(GameBeep.COUNTDOWN) }
    }

    // ── Timed games ────────────────────────────────────────────────────

    override fun start() {
        val s = mutableState.value
        if (!s.canStart) return
        if (s.game == WallGame.THIEF) {
            lightThiefClimb()
            return
        }
        if (s.game == WallGame.KNOT) {
            // The field for the settings shown, once it's worked out.
            viewModelScope.launch {
                knotJob?.join()
                lightKnot(mutableState.value)
            }
            return
        }
        val timeline = timelineFor(s) ?: return
        if (timeline.frames.isEmpty()) return
        // The wall goes dark for the countdown, then follows the game.
        sender.forget()
        show(emptyList())
        runner.start(timeline, GAME_COUNTDOWN_SECONDS, finishWithTimeline = s.game == WallGame.LAVA)
    }

    private fun timelineFor(s: WallGamesState): GameTimeline? {
        val board = s.board ?: return null
        return when (s.game) {
            WallGame.LAVA -> FloorIsLava.timeline(board.rows, s.level)
            WallGame.ENDURANCE -> s.climb?.let { EnduranceChallenge.timeline(it.holds, board.rows, s.level) }
            WallGame.TRAVERSE -> s.climb?.let { TraverseGame.timeline(it.holds, board.rows, s.traverseDifficulty) }
            WallGame.THIEF, WallGame.KNOT -> null
        }
    }

    /** Ends a running game and clears the wall. */
    override fun stop() {
        if (!mutableState.value.running) return
        if (mutableState.value.game == WallGame.KNOT) {
            voice.stop()
            knotRunner.stop()
            return
        }
        runner.stop()
        show(emptyList())
    }

    /** From the result back to the game's settings; clears the wall. */
    override fun reset() {
        if (mutableState.value.game == WallGame.KNOT) {
            voice.stop()
            knotRunner.reset()
            return
        }
        runner.reset()
        show(emptyList())
    }

    // ── Sending ────────────────────────────────────────────────────────

    /**
     * A wall state and the game it belongs to, so a write that runs late follows that game's rules.
     * Knot's [paint] lights its spots in their colours instead of [holds]' roles.
     */
    private data class WallState(val game: WallGame, val holds: List<BoardHold>, val paint: Map<Int, KnotColour>? = null)

    private fun show(holds: List<BoardHold>) = sender.show(WallState(mutableState.value.game, holds))

    private fun showKnot(spots: Map<Int, KnotColour>) {
        knotWall = spots
        sender.show(WallState(WallGame.KNOT, emptyList(), spots))
    }

    private suspend fun transmit(state: WallState): Boolean {
        val holds = state.holds
        val board = mutableState.value.board ?: return false
        val session = sessionQueueManager.state.value
        val problem = when {
            session.isActive || session.isConnecting -> WallSendProblem.SESSION
            ble.connectionState.value != ConnectionState.CONNECTED &&
                ble.connectionState.value != ConnectionState.SENDING -> WallSendProblem.NOT_CONNECTED
            ble.connectedBoardBrand.value != board.brand -> WallSendProblem.OTHER_BOARD
            ble.cleaningState.value.active -> WallSendProblem.CLEANING
            else -> null
        }
        if (problem != null) {
            mutableState.update { it.copy(sendProblem = problem) }
            return false
        }
        val sendProblem = if (state.paint != null) {
            sendKnotToBoard(ble, board, state.paint).also { problem ->
                // Which colours went to the wall, to check them against the calls.
                val colours = state.paint.values.groupingBy { it }.eachCount().entries.joinToString(" ") { "${it.key}×${it.value}" }
                Log.i(TAG, "Knot wall: ${colours.ifEmpty { "dark" }} → ${problem ?: "sent"}")
            }
        } else {
            sendToBoard(ble, board, holds, state.game)
        }
        // The answer for a game the user has left since doesn't speak for the one on screen.
        mutableState.update { if (it.game == state.game) it.copy(sendProblem = sendProblem) else it }
        return sendProblem == null
    }

    override fun onCleared() {
        // Leaving a running game ends it: the wall should not keep rising lava.
        // viewModelScope is already cancelled here, so the clear gets its own
        // short-lived scope.
        if (mutableState.value.running || mutableState.value.knot.run.phase == KnotPhase.FINISHED) {
            val brand = mutableState.value.board?.brand
            CoroutineScope(Dispatchers.Main.immediate + NonCancellable).launch {
                runCatching { ble.clearBoard(expectedBrand = brand) }
            }
        }
        sounds.release()
        voice.release()
        ledEffects.resume(AnimationPauseOwner.WALL_GAME)
        super.onCleared()
    }

    private companion object {
        const val TAG = "WallGames"
        const val NAME_LIMIT = 24
        /** Knot's settings can change before a round and while the field is lit for the start. */
        val KNOT_SETUP_PHASES = setOf(KnotPhase.IDLE, KnotPhase.POSITIONS)
        /** The call starts this long after its beep. */
        const val CALL_AFTER_BEEP_MS = 300L
    }
}

/** One wall state of [game] through the board's own send path. Empty clears the wall. Null when it went up. */
internal suspend fun sendToBoard(ble: BoardBleConnection, board: WallGameBoard, holds: List<BoardHold>, game: WallGame): WallSendProblem? {
    if (holds.isEmpty()) return if (ble.clearBoard(expectedBrand = board.brand)) null else WallSendProblem.FAILED
    val sent = when (board.brand) {
        // Only the holds that change go out, so the wall does not flash dark per step.
        BoardBrand.SHINYWALL -> board.shinyVariant?.let { ble.updateShinyWallHolds(holds, it) } ?: false
        BoardBrand.MOONBOARD -> board.moonVariant?.let { variant ->
            // The box shows so many LEDs at once. Rising lava keeps its newest whole rows lit; any other
            // game's wall is refused whole, with a message, since a part of it (no start holds) misleads.
            val shown = if (game == WallGame.LAVA) WallGameHolds.moonBoardLavaFit(holds, board.rows, variant, board.moonLedMode)
            else holds
            if (shown.isEmpty() || !WallGameHolds.moonBoardFits(shown, variant, board.moonLedMode)) {
                return WallSendProblem.TOO_MANY_HOLDS
            }
            ble.sendMoonBoardClimb(WallGameHolds.moonBoardFrames(shown), variant, board.moonLedMode)
        } ?: false
        BoardBrand.QUANTUM -> false
        else -> ble.sendClimb(holds, board.ledMap, board.roleColors, expectedBrand = board.brand)
    }
    return if (sent) null else WallSendProblem.FAILED
}

/**
 * Knot's field through the board's own send path: [spots] in their colours. Aurora boards take the colours as LED
 * colours, within the power limits PaintBox keeps; the MoonBoard shows each colour as a role. Empty clears the wall.
 */
internal suspend fun sendKnotToBoard(ble: BoardBleConnection, board: WallGameBoard, spots: Map<Int, KnotColour>): WallSendProblem? {
    if (spots.isEmpty()) return if (ble.clearBoard(expectedBrand = board.brand)) null else WallSendProblem.FAILED
    val sent = when {
        board.brand.usesAuroraProtocol -> {
            val leds = spots.entries.sortedBy { it.key }.mapNotNull { (id, colour) -> board.ledMap[id]?.let { it to colour.rgb332 } }
            val apiLevel = ble.connectedBoard?.apiLevel ?: return WallSendProblem.FAILED
            // API 3 has no power budget in the protocol: stay within what PaintBox sends (fitV3PictureBudget).
            val shown = if (apiLevel >= 3) {
                val fitted = BoardPacketEncoder.fitV3PictureBudget(leds.map { it.second }, board.ledMap.values.toSet().size)
                leds.zip(fitted) { (position, _), colour -> position to colour }
            } else leds
            ble.sendRawLeds(shown, expectedBrand = board.brand, maxPowerW = BoardPacketEncoder.V2_PICTURE_MAX_POWER)
        }
        board.brand == BoardBrand.MOONBOARD -> board.moonVariant?.let { variant ->
            val frames = Knot.moonBoardFrames(spots)
            if (!MoonBoardFrameEncoder.fits(frames, variant, board.moonLedMode)) return WallSendProblem.TOO_MANY_HOLDS
            ble.sendMoonBoardClimb(frames, variant, board.moonLedMode)
        } ?: false
        else -> false
    }
    return if (sent) null else WallSendProblem.FAILED
}

/** What Knot's field depends on, taken on the main thread. */
internal class KnotInput(
    val rows: WallRows,
    val atWall: Int,
    val colours: List<KnotColour>,
    val unit: Double,
    val spacing: KnotSpacing,
    val holds: KnotHolds,
    val bigHolds: Set<Int>,
    val draw: KnotDraw?,
    /** Whether a field fits what the board takes in the app besides [KNOT_MAX_LIT] (one frame on the MoonBoard). */
    val fitsBoard: (KnotLayout) -> Boolean = { true },
)

/** Knot's field worked out from a [KnotInput]: the settings it ended up with and the ones on offer. */
internal class KnotFieldResult(
    val spacing: KnotSpacing,
    val spacings: Set<KnotSpacing>,
    val sameReach: Set<KnotSpacing>,
    val holds: KnotHolds,
    val holdsOffered: Set<KnotHolds>,
    val holdsTooMany: Set<KnotHolds>,
    val layout: KnotLayout?,
    val reachM: Double?,
    val parts: List<Pair<Double, Double>>,
)

/**
 * The most holds a Knot field lights on an Aurora board: what one packet carries to the boxes with the oldest
 * protocol (API 2), which take no second packet.
 */
internal const val KNOT_MAX_LIT = 127

/** The order in which Knot falls back when a step of holds doesn't fit: Normal first, then Few, then Many. */
private val HOLDS_FALLBACK = listOf(KnotHolds.NORMAL, KnotHolds.FEW, KnotHolds.MANY)

/**
 * Knot's field for [input], pure and slow enough to keep off the main thread. A spacing the wall can't keep playable
 * for that many climbers is offered greyed out, as is one that reaches no further than a closer one (the same stripes).
 * A step of holds the wall can't fit, or that lights more than the board takes, is greyed out too. The field then
 * falls back: holds to Normal, then Few, then Many; spacing to the closest that fits.
 */
internal fun knotField(input: KnotInput): KnotFieldResult {
    // Whether a setting fits and what it changes don't depend on the draw: the plain fields tell.
    val plain = KnotSpacing.entries.associateWith { spacing ->
        KnotHolds.entries.associateWith { holds -> Knot.layout(input.rows, input.atWall, input.colours, input.unit, input.bigHolds, spacing, holds = holds) }
    }
    fun tooMany(layout: KnotLayout) = layout.spots.size > KNOT_MAX_LIT || !input.fitsBoard(layout)
    fun fits(spacing: KnotSpacing, holds: KnotHolds) = plain.getValue(spacing).getValue(holds)?.let { !tooMany(it) } == true
    val stripes = KnotSpacing.entries.associateWith { spacing -> plain.getValue(spacing).values.firstNotNullOfOrNull { it }?.stripes?.size }
    fun spacingsFor(holds: KnotHolds): Pair<Set<KnotSpacing>, Set<KnotSpacing>> {
        val playable = KnotSpacing.entries.filter { fits(it, holds) }.toSet()
        val same = playable.filterTo(mutableSetOf()) { spacing ->
            KnotSpacing.entries.any { closer -> closer < spacing && closer in playable && stripes[closer] == stripes[spacing] }
        }
        return (playable - same) to same
    }
    val holds = input.holds.takeIf { spacingsFor(it).first.isNotEmpty() }
        ?: HOLDS_FALLBACK.firstOrNull { spacingsFor(it).first.isNotEmpty() } ?: input.holds
    val (offered, sameReach) = spacingsFor(holds)
    val spacing = input.spacing.takeIf { it in offered } ?: offered.minOrNull() ?: KnotSpacing.CLOSE
    val holdsOffered = KnotHolds.entries.filter { fits(spacing, it) }.toSet()
    val holdsTooMany = KnotHolds.entries.filter { plain.getValue(spacing).getValue(it)?.let(::tooMany) == true }.toSet()
    val started = SystemClock.elapsedRealtime()
    val layout = if (!fits(spacing, holds)) null
    else if (input.draw == null) plain.getValue(spacing).getValue(holds)
    else Knot.layout(input.rows, input.atWall, input.colours, input.unit, input.bigHolds, spacing, input.draw, holds)
    // How long a field takes, and whether it was drawn, to check on a phone with a real catalogue.
    Log.i("WallGames", "Knot field: ${layout?.spots?.size ?: 0} spots for ${input.atWall}, $spacing, $holds, ${if (input.draw != null) "drawn" else "nearest"} in ${SystemClock.elapsedRealtime() - started} ms")
    val (start, width) = Knot.fieldSpan(input.rows, input.atWall, input.unit)
    val parts = List(input.atWall) { start + width / input.atWall * it to start + width / input.atWall * (it + 1) }
    return KnotFieldResult(spacing, offered, sameReach, holds, holdsOffered, holdsTooMany, layout,
        layout?.let { Knot.handReach(input.rows, it, input.unit) }, parts)
}
