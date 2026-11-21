package com.cruxcoach.android.ui.board.games

/**
 * The switch for the wall games (Floor is lava, Endurance, Hold thief, Traverse and Knot). Off, the app offers them
 * nowhere: no entry in the main menu and no "Play as a game" in a climb's menu. Nothing of them runs either, since
 * only their own screen starts a game. Turned on again, everything is back; they keep no data of their own.
 */
object WallGamesFeature {
    /** False hides the wall games in this build. Off for 0.2.5 and planned for 0.2.6 (owner 2026-11-21). */
    val enabled: Boolean = false
}
