package com.cruxcoach.app.profile

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.backup.LocalEventSigner
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.logbook.SerialDispatcher
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrEvents
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.nostr.RelayClient
import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.HttpResponse
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.WebSocketConnector
import com.cruxcoach.app.platform.WebSocketHandle
import com.cruxcoach.app.platform.WebSocketListener
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.db.secure.SecureDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The profile is signed with a real secp256k1 key and stored in a real
 * SecureDatabase; only the relay socket and HTTP are doubles, so what the
 * tests inspect is the kind-0 event a relay would actually receive.
 */
class ProfilePresenterTest {
    private val serialOwner = SerialDispatcher()
    private val serial = serialOwner.dispatcher
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        .also { SecureDatabase.Schema.create(it) }
    private val database = SecureDatabase(driver)
    private val clock = FixedClock(1_790_000_000L)
    private val store = NostrProfileStore(database, clock)
    private val secret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val pubkey = NostrKeys.publicKeyHex(secret)!!
    private val signer = LocalEventSigner(JvmHashing) { secret.copyOf() }

    @AfterTest
    fun tearDown() = runBlocking { withContext(serial) { driver.close() } }

    /** Accepts or refuses every published event, and can serve a kind-0. */
    private class FakeSockets(
        var accept: Boolean = true,
        var served: String? = null,
    ) : WebSocketConnector {
        val published = ArrayList<String>()
        override fun connect(url: String, maxFrameBytes: Int, listener: WebSocketListener): WebSocketHandle? {
            val handle = object : WebSocketHandle {
                override fun send(text: String) {
                    when {
                        text.startsWith("[\"EVENT\"") -> {
                            published += text
                            val id = Json.parseToJsonElement(text).let { root ->
                                root.jsonArray()[1].jsonObject["id"]!!.jsonPrimitive.content
                            }
                            listener.onText("""["OK","$id",$accept,""]""")
                        }
                        text.startsWith("[\"REQ\"") -> {
                            val sub = Json.parseToJsonElement(text).jsonArray()[1].jsonPrimitive.content
                            served?.let { listener.onText("""["EVENT","$sub",$it]""") }
                            listener.onText("""["EOSE","$sub"]""")
                        }
                    }
                }

                override fun close() = Unit
            }
            listener.onOpen()
            return handle
        }

        private fun kotlinx.serialization.json.JsonElement.jsonArray() =
            this as kotlinx.serialization.json.JsonArray
    }

    private class FakeHttp(var body: String?, var status: Int = 200) : HttpTransport {
        val urls = ArrayList<String>()
        override suspend fun request(
            method: String, url: String, headers: Map<String, String>, body: ByteArray?,
            maxResponseBytes: Long, timeoutSeconds: Int,
        ): HttpResult {
            urls += url
            val text = this.body ?: return HttpResult.Failed(HttpFailure.OFFLINE, "scripted")
            return HttpResult.Ok(HttpResponse(status, emptyMap(), text.encodeToByteArray()))
        }

        override suspend fun download(
            url: String, destinationPath: String, maxBytes: Long, timeoutSeconds: Int,
            onProgress: (Long, Long) -> Unit,
        ): HttpResult = error("not used")
    }

    private fun presenter(
        sockets: FakeSockets = FakeSockets(),
        http: HttpTransport = FakeHttp(null),
        identity: String = pubkey,
        blossom: com.cruxcoach.app.backup.BlossomClient? = null,
    ) = ProfilePresenter(
        store = store,
        blossom = blossom,
        relays = RelayClient(sockets, JvmHashing, listOf("wss://relay.test")),
        signer = signer,
        http = http,
        hashing = JvmHashing,
        clock = clock,
        pubkeyHex = { identity },
        npubOf = { "npub-$it" },
        scope = CoroutineScope(serial),
        ioDispatcher = serial,
    )

    private suspend fun await(
        presenter: ProfilePresenter,
        predicate: (ProfileState) -> Boolean,
    ): ProfileState = withTimeout(CI_WAIT_MS) {
        var seen = presenter.state.value
        while (!predicate(seen)) {
            delay(5)
            seen = presenter.state.value
        }
        seen
    }

    @Test
    fun `publishing signs a kind 0 event a relay can verify`() = runBlocking {
        val sockets = FakeSockets()
        val presenter = presenter(sockets)
        presenter.load()
        await(presenter) { !it.isLoading }

        presenter.setDisplayName("Alex")
        presenter.setAbout("Boards and coffee")
        presenter.setLightningAddress("alex@example.com")
        presenter.publish()

        val done = await(presenter) { !it.isPublishing && (it.publishedTo > 0 || it.errorCode() != "none") }
        assertEquals("none", done.errorCode(), "publish failed: ${done.error}")
        assertEquals(1, done.publishedTo)
        assertFalse(done.isDirty)

        val frame = sockets.published.single()
        val event = Json.parseToJsonElement(frame).let {
            (it as kotlinx.serialization.json.JsonArray)[1].jsonObject
        }
        assertEquals(0, event["kind"]!!.jsonPrimitive.content.toInt())
        assertEquals(pubkey, event["pubkey"]!!.jsonPrimitive.content)
        val content = Json.parseToJsonElement(event["content"]!!.jsonPrimitive.content).jsonObject
        assertEquals("Alex", content["name"]!!.jsonPrimitive.content)
        assertEquals("alex@example.com", content["lud16"]!!.jsonPrimitive.content)

        // And it is cached locally, so the screen survives a restart.
        val cached = withContext(serial) { store.cached(pubkey) }
        assertEquals("Alex", cached?.displayName)
        presenter.close()
    }

