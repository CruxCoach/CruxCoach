package com.cruxcoach.app.messages

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.nostr.KotlinNip44Cipher
import com.cruxcoach.app.nostr.Nip17
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.nostr.RelayClient
import com.cruxcoach.app.platform.WebSocketConnector
import com.cruxcoach.app.platform.WebSocketHandle
import com.cruxcoach.app.platform.WebSocketListener
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.ui.DevContactScreenModel
import com.cruxcoach.db.secure.SecureDatabase
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Android's bug report form (`BugReportScreen` → `DevContactViewModel.sendBugReport`), used by the
 * import error's "Report bug": the text the maintainer reads and the message that carries it.
 */
class BugReportTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        .also { SecureDatabase.Schema.create(it) }
    private val database = SecureDatabase(driver)
    private val userSecret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val devSecret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val devPubkey = NostrKeys.publicKeyHex(devSecret)!!
    private val executor = Executors.newSingleThreadExecutor()
    private val single = executor.asCoroutineDispatcher()

    @AfterTest
    fun tearDown() {
        executor.shutdownNow()
        driver.close()
    }

    @Test
    fun `the text follows Android's sections and leaves out empty steps`() {
        val withSteps = DevContactScreenModel.composeBugReport(
            " Import error ", "Import failed: invalid backup: bad\n", " pick the file ", "iOS 17.5 | mid-range | de", "0.2.4 (1000015)",
        )
        assertEquals(
            "Import error\n\nBeschreibung:\nImport failed: invalid backup: bad\n\nSchritte:\npick the file\n\n---\n" +
                "iOS 17.5 | mid-range | de\nApp 0.2.4 (1000015)",
            withSteps,
        )
        val noSteps = DevContactScreenModel.composeBugReport("T", "D", "  ", "iOS 18.0 | high-end | en", "0.2.4 (1)")
        assertEquals("T\n\nBeschreibung:\nD\n\n---\niOS 18.0 | high-end | en\nApp 0.2.4 (1)", noSteps)
    }

    @Test
    fun `the device line names only the memory tier`() {
        val gib = 1024L * 1024 * 1024
        assertEquals("iOS 17.5 | low-end | de", DevContactScreenModel.deviceLine("17.5", 3 * gib, "de"))
        assertEquals("iOS 17.5 | mid-range | de", DevContactScreenModel.deviceLine("17.5", 6 * gib, "de"))
        assertEquals("iOS 17.5 | high-end | en", DevContactScreenModel.deviceLine("17.5", 8 * gib, "en"))
    }

    @Test
    fun `the report reaches the maintainer as a bug report with its title as subject`() = runBlocking {
        val published = ArrayList<NostrEvent>()
        val relay = object : WebSocketConnector {
            override fun connect(url: String, maxFrameBytes: Int, listener: WebSocketListener): WebSocketHandle {
                val handle = object : WebSocketHandle {
                    override fun send(text: String) {
                        val frame = Json.parseToJsonElement(text) as JsonArray
                        if (frame[0].jsonPrimitive.content == "EVENT") {
                            val event = NostrEvent.fromJson(frame[1].jsonObject)!!
                            published += event
                            listener.onText("""["OK","${event.id}",true,""]""")
                        }
                    }

                    override fun close() = Unit
                }
                listener.onOpen()
                return handle
            }
        }
        val repository = DevContactRepository(
            database = database,
            relays = RelayClient(relay, JvmHashing, listOf("wss://relay.test")),
            hashing = JvmHashing,
            nip44 = KotlinNip44Cipher(JvmHashing),
            clock = FixedClock(1_790_000_000L),
            devPubkey = devPubkey,
            secretKeyProvider = { userSecret.copyOf() },
        )
        val model = withContext(single) {
            DevContactScreenModel(DevContactPresenter(repository, "en", CoroutineScope(SupervisorJob() + single), single))
        }
        withContext(single) {
            model.sendBugReport("Import error", "Import failed: IOException", "", "17.5", 6L * 1024 * 1024 * 1024, "de", "0.2.4 (7)")
        }
        val sent = withTimeout(CI_WAIT_MS) {
            var state = withContext(single) { model.currentState }
            while (state.lastSentId.isEmpty()) {
                delay(5)
                state = withContext(single) { model.currentState }
            }
            state
        }
        assertEquals("none", sent.errorCode)
        val toDev = published.first { it.firstTagValue("p") == devPubkey }
        val read = Nip17.unwrap(JvmHashing, KotlinNip44Cipher(JvmHashing), devSecret, toDev)!!
        assertEquals(
            "[BUG] Import error\n\nBeschreibung:\nImport failed: IOException\n\n---\niOS 17.5 | mid-range | de\nApp 0.2.4 (7)",
            read.content,
        )
        assertTrue(read.tags.any { it.getOrNull(0) == "subject" && it.getOrNull(1) == "Import error" })
        assertTrue(read.tags.any { it.getOrNull(1) == "bug-report" })
        withContext(single) { model.close() }
    }
}
