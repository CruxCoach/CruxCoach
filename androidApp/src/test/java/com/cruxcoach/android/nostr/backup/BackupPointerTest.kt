package com.cruxcoach.android.nostr.backup

import kotlinx.serialization.json.Json
import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPointerTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `round-trip preserves all fields`() {
        val pointer = BackupPointer(
            sha256 = "abcdef1234567890".padEnd(64, '0'),
            size = 94_000,
            servers = listOf("https://blossom.primal.net", "https://blossom.nostr.build"),
            previousSha256 = "f".repeat(64),
            updatedAt = 1_717_000_000,
            deviceId = "a1b2c3d4-1111-2222-3333-444455556666",
            categories = listOf("ASCENTS", "SESSIONS"),
        )
        val serialized = json.encodeToString(BackupPointer.serializer(), pointer)
        val parsed = json.decodeFromString(BackupPointer.serializer(), serialized)
        assertEquals(pointer, parsed)
    }

    @Test
    fun `unknown future fields are tolerated`() {
        val jsonWithExtra = """
            {
              "version": 1,
              "schema_version": 2,
              "sha256": "aaaa",
              "size": 100,
              "servers": ["https://a.example.com"],
              "updated_at": 1,
              "device_id": "x",
              "categories": ["A"],
              "future_field": "ignored"
            }
        """.trimIndent()
        val parsed = json.decodeFromString(BackupPointer.serializer(), jsonWithExtra)
        assertEquals("aaaa", parsed.sha256)
        assertNull(parsed.previousSha256)
    }

    @Test
    fun `snake_case field names on the wire`() {
        val pointer = BackupPointer(
            sha256 = "deadbeef",
            size = 1,
            servers = listOf("https://a"),
            updatedAt = 1,
            deviceId = "dev",
            categories = emptyList(),
        )
        val wire = json.encodeToString(BackupPointer.serializer(), pointer)
        // These are the names the rest of the Nostr ecosystem expects for
        // Kind 30078 tool-specific payloads.
        assertTrue("expected snake_case updated_at", wire.contains("\"updated_at\""))
        assertTrue("expected snake_case device_id", wire.contains("\"device_id\""))
        assertTrue("expected schema_version alongside payload version", wire.contains("\"schema_version\""))
    }

    @Test
    fun `previous_sha256 is optional and serializes as null`() {
        val pointer = BackupPointer(
            sha256 = "a",
            size = 1,
            servers = emptyList(),
            updatedAt = 1,
            deviceId = "d",
            categories = emptyList(),
            previousSha256 = null,
        )
        val wire = json.encodeToString(BackupPointer.serializer(), pointer)
        val roundtrip = json.decodeFromString(BackupPointer.serializer(), wire)
        assertNull(roundtrip.previousSha256)
    }

    @Test
    fun `default version is pinned to pointer v1`() {
        val minimal = BackupPointer(
            sha256 = "a",
            size = 1,
            servers = emptyList(),
            updatedAt = 1,
            deviceId = "d",
            categories = emptyList(),
        )
        assertEquals(BackupPointer.POINTER_VERSION, minimal.version)
        assertEquals(BackupPointer.PAYLOAD_SCHEMA_VERSION, minimal.schema_version)
    }

    // ── validateOrThrow ──────────────────────────────────────────────────────
    // Each branch in the require() chain is exercised here so a future
    // refactor that loses one of them can't ship silently. Constants
    // (MAX_BLOB_SIZE_BYTES, MAX_SERVERS, MAX_CLOCK_SKEW_SEC, etc.) are
    // private to BackupPointer; tests pin to known-bad values just past
    // the documented limits rather than reading them, so a constant tweak
    // would surface as a test failure that needs an explicit test update.

    private fun validPointer(
        version: Int = BackupPointer.POINTER_VERSION,
        schemaVersion: Int = BackupPointer.PAYLOAD_SCHEMA_VERSION,
        sha256: String = "a".repeat(64),
        size: Long = 1024,
        servers: List<String> = listOf("https://blossom.primal.net"),
        previousSha256: String? = null,
        updatedAt: Long = 1_700_000_000L,
        deviceId: String = "device-1",
        categories: List<String> = listOf("ASCENTS"),
    ) = BackupPointer(
        version = version,
        schema_version = schemaVersion,
        sha256 = sha256,
        size = size,
        servers = servers,
        previousSha256 = previousSha256,
        updatedAt = updatedAt,
        deviceId = deviceId,
        categories = categories,
    )

    @Test
    fun `validateOrThrow accepts a well-formed pointer`() {
        validPointer().validateOrThrow()  // must not throw
    }

    @Test
    fun `validateOrThrow rejects unknown future version`() {
        assertFailsWith<IllegalArgumentException> {
            validPointer(version = BackupPointer.POINTER_VERSION + 1).validateOrThrow()
        }
    }

    @Test
    fun `validateOrThrow rejects zero version`() {
        assertFailsWith<IllegalArgumentException> {
            validPointer(version = 0).validateOrThrow()
        }
    }

    @Test
    fun `validateOrThrow rejects unknown future schema_version`() {
        assertFailsWith<IllegalArgumentException> {
            validPointer(schemaVersion = BackupPointer.PAYLOAD_SCHEMA_VERSION + 1).validateOrThrow()
        }
    }

    @Test
    fun `validateOrThrow rejects sha256 wrong length`() {
        assertFailsWith<IllegalArgumentException> {
            validPointer(sha256 = "abc123").validateOrThrow()
        }
    }

    @Test
    fun `validateOrThrow rejects sha256 with non-hex chars`() {
        assertFailsWith<IllegalArgumentException> {
            validPointer(sha256 = "Z".repeat(64)).validateOrThrow()
        }
    }

    @Test
    fun `validateOrThrow rejects sha256 with uppercase hex`() {
        // Pointer hex MUST be lowercase — Nostr / Blossom convention; an
        // uppercase variant routes to a different blob URL.
        assertFailsWith<IllegalArgumentException> {
            validPointer(sha256 = "A".repeat(64)).validateOrThrow()
        }
    }

    @Test
    fun `validateOrThrow rejects zero or negative size`() {
        assertFailsWith<IllegalArgumentException> {
            validPointer(size = 0).validateOrThrow()
        }
        assertFailsWith<IllegalArgumentException> {
            validPointer(size = -1).validateOrThrow()
        }
    }

    @Test
    fun `validateOrThrow rejects size beyond max`() {
        // Max is 64 MB. 65 MB is unambiguously past it.
        assertFailsWith<IllegalArgumentException> {
            validPointer(size = 65L * 1024 * 1024).validateOrThrow()
        }
    }

    @Test
    fun `validateOrThrow rejects empty server list`() {
        assertFailsWith<IllegalArgumentException> {
            validPointer(servers = emptyList()).validateOrThrow()
        }
    }

    @Test
    fun `validateOrThrow rejects oversized server list`() {
        // Cap is 16; 17 is the smallest illegal value.
        assertFailsWith<IllegalArgumentException> {
            validPointer(servers = (0..16).map { "https://server$it.example.com" }).validateOrThrow()
        }
    }

    @Test
    fun `validateOrThrow rejects far-future updatedAt`() {
        val far = System.currentTimeMillis() / 1000 + 7200  // +2h, well past 60s skew
        assertFailsWith<IllegalArgumentException> {
            validPointer(updatedAt = far).validateOrThrow()
        }
    }

    @Test
    fun `validateOrThrow rejects negative updatedAt`() {
        assertFailsWith<IllegalArgumentException> {
            validPointer(updatedAt = -1).validateOrThrow()
        }
    }

    @Test
    fun `validateOrThrow rejects oversized deviceId`() {
        // MAX_DEVICE_ID_LEN = 64.
        assertFailsWith<IllegalArgumentException> {
            validPointer(deviceId = "x".repeat(65)).validateOrThrow()
        }
    }

    @Test
    fun `validateOrThrow rejects oversized categories list`() {
        // MAX_CATEGORIES = 32. 33 entries crosses.
        assertFailsWith<IllegalArgumentException> {
            validPointer(categories = (0..32).map { "C$it" }).validateOrThrow()
        }
    }
}
