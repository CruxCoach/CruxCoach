package com.cruxcoach.android.data.shinywall

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShinyWallParsersTest {

    private fun fields(json: String): Map<String, Any?> =
        FirestoreValues.decodeFields(Json.parseToJsonElement(json))

    @Test
    fun decodes_every_firestore_value_type() {
        val f = fields(
            """{
              "s":{"stringValue":"x"},"i":{"integerValue":"42"},"d":{"doubleValue":2.5},
              "b":{"booleanValue":true},"n":{"nullValue":null},"t":{"timestampValue":"2026-11-01T17:34:25.723Z"},
              "m":{"mapValue":{"fields":{"a":{"integerValue":"1"}}}},
              "a":{"arrayValue":{"values":[{"stringValue":"p"},{"integerValue":"2"}]}},
              "e":{"arrayValue":{}},"u":{"futureValue":"?"}
            }"""
        )
        assertEquals("x", f["s"])
        assertEquals(42L, f["i"])
        assertEquals(2.5, f["d"])
        assertEquals(true, f["b"])
        assertNull(f["n"])
        assertEquals("2026-11-01T17:34:25.723Z", f["t"])
        assertEquals(mapOf("a" to 1L), f["m"])
        assertEquals(listOf("p", 2L), f["a"])
        assertEquals(emptyList<Any?>(), f["e"])
        assertNull(f["u"])
    }

    @Test
    fun document_name_and_id() {
        val doc = Json.parseToJsonElement(
            """{"name":"projects/p/databases/(default)/documents/user_activity/u1/history/h42",
                "fields":{"routeId":{"stringValue":"r1"}},"createTime":"2026-10-01T10:00:00Z"}"""
        )
        val (name, f) = FirestoreValues.document(doc)!!
        assertEquals("h42", FirestoreValues.documentId(name))
        assertEquals("r1", f["routeId"])
        assertEquals("2026-10-01T10:00:00Z", FirestoreValues.createTime(doc))
        assertNull(FirestoreValues.document(Json.parseToJsonElement("""{"readTime":"x"}""")))
    }

    @Test
    fun history_entry_reads_all_fields() {
        val e = ShinyWallParsers.historyEntry(
            "h1",
            fields(
                """{"routeId":{"stringValue":"r1"},"wallId":{"stringValue":"w9"},"standard":{"stringValue":"ST5"},
                    "grade":{"stringValue":" 6A+ "},"angle":{"integerValue":"40"},"sent":{"integerValue":"3"},
                    "type":{"stringValue":"rating"},"timestamp":{"timestampValue":"2026-10-30T20:08:42.131553Z"}}"""
            ),
        )!!
        assertEquals(ShinyWallHistoryEntry("h1", "r1", "w9", "st5", "6A+", 40, 3, "rating", "2026-10-30T20:08:42.131553Z"), e)
        assertFalse(ShinyWallParsers.isCreation(e))
    }

    @Test
    fun history_entry_tolerates_odd_shapes() {
        assertNull(ShinyWallParsers.historyEntry("h", fields("""{"angle":{"integerValue":"40"}}""")))
        val e = ShinyWallParsers.historyEntry(
            "h2",
            fields("""{"routeId":{"stringValue":"r"},"angle":{"stringValue":"35"},"sent":{"booleanValue":true}}"""),
            createTime = "2026-01-01T00:00:00Z",
        )!!
        assertEquals(35, e.angle)
        assertEquals(ShinyWallParsers.SENT_UNKNOWN_ATTEMPTS, e.sent)
        assertEquals("2026-01-01T00:00:00Z", e.timestamp)
        assertNull(e.standard)
        val created = ShinyWallParsers.historyEntry("h3", fields("""{"routeId":{"stringValue":"r"}}"""))!!
        assertTrue(ShinyWallParsers.isCreation(created))
        val createdTyped = created.copy(sent = 2, type = "Creada")
        assertTrue(ShinyWallParsers.isCreation(createdTyped))
        val project = ShinyWallParsers.historyEntry("h4", fields("""{"routeId":{"stringValue":"r"},"sent":{"booleanValue":false}}"""))!!
        assertEquals(-1, project.sent)
    }

    @Test
    fun history_entry_takes_the_variant_from_the_wall_id() {
        // The shape real entries have: `standard` is a boolean, the variant is the wallId prefix.
        val real = ShinyWallParsers.historyEntry(
            "h1",
            fields(
                """{"routeId":{"stringValue":"r1"},"angle":{"integerValue":"40"},"sent":{"integerValue":"1"},
                    "grade":{"stringValue":"6B+"},"timestamp":{"timestampValue":"2026-11-02T13:20:11.123Z"},
                    "wallId":{"stringValue":"STP_Wall"},"standard":{"booleanValue":true},"type":{"stringValue":"route"}}"""
            ),
        )!!
        assertEquals(ShinyWallHistoryEntry("h1", "r1", "STP_Wall", "st1", "6B+", 40, 1, "route", "2026-11-02T13:20:11.123Z"), real)
        assertFalse(ShinyWallParsers.isCreation(real))

        fun standard(standard: String, wallId: String) = ShinyWallParsers.historyEntry(
            "h", fields("""{"routeId":{"stringValue":"r"},"wallId":{"stringValue":"$wallId"},"standard":$standard}"""),
        )!!.standard
        val yes = """{"booleanValue":true}"""
        assertEquals("st0", standard(yes, "ST0_Gym"))
        assertEquals("st2", standard(yes, "STM_Gym"))
        assertEquals("st4", standard(yes, "STF_Gym"))
        assertEquals("st5", standard(yes, "STMF_Gym"))
        // A code in `standard` wins over the prefix.
        assertEquals("st5", standard("""{"stringValue":"ST5"}""", "STP_Gym"))
        // Custom walls stay unresolved, so the import skips them.
        assertEquals("true", standard(yes, "CTM_Home"))
        assertEquals("true", standard(yes, "HomeWall"))
        assertEquals("false", standard("""{"booleanValue":false}""", "STP_Gym"))
    }

    @Test
    fun route_refs_split_angle_suffix_only_when_it_is_an_angle() {
        assertEquals(ShinyWallRouteRef("AbC123", 40), ShinyWallParsers.routeRef("AbC123-40"))
        assertEquals(ShinyWallRouteRef("AbC123", null), ShinyWallParsers.routeRef("AbC123"))
        assertEquals(ShinyWallRouteRef("AbC123-99", null), ShinyWallParsers.routeRef("AbC123-99"))
        assertEquals(ShinyWallRouteRef("r7", 30), ShinyWallParsers.routeRef(mapOf("routeId" to "r7", "angle" to 30L)))
        assertNull(ShinyWallParsers.routeRef(" "))
        assertNull(ShinyWallParsers.routeRef(5L))
        assertEquals(listOf("a", "b"), ShinyWallParsers.routeRefs(listOf("a-20", "a-40", "b", 3L)).map { it.routeId })
    }

    @Test
    fun custom_lists_accept_flat_nested_and_object_shapes() {
        assertEquals(
            mapOf("Warm-up" to listOf("a", "b")),
            ShinyWallParsers.customLists(mapOf("Warm-up" to listOf("a", "b-30"))).mapValues { it.value.map { r -> r.routeId } },
        )
        val nested = ShinyWallParsers.customLists(
            mapOf(
                "Stds" to mapOf("Power" to listOf("p1", "p2"), "Empty" to emptyList<String>()),
                "wall42" to mapOf("Power" to listOf("p2", "p3")),
            )
        )
        assertEquals(mapOf("Power" to listOf("p1", "p2", "p3")), nested.mapValues { it.value.map { r -> r.routeId } })
        val objects = ShinyWallParsers.customLists(mapOf("id9" to mapOf("name" to "Tuesday", "routes" to listOf("t1"))))
        assertEquals(mapOf("Tuesday" to listOf("t1")), objects.mapValues { it.value.map { r -> r.routeId } })
        assertEquals(emptyMap(), ShinyWallParsers.customLists(listOf("x")))
    }

    @Test
    fun profile_and_own_routes() {
        val p = ShinyWallParsers.profile(
            fields(
                """{"username":{"stringValue":"climber"},"email":{"stringValue":"secret@example.com"},
                    "favoriteRoutes":{"arrayValue":{"values":[{"stringValue":"f1"},{"stringValue":"f2-45"}]}},
                    "customLists":{"mapValue":{"fields":{"Stds":{"mapValue":{"fields":{
                        "Board day":{"arrayValue":{"values":[{"stringValue":"c1"}]}}}}}}}}}"""
            )
        )
        assertEquals("climber", p.username)
        assertEquals(listOf("f1", "f2"), p.favoriteRoutes.map { it.routeId })
        assertEquals(setOf("Board day"), p.customLists.keys)
        assertNull(ShinyWallParsers.ownRoute("d1", mapOf("id" to "d1", "draft" to true)))
        assertEquals(ShinyWallOwnRoute("r1", "st5"), ShinyWallParsers.ownRoute("r1", mapOf("originSt" to "ST5")))
    }

    @Test
    fun rating_drops_empty_votes() {
        val r = ShinyWallParsers.rating("r1", 40, mapOf("grade" to "6B", "punctuation" to 0L, "comment" to "  "))
        assertEquals(ShinyWallRating("r1", 40, "6B", null, null), r)
        assertEquals(4.0, ShinyWallParsers.rating("r1", 40, mapOf("punctuation" to 4L)).punctuation)
    }

    @Test
    fun send_shape_covers_every_attempt_code() {
        assertEquals(0L to 1L, ShinyWallSyncEngine.sendShape(0))
        assertEquals(0L to 1L, ShinyWallSyncEngine.sendShape(1))
        assertEquals(1L to 2L, ShinyWallSyncEngine.sendShape(2))
        assertEquals(1L to 9L, ShinyWallSyncEngine.sendShape(9))
        assertEquals(1L to 10L, ShinyWallSyncEngine.sendShape(10))
        assertEquals(1L to 10L, ShinyWallSyncEngine.sendShape(25))
        assertEquals(1L to 1L, ShinyWallSyncEngine.sendShape(ShinyWallParsers.SENT_UNKNOWN_ATTEMPTS))
        assertNull(ShinyWallSyncEngine.sendShape(-1))
        assertNull(ShinyWallSyncEngine.sendShape(-5))
    }

    @Test
    fun climbed_at_is_local_wall_clock_in_seconds() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        assertEquals("2026-11-01T18:34:25", ShinyWallSyncEngine.localClimbedAt("2026-11-01T17:34:25.723Z", zone))
        assertEquals("2026-07-01T14:00:00", ShinyWallSyncEngine.localClimbedAt("2026-07-01T12:00:00Z", zone))
        assertNull(ShinyWallSyncEngine.localClimbedAt("yesterday", zone))
        assertNull(ShinyWallSyncEngine.localClimbedAt(null, zone))
    }
}
