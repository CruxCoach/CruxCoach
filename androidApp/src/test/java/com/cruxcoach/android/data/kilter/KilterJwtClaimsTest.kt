package com.cruxcoach.android.data.kilter

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Unit tests for the pure [parseJwtClaimsPure] helper extracted from
 * [KilterApiClient.parseJwtClaims].
 *
 * Verifies the happy-path claim extraction (`sub`, `preferred_username`,
 * etc.) and the defensive returns-empty-map paths the in-class wrapper
 * relies on. The wrapper-side `try { … } catch` is a separate concern
 * (see error-handling/unhandled-errors/011 — silent swallow of
 * malformed-JWT exceptions is a known issue, not codified here).
 */
class KilterJwtClaimsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── helpers ─────────────────────────────────────────────────────

    /** Build a valid-shape JWT with the given JSON payload. */
    private fun jwtWith(payloadJson: String): String {
        val header = b64url("""{"alg":"none","typ":"JWT"}""")
        val payload = b64url(payloadJson)
        val sig = b64url("garbage-not-verified")
        return "$header.$payload.$sig"
    }

    private fun b64url(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    // ── happy paths ─────────────────────────────────────────────────

    @Test
    fun extracts_sub_and_preferred_username() {
        val jwt = jwtWith(
            """{"sub":"user-uuid-123","preferred_username":"alice@example.com","iat":1714000000}""",
        )
        val claims = parseJwtClaimsPure(jwt, json)
        assertEquals("user-uuid-123", claims["sub"])
        assertEquals("alice@example.com", claims["preferred_username"])
    }

    @Test
    fun coerces_numeric_claims_to_their_unquoted_string_content() {
        // JsonPrimitive.content returns the raw representation — for
        // numeric literals that's the digits, no quotes. The API client
        // only reads `sub` + `preferred_username` (both strings), but
        // other claims still need to round-trip through the Map cleanly.
        val jwt = jwtWith("""{"iat":1714000000,"exp":1714003600}""")
        val claims = parseJwtClaimsPure(jwt, json)
        assertEquals("1714000000", claims["iat"])
        assertEquals("1714003600", claims["exp"])
    }

    @Test
    fun coerces_boolean_claims_to_string() {
        val jwt = jwtWith("""{"email_verified":true,"tos_accepted":false}""")
        val claims = parseJwtClaimsPure(jwt, json)
        assertEquals("true", claims["email_verified"])
        assertEquals("false", claims["tos_accepted"])
    }

    @Test
    fun nested_objects_collapse_to_empty_string() {
        // Production reads only top-level primitive claims. Nested
        // objects/arrays are flattened to "" so the API client's
        // `claims["sub"]` lookup never returns a stringified JSON
        // blob by accident.
        val jwt = jwtWith("""{"sub":"u1","realm_access":{"roles":["climber"]}}""")
        val claims = parseJwtClaimsPure(jwt, json)
        assertEquals("u1", claims["sub"])
        assertEquals("", claims["realm_access"])
    }

    @Test
    fun handles_url_safe_base64_chars_in_payload() {
        // Payloads with `-` / `_` (URL-safe Base64) must round-trip.
        // Using a name with a `+`-equivalent chunk forces these chars
        // into the encoded payload.
        val jwt = jwtWith("""{"sub":"abc?def","preferred_username":"user~with*chars"}""")
        val claims = parseJwtClaimsPure(jwt, json)
        assertEquals("abc?def", claims["sub"])
        assertEquals("user~with*chars", claims["preferred_username"])
    }

    // ── defensive paths (return empty map) ──────────────────────────

    @Test
    fun returns_empty_for_jwt_with_wrong_part_count() {
        assertTrue(parseJwtClaimsPure("only.two", json).isEmpty())
        assertTrue(parseJwtClaimsPure("a.b.c.d", json).isEmpty())
        assertTrue(parseJwtClaimsPure("", json).isEmpty())
    }

    @Test
    fun returns_empty_for_payload_that_is_not_a_json_object() {
        // A JWT whose payload base64-decodes to a JSON array or
        // primitive (rather than an object) shouldn't crash — production
        // catches the cast and returns an empty map.
        val jwt = jwtWith("""["not","an","object"]""")
        val claims = parseJwtClaimsPure(jwt, json)
        assertTrue("array payload should produce empty claims, got=$claims", claims.isEmpty())
    }
}
