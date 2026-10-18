package com.cruxcoach.app.kilter

import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.community.ClimbEditorState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of Android's `KilterClimbPublisherTest`, run against the real board DB and
 * the real [KilterApi] over a recording portal stand-in, so the request shapes
 * and the stored lifecycle are what is asserted, not mocks. Never contacts Kilter.
 */
class KilterClimbPublisherTest {
    private val db = PublishTestDb()
    private val session = KilterTestSession()
    private var publishEnabled = true
    private val publisher = KilterClimbPublisher(
        session.api, session.account, db.repo, { publishEnabled }, { session.clock.epochMillis() },
    )

    private val uuid = "abc12345"
    private val state = ClimbEditorState(
        selectedHolds = mapOf(1164 to 12, 1233 to 13, 1392 to 14),
        name = "Test Climb",
        description = "x",
        setterGradeId = 22,
        angle = 40,
    )
    private val boardSize = BoardSize(
        id = 10L, productId = 1L, name = "12x12",
        edgeLeft = 1, edgeRight = 144, edgeBottom = 1, edgeTop = 156,
        imageFilename = null,
    )
    private val concat = "h1164p12h1233p13h1392p14"

    @AfterTest
    fun tearDown() = db.close()

    private fun answer(status: Int, body: String = "{}", headers: Map<String, String> = emptyMap()) {
        session.http.responder = { req ->
            when {
                req.url.endsWith("/users/${KilterTestSession.USER_UUID}") -> portalOk("""{"username":"wallsetter"}""")
                "/climbs/" in req.url -> portalOk(body, status, headers)
                else -> null
            }
        }
    }

    private fun ready() {
        db.ownClimb(uuid, published = true)
        session.signIn()
    }

    // ── Skipped paths ──

    @Test
    fun `publish is skipped when the user opted out`() = runBlocking {
        ready()
        publishEnabled = false
        val outcome = publisher.publish(uuid, 1L, state, boardSize, concat)
        assertEquals(KilterClimbPublishOutcome.skipped("user-opted-out"), outcome)
        assertTrue(session.http.requests.isEmpty())
        assertNull(db.kilterStatus(uuid), "no slot claim when opted out")
    }

    @Test
    fun `publish is skipped when another flow holds the slot`() = runBlocking {
        ready()
        db.repo.claimKilterPublishSlot(uuid)
        val outcome = publisher.publish(uuid, 1L, state, boardSize, concat)
        assertEquals(KilterClimbPublishOutcome.skipped("slot-busy"), outcome)
        assertTrue(session.http.climbRequests().isEmpty())
    }

    @Test
    fun `publish is skipped when the board size is unknown`() = runBlocking {
        ready()
        val outcome = publisher.publish(uuid, 1L, state, null, concat)
        assertEquals(KilterClimbPublishOutcome.skipped("no-board-size"), outcome)
        assertTrue(session.http.requests.isEmpty())
    }

    @Test
    fun `publish is skipped without a Kilter login`() = runBlocking {
        db.ownClimb(uuid)
        val outcome = publisher.publish(uuid, 1L, state, boardSize, concat)
        assertEquals(KilterClimbPublishOutcome.skipped("no-kilter-login"), outcome)
        assertTrue(session.http.requests.isEmpty())
    }

    @Test
    fun `publish is skipped when the user uuid is blank`() = runBlocking {
        ready()
        session.tokens.storeUserUuid("")
        val outcome = publisher.publish(uuid, 1L, state, boardSize, concat)
        assertEquals(KilterClimbPublishOutcome.skipped("no-kilter-login"), outcome)
    }

    // ── Synced path and request shape ──

