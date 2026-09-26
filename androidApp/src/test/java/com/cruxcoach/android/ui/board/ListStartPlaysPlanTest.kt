package com.cruxcoach.android.ui.board

import androidx.lifecycle.SavedStateHandle
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.data.PlaylistPlaybackCoordinator
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.Climb_lists
import com.cruxcoach.data.repository.ListPlaybackStepRow
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.IntensityZones
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** "Start" on a list with a training plan plays the plan: its tries and rests. */
@OptIn(ExperimentalCoroutinesApi::class)
class ListStartPlaysPlanTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    /** The IO hops inside the model are real threads; poll briefly for their result. */
    private fun awaitUntil(check: () -> Boolean) {
        repeat(300) { if (check()) return; Thread.sleep(10) }
    }

    private fun climb(uuid: String) = ClimbWithStats(
        uuid = uuid,
        layoutId = 1L,
        setterUsername = "setter",
        name = uuid,
        frames = "p1100r12",
        framesCount = 1L,
        difficultyAverage = 20.0,
        qualityAverage = 3.0,
        ascensionistCount = 10L,
        origin = "kilter",
        source = "kilter",
        syncStatus = "synced",
    )

    private fun climbStep(position: Long, uuid: String) =
        ListPlaybackStepRow(position, 7L, position, "climb", uuid, null, 40L)

    private fun restStep(position: Long, seconds: Long) =
        ListPlaybackStepRow(position, 7L, position, "rest", null, seconds, null)

    private fun model(hasPlan: Boolean): Pair<BoardListDetailViewModel, PlaylistPlaybackCoordinator> {
        val personal = mockk<PersonalBoardRepository>(relaxed = true)
        every { personal.getClimbListById(7L) } returns
            Climb_lists(7L, "At your limit", false, "2026-09-24", 2L, hasPlaybackPlan = hasPlan)
        every { personal.getClimbListEntryUuids(7L, any(), any()) } returns
            listOf("a" to "2026-09-24", "b" to "2026-09-24")
        every { personal.countClimbListEntries(7L) } returns 2L
        // Two tries of "a" with rests between, then "b".
        every { personal.getPlaybackSteps(7L) } returns listOf(
            climbStep(0, "a"), restStep(1, 240), climbStep(2, "a"), restStep(3, 300), climbStep(4, "b"),
        )
        val board = mockk<BoardRepository>(relaxed = true)
        every { board.getClimbsByUuids(any(), any()) } returns listOf(climb("a"), climb("b"))
        every { board.getClimbsByUuidsAnyAngle(any()) } returns listOf(climb("a"), climb("b"))
        val prefs = mockk<UserPreferences>(relaxed = true)
        every { prefs.boardAngle } returns flowOf(40)
        every { prefs.gradeScale } returns flowOf(GradeScale.FRENCH)
        val zones = mockk<IntensityZoneManager>(relaxed = true)
        every { zones.zones } returns MutableStateFlow(mockk<IntensityZones>(relaxed = true))
        val playback = mockk<PlaylistPlaybackCoordinator>(relaxed = true)
        val vm = BoardListDetailViewModel(
            SavedStateHandle(mapOf("listId" to "7")), board, personal, prefs, zones, playback, mockk(relaxed = true),
        )
        awaitUntil { vm.state.value.entries.size == 2 }
        return vm to playback
    }

    private fun start(vm: BoardListDetailViewModel) {
        var started = false
        vm.startPlayback("QA") { started = true }
        awaitUntil { started }
        assertTrue("playback did not start", started)
    }

    @Test
    fun `a list with a training plan plays its tries and rests`() {
        val (vm, playback) = model(hasPlan = true)

        start(vm)

        verify {
            playback.play(
                "QA",
                listOf(
                    QueueItem("a", 40, restAfterSeconds = 240),
                    QueueItem("a", 40, restAfterSeconds = 300),
                    QueueItem("b", 40),
                ),
                "list:7",
            )
        }
    }

    @Test
    fun `a list without a plan plays once in its saved order`() {
        val (vm, playback) = model(hasPlan = false)

        start(vm)

        verify { playback.play("QA", listOf(QueueItem("a", 40), QueueItem("b", 40)), "list:7") }
    }
}
