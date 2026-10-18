package com.cruxcoach.app.ui

import com.cruxcoach.app.community.CommunityDeleteResult
import com.cruxcoach.app.community.DeleteOutcome
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.data.repository.ClimbWithStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The detail screen's owner gate (Android `BoardClimbDetailScreen` menu and
 * `BoardClimbDetailViewModel` request/confirm deletes).
 */
class ClimbOwnerActionsModelTest {
    private val serial = com.cruxcoach.app.logbook.SerialDispatcher().dispatcher
    private val me = "a".repeat(64)

    private fun climb(
        origin: String = "cruxcoach",
        owner: String? = me,
        source: String = "local",
        eventId: String? = null,
        kilterStatus: String? = null,
    ) = ClimbWithStats(
        uuid = "c1", layoutId = 1L, setterUsername = "me", name = "Crimp line", frames = "p1r12",
        framesCount = 1L, difficultyAverage = 18.0, qualityAverage = 3.0, ascensionistCount = 0L,
        origin = origin, source = source, syncStatus = "local", createdByPubkey = owner,
        nostrEventId = eventId, kilterStatus = kilterStatus,
    )

    private class Calls {
        val drafts = mutableListOf<String>()
        val publications = mutableListOf<String>()
    }

    private fun model(
        loaded: ClimbWithStats?,
        calls: Calls,
        publication: (String) -> CommunityDeleteResult = { CommunityDeleteResult(DeleteOutcome.DONE, 3, 2, false, false) },
    ) = ClimbOwnerActionsModel(
        loadClimb = { loaded },
        currentPubkey = { me },
        deleteDraft = { calls.drafts += it },
        deletePublication = { uuid -> calls.publications += uuid; publication(uuid) },
        main = serial,
        io = serial,
    )

    private suspend fun ClimbOwnerActionsModel.await(check: (ClimbOwnerActionsState) -> Boolean): ClimbOwnerActionsState =
        withTimeout(CI_WAIT_MS) {
            while (!check(currentState)) delay(10)
            currentState
        }

    @Test
    fun `an own never-published climb deletes locally and closes the screen`() = runBlocking<Unit> {
        val calls = Calls()
        val actions = model(climb(), calls)
        actions.open("c1")
        val loaded = actions.await { it.isLoaded }
        assertTrue(loaded.isOwn)
        assertTrue(loaded.isUnpublishedDraft)
        assertTrue(loaded.canRemix)
        assertFalse(loaded.editLocked)

        actions.requestDelete()
        assertEquals("draft", actions.currentState.dialogCode)
        actions.confirmDelete()
        val done = actions.await { it.deleted }
        assertEquals("draftDeleted", done.resultCode)
        assertEquals("none", done.dialogCode)
        assertEquals(listOf("c1"), calls.drafts)
        assertTrue(calls.publications.isEmpty())
        actions.close()
    }

    @Test
    fun `a synced own climb is a publication and reports the relay counts`() = runBlocking<Unit> {
        val calls = Calls()
        // A fresh install's synced copy has no event id but source nostr: still published.
        val actions = model(climb(source = "nostr", eventId = null, kilterStatus = "synced"), calls) {
            CommunityDeleteResult(DeleteOutcome.DONE, 4, 3, true, false)
        }
        actions.open("c1")
        val loaded = actions.await { it.isLoaded }
        assertFalse(loaded.isUnpublishedDraft)
        assertTrue(loaded.editLocked)

        actions.requestDelete()
        val dialog = actions.currentState
        assertEquals("publication", dialog.dialogCode)
        assertTrue(dialog.dialogKilterWarning)
        actions.confirmDelete()
        val done = actions.await { it.resultCode != "none" }
        assertEquals("done", done.resultCode)
        assertEquals(3, done.resultAccepted)
        assertEquals(4, done.resultAttempted)
        assertTrue(done.resultKilterWarning)
        assertTrue(done.deleted)
        assertTrue(calls.drafts.isEmpty())
        actions.close()
    }

    @Test
    fun `refusals and failures keep the climb and say why`() = runBlocking<Unit> {
        val notOwner = model(climb(source = "nostr", eventId = "e1"), Calls()) {
            CommunityDeleteResult(DeleteOutcome.NOT_OWNER, 0, 0, false, false)
        }
        notOwner.open("c1")
        notOwner.await { it.isLoaded }
        notOwner.requestDelete()
        notOwner.confirmDelete()
        val refused = notOwner.await { it.resultCode != "none" }
        assertEquals("notOwner", refused.resultCode)
        assertFalse(refused.deleted)
        notOwner.close()

        val throwing = model(climb(source = "nostr", eventId = "e1"), Calls()) { error("relay down") }
        throwing.open("c1")
        throwing.await { it.isLoaded }
        throwing.requestDelete()
        throwing.confirmDelete()
        val failed = throwing.await { it.resultCode != "none" }
        assertEquals("failed", failed.resultCode)
        assertFalse(failed.deleted)
        assertFalse(failed.inProgress)
        throwing.close()
    }

    @Test
    fun `someone else's or a catalogue climb offers no delete`() = runBlocking<Unit> {
        for (other in listOf(climb(owner = "b".repeat(64)), climb(origin = "kilter"), climb(owner = null))) {
            val calls = Calls()
            val actions = model(other, calls)
            actions.open("c1")
            val loaded = actions.await { it.isLoaded }
            assertFalse(loaded.isOwn)
            assertTrue(loaded.canRemix)
            actions.requestDelete()
            assertEquals("none", actions.currentState.dialogCode)
            actions.confirmDelete()
            assertTrue(calls.drafts.isEmpty() && calls.publications.isEmpty())
            actions.close()
        }
    }
}