    @Test
    fun `publish marks synced and posts the Android create payload`() = runBlocking {
        ready()
        answer(200)
        val outcome = publisher.publish(uuid, 1L, state, boardSize, concat)

        assertEquals(KilterClimbPublishOutcome.SYNCED, outcome)
        assertEquals("synced", db.kilterStatus(uuid))
        val state = db.repo.getKilterPublishState(uuid)
        assertEquals(session.clock.epochSeconds(), state?.syncedAtEpochSeconds)

        val request = session.http.climbRequests().single()
        assertEquals("POST", request.method)
        assertEquals("https://portal.kiltergrips.com/api/climbs/create-climb/transaction", request.url)
        assertTrue(request.headers["Authorization"]!!.startsWith("Bearer "))
        assertEquals("application/json", request.headers["Content-Type"])
        val climb = Json.parseToJsonElement(request.body).jsonObject["climb"]!!.jsonObject
        assertEquals(uuid, climb["climbUuid"]!!.jsonPrimitive.content)
        assertEquals(KilterTestSession.USER_UUID, climb["userUuid"]!!.jsonPrimitive.content)
        assertEquals("wallsetter", climb["username"]!!.jsonPrimitive.content)
        assertEquals("Test Climb", climb["name"]!!.jsonPrimitive.content)
        assertEquals(concat, climb["climbConcat"]!!.jsonPrimitive.content)
        assertEquals("Kilter Board Original", climb["productName"]!!.jsonPrimitive.content)
        assertEquals("10", climb["productLayoutUuid"]!!.jsonPrimitive.content)
        assertEquals(40, climb["angle"]!!.jsonPrimitive.int)
        assertEquals(144, climb["edgeRight"]!!.jsonPrimitive.int)
        // Every default is spelled out: a missing key leaves a NOT NULL column empty upstream.
        assertEquals(1, climb["frameCount"]!!.jsonPrimitive.int)
        assertEquals(0, climb["framesPace"]!!.jsonPrimitive.int)
        assertEquals(true, climb["allowMatch"]!!.jsonPrimitive.boolean)
        assertEquals(false, climb["isDraft"]!!.jsonPrimitive.boolean)
        assertEquals(true, climb["isListed"]!!.jsonPrimitive.boolean)
        assertEquals(false, climb["isDeleted"]!!.jsonPrimitive.boolean)
        assertEquals(1, climb["accumulatedHoldSetValue"]!!.jsonPrimitive.int)
        assertEquals("NATIVE", climb["origin"]!!.jsonPrimitive.content)
        assertEquals(climb["createdAt"]!!.jsonPrimitive.content, climb["updatedAt"]!!.jsonPrimitive.content)
        assertTrue(climb["createdAt"]!!.jsonPrimitive.content.startsWith("2026-"))

        val attempt = db.repo.getKilterPublishAttempts(uuid).single()
        assertEquals("success", attempt.outcome)
        assertEquals("self", attempt.via)
    }

