package com.cruxcoach.communityclient

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where a community lives: `https://host` for HTTP and `wss://host` for the relay. */
data class CommunityEndpoint(val host: String, val secure: Boolean = true) {
    val httpOrigin: String get() = (if (secure) "https://" else "http://") + host
    val relayUrl: String get() = (if (secure) "wss://" else "ws://") + host

    fun httpUrl(path: String): String = httpOrigin + path

    companion object {
        /** Parses `wss://host[:port]`, `https://host[:port]` or a bare host; null for anything else. */
        fun parse(value: String): CommunityEndpoint? {
            val trimmed = value.trim().trimEnd('/')
            val scheme = trimmed.substringBefore("://", "")
            val rest = if (scheme.isEmpty()) trimmed else trimmed.substringAfter("://")
            val secure = when (scheme) {
                "", "https", "wss" -> true
                "http", "ws" -> false
                else -> return null
            }
            if (!isValidHost(rest)) return null
            return CommunityEndpoint(rest.lowercase(), secure)
        }
    }
}

/** A community invite: the relay host and the invite code. */
data class CommunityInvite(val endpoint: CommunityEndpoint, val code: String) {
    companion object {
        /**
         * Parses `https://<host>/invite/<code>` as the Buzz relay mints it, the same
         * link pasted with whitespace around it, or `buzz://join?relay=<wss://host>&code=<code>`.
         * Plain `http://` is accepted only for a local test server ([allowInsecure]).
         * Credentials, query strings on https links, fragments and other paths are refused.
         */
        fun parse(text: String, allowInsecure: Boolean = false): CommunityInvite? {
            val link = text.trim()
            if (link.any { it.isWhitespace() } || '#' in link) return null
            if (link.startsWith("buzz://join?")) return parseAppLink(link, allowInsecure)
            val scheme = link.substringBefore("://", "")
            val secure = when (scheme) {
                "https" -> true
                "http" -> if (allowInsecure) false else return null
                else -> return null
            }
            val afterScheme = link.substringAfter("://")
            if ('?' in afterScheme) return null
            val host = afterScheme.substringBefore('/')
            if (!isValidHost(host)) return null
            val segments = afterScheme.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
            if (segments.size != 2 || segments[0] != "invite") return null
            val code = segments[1]
            if (!isValidCode(code)) return null
            return CommunityInvite(CommunityEndpoint(host.lowercase(), secure), code)
        }

        private fun parseAppLink(link: String, allowInsecure: Boolean): CommunityInvite? {
            val params = link.substringAfter('?').split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=', "")
                if (key.isEmpty()) null else key to percentDecode(pair.substringAfter('='))
            }.toMap()
            val relay = params["relay"] ?: return null
            val code = params["code"]?.takeIf(::isValidCode) ?: return null
            val endpoint = CommunityEndpoint.parse(relay) ?: return null
            if (!relay.startsWith("wss://") && !(allowInsecure && relay.startsWith("ws://"))) return null
            if (!endpoint.secure && !allowInsecure) return null
            return CommunityInvite(endpoint, code)
        }

        /** v2 codes are `v2.` + unpadded base64url of 32 bytes; older v1 tokens are opaque base64url-ish text. */
        internal fun isValidCode(code: String): Boolean =
            code.length in 8..512 && code.all { it.isLetterOrDigit() && it.code < 128 || it in "-_.~" }
    }
}

