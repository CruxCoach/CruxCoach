package com.cruxcoach.app.kilter

import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.HttpResult
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of the backfill cases of Android's `KilterSyncEngineBackfillTest`, on the
 * real board DB with the `/climbs/logged` and `/climbs/climbdetails/user` wire
 * shapes served by a recording stand-in.
 */
class KilterClimbBackfillerTest {
    private val db = PublishTestDb()
    private val session = KilterTestSession()
    private var catalogueReady = true
    private val backfiller = KilterClimbBackfiller(session.api, db.repo) { catalogueReady }

    private val newWorldUuid = "a30d8042-aeea-42ce-8015-239016c87769"

    @AfterTest
    fun tearDown() = db.close()

    init {
        // Original layout 1 with size 10; holes 1..3 sit at placements 501..503.
        db.kilterSize()
        db.placement(501, 1); db.placement(502, 2); db.placement(503, 3)
        session.signIn()
    }

    private fun loggedClimb(
        uuid: String,
        userUuid: String = "author-uuid-alice",
        deleted: Boolean = false,
        angle: Int = 40,
    ) = """{"climbUuid":"$uuid","climbConcat":"h1p12h2p13h3p14","name":"Tallakrennesvingen","angle":$angle,
        |"description":"d","edgeLeft":4,"edgeRight":100,"edgeBottom":8,"edgeTop":120,"frameCount":1,"framesPace":0,
        |"productLayoutUuid":"10","productName":"Kilter Board Original","isListed":true,"isDraft":false,
        |"isDeleted":$deleted,"origin":"NATIVE","userUuid":"$userUuid","username":"alice",
        |"createdAt":"2026-05-01 10:00:00","updatedAt":"2026-05-01 10:00:00","unknownField":1}""".trimMargin()

    private fun loggedStat(uuid: String, angle: Int = 25) =
        """{"climbUuid":"$uuid","angle":$angle,"difficultyAverage":21.5,"qualityAverage":2.8,"ascentCount":7,
        |"currentDifficultyId":21,"faUsername":"fa","faAt":"2026-05-02"}""".trimMargin()

    private fun serve(logged: String? = null, authored: String? = null) {
        session.http.responder = { req ->
            when {
                req.url.endsWith("/climbs/logged") -> logged?.let { portalOk(it) }
                req.url.endsWith("/climbs/climbdetails/user") -> authored?.let { portalOk(it) }
                else -> null
            }
        }
    }

    @Test
    fun `a missing logged climb is inserted with placement frames stats and author`() = runBlocking {
        serve(logged = """{"climbs":[${loggedClimb(newWorldUuid)}],"climbStats":[${loggedStat(newWorldUuid)}]}""")
        assertEquals(1, backfiller.backfillLoggedClimbs())

        val stored = assertNotNull(db.repo.getClimbByUuid(newWorldUuid, 25))
        assertEquals("Tallakrennesvingen", stored.name)
        // "10" is a product SIZE; the climb is on layout 1.
        assertEquals(1L, stored.layoutId)
        // Holes 1/2/3 rewritten to their placements in that layout.
        assertEquals("p501r12p502r13p503r14", stored.frames)
        assertEquals(21.5, stored.difficultyAverage)
        // Stats came at 25°, the climb record says 40°: an ungraded row resolves there too.
        assertNotNull(db.repo.getClimbByUuid(newWorldUuid, 40))
        assertEquals("author-uuid-alice", db.repo.getClimbKilterAuthorUuid(newWorldUuid))
        assertEquals("GET", session.http.requests.single().method)
    }

    @Test
    fun `a climb already in the board DB is never re-inserted`() = runBlocking {
        db.ownClimb(newWorldUuid, published = false)
        serve(logged = """{"climbs":[${loggedClimb(newWorldUuid)}],"climbStats":[]}""")
        assertEquals(0, backfiller.backfillLoggedClimbs())
        assertEquals("Name $newWorldUuid", db.repo.getClimbByUuid(newWorldUuid, 40)?.name)
    }

