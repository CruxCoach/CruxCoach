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
 * Lightning-address (`lud16`) probe. Best-effort: hits the LNURL-pay
 * well-known endpoint and confirms the response carries a `callback`
 * URL — the minimum signal that the address actually resolves.
 *
 * Mirrors the URL assembly in Amethyst's `LightningAddressResolver`
 * (`amethyst/.../service/lnurl/LightningAddressResolver.kt`). Errors
 * are non-fatal in the editor flow — many custodial wallets block
 * the public well-known endpoint, and we still want users to save
 * those addresses with an amber warning.
 */
@Singleton
class LnurlVerifier @Inject constructor(
    @Named("nostr") private val okHttpClient: OkHttpClient,
) {

    sealed class State {
        data object Idle : State()
        data object Verifying : State()
        /** 200 + JSON containing `callback`. */
        data object Verified : State()
        /** Endpoint unreachable, non-2xx, or response missing `callback`. */
        data class Unreachable(val reason: String) : State()
    }

    suspend fun verify(lud16: String): State {
        val (local, domain) = parseAddress(lud16) ?: return State.Unreachable("invalid format")
        val url = "https://$domain/.well-known/lnurlp/$local"
        return withContext(Dispatchers.IO) {
            try {
                // followRedirects = false: a hostile LNURL host could
                // 302-redirect to an attacker-chosen URL — including
                // an HTTP downgrade to a private LAN host — and the
                // shared OkHttp client follows redirects by default.
                // The well-known endpoint is the authoritative source;
                // we don't second-guess via redirect chasing.
                val client = okHttpClient.newBuilder().followRedirects(false).build()
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use State.Unreachable("HTTP ${response.code}")
                    }
                    // Cap the body read at MAX_BODY_BYTES — same rationale
                    // as Nip05Verifier: bounds memory pressure if the
                    // server streams an unbounded payload.
                    val body = response.peekBody(MAX_BODY_BYTES).string()
                    if (body.isBlank()) return@use State.Unreachable("empty response")
                    if (hasCallback(body)) State.Verified
                    else State.Unreachable("no callback")
                }
            } catch (e: Exception) {
                Log.w(TAG, "LNURL verify failed (${e.javaClass.simpleName})")
                State.Unreachable(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    companion object {
        private const val TAG = "LnurlVerifier"

        /** Hard ceiling on body bytes the verifier will buffer. See
         *  [Nip05Verifier.MAX_BODY_BYTES] for rationale — same trade-off
         *  applies. LNURL-pay responses are typically <2 KB. */
        internal const val MAX_BODY_BYTES: Long = 64L * 1024L

        /** ASCII allowlist matching NIP-05's local-part shape. Letters
         *  in either case are accepted because LNURL spec doesn't
         *  mandate lowercase (a few wallets are case-sensitive). */
        private val LOCAL_CHARSET = Regex("[A-Za-z0-9._\\-]+")

        internal fun parseAddress(lud16: String): Pair<String, String>? {
            val trimmed = lud16.trim()
            val parts = trimmed.split("@", limit = 2)
            if (parts.size != 2) return null
            val (local, domain) = parts
            if (local.isBlank() || domain.isBlank()) return null
            // Char-allowlist on local-part (matches NIP-05's local
            // charset, modulo case). LNURL spec doesn't mandate this
            // explicitly but real-world Lightning addresses use the
            // same DNS-namelike alphabet; rejecting anything else
            // keeps path-injection (`evil/../admin`, encoded chars)
            // out of the URL we interpolate `local` into.
            if (!local.matches(LOCAL_CHARSET)) return null
            if (!domain.contains('.')) return null
            return local to domain.lowercase()
        }

        /** kotlinx-serialization-json so the parser works on plain JVM
         *  unit tests, not just on-device (Android's `org.json` is a stub
         *  on the JVM test classpath). */
        internal fun hasCallback(body: String): Boolean {
            return try {
                val root = JSON.parseToJsonElement(body).jsonObject
                root["callback"]?.jsonPrimitive?.contentOrNull
                    ?.isNotBlank() == true
            } catch (e: Exception) {
                false
            }
        }

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