    @Test
    fun `a Homewall climb names the Homewall product`() = runBlocking {
        ready()
        answer(200)
        publisher.publish(uuid, 8L, state, boardSize, concat)
        val climb = Json.parseToJsonElement(session.http.climbRequests().single().body).jsonObject["climb"]!!.jsonObject
        assertEquals("Kilter Board Homewall", climb["productName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an email shaped portal name is never published as the setter handle`() = runBlocking {
        db.ownClimb(uuid)
        session.signIn(nameClaim = "Alex Climber")
        session.http.responder = { req ->
            when {
                req.url.contains("/users/") -> portalOk("""{"username":"alex@example.com"}""")
                "/climbs/" in req.url -> portalOk("{}")
                else -> null
            }
        }
        publisher.publish(uuid, 1L, state, boardSize, concat)
        val climb = Json.parseToJsonElement(session.http.climbRequests().single().body).jsonObject["climb"]!!.jsonObject
        assertEquals("Alex Climber", climb["username"]!!.jsonPrimitive.content)
        assertEquals("Alex Climber", session.account.cachedUsername())
    }

    @Test
    fun `a cached name of another Kilter account is never reused`() = runBlocking {
        ready()
        answer(200)
        publisher.publish(uuid, 1L, state, boardSize, concat)
        assertEquals("wallsetter", session.account.cachedUsername())
        // Another Kilter account signs in under the same CruxCoach identity.
        session.signIn(userUuid = "user-uuid-2")
        assertEquals("", session.account.cachedUsername())
    }

    @Test
    fun `without any usable name the placeholder is sent and not cached`() = runBlocking {
        ready()
        session.http.responder = { req -> if ("/climbs/" in req.url) portalOk("{}") else null }
        publisher.publish(uuid, 1L, state, boardSize, concat)
        val climb = Json.parseToJsonElement(session.http.climbRequests().single().body).jsonObject["climb"]!!.jsonObject
        assertEquals("CruxCoach", climb["username"]!!.jsonPrimitive.content)
        assertEquals("", session.account.cachedUsername())
    }

    // ── Failed paths ──

    @Test
    fun `an expired session that cannot be refreshed fails with session expired`() = runBlocking {
        ready()
        session.http.responder = { req ->
            when {
                req.url.contains("/users/") -> portalOk("""{"username":"wallsetter"}""")
                req.url.contains("openid-connect/token") -> portalOk("""{"error":"invalid_grant"}""", 400)
                "/climbs/" in req.url -> portalOk("", 401)
                else -> null
            }
        }
        val outcome = publisher.publish(uuid, 1L, state, boardSize, concat)
        assertEquals(KilterClimbPublishOutcome.failed("kilter_publish_session_expired"), outcome)
        assertEquals("failed", db.kilterStatus(uuid))
        assertEquals("token expired", db.kilterError(uuid))
        assertEquals("auth", db.repo.getKilterPublishAttempts(uuid).single().outcome)
    }

    @Test
    fun `a 401 is answered with one refresh and one resend`() = runBlocking {
        ready()
        var climbCalls = 0
        session.http.responder = { req ->
            when {
                req.url.contains("/users/") -> portalOk("""{"username":"wallsetter"}""")
                req.url.contains("openid-connect/token") ->
                    portalOk("""{"access_token":"${testJwt("sub" to KilterTestSession.USER_UUID)}x","expires_in":3600}""")
                "/climbs/" in req.url -> { climbCalls++; if (climbCalls == 1) portalOk("", 401) else portalOk("{}") }
                else -> null
            }
        }
        val outcome = publisher.publish(uuid, 1L, state, boardSize, concat)
        assertEquals(KilterClimbPublishOutcome.SYNCED, outcome)
        assertEquals(2, session.http.climbRequests().size)
        assertTrue(session.http.climbRequests()[1].headers["Authorization"]!!.endsWith("x"))
    }

    @Test
    fun `a server error fails transiently and stays in the queue`() = runBlocking {
        ready()
        answer(503, "upstream down")
        val outcome = publisher.publish(uuid, 1L, state, boardSize, concat)
        assertEquals(KilterClimbPublishOutcome.failed("kilter_publish_transient"), outcome)
        assertEquals("failed", db.kilterStatus(uuid))
        assertEquals("transient: HTTP 503: upstream down", db.kilterError(uuid))
    }

    @Test
    fun `a transport failure fails transiently`() = runBlocking {
        ready()
        session.http.responder = { req ->
            when {
                req.url.contains("/users/") -> portalOk("""{"username":"wallsetter"}""")
                else -> com.cruxcoach.app.platform.HttpResult.Failed(com.cruxcoach.app.platform.HttpFailure.OFFLINE, "offline")
            }
        }
        val outcome = publisher.publish(uuid, 1L, state, boardSize, concat)
        assertEquals(KilterClimbPublishOutcome.failed("kilter_publish_transient"), outcome)
        assertTrue(db.kilterError(uuid)!!.startsWith("transient: "))
    }

    @Test
    fun `a rate limit fails transiently and keeps the retry after`() = runBlocking {
        ready()
        answer(429, "slow down", mapOf("Retry-After" to "120"))
        val outcome = publisher.publish(uuid, 1L, state, boardSize, concat)
        assertEquals(KilterClimbPublishOutcome.failed("kilter_publish_transient"), outcome)
        assertEquals("rate-limited: HTTP 429 retry-after=120", db.kilterError(uuid))
        val attempt = db.repo.getKilterPublishAttempts(uuid).single()
        assertEquals("rate_limited", attempt.outcome)
        assertEquals(429, attempt.httpCode)
    }

    @Test
    fun `a refused create is rejected for good`() = runBlocking {
        ready()
        answer(422, "nope")
        val outcome = publisher.publish(uuid, 1L, state, boardSize, concat)
        assertEquals(KilterClimbPublishOutcome.failed("kilter_publish_rejected_with_code", 422), outcome)
        assertEquals("rejected", db.kilterStatus(uuid))
        assertEquals("http=422: nope", db.kilterError(uuid))
    }

    @Test
    fun `error bodies are cut to 200 characters before they are stored`() = runBlocking {
        ready()
        answer(422, "y".repeat(5000))
        publisher.publish(uuid, 1L, state, boardSize, concat)
        assertEquals("http=422: " + "y".repeat(200), db.kilterError(uuid))
        assertEquals(200, db.repo.getKilterPublishAttempts(uuid).single().errorExcerpt!!.length)
    }

    // ── Update path: decided by the claim's pre-state, not the entry point ──

    private fun previouslySynced() {
        db.repo.markKilterPublishSynced(uuid, "self", 100L)
        db.repo.markKilterPublishFailed(uuid, "re-eligible: user edit")
    }

    @Test
    fun `a refused update diverges`() = runBlocking {
        ready()
        previouslySynced()
        answer(409, "cannot edit")
        val outcome = publisher.update(uuid, 1L, state, boardSize, concat)
        assertEquals(KilterClimbPublishOutcome.diverged(409), outcome)
        assertEquals("diverged", db.kilterStatus(uuid))
        assertEquals("http=409: cannot edit", db.kilterError(uuid))
    }

    @Test
    fun `an accepted update is synced over PATCH`() = runBlocking {
        ready()
        previouslySynced()
        answer(200)
        val outcome = publisher.update(uuid, 1L, state, boardSize, concat)
        assertEquals(KilterClimbPublishOutcome.SYNCED, outcome)
        val request = session.http.climbRequests().single()
        assertEquals("PATCH", request.method)
        assertTrue(request.url.endsWith("/climbs/update-climb/transaction"))
    }

    @Test
    fun `a previously synced climb uses update even through publish`() = runBlocking {
        ready()
        previouslySynced()
        answer(200)
        publisher.publish(uuid, 1L, state, boardSize, concat)
        assertTrue(session.http.climbRequests().single().url.endsWith("/climbs/update-climb/transaction"))
    }

    @Test
    fun `a never synced climb uses create`() = runBlocking {
        ready()
        answer(200)
        publisher.update(uuid, 1L, state, boardSize, concat)
        assertTrue(session.http.climbRequests().single().url.endsWith("/climbs/create-climb/transaction"))
    }
}
