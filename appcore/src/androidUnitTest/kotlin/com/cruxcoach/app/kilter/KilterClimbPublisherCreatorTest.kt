package com.cruxcoach.app.kilter

import com.cruxcoach.app.backup.LocalEventSigner
import com.cruxcoach.app.browse.BrowsePreferences
import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.community.CommunityPublisher
import com.cruxcoach.app.community.testing.FakeCommunityRelay
import com.cruxcoach.app.creator.ClimbDraftStore
import com.cruxcoach.app.creator.ClimbEditor
import com.cruxcoach.app.creator.PublishPhase
import com.cruxcoach.app.logbook.awaitValue
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.ui.CreatorScreenModel
import com.cruxcoach.app.ui.GradeFormatter
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.community.ClimbEditorState
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor's Kilter step and auto-note, end to end: real editor, real Nostr
 * publisher (signed, relay double), real board DB and the Kilter client over a
 * recording portal stand-in. One single-thread dispatcher carries the editor,
 * its IO and the test's own DB reads (one JDBC connection).
 */
class KilterClimbPublisherCreatorTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val serial = executor.asCoroutineDispatcher()
    private val database = BrowseTestDb()
    private val secret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val pubkey = NostrKeys.publicKeyHex(secret)!!
    private val relay = FakeCommunityRelay()
    private val session = KilterTestSession()
    private var publishEnabled = true

    @AfterTest
    fun tearDown() {
        runBlocking { withContext(serial) { database.close() } }
        serial.close()
        executor.shutdownNow()
    }

    private suspend fun <T> db(block: () -> T): T = withContext(serial) { block() }

    private fun editor(): ClimbEditor {
        val store = MemoryKeyValueStore()
        store.putString(BrowsePreferences.BOARD_BRAND, "kilter")
        store.putString(BrowsePreferences.BOARD_LAYOUT_ID, "1")
        store.putString(BrowsePreferences.BOARD_PRODUCT_SIZE_ID, "10")
        store.putString(BrowsePreferences.BOARD_ANGLE, "40")
        val clock = FixedClock(1_790_000_000L)
        val publisher = CommunityPublisher(
            boardRepository = database.boardRepo,
            relay = relay,
            signer = LocalEventSigner(JvmHashing) { secret.copyOf() },
            hashing = JvmHashing,
            clock = clock,
            pubkeyProvider = { pubkey },
        )
        val editor = ClimbEditor(
            boardRepository = database.boardRepo,
            drafts = ClimbDraftStore(database.boardRepo, JvmHashing, clock) { pubkey },
            preferences = BrowsePreferences(store),
            publisher = publisher,
            scope = CoroutineScope(serial),
            ioDispatcher = serial,
        )
        val kilter = KilterClimbPublisher(
            session.api, session.account, database.boardRepo, { publishEnabled }, { session.clock.epochMillis() },
        )
        editor.kilterLeg = KilterClimbCreatorLeg(kilter, session.account, database.boardRepo) { publishEnabled }
        return editor
    }

    private suspend fun ClimbEditor.readyToPublish(): ClimbEditor {
        db {
            database.boardRepo.upsertProductSize(10L, 1L, "12x12", 0L, 144L, 0L, 156L, null)
            database.boardRepo.upsertPlacement(1L, 901L, 1L, 10L, 10L)
            database.boardRepo.upsertPlacement(2L, 902L, 1L, 20L, 20L)
            database.boardRepo.upsertPlacement(3L, 903L, 1L, 30L, 30L)
        }
        open()
        awaitValue(false) { state.value.isLoading }
        toggleHold(1)
        toggleBrush(HoldRole.HAND); toggleHold(2)
        toggleBrush(HoldRole.FINISH); toggleHold(3)
        setName("Kantenspiel")
        awaitValue(true) { state.value.validationIssues.isEmpty() }
        return this
    }

    private fun portalAccepts() {
        session.http.responder = { req ->
            when {
                req.url.contains("/users/") -> portalOk("""{"username":"wallsetter"}""")
                "/climbs/" in req.url -> portalOk("{}")
                else -> null
            }
        }
    }

    private suspend fun ClimbEditor.published(note: String = ""): ClimbEditor {
        publish(note)
        awaitValue(true) { state.value.publishPhase == PublishPhase.PUBLISHED || state.value.publishPhase == PublishPhase.FAILED }
        return this
    }

    @Test
    fun `a published Kilter climb also goes to the Kilter account`() = runBlocking {
        session.signIn()
        portalAccepts()
        val editor = editor().readyToPublish().published()

        val state = editor.state.value
        assertEquals(PublishPhase.PUBLISHED, state.publishPhase)
        assertEquals("synced", state.kilterOutcome?.code)
        assertFalse(state.kilterConnectNudge)
        val request = session.http.climbRequests().single()
        assertTrue(request.url.endsWith("/climbs/create-climb/transaction"))
        // Placements 1..3 go out as their holes, in the editor's own uuid.
        assertTrue(request.body.contains("\"climbConcat\":\"h901p"), request.body)
        val uuid = state.loadedDraftUuid!!
        assertTrue(request.body.contains("\"climbUuid\":\"$uuid\""))
        assertEquals("synced", db { database.boardRepo.getKilterPublishState(uuid)?.status })
        assertEquals(1, relay.ofKind(30078).size)
    }

    @Test
    fun `with Kilter publishing off the climb stays community only`() = runBlocking {
        session.signIn()
        portalAccepts()
        publishEnabled = false
        val editor = editor().readyToPublish().published()
        assertNull(editor.state.value.kilterOutcome)
        assertFalse(editor.state.value.kilterConnectNudge)
        assertTrue(session.http.requests.isEmpty())
    }

    @Test
    fun `publishing on without a login nudges to connect`() = runBlocking {
        val editor = editor().readyToPublish().published()
        assertEquals("skipped", editor.state.value.kilterOutcome?.code)
        assertTrue(editor.state.value.kilterConnectNudge)
        assertTrue(session.http.requests.isEmpty())
    }

    @Test
    fun `no relay accepting the climb skips Kilter`() = runBlocking {
        session.signIn()
        portalAccepts()
        relay.acceptCount = 0
        val editor = editor().readyToPublish().published()
        assertEquals(PublishPhase.FAILED, editor.state.value.publishPhase)
        assertNull(editor.state.value.kilterOutcome)
        assertTrue(session.http.climbRequests().isEmpty())
    }

    @Test
    fun `a Kilter failure is reported and the climb stays published`() = runBlocking {
        session.signIn()
        session.http.responder = { req ->
            when {
                req.url.contains("/users/") -> portalOk("""{"username":"wallsetter"}""")
                "/climbs/" in req.url -> portalOk("bad hold", 422)
                else -> null
            }
        }
        val model = CreatorScreenModel(editor().readyToPublish().published(), GradeFormatter(false), serial)
        val ui = model.currentState
        assertEquals("published", ui.publishPhase)
        assertEquals("failed", ui.kilterOutcomeCode)
        assertEquals("kilter_publish_rejected_with_code", ui.kilterOutcomeMessageKey)
        assertEquals(422, ui.kilterOutcomeHttpCode)
        assertTrue(ui.kilterPublishAvailable)
        model.consumeKilterOutcome()
        assertEquals("none", model.currentState.kilterOutcomeCode)
    }

    @Test
    fun `the note toggle seeds the template once and keeps edited text`() = runBlocking {
        val editor = editor()
        editor.setAlsoPostNote(true, "template {name}")
        assertEquals("template {name}", editor.state.value.autoNoteText)
        editor.setAutoNoteText("my own words {naddr}")
        editor.setAlsoPostNote(false, "template {name}")
        editor.setAlsoPostNote(true, "template {name}")
        assertTrue(editor.state.value.alsoPostNote)
        assertEquals("my own words {naddr}", editor.state.value.autoNoteText)
    }

    @Test
    fun `the profile default switches the note on when the editor opens`() = runBlocking {
        val model = CreatorScreenModel(editor(), GradeFormatter(false), serial)
        assertFalse(model.currentState.alsoPostNote)
        model.attachKilter(null, autoNoteDefault = true)
        assertTrue(model.currentState.alsoPostNote)
        assertFalse(model.currentState.kilterPublishAvailable)
    }

    @Test
    fun `a note text posts a public note next to the climb`() = runBlocking {
        val editor = editor().readyToPublish()
        val model = CreatorScreenModel(editor, GradeFormatter(false), serial)
        model.publish("Neu: {name} nostr:{npub_cruxcoach}")
        awaitValue(true) { editor.state.value.publishPhase == PublishPhase.PUBLISHED }
        val note = relay.ofKind(1).single()
        assertTrue(note.content.startsWith("Neu: Kantenspiel nostr:npub1"), note.content)
        // Android's official build mentions the project key it names in the note.
        assertTrue(note.tags.any { it == listOf("p", com.cruxcoach.app.AppConfig.MAINTAINER_PUBKEY) })
        assertFalse(model.currentState.autoNoteZeroRelays)
    }

    @Test
    fun `a blank note text posts no note`() = runBlocking {
        val editor = editor().readyToPublish()
        val model = CreatorScreenModel(editor, GradeFormatter(false), serial)
        model.publish("")
        awaitValue(true) { editor.state.value.publishPhase == PublishPhase.PUBLISHED }
        assertTrue(relay.ofKind(1).isEmpty())
        assertFalse(model.currentState.autoNoteZeroRelays)
    }

    @Test
    fun `boards other than Kilter never reach the Kilter account`() = runBlocking {
        session.signIn()
        portalAccepts()
        val kilter = KilterClimbPublisher(session.api, session.account, database.boardRepo, { true }, { 0L })
        val leg = KilterClimbCreatorLeg(kilter, session.account, database.boardRepo) { true }
        val state = ClimbEditorState(selectedHolds = mapOf(1 to 12), name = "x", angle = 40, boardBrand = "tension")
        for (brand in listOf(BoardBrand.TENSION, BoardBrand.MOONBOARD)) {
            val result = leg.afterNostrPublish("u1", 9L, brand, state, null, isEdit = false)
            assertNull(result.outcome)
        }
        assertTrue(session.http.requests.isEmpty())
    }
}
