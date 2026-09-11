package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** The actual Kotlin permission/controller/vault + JNI + MDK + SQLCipher +
 * WebSocket path. Keys are ephemeral in Rust memory; none are test fixtures. */

class LiveMarmotIntegrationTest {
    private val json = Json { encodeDefaults = true }
    private val parties = mutableListOf<Party>()
    private val relays = mutableListOf<LocalRelay>()
    private fun String.bytes() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private val bip340 = Bip340Verifier { signature, message, public -> MarmotTestNative.verify(signature, message, public) }

    private inner class LocalRelay : AutoCloseable {
        val server = MockWebServer()
        val events = CopyOnWriteArrayList<BindingEvent>()
        @Volatile var reject = false
        @Volatile var dropGroup = false
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = json.parseToJsonElement(text).jsonArray
                        when (request[0].jsonPrimitive.content) {
                            "EVENT" -> {
                                val event = json.decodeFromJsonElement<BindingEvent>(request[1])
                                val valid = !reject && bip340.verify(event.sig.bytes(), event.id.bytes(), event.pubkey.bytes())
                                if (valid) events += event
                                webSocket.send(buildJsonArray { add("OK"); add(event.id); add(valid); add(if (reject) "blocked: synthetic outage" else "") }.toString())
                            }
                            "REQ" -> {
                                events.filter { event -> request.drop(2).any { raw ->
                                    val filter = raw.jsonObject
                                    (filter["authors"]?.jsonArray?.any { it.jsonPrimitive.content == event.pubkey } != false) &&
                                        (filter["kinds"]?.jsonArray?.any { it.jsonPrimitive.int == event.kind } != false) &&
                                        filter.filterKeys { it.startsWith('#') }.all { (name, values) ->
                                            event.tags.any { tag -> tag.size >= 2 && tag[0] == name.substring(1) && values.jsonArray.any { it.jsonPrimitive.content == tag[1] } }
                                        }
                                } && !(dropGroup && event.kind == 445) }.forEach { event ->
                                    val frame = buildJsonArray { add("EVENT"); add(request[1]); add(json.encodeToJsonElement(event)) }.toString()
                                    webSocket.send(frame); webSocket.send(frame)
                                }
                                webSocket.send(buildJsonArray { add("EOSE"); add(request[1]) }.toString())
                            }
                            "CLOSE" -> webSocket.close(1000, null)
                        }
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                })
            }
            server.start(); relays += this
        }
        val url get() = server.url("/").toString().replace("http://", "ws://")
        override fun close() { server.shutdown() }
    }

    private inner class Party(role: SnapshotEndpointRole, endpoints: List<String>, advance: () -> Long = { 0 }) :
        SyntheticMarmotEndpoint(role, endpoints, wall = { System.currentTimeMillis() + advance() },
            elapsed = { System.nanoTime() / 1_000_000 + advance() }) {
        init { parties += this }
    }

    @AfterTest fun cleanup() {
        parties.forEach { it.close() }
        relays.forEach(LocalRelay::close)
        MarmotTestNative.failCruxcoach(0)
    }
    private fun connected(server: Boolean = false, advance: () -> Long = { 0 }): Pair<Party, Party> {
        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        MarmotTestNative.failCruxcoach(closedPort)
        val endpoints = listOf(LocalRelay().url, LocalRelay().url, "wss://blossom.cruxcoach.org/nostr")
        val a = Party(if (server) SnapshotEndpointRole.SERVER else SnapshotEndpointRole.USER, endpoints, advance)
        val b = Party(SnapshotEndpointRole.USER, endpoints, advance)
        assertTrue(a.exchange.pinPeer(b.account, b.role)); assertTrue(b.exchange.pinPeer(a.account, a.role))
        a.port.bootstrap(); b.port.bootstrap(); a.port.invite(b.account); b.port.refresh()
        assertNull(b.port.withSession(a.account) { it }, "Welcome is not user approval")
        b.port.acceptInvitation(a.account)
        repeat(2) { a.tick(); b.tick() }
        assertNotNull(a.port.withSession(b.account) { it }); assertNotNull(b.port.withSession(a.account) { it })
        assertEquals("unavailable", a.port.relaySummary()["wss://blossom.cruxcoach.org/nostr"])
        return a to b
    }
    private fun categoryConsent(a: Party, b: Party) = runBlocking {
        assertTrue(a.controller.setPeerRule(PeerId(b.account), SharingCategory.PRIVATE_NOTES, AccessEffect.ALLOW).isSuccess)
        assertTrue(a.controller.offer(PeerId(b.account), SharingCircle.FRIENDS, setOf(SharingCategory.PRIVATE_NOTES)).isSuccess)
        repeat(2) { a.tick(); b.tick() }
        assertNotNull(b.policy.incoming(a.account)); assertTrue(a.repository.loadProjection().relationships[PeerId(b.account)]!!.consentedCategories.isEmpty())
        assertTrue(b.policy.accept(a.account, setOf(SharingCategory.PRIVATE_NOTES)))
        repeat(2) { b.tick(); a.tick() }
        assertEquals(setOf(SharingCategory.PRIVATE_NOTES), a.repository.loadProjection().relationships[PeerId(b.account)]!!.consentedCategories)
    }

    private fun exchangeAndRevoke(server: Boolean) {
        val (a, b) = connected(server)
        a.note(); assertNull(a.exchange.offer(b.account, "synthetic-note", 1, System.currentTimeMillis() + 60_000))
        categoryConsent(a, b)
        val id = assertNotNull(a.exchange.offer(b.account, "synthetic-note", 1, System.currentTimeMillis() + 60_000))
        a.tick(); b.tick(); assertNull(b.exchange.read(id)); assertTrue(b.exchange.accept(id))
        repeat(3) { b.tick(); a.tick() }
        assertEquals("synthetic native private note", b.exchange.read(id))
        assertEquals(SnapshotState.DELIVERED, a.exchange.views().single().state)
        a.reopen(); b.reopen(); repeat(2) { a.tick(); b.tick() }
        assertEquals("synthetic native private note", b.exchange.read(id))
        assertTrue(a.exchange.revoke(id)); a.tick(); b.tick()
        assertNull(b.exchange.read(id)); assertEquals(SnapshotState.REVOKED, b.exchange.views().single().state)
        repeat(2) { a.tick(); b.tick() }; assertNull(b.exchange.read(id))
        assertTrue(relays.flatMap { it.events }.filter { it.kind == 445 }.none { it.content.contains("synthetic native private note") })
    }
    @Test fun users_bootstrap_consent_native_transport_receipt_restart_and_revocation() = exchangeAndRevoke(false)
    @Test fun isolated_server_role_uses_same_peer_path_and_receives_no_user_authority() = exchangeAndRevoke(true)
    @Test fun reconnect_cannot_publish_content_queued_before_local_revocation() {
        val (a,b)=connected(); a.note(); categoryConsent(a,b)
        val id=assertNotNull(a.exchange.offer(b.account,"synthetic-note",1,System.currentTimeMillis()+120_000))
        a.tick(); b.tick(); assertTrue(b.exchange.accept(id)); b.tick()
        relays.forEach { it.reject=true }
        a.tick()
        assertEquals(SnapshotState.ACCEPTED,a.exchange.views().single().state)
        assertNull(b.exchange.read(id))
        val out=Json.parseToJsonElement(MarmotNative.call(a.nativeHandle,"{\"op\":\"pending\"}")).jsonObject["value"]!!.jsonArray
        assertTrue(out.any { it.jsonObject["content"]!!.jsonPrimitive.content.contains("synthetic native private note") })
        assertTrue(a.exchange.revoke(id)); a.reopen()
        relays.forEach { it.reject=false }
        Thread.sleep(2_100) // the actual persisted exponential retry deadline
        repeat(3) { a.tick(); b.tick() }
        assertNull(b.exchange.read(id)); assertEquals(SnapshotState.REVOKED,b.exchange.views().single().state)
        val inbox=Json.parseToJsonElement(MarmotNative.call(b.nativeHandle,"{\"op\":\"inbox\"}")).jsonObject["value"]!!.jsonArray
        assertFalse(inbox.any { it.jsonObject["content"]!!.jsonPrimitive.content.contains("synthetic native private note") })
    }

    @Test fun recipient_offline_backfills_after_restart_using_redundant_relay() {
        val (a,b)=connected(); a.note(); categoryConsent(a,b)
        relays.first().reject=true; relays.first().dropGroup=true
        val id=assertNotNull(a.exchange.offer(b.account,"synthetic-note",1,System.currentTimeMillis()+120_000))
        a.tick(); b.reopen(); b.tick(); assertTrue(b.exchange.accept(id))
        repeat(3) { b.tick(); a.tick() }
        assertEquals("synthetic native private note",b.exchange.read(id))
        assertEquals(SnapshotState.DELIVERED,a.exchange.views().single().state)
    }

    @Test fun expiry_closes_actual_native_delivered_access_and_compaction_cannot_replay_it() {
        var advance = 0L
        val (a, b) = connected(advance = { advance })
        a.note(); categoryConsent(a, b)
        val id = assertNotNull(a.exchange.offer(b.account, "synthetic-note", 1, a.clock.now() + 600_000))
        a.tick(); b.tick(); assertTrue(b.exchange.accept(id))
        repeat(3) { b.tick(); a.tick() }
        assertNotNull(b.exchange.read(id))
        advance = 1_200_000 // Advance both test wall and monotonic clocks, never bypass a lock.
        assertTrue(a.clock.healthy()); assertTrue(b.clock.healthy())
        assertNull(b.exchange.read(id), "expiry is enforced at the actual data-access door before sync")
        assertEquals(1, a.exchange.collectExpired()); assertEquals(1, b.exchange.collectExpired())
        a.reopen(); b.reopen(); repeat(2) { a.tick(); b.tick() }
        assertNull(b.exchange.read(id)); assertTrue(b.exchange.views().isEmpty())
        assertNotNull(a.database.sharingQueries.selectSealedItem("synthetic-note").executeAsOneOrNull())
    }

    @Test fun active_account_change_and_native_leaf_rotation_reject_old_snapshot_access() {
        val (a,b)=connected(); a.note(); categoryConsent(a,b)
        val id=assertNotNull(a.exchange.offer(b.account,"synthetic-note",1,System.currentTimeMillis()+120_000))
        a.tick(); b.tick(); assertTrue(b.exchange.accept(id)); repeat(3) {b.tick();a.tick()}
        b.currentAccount=a.account; assertNull(b.exchange.read(id)); b.currentAccount=b.account
        assertNotNull(b.exchange.read(id))
        val f=Json.parseToJsonElement(MarmotNative.call(a.nativeHandle,buildJsonObject {put("op","fence");put("peer",b.account)}.toString())).jsonObject["value"]!!
        val result=Json.parseToJsonElement(MarmotNative.call(a.nativeHandle,buildJsonObject {put("op","rotate");put("fence",f)}.toString())).jsonObject
        assertTrue(result["ok"]!!.jsonPrimitive.boolean)
        repeat(3) {a.tick();b.tick()}
        assertNull(b.exchange.read(id)); b.reopen(); assertNull(b.exchange.read(id))
    }

    @Test fun authenticated_third_party_cannot_replay_another_owners_snapshot_offer() {
        val (a,b)=connected(); a.note(); categoryConsent(a,b)
        val id=assertNotNull(a.exchange.offer(b.account,"synthetic-note",1,System.currentTimeMillis()+120_000))
        val offered=a.database.snapshotQueries.selectSnapshot(a.account,id).executeAsOne().offer_json
        val wire=json.encodeToString(SnapshotMessage(action=SnapshotMessageKind.OFFER,offer=json.decodeFromString<SnapshotOffer>(offered)))
        val c=Party(SnapshotEndpointRole.USER,a.endpoints)
        assertTrue(b.exchange.pinPeer(c.account,SnapshotEndpointRole.USER));assertTrue(c.exchange.pinPeer(b.account,SnapshotEndpointRole.USER))
        c.port.bootstrap(); c.port.invite(b.account); b.port.refresh(); b.port.acceptInvitation(c.account)
        repeat(2){c.tick();b.tick()}
        val fence=Json.parseToJsonElement(MarmotNative.call(c.nativeHandle,buildJsonObject{put("op","fence");put("peer",b.account)}.toString())).jsonObject["value"]!!
        val sent=Json.parseToJsonElement(MarmotNative.call(c.nativeHandle,buildJsonObject{
            put("op","handoff");put("fence",fence);put("kind",1220);put("tags",json.encodeToJsonElement(SharingSnapshotExchange.TAGS));put("content",wire);put("expires_at",System.currentTimeMillis()+120_000)
        }.toString())).jsonObject
        assertTrue(sent["ok"]!!.jsonPrimitive.boolean)
        val published=Json.parseToJsonElement(MarmotNative.call(c.nativeHandle,buildJsonObject{put("op","publish");put("event_id",sent["value"]!!);put("fence",fence)}.toString())).jsonObject
        assertTrue(published["ok"]!!.jsonPrimitive.boolean);b.tick()
        assertFalse(b.exchange.views().any{it.id==id});assertNull(b.exchange.read(id))
    }

    @Test fun replacement_invitation_requires_explicit_acceptance_and_never_restores_old_snapshot() {
        val (a,b)=connected();a.note();categoryConsent(a,b)
        val id=assertNotNull(a.exchange.offer(b.account,"synthetic-note",1,System.currentTimeMillis()+120_000))
        a.tick();b.tick();assertTrue(b.exchange.accept(id));repeat(3){a.tick();b.tick()}
        assertNotNull(b.exchange.read(id))
        a.port.resetPeer(b.account);assertNull(a.port.withSession(b.account){it})
        a.port.invite(b.account);b.port.refresh()
        val replacement=b.port.peers().single{it.account==a.account&&!it.accepted}
        assertNotNull(b.exchange.read(id),"a replacement Welcome alone must not rewrite an accepted session")
        b.port.acceptInvitation(a.account,replacement.group);repeat(2){a.tick();b.tick()}
        assertNull(b.exchange.read(id));assertNotNull(b.port.withSession(a.account){it})
    }

}
