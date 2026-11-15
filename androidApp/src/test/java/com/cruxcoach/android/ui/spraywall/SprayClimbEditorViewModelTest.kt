package com.cruxcoach.android.ui.spraywall

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.spraywall.SprayWallPhotoStore
import com.cruxcoach.android.spraywall.SprayWallPublisher
import com.cruxcoach.android.spraywall.SprayWallRepository
import com.cruxcoach.android.spraywall.SprayWithdrawal
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.spraywall.EditableHold
import com.cruxcoach.domain.spraywall.SprayHoldRole
import com.cruxcoach.domain.spraywall.SprayWallVisibility
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Save in the app bar always answers; Delete keeps a climb no server took back. */
@OptIn(ExperimentalCoroutinesApi::class)
class SprayClimbEditorViewModelTest {
    private lateinit var driver: SqlDriver
    private lateinit var repository: SprayWallRepository
    private val photo = SprayWallPhotoStore.StoredPhoto("0b1c2d3e-photo.jpg", 3000, 2000)
    private val photos = mockk<SprayWallPhotoStore>(relaxed = true)
    private val preferences = mockk<UserPreferences>(relaxed = true)
    private val viewModels = mutableListOf<SprayClimbEditorViewModel>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SecureDatabase.Schema.create(driver)
        repository = SprayWallRepository(SecureDatabase(driver), mockk(relaxed = true))
        coEvery { photos.load(any(), any()) } returns null
        every { preferences.gradeScale } returns flowOf(GradeScale.FRENCH)
    }

    @AfterTest
    fun tearDown() {
        try {
            // The editor goes on loading after the state a test waited for (the photo comes last). Cancelling waits
            // for that work, so none of it meets a closed database or a Main that is gone.
            runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        } finally {
            Dispatchers.resetMain()
            driver.close()
        }
    }

    /** The editor's view model for [arguments]; [tearDown] ends its work. */
    private fun editor(arguments: Map<String, String>, publisher: SprayWallPublisher = mockk(relaxed = true)) =
        SprayClimbEditorViewModel(SavedStateHandle(arguments), repository, photos, preferences, publisher).also { viewModels += it }

    @Test
    fun `saving a climb with something to fix shows the problems and says so to the screen`() = runTest {
        val wall = repository.createWall("Wall", 40, SprayWallVisibility.PRIVATE, null, photo, listOf(EditableHold(-1, 0.5f, 0.5f, 0.02f)))
        val vm = editor(mapOf(SprayWallRoutes.ARG_WALL to wall))
        vm.state.first { !it.loading }

        vm.events.test {
            vm.save()
            assertEquals(SprayClimbEditorEvent.NotSaved, awaitItem())
            // A second Save with the same problems answers again.
            vm.save()
            assertEquals(SprayClimbEditorEvent.NotSaved, awaitItem())
        }
        assertTrue(vm.state.value.showProblems)
        assertTrue(vm.state.value.problems.isNotEmpty())
    }

    @Test
    fun `a climb no server took back stays, and the screen says why`() = runTest {
        val wall = repository.createWall("Wall", 40, SprayWallVisibility.PUBLIC, null, photo, listOf(EditableHold(-1, 0.5f, 0.5f, 0.02f)))
        val climb = repository.saveClimb(null, wall, "Line", "", 20, mapOf(1L to SprayHoldRole.START))
        val publisher = mockk<SprayWallPublisher>()
        val answer = CompletableDeferred<SprayWithdrawal>()
        coEvery { publisher.deleteClimb(climb) } coAnswers { answer.await() }
        val vm = editor(mapOf(SprayWallRoutes.ARG_WALL to wall, SprayWallRoutes.ARG_CLIMB to climb), publisher)
        vm.state.first { !it.loading }

        vm.delete()
        assertTrue(vm.state.value.deleting)
        answer.complete(SprayWithdrawal.SIGNING_FAILED)
        vm.state.first { !it.deleting }
        assertEquals(SprayWithdrawal.SIGNING_FAILED, vm.state.value.deleteFailed)
        assertFalse(vm.state.value.deleted)

        // Taken back on a second try: deleted, and the screen closes.
        coEvery { publisher.deleteClimb(climb) } returns SprayWithdrawal.DONE
        vm.delete()
        vm.state.first { it.deleted }
        assertNull(vm.state.value.deleteFailed)
    }
}
