package com.cruxcoach.app.detail

import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ClimbDetailPresenterTest {
    private lateinit var db: BrowseTestDb
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @BeforeTest
    fun setUp() {
        db = BrowseTestDb()
        // Two same-row, mirror-symmetric placements on the default 0..144 edge box.
        db.boardRepo.upsertPlacement(100, 1, 1, 24, 78)
        db.boardRepo.upsertPlacement(101, 2, 1, 120, 78)
        db.boardRepo.upsertProductSize(10, 1, "12x12", 0, 144, 0, 156, null)
        db.climb("k1", frames = "p100r12p999r13", angle = 40, difficulty = 15.0)
        db.board.boardQueries.upsertClimbStat("k1", 25, 12.0, 12.0, 3.0, 4, null, null, null, null)
        db.climb("com1", provenance = "cruxcoach", pubkey = "abcdef0123456789ffff", angle = 35)
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun presenter() =
        ClimbDetailPresenter(db.boardRepo, db.personalRepo, MemoryKeyValueStore(), main = dispatcher, io = dispatcher)

    @Test
    fun `loads stats per angle history flags and drawable holds`() = scope.runTest {
        db.send("k1")
        val p = presenter()
        p.open("K1", 40); advanceUntilIdle()
        var s = p.state.value
        assertEquals(ClimbDetailStatus.READY, s.status)
        val data = s.data!!
        assertEquals("k1", data.climb.uuid)
        // 25/40 carry stats; 35 is an angle the board is used at (com1), offered without a grade.
        assertEquals(listOf(25, 35, 40), data.availableAngles.map { it.angle })
        assertNull(data.availableAngles[1].difficultyAverage)
        assertEquals(12.0, data.availableAngles.first().difficultyAverage)
        assertEquals(1, data.userAscents.size)
        assertTrue(data.isMirrorable)
        assertEquals(mapOf(100 to 101, 101 to 100), data.mirrorMap)
        assertEquals(listOf("board_images/board_10.webp"), data.boardImagePaths)
        // p999 has no placement and is not drawn; p100 sits at x=24/144, y=1-78/156.
        assertEquals(1, s.holds.size)
        assertEquals(24f / 144f, s.holds[0].x, 1e-6f)
        assertEquals(0.5f, s.holds[0].y, 1e-6f)
        assertEquals(0xFFFF00FF, s.holds[0].argb, "role 12 = start; the default CruxCoach start colour is magenta (0xE3)")
        p.close()
    }

    @Test
    fun `mirror favourite ignore and note round trip through the secure db`() = scope.runTest {
        val p = presenter()
        p.open("k1", 40); advanceUntilIdle()
        p.toggleMirror()
        assertTrue(p.state.value.isMirrored)
        assertEquals(101, p.state.value.holds[0].placementId)
        assertEquals(120f / 144f, p.state.value.holds[0].x, 1e-6f)

        p.toggleFavourite(); advanceUntilIdle()
        assertTrue(p.state.value.isFavorited)
        assertTrue(db.personalRepo.isClimbFavorited("k1"))
        p.setIgnored(true); advanceUntilIdle()
        p.setIgnored(true); advanceUntilIdle()
        assertTrue(db.personalRepo.isClimbIgnored("k1"), "setting twice must not toggle back")
        assertTrue(p.state.value.browserDirty)
        p.setIgnored(false); advanceUntilIdle()
        assertFalse(db.personalRepo.isClimbIgnored("k1"))

        p.saveNote("  " + "x".repeat(1200) + "  "); advanceUntilIdle()
        assertEquals(NoteSaveStatus.SAVED, p.state.value.noteStatus)
        assertEquals(1000, db.personalRepo.getClimbNote("k1")!!.length)

        p.selectAngle(25); advanceUntilIdle()
        assertEquals(12.0, p.state.value.data!!.climb.difficultyAverage)
        assertTrue(p.state.value.isFavorited)
        p.close()
    }

    @Test
    fun `setter rule logbook only and not found`() = scope.runTest {
        val p = presenter()
        p.open("com1", 35); advanceUntilIdle()
        val data = p.state.value.data!!
        assertEquals(SetterProfile("setter", null, true), data.setterProfile)
        assertTrue(data.availableAngles.single { it.angle == 35 }.isSetterAngle)
        val anonymous = data.climb.copy(setterUsername = " ")
        assertEquals("npub:abcdef0123456789", ClimbDetailAssembler.seedSetterProfile(anonymous)!!.displayName)
        assertNull(ClimbDetailAssembler.seedSetterProfile(anonymous.copy(origin = "kilter")))

        db.send("only-in-logbook")
        p.open("only-in-logbook", 40); advanceUntilIdle()
        assertEquals(ClimbDetailStatus.LOGBOOK_ONLY, p.state.value.status)
        assertEquals(1, p.state.value.logbookAscents.size)
        p.open("nope", 40); advanceUntilIdle()
        assertEquals(ClimbDetailStatus.NOT_FOUND, p.state.value.status)
        p.toggleFavourite(); p.saveNote("x"); advanceUntilIdle() // no climb: no-ops, no crash
        p.close()
    }
}
