package com.cruxcoach.android.nostr.profile

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * NIP-05 DNS-identifier verifier. Given an `<local>@<domain>` string and
 * the user's expected pubkey, fetches the domain's well-known endpoint,
 * looks up the local part, and reports whether it maps to the same
 * pubkey.
 *
 * The shape and error states mirror Amethyst's `Nip05Client` /
 * `Nip05VerifState` (`com.vitorpamplona.quartz.nip05DnsIdentifiers`)
 * including the **must-not-follow-redirects** rule from the NIP-05
 * spec — a verifier that silently follows a redirect to attacker-
 * controlled JSON is a forgery vector.
 */
@Singleton
class Nip05Verifier @Inject constructor(
    @Named("nostr") private val okHttpClient: OkHttpClient,
) {

    /** Verification outcome carried by the editor's UI state. */
    sealed class State {
        /** No verification attempted yet (empty field, just opened). */
        data object Idle : State()
        /** Fetch in flight. */
        data object Verifying : State()
        /** `names[<local>]` matches the expected pubkey. */
        data object Verified : State()
        /** Endpoint reachable but the pubkey doesn't match (or local
         *  isn't in the names map). [foundPubkey] is what the server
         *  returned, null when the local part wasn't listed at all. */
        data class Mismatch(val foundPubkey: String?) : State()
        /** Network/parse/HTTP error — verification couldn't complete.
         *  Editor allows save anyway with an amber warning. */
        data class Unreachable(val reason: String) : State()
    }

    /**
     * Verify [nip05] against [expectedPubkey] (the user's own pubkey,
     * 64-char lowercase hex).
     *
     * Caller is expected to debounce — this method does no rate-limiting
     * of its own. Safe to call repeatedly; each invocation is a fresh
     * fetch.
     */
    suspend fun verify(nip05: String, expectedPubkey: String): State {
        val (local, domain) = parseAddress(nip05) ?: return State.Mismatch(null)
        val url = "https://$domain/.well-known/nostr.json?name=$local"
        return withContext(Dispatchers.IO) {
            try {
                // followRedirects = false per NIP-05 spec — the server's
                // own response is the only authoritative source.
                val client = okHttpClient.newBuilder().followRedirects(false).build()
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use State.Unreachable("HTTP ${response.code}")
                    }
                    val body = response.body?.string()
                        ?: return@use State.Unreachable("empty response")
                    val foundPubkey = parsePubkey(body, local)
                    when {
                        foundPubkey == null -> State.Mismatch(null)
                        foundPubkey.equals(expectedPubkey, ignoreCase = true) -> State.Verified
                        else -> State.Mismatch(foundPubkey)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "NIP-05 verify failed for $url", e)
                State.Unreachable(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    companion object {
        private const val TAG = "Nip05Verifier"

        /** Split `<local>@<domain>` into a (local, lowercased-domain)
         *  pair. Returns null if the address is malformed or contains
         *  unexpected characters that NIP-05 forbids. */
        internal fun parseAddress(nip05: String): Pair<String, String>? {
            val trimmed = nip05.trim().lowercase()
            val parts = trimmed.split("@", limit = 2)
            if (parts.size != 2) return null
            val (local, domain) = parts
            if (local.isBlank() || domain.isBlank()) return null
            // NIP-05 spec: local-part = a-z 0-9 - _ . ; domain = standard.
            if (!local.matches(Regex("[a-z0-9\\-_.]+"))) return null
            if (!domain.contains('.')) return null
            return local to domain
        }

        /** Parse the well-known JSON body, return the pubkey under
         *  `names[local]` or null if absent / malformed. Uses
         *  kotlinx-serialization-json (not `org.json.JSONObject`) so the
         *  parser works on plain JVM unit tests, not just on-device. */
        internal fun parsePubkey(body: String, local: String): String? {
            return try {
                val root = JSON.parseToJsonElement(body).jsonObject
                val names = root["names"]?.jsonObject ?: return null
                names[local]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
