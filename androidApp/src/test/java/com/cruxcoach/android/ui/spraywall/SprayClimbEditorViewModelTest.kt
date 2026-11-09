package com.cruxcoach.android.ui.spraywall

import androidx.lifecycle.SavedStateHandle
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.spraywall.SprayWallPhotoStore
import com.cruxcoach.android.spraywall.SprayWallRepository
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.spraywall.EditableHold
import com.cruxcoach.domain.spraywall.SprayWallVisibility
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Save in the app bar always answers: a climb with something to fix brings its problems into view. */
@OptIn(ExperimentalCoroutinesApi::class)
class SprayClimbEditorViewModelTest {
    private lateinit var driver: SqlDriver
    private lateinit var repository: SprayWallRepository
    private val photo = SprayWallPhotoStore.StoredPhoto("0b1c2d3e-photo.jpg", 3000, 2000)
    private val photos = mockk<SprayWallPhotoStore>(relaxed = true)
    private val preferences = mockk<UserPreferences>(relaxed = true)

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
        // The view model's last database call resumes on Main after the observed state: let it land first.
        Thread.sleep(200)
        Dispatchers.resetMain()
        driver.close()
    }

    @Test
    fun `saving a climb with something to fix shows the problems and says so to the screen`() = runTest {
        val wall = repository.createWall("Wall", 40, SprayWallVisibility.PRIVATE, null, photo, listOf(EditableHold(-1, 0.5f, 0.5f, 0.02f)))
        val vm = SprayClimbEditorViewModel(
            SavedStateHandle(mapOf(SprayWallRoutes.ARG_WALL to wall)), repository, photos, preferences, mockk(relaxed = true),
        )
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
}
