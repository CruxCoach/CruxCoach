package com.cruxcoach.android.data.shinywall

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShinyWallAccountClientHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryShinyWallTokenStorage
    private lateinit var client: ShinyWallAccountClient
    private var now = 1_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemoryShinyWallTokenStorage().also { it.now = { now } }
        val http = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        client = ShinyWallAccountClient(store, http)
        client.setEndpointsForTesting(server.url("/").toString().trimEnd('/'))
        client.clock = { now }
        client.retryBackoffMs = 1
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun signedIn(expiresAt: Long = now + 3_600_000) =
        store.storeSession(ShinyWallSession("uid1", "id-1", "refresh-1", expiresAt, "climber"))

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    private fun firebaseError(message: String) = json("""{"error":{"code":400,"message":"$message","errors":[]}}""", 400)

    // ── sign-in ─────────────────────────────────────────────────────

    @Test
    fun sign_in_success_returns_session_and_never_sends_the_password_elsewhere() = runTest {
        server.enqueue(json("""{"idToken":"id-1","refreshToken":"refresh-1","localId":"uid1","expiresIn":"3600","email":"a@b.c"}"""))
        val result = client.signIn(" a@b.c ", "pw")
        assertTrue(result is ShinyWallAuthResult.Success, "got $result")
        assertEquals(ShinyWallSession("uid1", "id-1", "refresh-1", now + 3_600_000, "a@b.c"), result.session)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.startsWith("/identity/v1/accounts:signInWithPassword?key="), request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"email\":\"a@b.c\"") && body.contains("\"returnSecureToken\":true"), body)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun sign_in_maps_firebase_errors() = runTest {
        val cases = listOf(
            "INVALID_LOGIN_CREDENTIALS" to ShinyWallAuthResult.Error.Reason.InvalidCredentials,
            "EMAIL_NOT_FOUND" to ShinyWallAuthResult.Error.Reason.InvalidCredentials,
            "USER_DISABLED" to ShinyWallAuthResult.Error.Reason.AccountDisabled,
            "TOO_MANY_ATTEMPTS_TRY_LATER : Access to this account has been temporarily disabled" to
                ShinyWallAuthResult.Error.Reason.Throttled,
            "OPERATION_NOT_ALLOWED" to ShinyWallAuthResult.Error.Reason.HttpFailure,
        )
        cases.forEachIndexed { i, (message, reason) ->
            server.enqueue(firebaseError(message))
            // A different email per case: the client backs off per email after a failure.
            val result = client.signIn("user$i@b.c", "pw")
            assertEquals(reason, (result as ShinyWallAuthResult.Error).reason, message)
        }
    }

    @Test
    fun sign_in_backs_off_after_a_failure() = runTest {
        server.enqueue(firebaseError("INVALID_LOGIN_CREDENTIALS"))
        client.signIn("a@b.c", "wrong")
        val second = client.signIn("a@b.c", "wrong")
        assertEquals(ShinyWallAuthResult.Error.Reason.Throttled, (second as ShinyWallAuthResult.Error).reason)
        assertEquals(1, server.requestCount)
        now += 5_000
        server.enqueue(json("""{"idToken":"i","refreshToken":"r","localId":"u","expiresIn":"3600"}"""))
        assertTrue(client.signIn("a@b.c", "right") is ShinyWallAuthResult.Success)
    }

    @Test
    fun sign_in_offline_is_a_network_error() = runTest {
        server.shutdown()
        val result = client.signIn("a@b.c", "pw")
        assertEquals(ShinyWallAuthResult.Error.Reason.NetworkError, (result as ShinyWallAuthResult.Error).reason)
    }

    @Test
    fun sign_in_without_tokens_is_unexpected() = runTest {
        server.enqueue(json("""{"kind":"identitytoolkit#VerifyPasswordResponse"}"""))
        val result = client.signIn("a@b.c", "pw")
        assertEquals(ShinyWallAuthResult.Error.Reason.UnexpectedResponse, (result as ShinyWallAuthResult.Error).reason)
    }

    // ── data calls ──────────────────────────────────────────────────

    private fun historyDoc(id: String, routeId: String, sent: Int) = """
        {"name":"projects/climbingwall3js/databases/(default)/documents/user_activity/uid1/history/$id",
         "fields":{"routeId":{"stringValue":"$routeId"},"standard":{"stringValue":"st1"},
                   "angle":{"integerValue":"40"},"sent":{"integerValue":"$sent"},
                   "timestamp":{"timestampValue":"2026-10-30T20:08:42.131553Z"}},
         "createTime":"2026-10-30T20:08:42.131553Z"}"""

    @Test
    fun history_follows_every_page_with_the_bearer_token() = runTest {
        signedIn()
        server.enqueue(json("""{"documents":[${historyDoc("h1", "r1", 1)},${historyDoc("h2", "r2", -1)}],"nextPageToken":"p2"}"""))
        server.enqueue(json("""{"documents":[${historyDoc("h3", "r3", 4)}]}"""))
        val entries = client.fetchHistory("uid1").getOrThrow()
        assertEquals(listOf("h1", "h2", "h3"), entries.map { it.docId })
        val first = server.takeRequest()
        assertEquals("Bearer id-1", first.getHeader("Authorization"))
        assertTrue(first.path!!.startsWith("/firestore/v1/projects/climbingwall3js/databases/"), first.path)
        assertTrue(first.path!!.contains("/documents/user_activity/uid1/history?pageSize=300"), first.path)
        val second = server.takeRequest()
        assertTrue(second.path!!.contains("pageToken=p2"), second.path)
    }

    @Test
    fun empty_history_is_an_empty_list() = runTest {
        signedIn()
        server.enqueue(json("{}"))
        assertEquals(emptyList(), client.fetchHistory("uid1").getOrThrow())
    }

    @Test
    fun expired_id_token_is_refreshed_before_the_call() = runTest {
        signedIn(expiresAt = now - 1)
        server.enqueue(json("""{"id_token":"id-2","refresh_token":"refresh-2","expires_in":"3600","user_id":"uid1"}"""))
        server.enqueue(json("{}"))
        client.fetchHistory("uid1").getOrThrow()
        val refresh = server.takeRequest()
        assertTrue(refresh.path!!.startsWith("/securetoken/v1/token?key="), refresh.path)
        val form = refresh.body.readUtf8()
        assertTrue(form.contains("grant_type=refresh_token") && form.contains("refresh_token=refresh-1"), form)
        assertEquals("Bearer id-2", server.takeRequest().getHeader("Authorization"))
        assertEquals("refresh-2", store.session()!!.refreshToken)
    }

    @Test
    fun a_401_refreshes_once_and_retries() = runTest {
        signedIn()
        server.enqueue(json("""{"error":{"code":401,"status":"UNAUTHENTICATED"}}""", 401))
        server.enqueue(json("""{"id_token":"id-2","refresh_token":"refresh-2","expires_in":"3600"}"""))
        server.enqueue(json("{}"))
        assertTrue(client.fetchHistory("uid1").isSuccess)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun a_refused_refresh_means_sign_in_again() = runTest {
        signedIn(expiresAt = now - 1)
        server.enqueue(firebaseError("TOKEN_EXPIRED"))
        val error = client.fetchHistory("uid1").exceptionOrNull()
        assertEquals(ShinyWallApiException.Reason.NotAuthenticated, (error as ShinyWallApiException).reason)
    }

    @Test
    fun not_signed_in_fails_without_a_request() = runTest {
        val error = client.fetchHistory("uid1").exceptionOrNull()
        assertEquals(ShinyWallApiException.Reason.NotAuthenticated, (error as ShinyWallApiException).reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun server_errors_are_retried_and_permission_errors_are_not() = runTest {
        signedIn()
        server.enqueue(json("{}", 503))
        server.enqueue(json("{}"))
        assertTrue(client.fetchHistory("uid1").isSuccess)
        server.enqueue(json("""{"error":{"code":403,"status":"PERMISSION_DENIED"}}""", 403))
        val denied = client.fetchHistory("uid1").exceptionOrNull() as ShinyWallApiException
        assertEquals(403, denied.httpCode)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun profile_reads_the_own_user_document() = runTest {
        signedIn()
        server.enqueue(json("""{"name":"projects/climbingwall3js/databases/(default)/documents/users/uid1",
            "fields":{"username":{"stringValue":"climber"},
                      "favoriteRoutes":{"arrayValue":{"values":[{"stringValue":"f1"}]}}}}"""))
        val profile = client.fetchProfile("uid1").getOrThrow()
        assertEquals("climber", profile.username)
        assertEquals(listOf("f1"), profile.favoriteRoutes.map { it.routeId })
        assertTrue(server.takeRequest().path!!.endsWith("/documents/users/uid1"))
    }

    @Test
    fun own_routes_query_filters_on_the_own_uid_and_drops_drafts() = runTest {
        signedIn()
        server.enqueue(json("""[
            {"document":{"name":"projects/climbingwall3js/databases/(default)/documents/standard_routes/r1",
              "fields":{"id":{"stringValue":"r1"},"originSt":{"stringValue":"st5"}}},"readTime":"t"},
            {"document":{"name":"projects/climbingwall3js/databases/(default)/documents/standard_routes/r2",
              "fields":{"id":{"stringValue":"r2"},"draft":{"booleanValue":true}}},"readTime":"t"},
            {"readTime":"t"}]"""))
        assertEquals(listOf(ShinyWallOwnRoute("r1", "st5")), client.fetchOwnRoutes("uid1").getOrThrow())
        val request = server.takeRequest()
        assertTrue(request.path!!.endsWith("/documents:runQuery"), request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"creatorId\"") && body.contains("\"stringValue\":\"uid1\""), body)
        assertFalse(body.contains("details"), body)
    }
}
