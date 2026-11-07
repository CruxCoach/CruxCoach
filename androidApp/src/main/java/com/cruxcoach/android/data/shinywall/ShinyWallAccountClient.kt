package com.cruxcoach.android.data.shinywall

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

sealed class ShinyWallAuthResult {
    data class Success(val session: ShinyWallSession) : ShinyWallAuthResult()

    /** [cause] is for logcat only, never shown. */
    data class Error(val reason: Reason, val httpCode: Int? = null, val cause: String? = null) : ShinyWallAuthResult() {
        enum class Reason { InvalidCredentials, AccountDisabled, Throttled, NetworkError, HttpFailure, UnexpectedResponse }
    }
}

/** Failure of a data call; [reason] lets the UI tell expired sign-in from offline. */
class ShinyWallApiException(val reason: Reason, message: String, val httpCode: Int? = null) : Exception(message) {
    enum class Reason { NotAuthenticated, Network, Http, UnexpectedResponse }
}

/**
 * Reads the signed-in user's OWN Shiny Wall data, at the user's request:
 * sign-in through Shiny Wall's Firebase sign-in service, then single REST
 * reads of the user's profile, activity history and own published routes.
 * Nothing is ever written to the Shiny Wall account, no
 * other user's data is requested, and the route catalogue keeps coming from
 * CruxCoach's own Blossom mirror ([com.cruxcoach.android.data.ShinyWallCatalogueSync]).
 *
 * Only the refresh and ID tokens are kept ([ShinyWallTokenStorage]); the
 * password is sent once to the sign-in endpoint and dropped.
 */
