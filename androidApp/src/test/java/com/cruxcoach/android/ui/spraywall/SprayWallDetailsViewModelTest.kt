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
import com.cruxcoach.domain.spraywall.SprayWall
import com.cruxcoach.domain.spraywall.SprayWallVisibility
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

/** Saving a public wall's details: a published wall's head goes out again, also when taking the wall back failed and the relays still show it. */
@OptIn(ExperimentalCoroutinesApi::class)
class SprayWallDetailsViewModelTest {
    private lateinit var driver: SqlDriver
    private lateinit var repository: SprayWallRepository
    private val publisher = mockk<SprayWallPublisher>(relaxed = true)
    private val photo = SprayWallPhotoStore.StoredPhoto("0b1c2d3e-photo.jpg", 3000, 2000)
    private val withdrawing = MutableStateFlow<Set<String>>(emptySet())

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SecureDatabase.Schema.create(driver)
        repository = SprayWallRepository(SecureDatabase(driver), mockk(relaxed = true))
        every { publisher.hasIdentity() } returns true
        every { publisher.withdrawing } returns withdrawing
    }

    @AfterTest
    fun tearDown() {
        // The view model's last database call resumes on Main after the observed state: let it land first.
        Thread.sleep(200)
        Dispatchers.resetMain()
        driver.close()
    }

    /**
     * The publisher's withdrawal of [id] ends with [result]: it runs the screen's step before it, then [during] (what
     * the withdrawal itself does to the wall), and the step after it once the wall is private.
     */
    private fun withdrawalEnds(id: String, result: SprayWithdrawal, during: suspend () -> Unit = {}) {
        coEvery { publisher.withdrawWall(id, any(), any()) } coAnswers {
            secondArg<suspend () -> Unit>().invoke()
            during()
            if (result == SprayWithdrawal.DONE) thirdArg<suspend () -> Unit>().invoke()
            result
        }
    }

    /** A public wall with version 1 on Nostr. */
    private suspend fun publishedWall(): String {
        val id = repository.createWall("Wall", 40, SprayWallVisibility.PUBLIC, null, photo, listOf(EditableHold(-1, 0.5f, 0.5f, 0.02f)))
        repository.setPhotoBlob(id, photo.fileName, SHA, "https://cdn.example.org/$SHA")
        repository.markPublished(id, 1)
        return id
    }

    private suspend fun detailsOf(id: String): SprayWallDetailsViewModel {
        val vm = SprayWallDetailsViewModel(SavedStateHandle(mapOf(SprayWallRoutes.ARG_WALL to id)), repository, publisher, mockk(relaxed = true))
        assertTrue(vm.state.first { !it.loading }.published)
        return vm
    }

    /**
     * A published wall, renamed and set to private, but the withdrawal ends with [result]; [tombstonesTaken] = a relay
     * took a tombstone before it failed (the withdrawal marks the wall then).
     */
    private suspend fun renameAndWithdraw(result: SprayWithdrawal, tombstonesTaken: Boolean = false): Pair<String, SprayWallDetailsState> {
        val id = publishedWall()
        withdrawalEnds(id, result) { if (tombstonesTaken) repository.markWithdrawStarted(id) }
        val vm = detailsOf(id)
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

    /** The head with the new details waits in the database until a relay takes it, also across a restart. */
    private suspend fun headPending(id: String): Boolean = repository.wallsWithHeadPending().any { it.id == id }

    @Test
    fun `renaming a published wall keeps its head to send`() = runTest {
        val id = publishedWall()
        val vm = detailsOf(id)
        vm.setName("Wall B")
        vm.events.test {
            vm.save()
            assertEquals(SprayWallDetailsEvent.Saved, awaitItem())
        }

        assertTrue(headPending(id))
        verify { publisher.publishSoon() }
    }

    @Test
    fun `saving a published wall unchanged sends no head`() = runTest {
        val id = publishedWall()
        val vm = detailsOf(id)
        vm.events.test {
            vm.save()
            assertEquals(SprayWallDetailsEvent.Saved, awaitItem())
        }

        assertFalse(headPending(id))
    }

    @Test
    fun `a rename kept after the relays did not confirm the withdrawal sends the head again`() = runTest {
        val (id, state) = renameAndWithdraw(SprayWithdrawal.NOT_CONFIRMED)

        assertEditsKept(id, state)
        assertTrue(headPending(id))
        verify { publisher.publishSoon() }
    }

    @Test
    fun `a rename kept without an identity keeps its head to send`() = runTest {
        val (id, state) = renameAndWithdraw(SprayWithdrawal.NO_IDENTITY)

        assertEditsKept(id, state)
        assertTrue(headPending(id))
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

        assertTrue(headPending(id))
    }

    @Test
    fun `no head after the relays took the tombstones and a photo stayed on Blossom`() = runTest {
        val (id, state) = renameAndWithdraw(SprayWithdrawal.PHOTOS_LEFT, tombstonesTaken = true)

        assertEditsKept(id, state)
        assertFalse(headPending(id))
        assertTrue(state.withdrawStarted)
        verify(exactly = 0) { publisher.publishSoon() }
    }

    @Test
    fun `no head after the signer declined once the relays took a tombstone`() = runTest {
        val (id, state) = renameAndWithdraw(SprayWithdrawal.SIGNING_FAILED, tombstonesTaken = true)

        assertEditsKept(id, state)
        assertFalse(headPending(id))
        assertTrue(state.withdrawStarted)
    }

    @Test
    fun `a rename kept after the signer declined the first tombstone keeps its head to send, but not at once`() = runTest {
        // Nothing left the phone: the wall is fully public, and the relays get the new name. Not right away, though:
        // that would ask the signer to publish the wall the climber meant to make private (the publisher holds the
        // wall then, see SprayWallPublishingTest).
        val (id, state) = renameAndWithdraw(SprayWithdrawal.SIGNING_FAILED)

        assertEditsKept(id, state)
        assertTrue(headPending(id))
        assertFalse(state.withdrawStarted)
        assertFalse(state.withdrawFailed!!.started)
        verify(exactly = 0) { publisher.publishSoon() }
    }

    @Test
    fun `the failure says the relays took part of the withdrawal once they did`() = runTest {
        val (_, state) = renameAndWithdraw(SprayWithdrawal.SIGNING_FAILED, tombstonesTaken = true)

        assertTrue(state.withdrawFailed!!.started)
    }

    @Test
    fun `a read of the wall from before a relay took a tombstone does not hide the unfinished withdrawal when it lands late`() = runTest {
        // The database answers each read when the test lets it, the newest first: the read the saved details asked
        // for, taken before the withdrawal marked the wall, is ready only after the one the failure asked for.
        val id = publishedWall()
        var stored = repository.wall(id)!!
        val changes = MutableStateFlow(0L)
        val reads = ArrayDeque<Pair<SprayWall, CompletableDeferred<SprayWall?>>>()
        val tombstoneTaken = CompletableDeferred<Unit>()
        val slow = mockk<SprayWallRepository>(relaxed = true)
        every { slow.changes } returns changes
        coEvery { slow.wall(id) } coAnswers { CompletableDeferred<SprayWall?>().also { reads.addLast(stored to it) }.await() }
        coEvery { slow.updateWallDetails(id, any(), any(), any(), any(), any()) } coAnswers {
            stored = stored.copy(name = secondArg())
            changes.value++
        }
        coEvery { publisher.withdrawWall(id, any(), any()) } coAnswers {
            secondArg<suspend () -> Unit>().invoke()
            tombstoneTaken.await()
            stored = stored.copy(withdrawStarted = 1L)
            changes.value++
            SprayWithdrawal.SIGNING_FAILED
        }
        fun land() {
            check(reads.isNotEmpty()) { "the view model waits for something else than a read of the wall" }
            reads.toList().asReversed().also { reads.clear() }.forEach { (wall, read) -> read.complete(wall) }
        }

        val vm = SprayWallDetailsViewModel(SavedStateHandle(mapOf(SprayWallRoutes.ARG_WALL to id)), slow, publisher, mockk(relaxed = true))
        while (reads.isNotEmpty()) land()
        vm.setName("Wall B")
        vm.chooseVisibility(SprayWallVisibility.PRIVATE)
        vm.confirmDialog()
        vm.save()
        // The wall as it was stored, then the details are saved with the withdrawal: the screen reads the wall.
        land()
        assertEquals("Wall B", reads.single().first.name)
        assertNull(reads.single().first.withdrawStarted)
        tombstoneTaken.complete(Unit)
        while (vm.state.value.withdrawFailed == null) land()

        assertTrue(vm.state.value.withdrawFailed!!.started)
        assertTrue(vm.state.value.withdrawStarted)
        // And it stays once every read is in.
        while (reads.isNotEmpty()) land()
        assertTrue(vm.state.value.withdrawStarted)
    }

    @Test
    fun `the details are saved before the withdrawal runs, so leaving the screen meanwhile loses none`() = runTest {
        val id = publishedWall()
        var nameWhileWithdrawing: String? = null
        withdrawalEnds(id, SprayWithdrawal.DONE) {
            nameWhileWithdrawing = repository.wall(id)!!.name
            repository.updateWallDetails(id, repository.wall(id)!!.name, 40, SprayWallVisibility.PRIVATE, null)
        }
        val vm = detailsOf(id)
        vm.setName("Wall B")
        vm.chooseVisibility(SprayWallVisibility.PRIVATE)
        vm.confirmDialog()
        vm.events.test {
            vm.save()
            assertEquals(SprayWallDetailsEvent.Saved, awaitItem())
        }

        assertEquals("Wall B", nameWhileWithdrawing)
        val wall = repository.wall(id)!!
        assertEquals("Wall B", wall.name)
        assertEquals(SprayWallVisibility.PRIVATE, wall.visibility)
        assertNull(wall.headPending)
    }

    @Test
    fun `a details screen open while the wall page finished the withdrawal keeps the wall private`() = runTest {
        val id = publishedWall()
        repository.markWithdrawStarted(id)
        val vm = detailsOf(id)
        assertTrue(vm.state.value.withdrawStarted)

        // "Try withdrawing again" on the wall page, done while this screen stayed open.
        repository.updateWallDetails(id, "Wall", 40, SprayWallVisibility.PRIVATE, null)
        val fresh = vm.state.first { !it.withdrawStarted }
        assertEquals(SprayWallVisibility.PRIVATE, fresh.visibility)
        assertFalse(fresh.published)

        vm.events.test {
            vm.save()
            assertEquals(SprayWallDetailsEvent.Saved, awaitItem())
        }
        val wall = repository.wall(id)!!
        assertEquals(SprayWallVisibility.PRIVATE, wall.visibility)
        assertTrue(repository.wallsNeedingPublish().none { it.id == id })
        verify(exactly = 0) { publisher.publishSoon() }
    }

    @Test
    fun `a save from a screen read before the withdrawal finished does not make the wall public again`() = runTest {
        // Also when the screen had not seen the change yet: save reads the wall again.
        val id = publishedWall()
        repository.markWithdrawStarted(id)
        val vm = detailsOf(id)
        vm.setName("Wall B")
        repository.updateWallDetails(id, "Wall", 40, SprayWallVisibility.PRIVATE, null)

        vm.events.test {
            vm.save()
            assertEquals(SprayWallDetailsEvent.Saved, awaitItem())
        }
        val wall = repository.wall(id)!!
        assertEquals(SprayWallVisibility.PRIVATE, wall.visibility)
        assertEquals("Wall B", wall.name)
        verify(exactly = 0) { publisher.publishSoon() }
    }

    @Test
    fun `deleting a public wall leaves the deleting to the withdrawal, so leaving the screen does not stop it`() = runTest {
        val id = publishedWall()
        withdrawalEnds(id, SprayWithdrawal.DONE) { repository.updateWallDetails(id, "Wall", 40, SprayWallVisibility.PRIVATE, null) }
        val vm = detailsOf(id)
        vm.events.test {
            vm.delete()
            assertEquals(SprayWallDetailsEvent.Deleted, awaitItem())
        }

        assertNull(repository.wall(id))
    }

    @Test
    fun `while a withdrawal runs the screen keeps the choice it showed`() = runTest {
        // The withdrawal marks the wall when it starts; the card for an unfinished one is not for a running one.
        val id = publishedWall()
        val vm = detailsOf(id)
        withdrawing.value = setOf(id)
        vm.state.first { it.withdrawing }
        repository.markWithdrawStarted(id)
        Thread.sleep(100)
        assertFalse(vm.state.value.withdrawStarted)

        // It ends with the relays holding part of it: now the card shows.
        withdrawing.value = emptySet()
        assertTrue(vm.state.first { !it.withdrawing && it.withdrawStarted }.withdrawStarted)
    }

    @Test
    fun `saving waits while the wall is being withdrawn`() = runTest {
        val id = publishedWall()
        val vm = detailsOf(id)
        withdrawing.value = setOf(id)
        vm.state.first { it.withdrawing }

        assertFalse(vm.state.value.canSave)
        withdrawing.value = emptySet()
        assertTrue(vm.state.first { !it.withdrawing }.canSave)
    }

    @Test
    fun `a wall the relays took back in part offers trying again, and done it is private`() = runTest {
        val id = publishedWall()
        repository.markWithdrawStarted(id)
        withdrawalEnds(id, SprayWithdrawal.DONE) { repository.updateWallDetails(id, "Wall", 40, SprayWallVisibility.PRIVATE, null) }
        val vm = detailsOf(id)
        assertTrue(vm.state.value.withdrawStarted)

        vm.withdrawAgain()
        val done = vm.state.first { !it.withdrawStarted }

        assertEquals(SprayWallVisibility.PRIVATE, done.visibility)
        assertFalse(done.published)
        assertEquals(SprayWallVisibility.PRIVATE, repository.wall(id)!!.visibility)
        assertNull(repository.wall(id)!!.withdrawStarted)
    }

    @Test
    fun `a rename of a wall the relays took back in part waits for the withdrawal`() = runTest {
        val id = publishedWall()
        repository.markWithdrawStarted(id)
        val vm = detailsOf(id)
        vm.setName("Wall B")
        vm.events.test {
            vm.save()
            assertEquals(SprayWallDetailsEvent.Saved, awaitItem())
        }

        assertEquals("Wall B", repository.wall(id)!!.name)
        assertFalse(headPending(id))
    }

    private companion object {
        val SHA = "5e".repeat(32)
    }
}
