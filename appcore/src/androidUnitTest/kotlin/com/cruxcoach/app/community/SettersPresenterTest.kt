package com.cruxcoach.app.community

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.browse.BrowsePreferences
import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.logbook.SerialDispatcher
import com.cruxcoach.app.nostr.RelayClient
import com.cruxcoach.app.platform.WebSocketConnector
import com.cruxcoach.app.platform.WebSocketHandle
import com.cruxcoach.app.platform.WebSocketListener
import com.cruxcoach.app.profile.NostrProfileData
import com.cruxcoach.app.profile.NostrProfileStore
import com.cruxcoach.app.profile.ProfileLookup
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.ui.SetterDetailScreenModel
import com.cruxcoach.db.secure.SecureDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Real catalogue and real profile cache; the relay socket never answers. */
class SettersPresenterTest {
    private val serialOwner = SerialDispatcher()
    private val serial = serialOwner.dispatcher
    private val db = BrowseTestDb()
    private val secureDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        .also { SecureDatabase.Schema.create(it) }
    private val profileStore = NostrProfileStore(SecureDatabase(secureDriver), FixedClock(1_790_000_000L))
    private val store = MemoryKeyValueStore().apply {
        values[BrowsePreferences.BOARD_BRAND] = "kilter"
        values[BrowsePreferences.BOARD_LAYOUT_ID] = "1"
        values[BrowsePreferences.BOARD_PRODUCT_SIZE_ID] = "0"
        values[BrowsePreferences.BOARD_ANGLE] = "40"
    }

    /** A socket that opens and then says nothing; every query times out. */
    private object SilentSockets : WebSocketConnector {
        override fun connect(url: String, maxFrameBytes: Int, listener: WebSocketListener): WebSocketHandle? = null
    }

    private val lookup = ProfileLookup(
        profileStore,
        RelayClient(SilentSockets, JvmHashing, listOf("wss://relay.test")),
    )

    @AfterTest
    fun tearDown() = runBlocking {
        withContext(serial) { db.close(); secureDriver.close() }
    }

    private suspend fun <T> await(read: () -> T, predicate: (T) -> Boolean): T =
        withTimeout(CI_WAIT_MS) {
            var seen = read()
            while (!predicate(seen)) {
                delay(5)
                seen = read()
            }
            seen
        }

    @Test
    fun `setters are listed with the climbs they set on this board`() = runBlocking {
        withContext(serial) {
            db.climb(uuid = "a1", name = "Arete", provenance = "cruxcoach", pubkey = "pk-one")
            db.climb(uuid = "a2", name = "Crimp", provenance = "cruxcoach", pubkey = "pk-one")
            db.climb(uuid = "b1", name = "Sloper", provenance = "cruxcoach", pubkey = "pk-two")
        }
        val presenter = SettersListPresenter(
            db.boardRepo, BrowsePreferences(store), CoroutineScope(serial), serial,
        )
        val state = await({ presenter.state.value }) { !it.isLoading }
        assertEquals(2, state.setters.size)
        // Most problems first, as the screen shows them.
        assertEquals("pk-one", state.setters.first().pubkey)
        assertEquals(2L, state.setters.first().climbCount)
        presenter.close()
    }

    @Test
    fun `a setter page shows their climbs even when no relay answers`() = runBlocking {
        withContext(serial) {
            db.climb(uuid = "a1", name = "Arete", provenance = "cruxcoach", pubkey = "pk-one")
            profileStore.cacheIfNewer(
                NostrProfileData("pk-one", displayName = "Robin", about = "sets crimps"),
                eventCreatedAt = 1_789_000_000L,
                localPrimary = false,
            )
        }
        val presenter = SetterDetailPresenter(
            db.boardRepo, BrowsePreferences(store), lookup, "pk-one",
            CoroutineScope(serial), serial,
        )
        val model = SetterDetailScreenModel(presenter, "FRENCH")
        val ui = await({ model.currentState }) { !it.isLoading && it.climbs.isNotEmpty() }
        assertEquals("Arete", ui.climbs.single().name)
        assertEquals(40, ui.climbs.single().angle)
        assertTrue(ui.climbs.single().grade.isNotEmpty(), "the row needs a readable grade")
        // The cached profile is painted without waiting for the relay.
        assertEquals("Robin", await({ model.currentState }) { it.displayName == "Robin" }.displayName)
        model.close()
    }

    @Test
    fun `an unknown setter falls back to a readable stub`() = runBlocking {
        val presenter = SetterDetailPresenter(
            db.boardRepo, BrowsePreferences(store), lookup, "ff".repeat(32),
            CoroutineScope(serial), serial,
        )
        val state = await({ presenter.state.value }) { !it.isLoading }
        assertTrue(state.displayName.startsWith("npub:"), "got ${state.displayName}")
        assertTrue(state.climbs.isEmpty())
        presenter.close()
    }
}
