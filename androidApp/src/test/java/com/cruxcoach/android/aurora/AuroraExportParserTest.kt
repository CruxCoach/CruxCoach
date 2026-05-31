package com.cruxcoach.android.aurora

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parser smoke-tests using small in-line JSON fixtures covering the
 * Aurora email-export shape.
 * Larger end-to-end fixtures are not committed to the repo because
 * real Aurora exports contain user-PII (real names, gym addresses);
 * synthetic minimal JSON proves the schema parses without leaking
 * anything user-attributable.
 */
class AuroraExportParserTest {

    private val parser = AuroraExportParser()

    @Test
    fun parses_minimal_user_only_export() {
        val json = """
        {
            "user": { "username": "test-user" }
        }
        """.trimIndent()
        val result = parser.parse(json).getOrThrow()
        assertEquals("test-user", result.user.username)
        assertTrue(result.ascents.isEmpty())
        assertTrue(result.attempts.isEmpty())
        assertTrue(result.circuits.isEmpty())
        assertTrue(result.climbs.isEmpty())
    }

    @Test
    fun parses_full_export_with_one_of_each_entity() {
        val json = """
        {
            "user": { "username": "test-user", "email_address": "x@y" },
            "ascents": [{
                "climb": "Test Boulder",
                "angle": 40,
                "count": 1,
                "stars": 5,
                "grade": "6A",
                "climbed_at": "2024-01-15 10:30:00",
                "created_at": "2024-01-15T10:30:00Z"
            }],
            "attempts": [{
                "climb": "Test Project",
                "angle": 50,
                "count": 8,
                "climbed_at": "2024-02-01T18:45:00Z",
                "created_at": "2024-02-01T18:45:00Z"
            }],
            "circuits": [{
                "name": "Warm-up",
                "color": "FF0000",
                "created_at": "2024-01-01T00:00:00Z",
                "description": "Easy starters",
                "is_private": false,
                "climbs": ["Test Boulder"]
            }],
            "climbs": [{
                "name": "My Draft",
                "layout": "Kilter Board Original",
                "created_at": "2024-03-01T00:00:00Z",
                "is_draft": true,
                "holds": [{ "x": 100, "y": 200, "role": "start" }]
            }]
        }
        """.trimIndent()
        val result = parser.parse(json).getOrThrow()
        assertEquals(1, result.ascents.size)
        assertEquals("Test Boulder", result.ascents[0].climb)
        assertEquals(5, result.ascents[0].stars)
        assertEquals("6A", result.ascents[0].grade)
        assertEquals(1, result.attempts.size)
        assertEquals(8, result.attempts[0].count)
        assertEquals(1, result.circuits.size)
        assertEquals("FF0000", result.circuits[0].color)
        assertEquals(1, result.climbs.size)
        assertEquals(true, result.climbs[0].is_draft)
    }

    @Test
    fun ignores_unknown_top_level_keys() {
        val json = """
        {
            "user": { "username": "x" },
            "follows": [{"user": "y"}],
            "walls": [{"id": 1}],
            "blocks": [{"id": 2}],
            "agreements": [{"signed_at": "2024-01-01"}],
            "beta_links": [{"climb": "x", "url": "https://example.com"}]
        }
        """.trimIndent()
        val result = parser.parse(json)
        assertTrue(result.isSuccess, "Unknown top-level keys should be silently dropped")
    }

    @Test
    fun fails_on_missing_required_user() {
        val json = """{ "ascents": [] }"""
        assertTrue(parser.parse(json).isFailure)
    }

    @Test
    fun fails_on_garbage_json() {
        assertTrue(parser.parse("not json").isFailure)
        assertTrue(parser.parse("").isFailure)
    }
}
