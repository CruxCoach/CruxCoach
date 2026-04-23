package com.cruxcoach.android.nostr.backup

import kotlinx.serialization.json.Json
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
}
