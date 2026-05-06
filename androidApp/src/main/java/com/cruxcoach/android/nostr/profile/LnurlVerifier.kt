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
                val request = Request.Builder().url(url).build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use State.Unreachable("HTTP ${response.code}")
                    }
                    val body = response.body?.string()
                        ?: return@use State.Unreachable("empty response")
                    if (hasCallback(body)) State.Verified
                    else State.Unreachable("no callback")
                }
            } catch (e: Exception) {
                Log.w(TAG, "LNURL verify failed for $url", e)
                State.Unreachable(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    companion object {
        private const val TAG = "LnurlVerifier"

        internal fun parseAddress(lud16: String): Pair<String, String>? {
            val trimmed = lud16.trim()
            val parts = trimmed.split("@", limit = 2)
            if (parts.size != 2) return null
            val (local, domain) = parts
            if (local.isBlank() || domain.isBlank()) return null
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
