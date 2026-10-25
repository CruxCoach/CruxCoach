package com.cruxcoach.android.ui.board.games

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
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class WallGame {
    LAVA, ENDURANCE, THIEF, TRAVERSE;

    /** Endurance and traverse play a chosen climb; the others use the whole wall. */
    val needsClimb: Boolean get() = this == ENDURANCE || this == TRAVERSE
}

/** Why the wall does not show what the screen shows. */
enum class WallSendProblem { NOT_CONNECTED, OTHER_BOARD, SESSION, CLEANING, FAILED }

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
) {
    val running: Boolean get() = run.phase == GamePhase.COUNTDOWN || run.phase == GamePhase.RUNNING

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
) : ViewModel() {

    private val mutableState = MutableStateFlow(WallGamesState())
    val state: StateFlow<WallGamesState> = mutableState.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = ble.connectionState

    private val sounds = GameSounds()
    private var thiefSeed: Long = Random.nextLong()
    private val stealRandom = Random(Random.nextLong())

    private val sender = WallGameSender(viewModelScope, ::transmit)
    private val runner = WallGameRunner(
        scope = viewModelScope,
        now = SystemClock::elapsedRealtime,
        onFrame = sender::show,
        onBeep = { if (mutableState.value.soundOn) sounds.play(it) },
    )

    init {
        val climbUuid: String? = savedStateHandle["climbUuid"]
        val angle: Int = savedStateHandle["angle"] ?: -1
        viewModelScope.launch {
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
                it.copy(
                    loading = false,
                    board = board,
                    unavailable = load.takeIf { board == null },
                    thiefRoute = board?.let { b -> HoldsThief.route(b.rows, it.thiefDensity, thiefSeed) }.orEmpty(),
                )
            }
            if (board != null) {
                if (climbUuid != null) {
                    mutableState.update { it.copy(game = WallGame.ENDURANCE) }
                    chooseClimb(climbUuid, angle)
                }
                observeRecentClimbs(board)
            }
        }
        viewModelScope.launch { runner.run.collect { run -> mutableState.update { it.copy(run = run) } } }
        viewModelScope.launch {
            // After a reconnect the wall shows nothing of ours: put the current frame back.
            var previous = ble.connectionState.value
            ble.connectionState.collect { now ->
                if (now == ConnectionState.CONNECTED && previous == ConnectionState.CONNECTING) {
                    sender.forget()
                    val s = mutableState.value
                    when {
                        s.running && s.run.phase == GamePhase.RUNNING -> sender.show(s.run.lit)
                        s.game == WallGame.THIEF && s.thiefLive -> sender.show(s.thiefRoute)
                    }
                }
                if (now == ConnectionState.DISCONNECTED) {
                    mutableState.update { it.copy(sendProblem = null) }
                }
                previous = now
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

    fun selectGame(game: WallGame) {
        if (mutableState.value.running) return
        // A finished game's last state (all the lava) should not linger.
        if (mutableState.value.run.phase == GamePhase.FINISHED) sender.show(emptyList())
        runner.reset()
        mutableState.update { it.copy(game = game, thiefLive = false, sendProblem = null) }
    }

    fun setLevel(level: Int) {
        if (mutableState.value.running) return
        mutableState.update { it.copy(level = level.coerceIn(GAME_LEVELS)) }
    }

    fun setTraverseDifficulty(difficulty: Double) {
        if (mutableState.value.running) return
        mutableState.update { it.copy(traverseDifficulty = difficulty.coerceIn(0.0, TraverseGame.MAX_DIFFICULTY)) }
    }

    fun setSoundOn(on: Boolean) {
        mutableState.update { it.copy(soundOn = on) }
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

    fun setThiefDensity(density: Int) {
        val board = mutableState.value.board ?: return
        mutableState.update { it.copy(thiefDensity = density, thiefRoute = HoldsThief.route(board.rows, density, thiefSeed)) }
        followThief()
    }

    fun newThiefClimb() {
        val board = mutableState.value.board ?: return
        thiefSeed = Random.nextLong()
        mutableState.update { it.copy(thiefRoute = HoldsThief.route(board.rows, it.thiefDensity, thiefSeed)) }
        followThief()
    }

    fun setStealCount(count: Int) {
        mutableState.update { it.copy(stealCount = count.coerceIn(1, WallGamesState.MAX_STEAL)) }
    }

    fun stealHolds() {
        mutableState.update { it.copy(thiefRoute = HoldsThief.steal(it.thiefRoute, it.stealCount, stealRandom)) }
        followThief()
    }

    /** Puts the hold-thief climb on the wall; from now on its changes follow. */
    fun lightThiefClimb() {
        mutableState.update { it.copy(thiefLive = true) }
        sender.forget()
        sender.show(mutableState.value.thiefRoute)
    }

    private fun followThief() {
        val s = mutableState.value
        if (s.game == WallGame.THIEF && s.thiefLive) sender.show(s.thiefRoute)
    }

    // ── Timed games ────────────────────────────────────────────────────

    fun start() {
        val s = mutableState.value
        if (!s.canStart) return
        if (s.game == WallGame.THIEF) {
            lightThiefClimb()
            return
        }
        val timeline = timelineFor(s) ?: return
        if (timeline.frames.isEmpty()) return
        // The wall goes dark for the countdown, then follows the game.
        sender.forget()
        sender.show(emptyList())
        runner.start(timeline, GAME_COUNTDOWN_SECONDS, finishWithTimeline = s.game == WallGame.LAVA)
    }

    private fun timelineFor(s: WallGamesState): GameTimeline? {
        val board = s.board ?: return null
        return when (s.game) {
            WallGame.LAVA -> FloorIsLava.timeline(board.rows, s.level)
            WallGame.ENDURANCE -> s.climb?.let { EnduranceChallenge.timeline(it.holds, board.rows, s.level) }
            WallGame.TRAVERSE -> s.climb?.let { TraverseGame.timeline(it.holds, board.rows, s.traverseDifficulty) }
            WallGame.THIEF -> null
        }
    }

    /** Ends a running game and clears the wall. */
    fun stop() {
        if (!mutableState.value.running) return
        runner.stop()
        sender.show(emptyList())
    }

    /** From the result back to the game's settings; clears the wall. */
    fun reset() {
        runner.reset()
        sender.show(emptyList())
    }

    // ── Sending ────────────────────────────────────────────────────────

    private suspend fun transmit(holds: List<BoardHold>): Boolean {
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
        val ok = sendToBoard(ble, board, holds)
        mutableState.update { it.copy(sendProblem = if (ok) null else WallSendProblem.FAILED) }
        return ok
    }

    override fun onCleared() {
        // Leaving a running game ends it: the wall should not keep rising lava.
        // viewModelScope is already cancelled here, so the clear gets its own
        // short-lived scope.
        if (mutableState.value.running) {
            val brand = mutableState.value.board?.brand
            CoroutineScope(Dispatchers.Main.immediate + NonCancellable).launch {
                runCatching { ble.clearBoard(expectedBrand = brand) }
            }
        }
        sounds.release()
        super.onCleared()
    }

    private companion object {
        const val TAG = "WallGames"
    }
}

/** One wall state through the board's own send path. Empty clears the wall. */
internal suspend fun sendToBoard(ble: BoardBleConnection, board: WallGameBoard, holds: List<BoardHold>): Boolean {
    if (holds.isEmpty()) return ble.clearBoard(expectedBrand = board.brand)
    return when (board.brand) {
        BoardBrand.SHINYWALL -> board.shinyVariant?.let { ble.sendShinyWallClimb(holds, it) } ?: false
        BoardBrand.MOONBOARD -> board.moonVariant?.let {
            ble.sendMoonBoardClimb(WallGameHolds.moonBoardFrames(holds), it, board.moonLedMode)
        } ?: false
        BoardBrand.QUANTUM -> false
        else -> ble.sendClimb(holds, board.ledMap, board.roleColors, expectedBrand = board.brand)
    }
}
