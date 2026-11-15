package com.cruxcoach.android.ui.spraywall

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.SavedStateHandle
import com.cruxcoach.android.spraywall.SprayWallPublisher
import com.cruxcoach.android.spraywall.SprayWallRepository
import com.cruxcoach.android.spraywall.SprayWithdrawal
import com.cruxcoach.domain.spraywall.SprayVersionKind
import com.cruxcoach.domain.spraywall.SprayWall
import com.cruxcoach.domain.spraywall.SprayWallVersion
import com.cruxcoach.domain.spraywall.SprayWallVisibility
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a public wall's details say while Delete runs. Deleting withdraws the wall first, and "Withdrawing the wall …"
 * alone made a tester think withdrawing had deleted it: deleting names both steps, making the wall private keeps the
 * one. A failed withdrawal still says the wall is not deleted, and the wall stays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w360dp-h800dp")
class SprayWallDeleteTextTest {
    @get:Rule val compose = createComposeRule()

    private var stored: SprayWall? = SprayWall(
        id = ID, name = "Garage", angle = 40, visibility = SprayWallVisibility.PUBLIC, gym = null, ownerPubkey = null,
        current = SprayWallVersion(ID, 1, SprayVersionKind.CREATED, null, "photo.jpg", 3000, 2000, null, null, 12, 0L),
        publishedVersion = 1, createdAt = 0L, updatedAt = 0L,
    )
    private val repository = mockk<SprayWallRepository>(relaxed = true).also {
        every { it.changes } returns MutableStateFlow(0L)
        coEvery { it.wall(ID) } answers { stored }
        coEvery { it.deleteWall(ID) } answers { stored = null }
    }

    /** How the withdrawal ends; until a test says, it runs. */
    private val withdrawal = CompletableDeferred<SprayWithdrawal>()
    private val withdrawing = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Runs a withdrawal as the publisher does: shown (deleting or not) while it runs, the wall deleted once it is done. */
    private val publisher = mockk<SprayWallPublisher>(relaxed = true).also {
        every { it.withdrawing } returns withdrawing
        every { it.hasIdentity() } returns true
        coEvery { it.withdrawWall(ID, any(), any()) } coAnswers {
            val delete = thirdArg<Boolean>()
            withdrawing.value = mapOf(ID to delete)
            secondArg<suspend () -> Unit>().invoke()
            val result = withdrawal.await()
            if (result == SprayWithdrawal.DONE && delete) repository.deleteWall(ID)
            withdrawing.value = emptyMap()
            result
        }
    }
    private var deleted = 0

    private fun show() {
        val viewModel = SprayWallDetailsViewModel(SavedStateHandle(mapOf(SprayWallRoutes.ARG_WALL to ID)), repository, publisher, mockk(relaxed = true))
        compose.setContent { MaterialTheme { SprayWallDetailsScreen({}, {}, { deleted++ }, viewModel = viewModel) } }
        compose.onNodeWithTag("spray_wall_delete").assertExists()
    }

    private fun click(tag: String) = compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)

    /** "Wand löschen", then "Löschen" in the question. */
    private fun delete() {
        click("spray_wall_delete")
        click("spray_confirm")
    }

    @Test
    fun `deleting a public wall says it is withdrawn and then deleted`() {
        show()
        delete()

        compose.onNodeWithText(DELETING_DE).assertExists()
        compose.onNodeWithText(WITHDRAWING_DE).assertDoesNotExist()
        coVerify(exactly = 0) { repository.deleteWall(any()) }

        withdrawal.complete(SprayWithdrawal.DONE)
        compose.waitForIdle()
        coVerify(exactly = 1) { repository.deleteWall(ID) }
        assertEquals(1, deleted)
    }

    @Test
    @Config(qualifiers = "en-w360dp-h800dp")
    fun `deleting a public wall names both steps in English`() {
        show()
        delete()

        compose.onNodeWithText("Withdrawing the wall, then deleting it …").assertExists()
        compose.onNodeWithText("Withdrawing the wall …").assertDoesNotExist()
    }

    @Test
    fun `deleting a wall with an unfinished withdrawal says so on its card`() {
        stored = stored!!.copy(withdrawStarted = 1L)
        show()
        compose.onNodeWithTag("spray_withdraw_unfinished").assertExists()
        delete()

        // The card's button and Save both show the progress.
        compose.onNodeWithTag("spray_withdraw_again").assert(hasText(DELETING_DE))
        compose.onNodeWithTag("spray_wall_save").assert(hasText(DELETING_DE))
        compose.onNodeWithText(WITHDRAWING_DE).assertDoesNotExist()
    }

    @Test
    fun `making a public wall private only says it is withdrawn`() {
        show()
        click("spray_visibility_private")
        click("spray_confirm")
        click("spray_wall_save")

        compose.onNodeWithText(WITHDRAWING_DE).assertExists()
        compose.onNodeWithText(DELETING_DE).assertDoesNotExist()
    }

    @Test
    fun `a delete whose withdrawal failed says the wall is not deleted, and the wall stays`() {
        show()
        delete()
        withdrawal.complete(SprayWithdrawal.NOT_CONFIRMED)
        compose.waitForIdle()

        compose.onNodeWithText("Wand nicht gelöscht").assertExists()
        compose.onNodeWithText("Eine öffentliche Wand wird hier erst gelöscht, wenn sie zurückgezogen ist.", substring = true).assertExists()
        compose.onNodeWithText(DELETING_DE).assertDoesNotExist()
        coVerify(exactly = 0) { repository.deleteWall(any()) }
        assertEquals(0, deleted)

        // "OK": the wall is still here and public, to save or to delete again.
        click("spray_confirm")
        compose.onNodeWithText("Wand nicht gelöscht").assertDoesNotExist()
        compose.onNodeWithTag("spray_wall_save").assertIsEnabled()
        compose.onNodeWithTag("spray_wall_delete").assertIsEnabled()
        compose.onNodeWithTag("spray_visibility_public").assertIsSelected()
    }

    @Test
    fun `the card of an unfinished withdrawal names deleting only for a withdrawal that deletes`() {
        var deletes by mutableStateOf(true)
        compose.setContent { MaterialTheme { WithdrawUnfinishedCard(withdrawing = true, deleting = deletes, enabled = true, onRetry = {}) } }
        compose.onNodeWithText(DELETING_DE).assertExists()

        deletes = false
        compose.onNodeWithText(WITHDRAWING_DE).assertExists()
        compose.onNodeWithText(DELETING_DE).assertDoesNotExist()
    }

    private companion object {
        const val ID = "wall-1"
        const val DELETING_DE = "Wand wird zurückgezogen und danach gelöscht …"
        const val WITHDRAWING_DE = "Wand wird zurückgezogen …"
    }
}
