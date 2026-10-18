package com.cruxcoach.app.kilter

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of Android's `KilterPublishRetryWorkerTest` (the pass, not WorkManager):
 * real board DB, real API client, recording portal stand-in.
 */
class KilterClimbPublisherRetryTest {
    private val db = PublishTestDb()
    private val session = KilterTestSession()
    private var publishEnabled = true
    private var pubkey: String? = PublishTestDb.OWN_PUBKEY
    private val publisher = KilterClimbPublisher(
        session.api, session.account, db.repo, { publishEnabled }, { session.clock.epochMillis() },
    )
    private val retry = KilterClimbPublishRetry(
        publisher, session.account, db.repo, { publishEnabled }, { pubkey }, { session.clock.epochMillis() },
    )

    @AfterTest
    fun tearDown() = db.close()

    /** Portal answer per climb uuid; the setter name lookup always succeeds. */
    private fun answer(byUuid: (String) -> Int) {
        session.http.responder = { req ->
            when {
                req.url.contains("/users/") -> portalOk("""{"username":"wallsetter"}""")
                "/climbs/" in req.url -> {
                    val uuid = Json.parseToJsonElement(req.body).jsonObject["climb"]!!.jsonObject["climbUuid"]!!.jsonPrimitive.content
                    portalOk("{}", byUuid(uuid))
                }
                else -> null
            }
        }
    }

    private fun queued(vararg uuids: String) {
        db.kilterSize()
        db.placement(1, 101); db.placement(2, 102); db.placement(3, 103)
        for (uuid in uuids) db.ownClimb(uuid)
        session.signIn()
    }

    @Test
    fun `nothing happens when the user opted out`() = runBlocking {
        queued("c1")
        publishEnabled = false
        val report = retry.run()
        assertEquals("user-opted-out", report.skipped)
        assertTrue(session.http.requests.isEmpty())
    }

    @Test
    fun `nothing happens without a Kilter login`() = runBlocking {
        db.kilterSize()
        db.ownClimb("c1")
        val report = retry.run()
        assertEquals("no-kilter-login", report.skipped)
        assertTrue(session.http.requests.isEmpty())
    }

    @Test
    fun `nothing happens without an active pubkey`() = runBlocking {
        queued("c1")
        pubkey = null
        val report = retry.run()
        assertEquals("no-nostr-pubkey", report.skipped)
        assertTrue(session.http.requests.isEmpty())
        assertNull(db.kilterStatus("c1"))
    }

    @Test
    fun `an empty queue is a quiet success`() = runBlocking {
        session.signIn()
        val report = retry.run()
        assertEquals(0, report.attempted)
        assertFalse(report.retrySoon)
    }