    @Test
    fun `a publish no relay stored is not written to the local cache`() = runBlocking {
        val presenter = presenter(FakeSockets(accept = false))
        presenter.load()
        await(presenter) { !it.isLoading }
        presenter.setDisplayName("Ghost")
        presenter.publish()

        val done = await(presenter) { !it.isPublishing && it.errorCode() != "none" }
        assertEquals("noRelayAccepted", done.errorCode())
        assertEquals(null, withContext(serial) { store.cached(pubkey) })
        presenter.close()
    }

    @Test
    fun `a relay copy never overwrites what this device published`() = runBlocking {
        val mine = NostrProfileData(pubkey, displayName = "Mine")
        withContext(serial) { store.saveLocal(mine) }

        val stale = signer.sign(
            clock.epochSeconds() - 10, 0, emptyList(),
            """{"name":"Stale"}""",
        )!!
        val presenter = presenter(FakeSockets(served = Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), stale.toJson())))
        presenter.load()
        val loaded = await(presenter) { !it.isLoading }
        assertEquals("Mine", loaded.displayName)
        // Give a refresh that must not happen a chance to run.
        delay(50)
        assertEquals("Mine", withContext(serial) { store.cached(pubkey)?.displayName })
        presenter.close()
    }

    @Test
    fun `a nip05 identifier is only a match when the domain lists this key`() = runBlocking {
        val http = FakeHttp("""{"names":{"alex":"$pubkey"}}""")
        val presenter = presenter(http = http)
        presenter.load()
        await(presenter) { !it.isLoading }

        presenter.setNip05("alex@example.com")
        presenter.verifyNip05()
        val ok = await(presenter) {
            it.nip05Status != Nip05Status.UNKNOWN && it.nip05Status != Nip05Status.CHECKING
        }
        assertEquals(Nip05Status.MATCHES, ok.nip05Status)
        assertTrue(http.urls.single().startsWith("https://example.com/.well-known/nostr.json?name=alex"))

        http.body = """{"names":{"alex":"00ff"}}"""
        presenter.setNip05("alex@example.com")
        presenter.verifyNip05()
        assertEquals(
            Nip05Status.MISMATCH,
            await(presenter) {
                it.nip05Status == Nip05Status.MISMATCH || it.nip05Status == Nip05Status.MATCHES
            }.nip05Status,
        )

        http.body = null
        presenter.setNip05("alex@example.com")
        presenter.verifyNip05()
        assertEquals(
            Nip05Status.UNREACHABLE,
            await(presenter) {
                it.nip05Status == Nip05Status.UNREACHABLE || it.nip05Status == Nip05Status.MATCHES
            }.nip05Status,
        )
        presenter.close()
    }

    @Test
    fun `an uploaded picture becomes the profile's picture url`() = runBlocking {
        // A Blossom server that accepts anything and reports the hash back.
        val uploads = ArrayList<Pair<String, Int>>()
        val transport = object : HttpTransport {
            override suspend fun request(
                method: String, url: String, headers: Map<String, String>, body: ByteArray?,
                maxResponseBytes: Long, timeoutSeconds: Int,
            ): HttpResult {
                uploads += url to (body?.size ?: 0)
                assertEquals("image/jpeg", headers["Content-Type"])
                return HttpResult.Ok(HttpResponse(200, emptyMap(), ByteArray(0)))
            }

            override suspend fun download(
                url: String, destinationPath: String, maxBytes: Long, timeoutSeconds: Int,
                onProgress: (Long, Long) -> Unit,
            ): HttpResult = error("not used")
        }
        val client = com.cruxcoach.app.backup.BlossomClient(
            transport, JvmHashing, clock,
            com.cruxcoach.app.backup.LocalEventSigner(JvmHashing) { secret.copyOf() },
        )
        val presenter = presenter(blossom = client)
        presenter.load()
        await(presenter) { !it.isLoading }

        presenter.uploadImage(byteArrayOf(1, 2, 3), asBanner = false)
        val done = await(presenter) { it.pictureUrl.isNotEmpty() || it.error != ProfileError.NONE }
        assertEquals(ProfileError.NONE, done.error)
        assertTrue(done.pictureUrl.startsWith("https://"), "got ${done.pictureUrl}")
        assertTrue(done.isDirty, "an uploaded picture is an unsaved edit")
        assertTrue(uploads.first().first.endsWith("/upload"))
        presenter.close()
    }

    @Test
    fun `without an upload server the screen says so instead of silently doing nothing`() = runBlocking {
        val presenter = presenter()
        presenter.load()
        await(presenter) { !it.isLoading }
        presenter.uploadImage(byteArrayOf(1), asBanner = false)
        assertEquals(ProfileError.UPLOAD_FAILED, presenter.state.value.error)
        presenter.close()
    }

    @Test
    fun `a picture that is not https is refused before anything is signed`() = runBlocking {
        val sockets = FakeSockets()
        val presenter = presenter(sockets)
        presenter.load()
        await(presenter) { !it.isLoading }
        presenter.setPictureUrl("http://example.com/me.jpg")
        presenter.publish()

        val done = await(presenter) { it.errorCode() != "none" }
        assertEquals("invalidUrl", done.errorCode())
        assertTrue(sockets.published.isEmpty(), "nothing may be signed or sent")
        presenter.close()
    }
}

/** The presenter's own error enum, as the facade codes it. */
private fun ProfileState.errorCode(): String = when (error) {
    ProfileError.NONE -> "none"
    ProfileError.LOAD_FAILED -> "loadFailed"
    ProfileError.NO_IDENTITY -> "noIdentity"
    ProfileError.SIGNING_FAILED -> "signingFailed"
    ProfileError.NO_RELAY_ACCEPTED -> "noRelayAccepted"
    ProfileError.INVALID_URL -> "invalidUrl"
    ProfileError.UPLOAD_FAILED -> "uploadFailed"
}
