package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.R
import com.cruxcoach.domain.board.QuantumLaneBadge
import com.cruxcoach.domain.board.QuantumLaneBadgeKind
import com.cruxcoach.domain.board.QuantumLaneSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the lane chips actually put on screen.
 *
 * Two properties are worth a UI test rather than a unit test. The first is the
 * capability gate: on every board that shows one climb at a time these
 * composables must draw *nothing*, not an empty row that shifts the layout.
 * The second is that the sentence a screen reader gets is the whole sentence —
 * the chip itself is four characters wide, so if the description is not right
 * the information is not there at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BoardPlaylistLaneUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun badges(vararg badge: QuantumLaneBadge) = BoardPlaylistRowLanes(badges = badge.toList())

    // ── The capability gate ───────────────────────────────────────────────

    @Test
    fun `a single-projection board draws no chips at all`() {
        compose.setContent {
            BoardPlaylistLaneChips(
                lanes = BoardPlaylistRowLanes(),
                interactive = true,
                onAssign = {},
            )
        }

        compose.onNodeWithTag("board_playlist_lane_chips").assertDoesNotExist()
    }

    @Test
    fun `an unavailable rack draws no strip at all`() {
        compose.setContent { BoardPlaylistLaneStrip(laneState = BoardPlaylistLaneState()) }

        compose.onNodeWithTag("board_playlist_lane_strip").assertDoesNotExist()
    }

    // ── The sentence behind the four characters ───────────────────────────

    @Test
    fun `a compatible lane says so in full`() {
        compose.setContent {
            BoardPlaylistLaneChips(
                lanes = badges(QuantumLaneBadge(1, QuantumLaneBadgeKind.COMPATIBLE)),
                interactive = true,
                onAssign = {},
            )
        }

        compose.onNodeWithContentDescription(
            context.getString(R.string.board_lane_cd_compatible, 2),
        ).assertIsDisplayed()
    }

    @Test
    fun `an overlap names the count and the lanes in the way`() {
        compose.setContent {
            BoardPlaylistLaneChips(
                lanes = badges(
                    QuantumLaneBadge(
                        lane = 2,
                        kind = QuantumLaneBadgeKind.NEAR,
                        overlapCount = 1,
                        conflictingLanes = listOf(0),
                    ),
                ),
                interactive = true,
                onAssign = {},
            )
        }

        val expected = context.resources.getQuantityString(
            R.plurals.board_lane_cd_overlap, 1, 3, 1,
        ) + ". " + context.getString(R.string.board_lane_conflict_with, "L1")
        compose.onNodeWithContentDescription(expected).assertIsDisplayed()
    }

    @Test
    fun `an unknown layer reads as unknown, never as compatible`() {
        compose.setContent {
            BoardPlaylistLaneChips(
                lanes = badges(QuantumLaneBadge(0, QuantumLaneBadgeKind.UNKNOWN)),
                interactive = true,
                onAssign = {},
            )
        }

        compose.onNodeWithContentDescription(
            context.getString(R.string.board_lane_cd_unknown, 1),
        ).assertIsDisplayed()
    }

    // ── Planning versus writing ───────────────────────────────────────────

    @Test
    fun `tapping a chip plans a lane and touches nothing else`() {
        val planned = mutableListOf<Int>()
        compose.setContent {
            BoardPlaylistLaneChips(
                lanes = badges(
                    QuantumLaneBadge(0, QuantumLaneBadgeKind.COMPATIBLE),
                    QuantumLaneBadge(1, QuantumLaneBadgeKind.COMPATIBLE),
                ),
                interactive = true,
                onAssign = { planned += it },
            )
        }

        compose.onNodeWithTag("board_playlist_lane_chip_2").performClick()

        assertEquals(listOf(1), planned)
    }

    @Test
    fun `a device that may not write layers cannot plan them either`() {
        // A control that does nothing is a claim. A member's assignment would
        // never reach the controller's own plan, so it is disabled rather than
        // offered and quietly ignored.
        compose.setContent {
            BoardPlaylistLaneChips(
                lanes = badges(QuantumLaneBadge(0, QuantumLaneBadgeKind.COMPATIBLE)),
                interactive = false,
                onAssign = {},
            )
        }

        compose.onNodeWithTag("board_playlist_lane_chip_1").assertIsNotEnabled()
    }

    // ── The strip ─────────────────────────────────────────────────────────

    @Test
    fun `an orphaned lane says it is still lit for an entry that has gone`() {
        val state = BoardPlaylistLaneState(
            available = true,
            maxLanes = 2,
            lanes = listOf(
                BoardPlaylistLaneCard(
                    lane = 0, climbName = "Zombie Hands", color = null,
                    source = QuantumLaneSource.CONFIRMED, entryId = "gone",
                    onList = false, holdsKnown = true,
                ),
                BoardPlaylistLaneCard(
                    lane = 1, climbName = null, color = null,
                    source = QuantumLaneSource.FREE, entryId = null,
                    onList = true, holdsKnown = true,
                ),
            ),
            orphanedLanes = listOf(0),
        )

        compose.setContent { BoardPlaylistLaneStrip(laneState = state) }

        val expected = context.getString(R.string.board_lane_cd_on_board, 1) +
            ": Zombie Hands. " + context.getString(R.string.board_lane_orphaned, "L1")
        compose.onNodeWithTag("board_playlist_lane_strip").assertIsDisplayed()
        compose.onNodeWithContentDescription(expected).assertIsDisplayed()
    }

    @Test
    fun `a free lane reads as free`() {
        val state = BoardPlaylistLaneState(
            available = true,
            maxLanes = 1,
            lanes = listOf(
                BoardPlaylistLaneCard(
                    lane = 0, climbName = null, color = null,
                    source = QuantumLaneSource.FREE, entryId = null,
                    onList = true, holdsKnown = true,
                ),
            ),
        )

        compose.setContent { BoardPlaylistLaneStrip(laneState = state) }

        val expected = context.getString(R.string.board_lane_short, 1) + ": " +
            context.getString(R.string.board_lane_free)
        compose.onNodeWithContentDescription(expected).assertIsDisplayed()
    }
}