    @Test
    fun `a successful row is synced with hole ids and its own size`() = runBlocking {
        queued("c1")
        answer { 200 }
        val report = retry.run()
        assertEquals(1, report.synced)
        assertEquals("synced", db.kilterStatus("c1"))
        val climb = Json.parseToJsonElement(session.http.climbRequests().single().body).jsonObject["climb"]!!.jsonObject
        assertEquals("h101p12h102p13h103p14", climb["climbConcat"]!!.jsonPrimitive.content)
        assertEquals("10", climb["productLayoutUuid"]!!.jsonPrimitive.content)
        assertEquals(144, climb["edgeRight"]!!.jsonPrimitive.int)
        assertEquals(40, climb["angle"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the row size comes from the climb not from any active board`() = runBlocking {
        // Two Original sizes; the climb's box fits only the bigger one (edges 0..200).
        db.kilterSize(id = 7L, edges = longArrayOf(0, 50, 0, 50))
        db.kilterSize(id = 14L, edges = longArrayOf(0, 200, 0, 200))
        db.placement(1, 101); db.placement(2, 102); db.placement(3, 103)
        db.ownClimb("c1", edges = longArrayOf(10, 150, 10, 150))
        session.signIn()
        answer { 200 }
        retry.run()
        val climb = Json.parseToJsonElement(session.http.climbRequests().single().body).jsonObject["climb"]!!.jsonObject
        assertEquals("14", climb["productLayoutUuid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unresolvable size fails that row and the batch goes on`() = runBlocking {
        queued("c2")
        db.ownClimb("c1", layoutId = 99L)
        answer { 200 }
        retry.run()
        assertEquals("failed", db.kilterStatus("c1"))
        assertEquals("no product size resolvable for layout=99", db.kilterError("c1"))
        assertEquals("synced", db.kilterStatus("c2"))
    }

    @Test
    fun `a transient row is failed with the retry prefix and asks for an early retry`() = runBlocking {
        queued("c1")
        answer { 503 }
        val report = retry.run()
        assertEquals("failed", db.kilterStatus("c1"))
        assertTrue(db.kilterError("c1")!!.startsWith("retry transient: HTTP 503"))
        assertTrue(report.retrySoon)
    }

    @Test
    fun `mixed outcomes do not ask for an early retry`() = runBlocking {
        queued("c1", "c2")
        answer { uuid -> if (uuid == "c1") 503 else 200 }
        val report = retry.run()
        assertEquals(2, report.attempted)
        assertFalse(report.retrySoon)
    }

    @Test
    fun `a refused update diverges and a refused create is rejected`() = runBlocking {
        queued("c1", "c2")
        db.repo.markKilterPublishSynced("c1", "self", 100L)
        db.repo.markKilterPublishFailed("c1", "edited")
        answer { 409 }
        val report = retry.run()
        assertEquals(2, report.permanent)
        assertEquals("diverged", db.kilterStatus("c1"))
        assertTrue(db.kilterError("c1")!!.startsWith("retry http=409"))
        assertEquals("rejected", db.kilterStatus("c2"))
        assertTrue(session.http.climbRequests().first { it.method == "PATCH" }.url.endsWith("update-climb/transaction"))
    }

    @Test
    fun `a lost session mid batch stops the pass`() = runBlocking {
        queued("c1", "c2")
        session.http.responder = { req ->
            when {
                req.url.contains("/users/") -> portalOk("""{"username":"wallsetter"}""")
                req.url.contains("openid-connect/token") -> portalOk("{}", 400)
                "/climbs/" in req.url -> portalOk("", 403)
                else -> null
            }
        }
        val report = retry.run()
        assertEquals("auth-lost", report.skipped)
        assertEquals(1, session.http.climbRequests().size)
        // One row was tried (its claim stays until the sweep), the other never reached.
        assertEquals(setOf("pending", null), setOf(db.kilterStatus("c1"), db.kilterStatus("c2")))
    }

    @Test
    fun `the queue holds only the active identity's climbs`() = runBlocking {
        queued("mine")
        db.ownClimb("theirs", pubkey = "b".repeat(64))
        answer { 200 }
        retry.run()
        assertEquals("synced", db.kilterStatus("mine"))
        assertNull(db.kilterStatus("theirs"))
        assertEquals(1, session.http.climbRequests().size)
    }

    @Test
    fun `a stranded pending row is swept back and retried`() = runBlocking {
        queued("c1")
        db.repo.claimKilterPublishSlot("c1")
        db.repo.recordKilterPublishAttempt(
            "c1", session.clock.epochMillis() - KilterClimbPublishRetry.STUCK_PENDING_GRACE_MS - 1,
            com.cruxcoach.data.repository.KilterPublishOp.CREATE, "self",
            com.cruxcoach.data.repository.KilterPublishOutcomeKind.TRANSIENT,
        )
        answer { 200 }
        retry.run()
        assertEquals("synced", db.kilterStatus("c1"))
    }

    @Test
    fun `climbs not yet on Nostr are not retried`() = runBlocking {
        db.kilterSize()
        db.ownClimb("draft", published = false)
        session.signIn()
        answer { 200 }
        retry.run()
        assertTrue(session.http.climbRequests().isEmpty())
    }
}