    @Test
    fun `a climb stored under the legacy spelling is not duplicated`() = runBlocking {
        val legacy = newWorldUuid.replace("-", "").uppercase()
        db.ownClimb(legacy, published = false)
        serve(logged = """{"climbs":[${loggedClimb(newWorldUuid)}],"climbStats":[${loggedStat(newWorldUuid)}]}""")
        assertEquals(0, backfiller.backfillLoggedClimbs())
        assertNull(db.repo.getClimbByUuid(newWorldUuid, 25))
    }

    @Test
    fun `a backfilled climb is stored under the lowercase uuid`() = runBlocking {
        val upper = "A30D8042AEEA42CE8015239016C87769"
        serve(logged = """{"climbs":[${loggedClimb(upper)}],"climbStats":[${loggedStat(upper)}]}""")
        assertEquals(1, backfiller.backfillLoggedClimbs())
        assertEquals(upper.lowercase(), db.repo.findClimbCanonicalUuid(upper))
        assertEquals("author-uuid-alice", db.repo.getClimbKilterAuthorUuid(upper.lowercase()))
    }

    @Test
    fun `a soft deleted climb is not backfilled`() = runBlocking {
        serve(logged = """{"climbs":[${loggedClimb(newWorldUuid, deleted = true)}],"climbStats":[]}""")
        assertEquals(0, backfiller.backfillLoggedClimbs())
        assertNull(db.repo.findClimbCanonicalUuid(newWorldUuid))
    }

    @Test
    fun `a failed fetch is no error`() = runBlocking {
        session.http.responder = { HttpResult.Failed(HttpFailure.OFFLINE, "offline") }
        assertEquals(0, backfiller.backfillLoggedClimbs())
        assertEquals(0, backfiller.backfillAuthoredClimbs())
        session.http.responder = { portalOk("<html>oops</html>", 500) }
        assertEquals(0, backfiller.backfillLoggedClimbs())
        session.http.responder = { portalOk("not json") }
        assertEquals(0, backfiller.backfillAuthoredClimbs())
    }

    @Test
    fun `a missing authored climb is inserted with its author at the setter angle`() = runBlocking {
        serve(authored = "[${loggedClimb(newWorldUuid, userUuid = KilterTestSession.USER_UUID, angle = 45)}]")
        assertEquals(1, backfiller.backfillAuthoredClimbs())
        assertNotNull(db.repo.getClimbByUuid(newWorldUuid, 45))
        assertEquals(KilterTestSession.USER_UUID, db.repo.getClimbKilterAuthorUuid(newWorldUuid))
    }

    @Test
    fun `an authored climb already present is author marked but not clobbered`() = runBlocking {
        val legacy = newWorldUuid.replace("-", "").uppercase()
        db.ownClimb(legacy, published = false)
        serve(authored = """{"climbs":[${loggedClimb(newWorldUuid, userUuid = KilterTestSession.USER_UUID)}]}""")
        assertEquals(1, backfiller.backfillAuthoredClimbs())
        assertEquals(KilterTestSession.USER_UUID, db.repo.getClimbKilterAuthorUuid(legacy))
        assertEquals("Name $legacy", db.repo.getClimbByUuid(legacy, 40)?.name)
        assertNull(db.repo.getClimbByUuid(newWorldUuid, 40))
    }

    @Test
    fun `the pass waits for the Kilter catalogue`() = runBlocking {
        catalogueReady = false
        serve(logged = """{"climbs":[${loggedClimb(newWorldUuid)}],"climbStats":[]}""", authored = "[]")
        val counts = backfiller.backfillOwnClimbs()
        assertTrue(counts.deferred)
        assertTrue(session.http.requests.isEmpty())
        catalogueReady = true
        val second = backfiller.backfillOwnClimbs()
        assertEquals(1, second.loggedUpserted)
        assertEquals(0, second.ownRecognised)
    }
}
