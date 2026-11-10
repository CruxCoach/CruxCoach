package com.cruxcoach.android.ui.board.games

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.games.WallHold
import com.cruxcoach.domain.board.games.WallRows
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the wall games screen shows and offers in each state, without the view model. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class WallGamesContentTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = object : WallGamesActions {
        override fun selectGame(game: WallGame) { calls += "select:$game" }
        override fun setLevel(level: Int) { calls += "level:$level" }
        override fun setTraverseDifficulty(difficulty: Double) = Unit
        override fun setSoundOn(on: Boolean) = Unit
        override fun setThiefDensity(density: Int) = Unit
        override fun newThiefClimb() { calls += "new" }
        override fun setStealCount(count: Int) = Unit
        override fun stealHolds() { calls += "steal" }
        override fun start() { calls += "start" }
        override fun stop() { calls += "stop" }
        override fun reset() = Unit
    }

    private val board = WallGameBoard(
        brand = BoardBrand.SHINYWALL,
        layoutId = 1,
        rows = WallRows.of((1..12).map { WallHold(it, (it % 4) * 10.0, (it / 4) * 10.0) }),
    )
    private val idle = WallGamesState(loading = false, board = board)

    private var state by mutableStateOf(idle)

    private fun show(initial: WallGamesState, connected: Boolean = true) {
        state = initial
        compose.setContent {
            MaterialTheme {
                WallGamesContent(state, connected, actions, onNavigateBack = {}, onConnect = {}, onChooseClimb = {}, onOpenCatalogues = { calls += "catalogues" })
            }
        }
    }

    @Test fun `a running game shows its clock and stop, not the game choice`() {
        show(idle.copy(run = GameRun(GamePhase.RUNNING, elapsedMs = 12_300, step = 4, steps = 11)))
        compose.onNodeWithTag("wall_games_clock").assertIsDisplayed()
        compose.onNodeWithText("Row 4 of 11").assertIsDisplayed()
        compose.onNodeWithTag("wall_game_lava").assertDoesNotExist()
        compose.onNodeWithTag("wall_games_stop").performClick()
        assertEquals(listOf("stop"), calls)
    }

    @Test fun `the hold thief lights its climb first, then stealing is the main action`() {
        val route = listOf(BoardHold(1, 13), BoardHold(5, 13), BoardHold(9, 13))
        show(idle.copy(game = WallGame.THIEF, thiefRoute = route))
        compose.onNodeWithText("Light on the wall").performClick()
        compose.onNodeWithTag("wall_games_steal").assertDoesNotExist()

        state = idle.copy(game = WallGame.THIEF, thiefRoute = route, thiefLive = true)
        compose.onNodeWithTag("wall_games_steal").performClick()
        compose.onNodeWithText("Light again").performClick()
        assertEquals(listOf("start", "steal", "start"), calls)
    }

    @Test fun `a board without hold positions says so and leads to the catalogues`() {
        show(WallGamesState(loading = false, unavailable = WallGameBoardLoad.NoHolds))
        compose.onNodeWithText("The hold positions of this board are not on the device yet. Download its catalogue first.").assertIsDisplayed()
        compose.onNodeWithTag("wall_games_start").assertDoesNotExist()
        compose.onNodeWithText("Board catalogs").performClick()
        assertEquals(listOf("catalogues"), calls)
    }

    @Test fun `a board without wall games says so and offers no catalogue`() {
        show(WallGamesState(loading = false, unavailable = WallGameBoardLoad.Unsupported))
        compose.onNodeWithText("Wall games are not available for this board.").assertIsDisplayed()
        compose.onNodeWithTag("wall_games_catalogues").assertDoesNotExist()
    }

    @Test fun `without a connection the bar says the game runs on the screen only`() {
        show(idle, connected = false)
        compose.onNodeWithText("Not connected: screen only.").assertIsDisplayed()
        compose.onNodeWithTag("wall_games_start").assertIsDisplayed()
    }
}