/** The community rules (`GET /api/join-policy`). Absent when the operator configured none. */
data class JoinPolicy(
    val termsMarkdown: String?,
    val privacyMarkdown: String?,
    val ageAttestationRequired: Boolean,
    val version: String,
) {
    companion object {
        /** Parses the endpoint body: `{"policy": {...}}`, or `{}` when there is no policy. */
        fun parseResponse(body: String): Result<JoinPolicy?> = runCatching {
            val root = CommunityJson.parseToJsonElement(body).jsonObject
            val policy = root["policy"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return@runCatching null
            JoinPolicy(
                termsMarkdown = policy.stringOrNull("terms_markdown"),
                privacyMarkdown = policy.stringOrNull("privacy_markdown"),
                ageAttestationRequired = policy["age_attestation_required"]?.jsonPrimitive?.booleanOrNull ?: false,
                version = policy.stringOrNull("version") ?: error("join policy without version"),
            )
        }
    }
}

/** Result of `POST /api/invites/claim`. */
sealed interface ClaimResult {
    data class Joined(val alreadyMember: Boolean, val host: String?) : ClaimResult
    data class Refused(val reason: ClaimRefusal, val status: Int, val message: String) : ClaimResult
}

/** Why the relay refused an invite claim, mapped from its error codes. */
enum class ClaimRefusal { EXPIRED, EXHAUSTED, INVALID, POLICY_REQUIRED, RATE_LIMITED, AUTH_FAILED, OTHER }

object InviteApi {
    fun acceptPolicyBody(code: String, policyVersion: String, ageConfirmed: Boolean): String =
        kotlinx.serialization.json.buildJsonObject {
            put("code", kotlinx.serialization.json.JsonPrimitive(code))
            put("policy_version", kotlinx.serialization.json.JsonPrimitive(policyVersion))
            put("age_confirmed", kotlinx.serialization.json.JsonPrimitive(ageConfirmed))
        }.toString()

    fun parseReceipt(body: String): String? = runCatching {
        CommunityJson.parseToJsonElement(body).jsonObject.stringOrNull("receipt")
    }.getOrNull()

    fun claimBody(code: String, policyReceipt: String?): String =
        kotlinx.serialization.json.buildJsonObject {
            put("code", kotlinx.serialization.json.JsonPrimitive(code))
            if (policyReceipt != null) put("policy_receipt", kotlinx.serialization.json.JsonPrimitive(policyReceipt))
        }.toString()

    fun parseClaim(status: Int, body: String): ClaimResult {
        val obj = runCatching { CommunityJson.parseToJsonElement(body).jsonObject }.getOrNull()
        if (status in 200..299 && obj != null) {
            return when (obj.stringOrNull("status")) {
                "joined" -> ClaimResult.Joined(alreadyMember = false, host = obj.stringOrNull("host"))
                "already_member" -> ClaimResult.Joined(alreadyMember = true, host = obj.stringOrNull("host"))
                else -> ClaimResult.Refused(ClaimRefusal.OTHER, status, body.take(200))
            }
        }
        val message = obj?.stringOrNull("error") ?: obj?.stringOrNull("message") ?: body.take(200)
        val reason = when {
            status == 429 -> ClaimRefusal.RATE_LIMITED
            status == 401 -> ClaimRefusal.AUTH_FAILED
            "invite_expired" in message -> ClaimRefusal.EXPIRED
            "invite_exhausted" in message -> ClaimRefusal.EXHAUSTED
            "join_policy_required" in message -> ClaimRefusal.POLICY_REQUIRED
            "invite_invalid" in message -> ClaimRefusal.INVALID
            else -> ClaimRefusal.OTHER
        }
        return ClaimResult.Refused(reason, status, message)
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive }.getOrNull() }?.takeIf { it.isString }?.content

/** DNS name or IPv4 with an optional port; no user info, path or IPv6 literal. */
internal fun isValidHost(value: String): Boolean {
    if (value.isEmpty() || value.length > 260 || '@' in value || '/' in value) return false
    val host = value.substringBefore(':')
    val port = value.substringAfter(':', "")
    if (port.isNotEmpty() && (port.toIntOrNull() ?: 0) !in 1..65535) return false
    if (host.isEmpty() || host.startsWith('.') || host.endsWith('.') || ".." in host) return false
    return host.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '.' }
}

/** Decodes `%XX` escapes of ASCII characters; invite parameters are ASCII. */
private fun percentDecode(value: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        val hex = if (c == '%' && i + 2 < value.length) value.substring(i + 1, i + 3).toIntOrNull(16) else null
        if (hex != null && hex < 128) {
            out.append(hex.toChar())
            i += 3
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}
