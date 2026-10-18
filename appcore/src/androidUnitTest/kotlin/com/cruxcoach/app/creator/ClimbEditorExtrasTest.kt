package com.cruxcoach.app.creator

import com.cruxcoach.app.backup.LocalEventSigner
import com.cruxcoach.app.browse.BrowsePreferences
import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.community.CommunityPublisher
import com.cruxcoach.app.community.testing.FakeCommunityRelay
import com.cruxcoach.app.logbook.awaitValue
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.community.ClimbEditorState
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The climb editor's Android extras on a real board database: the per-board autosave,
 * "Clear editor", the own-duplicate choice, the profile hint and the heatmap.
 *
 * One single-thread dispatcher backs the editor and the test's own repository calls
 * (see [ClimbEditorTest] for why).
 */
class ClimbEditorExtrasTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val serial = executor.asCoroutineDispatcher()
    private val database = BrowseTestDb()
    private val secret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val pubkey = NostrKeys.publicKeyHex(secret)!!
    private val relay = FakeCommunityRelay()

    @AfterTest
    fun tearDown() {
        runBlocking { withContext(serial) { database.close() } }
        serial.close()
        executor.shutdownNow()
    }

    private suspend fun <T> db(block: () -> T): T = withContext(serial) { block() }

    private fun store(sizeId: Int = 10) = MemoryKeyValueStore().also {
        it.putString(BrowsePreferences.BOARD_BRAND, "kilter")
        it.putString(BrowsePreferences.BOARD_LAYOUT_ID, "1")
        it.putString(BrowsePreferences.BOARD_PRODUCT_SIZE_ID, sizeId.toString())
        it.putString(BrowsePreferences.BOARD_ANGLE, "40")
    }

    private fun drafts() = ClimbDraftStore(database.boardRepo, JvmHashing, FixedClock(1_790_000_000L)) { pubkey }

    private fun editor(
        store: MemoryKeyValueStore,
        autosave: EditorAutosave? = null,
        hint: CreatorProfileHint? = null,
        publishing: Boolean = false,
    ): ClimbEditor {
        val publisher = if (publishing) {
            CommunityPublisher(
                boardRepository = database.boardRepo,
                relay = relay,
                signer = LocalEventSigner(JvmHashing) { secret.copyOf() },
                hashing = JvmHashing,
                clock = FixedClock(1_790_000_000L),
                pubkeyProvider = { pubkey },
            )
        } else {
            null
        }
        return ClimbEditor(
            boardRepository = database.boardRepo,
            drafts = drafts(),
            preferences = BrowsePreferences(store),
            publisher = publisher,
            scope = CoroutineScope(serial),
            ioDispatcher = serial,
        ).also {
            it.autosave = autosave
            it.profileHint = hint
        }
    }

    private suspend fun ClimbEditor.opened(editUuid: String? = null): ClimbEditor {
        open(editUuid = editUuid)
        awaitValue(false) { state.value.isLoading }
        // open() finishes its seeding in the same coroutine; wait until it ran.
        db { }
        return this
    }

    private fun ClimbEditor.paintValidClimb(name: String = "Testaufgabe") {
        toggleHold(1)
        toggleBrush(HoldRole.HAND); toggleHold(2)
        toggleBrush(HoldRole.FINISH); toggleHold(3)
        setName(name)
    }

    private fun autosaveOf(store: MemoryKeyValueStore, scope: String = "id-a") =
        EditorAutosave(store, { "$scope/$it" }, { 1_790_000_000_000L })

    @Test
    fun autosaveBringsTheDraftBackOnlyIntoAFreshEditorOfTheSameBoard() = runBlocking {
        val prefs = store()
        val first = editor(prefs, autosaveOf(prefs)).opened()
        first.toggleHold(5)
        first.setName("Halb fertig")
        awaitValue("5:${HoldRole.START}") { prefs.getString("id-a/editor_autosave_kilter_1_10_holds") }
        awaitValue("Halb fertig") { prefs.getString("id-a/editor_autosave_kilter_1_10_name") }
        first.close()

        val second = editor(prefs, autosaveOf(prefs)).opened()
        awaitValue(mapOf(5 to HoldRole.START)) { second.state.value.editor.selectedHolds }
        assertEquals("Halb fertig", second.state.value.editor.name)
        assertEquals("kilter", second.state.value.editor.boardBrand, "the board load keeps the brand")
        second.close()

        // Another identity on this phone has its own slot.
        val otherIdentity = editor(prefs, autosaveOf(prefs, scope = "id-b")).opened()
        assertEquals(emptyMap(), otherIdentity.state.value.editor.selectedHolds)
        otherIdentity.close()

        // Another size of the same layout is another wall.
        val otherBoard = store(sizeId = 11)
        prefs.keys().filter { it.startsWith("id-a/") }.forEach { otherBoard.putString(it, prefs.getString(it)) }
        val bigger = editor(otherBoard, autosaveOf(otherBoard)).opened()
        assertEquals(emptyMap(), bigger.state.value.editor.selectedHolds)
        bigger.close()

        // An edit that found nothing to seed must not turn into yesterday's draft.
        val edit = editor(prefs, autosaveOf(prefs)).opened(editUuid = "missing-climb")
        assertEquals(emptyMap(), edit.state.value.editor.selectedHolds)
        edit.close()
    }

    @Test
    fun savingAndClearingEmptyTheSlotAndClearKeepsTheAngle() = runBlocking {
        val prefs = store()
        val editor = editor(prefs, autosaveOf(prefs)).opened()
        val slot = "id-a/editor_autosave_kilter_1_10_holds"
        editor.paintValidClimb()
        awaitValue(true) { prefs.getString(slot) != null }

        editor.saveAsDraft()
        awaitValue(true) { editor.state.value.savedDraftUuid != null }
        assertNull(prefs.getString(slot), "a saved draft lives in the drafts drawer, not in the slot")

        editor.setAngle(45)
        editor.toggleBrush(HoldRole.FOOT)
        editor.toggleHold(8)
        awaitValue(true) { prefs.getString(slot) != null }

        editor.clearEditor()
        assertEquals(emptyMap(), editor.state.value.editor.selectedHolds)
        assertEquals("", editor.state.value.editor.name)
        assertEquals(45, editor.state.value.editor.angle, "Clear editor wipes the climb, not the wall's angle")
        assertNull(editor.state.value.loadedDraftUuid)
        // Past the debounce: nothing writes the cleared state back.
        delay(ClimbEditor.AUTOSAVE_DEBOUNCE_MS + 200)
        assertNull(prefs.getString(slot))

        editor.undo()
        assertEquals(4, editor.state.value.editor.selectedHolds.size, "one Undo brings the wiped climb back")
        editor.close()
    }

    @Test
    fun autosaveSlotEncodesHoldsAsAndroidAndSkipsDamagedPairs() {
        assertEquals("1:12;20:15", EditorAutosave.encodeHolds(linkedMapOf(1 to 12, 20 to 15)))
        assertEquals(mapOf(1 to 12, 20 to 15), EditorAutosave.decodeHolds("1:12;x:3;20:15;7;8:"))
        assertEquals(emptyMap(), EditorAutosave.decodeHolds(""))

        val prefs = MemoryKeyValueStore()
        val autosave = autosaveOf(prefs)
        autosave.save("kilter_1_10", ClimbEditorState())
        assertNull(autosave.load("kilter_1_10"), "an empty editor leaves no slot behind")

        autosave.save("kilter_1_10", ClimbEditorState(selectedHolds = mapOf(3 to 14), name = "A", angle = 35, setterGradeId = null))
        val loaded = assertNotNull(autosave.load("kilter_1_10"))
        assertEquals(mapOf(3 to 14), loaded.selectedHolds)
        assertEquals(35, loaded.angle)
        assertNull(loaded.setterGradeId)
        assertEquals("1790000000000", prefs.getString("id-a/editor_autosave_kilter_1_10_saved_at"))

        autosave.clear("kilter_1_10")
        assertNull(autosave.load("kilter_1_10"))
        assertTrue(prefs.keys().none { prefs.getString(it) != null && it.contains("editor_autosave") })
    }

    @Test
    fun anOwnDuplicateCanBeUpdatedInsteadOfPublishedAgain() = runBlocking {
        val existing = ClimbEditorState(
            selectedHolds = mapOf(1 to HoldRole.START, 2 to HoldRole.HAND, 3 to HoldRole.FINISH),
            name = "Original",
            angle = 40,
        )
        val existingUuid = assertNotNull(db { drafts().saveDraft(existing, layoutId = 1L) })

        val editor = editor(store(), publishing = true).opened()
        editor.paintValidClimb(name = "Besserer Name")
        editor.publish()
        awaitValue(true) { editor.state.value.pendingDuplicateConfirm }
        assertTrue(editor.state.value.duplicateIsOwn, "the user's own climb: Update existing is offered")
        assertEquals(existingUuid, editor.state.value.duplicateOf?.uuid)
        assertEquals(PublishPhase.PUBLISHING, editor.state.value.publishPhase)

        editor.updateExistingOnDuplicate()
        // A second tap on the closing dialog must not publish again.
        editor.confirmPublishWithDuplicate()
        awaitValue(PublishPhase.PUBLISHED) { editor.state.value.publishPhase }
        assertEquals(existingUuid, editor.state.value.loadedDraftUuid)
        assertTrue(editor.state.value.isEditingExisting)
        val own = db { database.boardRepo.getMyClimbs(pubkey) }
        assertEquals(1, own.size, "an update, not a second climb with the same holds")
        assertEquals("Besserer Name", own.single().name)
        assertEquals(existingUuid, own.single().uuid)
        assertEquals(1, relay.published.count { it.kind != 1 }, "one climb event went out")
        editor.close()
    }

    @Test
    fun aDuplicateOfSomeoneElseOffersNoUpdate() = runBlocking {
        val otherUser = ClimbDraftStore(database.boardRepo, JvmHashing, FixedClock(1_790_000_000L)) { "bb".repeat(32) }
        val theirs = ClimbEditorState(
            selectedHolds = mapOf(1 to HoldRole.START, 2 to HoldRole.HAND, 3 to HoldRole.FINISH),
            name = "Fremd",
            angle = 40,
        )
        assertNotNull(db { otherUser.saveDraft(theirs, layoutId = 1L) })

        val editor = editor(store(), publishing = true).opened()
        editor.paintValidClimb()
        editor.publish()
        awaitValue(true) { editor.state.value.pendingDuplicateConfirm }
        assertFalse(editor.state.value.duplicateIsOwn)
        editor.updateExistingOnDuplicate()
        assertTrue(editor.state.value.pendingDuplicateConfirm, "nothing to update on someone else's climb")
        assertNull(editor.state.value.loadedDraftUuid)
        editor.cancelPublish()
        assertEquals(PublishPhase.IDLE, editor.state.value.publishPhase)
        assertFalse(editor.state.value.duplicateIsOwn)
        assertTrue(relay.published.isEmpty())
        editor.close()
    }

    @Test
    fun theProfileHintPausesTheFirstPublishUntilAnswered() = runBlocking {
        var dismissed = false
        var displayName: String? = null
        val hint = CreatorProfileHint(
            dismissed = { dismissed },
            setDismissed = { dismissed = true },
            ownDisplayName = { displayName },
            hasIdentity = { true },
        )
        val editor = editor(store(), hint = hint, publishing = true).opened()
        editor.paintValidClimb()
        editor.publish()
        awaitValue(true) { editor.state.value.pendingProfileHint }
        assertEquals(PublishPhase.PUBLISHING, editor.state.value.publishPhase)
        assertTrue(relay.published.isEmpty(), "nothing goes out while the hint waits")

        // "Set up profile": no publish, the profile editor opens, the hint is answered.
        editor.acceptProfileHint()
        awaitValue(true) { dismissed }
        assertTrue(editor.state.value.profileSetupRequested)
        assertFalse(editor.state.value.pendingProfileHint)
        assertEquals(PublishPhase.IDLE, editor.state.value.publishPhase)
        assertTrue(relay.published.isEmpty())
        editor.acknowledgeProfileSetupNavigated()
        assertFalse(editor.state.value.profileSetupRequested)

        // Answered once: the next publish goes straight out.
        editor.publish()
        awaitValue(PublishPhase.PUBLISHED) { editor.state.value.publishPhase }
        assertFalse(editor.state.value.pendingProfileHint)
        editor.close()

        // "Skip for now" publishes and answers the hint too.
        dismissed = false
        val second = editor(store(), hint = hint, publishing = true).opened()
        second.paintValidClimb(name = "Zweite")
        second.toggleBrush(HoldRole.FOOT)
        second.toggleHold(9)
        second.publish()
        awaitValue(true) { second.state.value.pendingProfileHint }
        second.dismissProfileHintAndPublish()
        awaitValue(PublishPhase.PUBLISHED) { second.state.value.publishPhase }
        assertTrue(dismissed)
        second.close()

        // A profile with a name never asks.
        dismissed = false
        displayName = "Alex"
        assertFalse(hint.shouldShow())
        displayName = "  "
        assertTrue(hint.shouldShow())
        assertFalse(CreatorProfileHint({ false }, {}, { null }, { false }).shouldShow(), "no identity, nothing to set up")
    }

    @Test
    fun theHeatmapSuggestsHoldsOfClimbsSharingTheSeedForTheActiveBrush() = runBlocking {
        db {
            database.climb(uuid = "a", frames = "p1r12p2r13p3r14")
            database.climb(uuid = "b", frames = "p1r12p4r13p5r14")
            database.climb(uuid = "c", frames = "p6r12p7r14")
        }
        val editor = editor(store()).opened()
        editor.toggleHold(1)
        editor.toggleBrush(HoldRole.HAND)
        editor.toggleHeatmap()
        assertTrue(editor.state.value.heatmapEnabled)
        // Hand brush: hand holds of the climbs that contain hold 1.
        awaitValue(mapOf(2 to 1f, 4 to 1f)) { editor.state.value.heatmap }

        // No brush: every hold of those climbs, the seed itself left out.
        editor.toggleBrush(HoldRole.HAND)
        awaitValue(mapOf(2 to 1f, 3 to 1f, 4 to 1f, 5 to 1f)) { editor.state.value.heatmap }

        // A new seed narrows the climbs to the one that has both holds; the finish brush
        // then asks for its finish holds only.
        editor.toggleBrush(HoldRole.FINISH)
        editor.toggleHold(4)
        awaitValue(mapOf(5 to 1f)) { editor.state.value.heatmap }

        // A draft loaded while the overlay is on reseeds it (the loaded editor holds the start brush).
        val draft = ClimbEditorState(selectedHolds = mapOf(7 to HoldRole.FINISH), name = "Andere", angle = 40)
        val draftUuid = assertNotNull(db { drafts().saveDraft(draft, layoutId = 1L) })
        editor.loadDraft(draftUuid)
        awaitValue(mapOf(6 to 1f)) { editor.state.value.heatmap }

        editor.toggleHeatmap()
        assertFalse(editor.state.value.heatmapEnabled)
        editor.close()
    }
}
