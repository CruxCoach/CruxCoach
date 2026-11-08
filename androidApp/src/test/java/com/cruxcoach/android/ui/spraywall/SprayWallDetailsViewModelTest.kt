package com.cruxcoach.android.ui.spraywall

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.android.spraywall.SprayWallPhotoStore
import com.cruxcoach.android.spraywall.SprayWallPublisher
import com.cruxcoach.android.spraywall.SprayWallRepository
import com.cruxcoach.android.spraywall.SprayWithdrawal
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.spraywall.EditableHold
import com.cruxcoach.domain.spraywall.SprayWallVisibility
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Saving a public wall's details when taking it back fails: the edits stay, and the relays get them when they still show the wall. */
@OptIn(ExperimentalCoroutinesApi::class)
class SprayWallDetailsViewModelTest {
    private lateinit var driver: SqlDriver
    private lateinit var repository: SprayWallRepository
    private val publisher = mockk<SprayWallPublisher>(relaxed = true)
    private val photo = SprayWallPhotoStore.StoredPhoto("0b1c2d3e-photo.jpg", 3000, 2000)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SecureDatabase.Schema.create(driver)
        repository = SprayWallRepository(SecureDatabase(driver), mockk(relaxed = true))
        every { publisher.hasIdentity() } returns true
    }

    @AfterTest
    fun tearDown() {
        // The view model's last database call resumes on Main after the observed state: let it land first.
        Thread.sleep(200)
        Dispatchers.resetMain()
        driver.close()
    }

    /** A public wall on Nostr; renamed and set to private, but the withdrawal ends with [result]. */
    private suspend fun renameAndWithdraw(result: SprayWithdrawal): Pair<String, SprayWallDetailsState> {
        val id = repository.createWall("Wall", 40, SprayWallVisibility.PUBLIC, null, photo, listOf(EditableHold(-1, 0.5f, 0.5f, 0.02f)))
        repository.setPhotoBlob(id, photo.fileName, SHA, "https://cdn.example.org/$SHA")
        repository.markPublished(id, 1)
        coEvery { publisher.withdrawWall(id) } returns result

        val vm = SprayWallDetailsViewModel(SavedStateHandle(mapOf(SprayWallRoutes.ARG_WALL to id)), repository, publisher, mockk(relaxed = true))
        assertTrue(vm.state.first { !it.loading }.published)
        vm.setName("Wall B")
        vm.setAngle(45)
        vm.chooseVisibility(SprayWallVisibility.PRIVATE)
        assertEquals(SprayWallDetailsState.Dialog.CONFIRM_WITHDRAW, vm.state.value.dialog)
        vm.confirmDialog()
        vm.save()
        return id to vm.state.first { it.withdrawFailed != null }
    }

    private suspend fun assertEditsKept(id: String, state: SprayWallDetailsState) {
        assertTrue(state.withdrawFailed!!.keptEdits)
        assertEquals(SprayWallVisibility.PUBLIC, state.visibility)
        val wall = repository.wall(id)!!
        assertEquals("Wall B", wall.name)
        assertEquals(45, wall.angle)
        assertEquals(SprayWallVisibility.PUBLIC, wall.visibility)
        assertEquals(1, wall.publishedVersion)
    }

    @Test
    fun `a rename kept after the relays did not confirm the withdrawal sends the head again`() = runTest {
        val (id, state) = renameAndWithdraw(SprayWithdrawal.NOT_CONFIRMED)

        assertEditsKept(id, state)
        verify(exactly = 1) { publisher.publishHeadSoon(id) }
    }

    @Test
    fun `a rename kept without an identity waits for the head to go out`() = runTest {
        val (id, state) = renameAndWithdraw(SprayWithdrawal.NO_IDENTITY)

        assertEditsKept(id, state)
        verify(exactly = 1) { publisher.publishHeadSoon(id) }
    }

    @Test
    fun `a rename sends the head of a wall whose first publish ended while the screen was open`() = runTest {
        val id = repository.createWall("Wall", 40, SprayWallVisibility.PUBLIC, null, photo, listOf(EditableHold(-1, 0.5f, 0.5f, 0.02f)))
        val vm = SprayWallDetailsViewModel(SavedStateHandle(mapOf(SprayWallRoutes.ARG_WALL to id)), repository, publisher, mockk(relaxed = true))
        vm.state.first { !it.loading }
        repository.markPublished(id, 1)

        vm.setName("Wall B")
        vm.events.test {
            vm.save()
            assertEquals(SprayWallDetailsEvent.Saved, awaitItem())
        }

        verify(exactly = 1) { publisher.publishHeadSoon(id) }
    }

    @Test
    fun `no head after the relays took the tombstones and a photo stayed on Blossom`() = runTest {
        val (id, state) = renameAndWithdraw(SprayWithdrawal.PHOTOS_LEFT)

        assertEditsKept(id, state)
        verify(exactly = 0) { publisher.publishHeadSoon(any()) }
    }

    @Test
    fun `no head right after the signer declined`() = runTest {
        val (id, state) = renameAndWithdraw(SprayWithdrawal.SIGNING_FAILED)

        assertEditsKept(id, state)
        verify(exactly = 0) { publisher.publishHeadSoon(any()) }
    }

    private companion object {
        val SHA = "5e".repeat(32)
    }
}