@Singleton
class ShinyWallAccountClient @Inject constructor(
    private val tokenStore: ShinyWallTokenStorage,
    @Named("shinywall") private val httpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "ShinyWallAccount"

        /**
         * Public web API key of Shiny Wall's Firebase project, as shipped in
         * the official app. It only names the project for the sign-in and
         * database endpoints; access is decided by the user's own sign-in.
         */
        internal const val FIREBASE_WEB_API_KEY = "AIzaSyAYCz7nVfYZe476PYpYdbYXjahGsbgXl9Y"
        internal const val PROJECT_ID = "climbingwall3js"

        internal const val PAGE_SIZE = 300
        /** Bounds a runaway pagination (300 × 200 = 60 000 history entries). */
        internal const val MAX_PAGES = 200
        private const val MAX_RETRIES = 2
        private const val BACKOFF_MS = 1_000L
        private const val EXPIRY_BUFFER_MS = 60_000L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private var authBase = "https://identitytoolkit.googleapis.com/v1"
    private var tokenBase = "https://securetoken.googleapis.com/v1"
    private var firestoreBase = "https://firestore.googleapis.com/v1"

    @androidx.annotation.VisibleForTesting
    internal fun setEndpointsForTesting(base: String) {
        authBase = "$base/identity/v1"
        tokenBase = "$base/securetoken/v1"
        firestoreBase = "$base/firestore/v1"
    }

    /** Wall clock; replaced in tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    /** Base pause between retries; shortened in tests. */
    internal var retryBackoffMs: Long = BACKOFF_MS

    private val databasePath = "projects/$PROJECT_ID/databases/(default)/documents"
    private val documentsRoot get() = "$firestoreBase/$databasePath"
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()

    // Client-side login backoff per email, in memory only, so a stuck
    // retry loop cannot lock the user out of their own account.
    private val authBackoffUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val authFailures = java.util.concurrent.ConcurrentHashMap<String, Int>()

    suspend fun signIn(email: String, password: String): ShinyWallAuthResult = withContext(Dispatchers.IO) {
        val throttleKey = email.trim().lowercase()
        if (clock() < (authBackoffUntil[throttleKey] ?: 0L)) {
            return@withContext ShinyWallAuthResult.Error(ShinyWallAuthResult.Error.Reason.Throttled)
        }
        try {
            val body = buildJsonObject {
                put("email", email.trim())
                put("password", password)
                put("returnSecureToken", true)
            }.toString().toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$authBase/accounts:signInWithPassword".toHttpUrl().newBuilder()
                    .addQueryParameter("key", FIREBASE_WEB_API_KEY).build())
                .post(body)
                .build()
            val (code, text) = httpClient.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            if (code !in 200..299) {
                bumpBackoff(throttleKey)
                val message = firebaseErrorMessage(text)
                Log.w(TAG, "Sign-in failed: HTTP $code ${message ?: ""}")
                return@withContext ShinyWallAuthResult.Error(mapAuthError(code, message), code, message)
            }
            val obj = json.parseToJsonElement(text) as? JsonObject
            val idToken = obj?.string("idToken")
            val refreshToken = obj?.string("refreshToken")
            val uid = obj?.string("localId")
            if (idToken.isNullOrBlank() || refreshToken.isNullOrBlank() || uid.isNullOrBlank()) {
                bumpBackoff(throttleKey)
                return@withContext ShinyWallAuthResult.Error(ShinyWallAuthResult.Error.Reason.UnexpectedResponse, code)
            }
            val expiresIn = obj?.string("expiresIn")?.toLongOrNull() ?: 3600L
            authFailures.remove(throttleKey)
            authBackoffUntil.remove(throttleKey)
            ShinyWallAuthResult.Success(
                ShinyWallSession(
                    uid = uid,
                    idToken = idToken,
                    refreshToken = refreshToken,
                    idTokenExpiresAtMs = clock() + expiresIn * 1000,
                    displayName = email.trim(),
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Sign-in unreachable (${e.javaClass.simpleName})")
            ShinyWallAuthResult.Error(ShinyWallAuthResult.Error.Reason.NetworkError)
        } catch (e: Exception) {
            Log.w(TAG, "Sign-in answer unreadable", e)
            bumpBackoff(throttleKey)
            ShinyWallAuthResult.Error(ShinyWallAuthResult.Error.Reason.UnexpectedResponse)
        }
    }

    /** The user's own profile, or a failure. */
    suspend fun fetchProfile(uid: String): Result<ShinyWallProfile> = call {
        val text = authorized { token ->
            Request.Builder().url("$documentsRoot/users/$uid").header("Authorization", "Bearer $token").build()
        }
        val doc = FirestoreValues.document(json.parseToJsonElement(text))
            ?: throw ShinyWallApiException(ShinyWallApiException.Reason.UnexpectedResponse, "profile without fields")
        ShinyWallParsers.profile(doc.second)
    }

    /** Every entry of the user's own activity history (all pages). */
    suspend fun fetchHistory(uid: String): Result<List<ShinyWallHistoryEntry>> = call {
        val out = mutableListOf<ShinyWallHistoryEntry>()
        var pageToken: String? = null
        var pages = 0
        do {
            val url = "$documentsRoot/user_activity/$uid/history".toHttpUrl().newBuilder()
                .addQueryParameter("pageSize", PAGE_SIZE.toString())
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val text = authorized { token -> Request.Builder().url(url).header("Authorization", "Bearer $token").build() }
            val obj = json.parseToJsonElement(text) as? JsonObject
                ?: throw ShinyWallApiException(ShinyWallApiException.Reason.UnexpectedResponse, "history page is not an object")
            for (element in FirestoreValues.documents(obj)) {
                val (name, fields) = FirestoreValues.document(element) ?: continue
                val docId = FirestoreValues.documentId(name) ?: continue
                ShinyWallParsers.historyEntry(docId, fields, FirestoreValues.createTime(element))?.let(out::add)
            }
            pageToken = obj.string("nextPageToken")?.takeIf { it.isNotBlank() }
            pages++
        } while (pageToken != null && pages < MAX_PAGES)
        if (pageToken != null) Log.w(TAG, "History stopped after $MAX_PAGES pages")
        Log.i(TAG, "History: ${out.size} entries in $pages page(s)")
        out
    }

    /** The user's own published standard routes. */
    suspend fun fetchOwnRoutes(uid: String): Result<List<ShinyWallOwnRoute>> = call {
        val query = buildJsonObject {
            putJsonObject("structuredQuery") {
                putJsonArray("from") { add(buildJsonObject { put("collectionId", "standard_routes") }) }
                putJsonObject("where") {
                    putJsonObject("fieldFilter") {
                        putJsonObject("field") { put("fieldPath", "creatorId") }
                        put("op", "EQUAL")
                        putJsonObject("value") { put("stringValue", uid) }
                    }
                }
                putJsonObject("select") {
                    putJsonArray("fields") {
                        for (f in listOf("id", "originSt", "draft")) add(buildJsonObject { put("fieldPath", f) })
                    }
                }
            }
        }.toString()
        val text = authorized { token ->
            Request.Builder().url("$documentsRoot:runQuery").header("Authorization", "Bearer $token")
                .post(query.toRequestBody(JSON_MEDIA)).build()
        }
        val rows = json.parseToJsonElement(text) as? JsonArray
            ?: throw ShinyWallApiException(ShinyWallApiException.Reason.UnexpectedResponse, "query answer is not a list")
        rows.mapNotNull { row ->
            val (name, fields) = FirestoreValues.document((row as? JsonObject)?.get("document")) ?: return@mapNotNull null
            ShinyWallParsers.ownRoute(FirestoreValues.documentId(name) ?: return@mapNotNull null, fields)
        }.distinctBy { it.routeId }
    }

    // ── plumbing ──────────────────────────────────────────────────────

    private suspend fun <T> call(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ShinyWallApiException) {
            Result.failure(e)
        } catch (e: java.io.IOException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected answer", e)
            Result.failure(ShinyWallApiException(ShinyWallApiException.Reason.UnexpectedResponse, e.javaClass.simpleName))
        }
    }

    /**
     * Runs an authorized request with a valid ID token. Refreshes once on
     * 401/403-with-expired-token, retries network errors, 429 and 5xx with
     * backoff, and returns the body of a 2xx answer.
     */
    private suspend fun authorized(build: (String) -> Request): String {
        var token = validIdToken(forceRefresh = false)
        var refreshed = false
        var lastError: Exception? = null
        repeat(MAX_RETRIES + 1) { attempt ->
            try {
                val (code, text) = httpClient.newCall(build(token)).execute().use { it.code to it.body?.string().orEmpty() }
                when {
                    code in 200..299 -> return text
                    code == 401 && !refreshed -> {
                        refreshed = true
                        token = validIdToken(forceRefresh = true)
                        return authorizedAfterRefresh(build, token)
                    }
                    code == 401 -> throw ShinyWallApiException(ShinyWallApiException.Reason.NotAuthenticated, "HTTP 401", code)
                    code == 429 || code in 500..599 -> lastError =
                        ShinyWallApiException(ShinyWallApiException.Reason.Http, "HTTP $code", code)
                    else -> throw ShinyWallApiException(ShinyWallApiException.Reason.Http, "HTTP $code", code)
                }
            } catch (e: java.io.IOException) {
                lastError = e
            }
            if (attempt < MAX_RETRIES) delay(retryBackoffMs shl attempt)
        }
        throw lastError ?: ShinyWallApiException(ShinyWallApiException.Reason.Network, "no answer")
    }

    private suspend fun authorizedAfterRefresh(build: (String) -> Request, token: String): String {
        val (code, text) = httpClient.newCall(build(token)).execute().use { it.code to it.body?.string().orEmpty() }
        if (code in 200..299) return text
        if (code == 401) throw ShinyWallApiException(ShinyWallApiException.Reason.NotAuthenticated, "HTTP 401", code)
        throw ShinyWallApiException(ShinyWallApiException.Reason.Http, "HTTP $code", code)
    }

    private suspend fun validIdToken(forceRefresh: Boolean): String = refreshMutex.withLock {
        val session = tokenStore.session()
            ?: throw ShinyWallApiException(ShinyWallApiException.Reason.NotAuthenticated, "not signed in")
        if (!forceRefresh && session.idToken.isNotBlank() && clock() < session.idTokenExpiresAtMs - EXPIRY_BUFFER_MS) {
            return@withLock session.idToken
        }
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", session.refreshToken)
            .build()
        val request = Request.Builder()
            .url("$tokenBase/token".toHttpUrl().newBuilder().addQueryParameter("key", FIREBASE_WEB_API_KEY).build())
            .post(body)
            .build()
        val (code, text) = httpClient.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        if (code !in 200..299) {
            Log.w(TAG, "Token refresh failed: HTTP $code ${firebaseErrorMessage(text) ?: ""}")
            if (code in 400..403) {
                throw ShinyWallApiException(ShinyWallApiException.Reason.NotAuthenticated, "refresh refused", code)
            }
            throw ShinyWallApiException(ShinyWallApiException.Reason.Http, "refresh HTTP $code", code)
        }
        val obj = json.parseToJsonElement(text) as? JsonObject
        val idToken = obj?.string("id_token")
        val refreshToken = obj?.string("refresh_token") ?: session.refreshToken
        if (idToken.isNullOrBlank()) {
            throw ShinyWallApiException(ShinyWallApiException.Reason.UnexpectedResponse, "refresh without id_token")
        }
        val expiresIn = obj?.string("expires_in")?.toLongOrNull() ?: 3600L
        tokenStore.updateTokens(idToken, refreshToken, expiresIn)
        idToken
    }

    private fun bumpBackoff(key: String) {
        val failures = (authFailures[key] ?: 0) + 1
        authFailures[key] = failures
        val backoffMs = (1000L * (1L shl (failures - 1).coerceAtMost(8))).coerceAtMost(300_000L)
        authBackoffUntil[key] = clock() + backoffMs
    }

    private fun firebaseErrorMessage(text: String): String? = runCatching {
        val error = (json.parseToJsonElement(text) as? JsonObject)?.get("error") as? JsonObject
        error?.string("message")?.substringBefore(" : ")?.trim()
    }.getOrNull()

    private fun mapAuthError(code: Int, message: String?): ShinyWallAuthResult.Error.Reason = when (message) {
        "INVALID_LOGIN_CREDENTIALS", "EMAIL_NOT_FOUND", "INVALID_PASSWORD", "INVALID_EMAIL", "MISSING_PASSWORD" ->
            ShinyWallAuthResult.Error.Reason.InvalidCredentials
        "USER_DISABLED" -> ShinyWallAuthResult.Error.Reason.AccountDisabled
        "TOO_MANY_ATTEMPTS_TRY_LATER" -> ShinyWallAuthResult.Error.Reason.Throttled
        else -> if (code == 429) ShinyWallAuthResult.Error.Reason.Throttled else ShinyWallAuthResult.Error.Reason.HttpFailure
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
