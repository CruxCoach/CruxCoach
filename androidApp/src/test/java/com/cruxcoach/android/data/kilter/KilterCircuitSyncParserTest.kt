package com.cruxcoach.android.data.kilter

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [KilterCircuitSyncParser] — the fold of Kilter's PowerSync
 * `/sync/stream` ndjson into the user's circuits. The wire shape (bucket names
 * `circuit_buckets["<uuid>"]`, `circuits` + `circuit_climbs` object types,
 * per-op `data` as a JSON *string*, PUT/REMOVE) matches what was captured live
 * 2026-07-11. Lines are built with kotlinx-serialization so escaping is exact.
 */
class KilterCircuitSyncParserTest {

    // ── ndjson line builders (data embedded as a JSON string = raw_data mode) ──

    private fun checkpointLine(vararg buckets: Pair<String, Int>): String =
        buildJsonObject {
            put("checkpoint", buildJsonObject {
                put("buckets", buildJsonArray {
                    for ((name, count) in buckets) add(buildJsonObject {
                        put("bucket", name)
                        put("count", count)
                    })
                })
            })
        }.toString()

    private fun op(op: String, objectType: String, objectId: String, data: Map<String, Any?>?): JsonObject =
        buildJsonObject {
            put("op", op)
            put("object_type", objectType)
            put("object_id", objectId)
            if (data != null) {
                val inner = buildJsonObject {
                    for ((k, v) in data) when (v) {
                        is Int -> put(k, v)
                        is Boolean -> put(k, v)
                        is String -> put(k, v)
                        null -> {}
                        else -> put(k, v.toString())
                    }
                }
                put("data", inner.toString())   // raw_data: row is a JSON string
            }
        }

    private fun dataLine(bucket: String, vararg ops: JsonObject): String =
        buildJsonObject {
            put("data", buildJsonObject {
                put("bucket", bucket)
                put("data", buildJsonArray { ops.forEach { add(it) } })
            })
        }.toString()

    private fun feed(parser: KilterCircuitSyncParser, lines: List<String>): Boolean {
        for (l in lines) if (parser.accept(l)) return true
        return false
    }

    @Test
    fun folds_circuit_with_member_and_stops_before_global_climbs() {
        val bucket = """circuit_buckets["c-1"]"""
        val parser = KilterCircuitSyncParser()
        val stopped = feed(parser, listOf(
            // checkpoint also announces the huge global_climbs bucket — which we
            // must NOT wait for.
            checkpointLine(bucket to 2, "global_climbs[]" to 31_000),
            dataLine(
                bucket,
                op("PUT", "circuits", "c-1", mapOf(
                    "circuit_uuid" to "c-1", "name" to "test", "description" to "d",
                    "color" to "FF0000", "user_uuid" to "u-1", "is_public" to 0,
                    "created_at" to "2024-03-03T00:00:00Z",
                )),
                op("PUT", "circuit_climbs", "c-1.climb-a", mapOf(
                    "circuit_uuid" to "c-1", "climb_uuid" to "climb-a", "sort_order" to 1,
                )),
            ),
        ))

        assertTrue(stopped, "must stop the instant the 2-op circuit bucket drained, before global_climbs")
        val c = parser.circuits().single()
        assertEquals("c-1", c.circuitUuid)
        assertEquals("test", c.name)
        assertEquals("d", c.description)
        assertEquals("FF0000", c.color)
        assertEquals("u-1", c.userUuid)
        assertEquals(listOf("climb-a"), c.memberClimbUuids())
    }

    @Test
    fun put_then_remove_member_nets_to_empty() {
        // Exactly how the live "Liked Climbs" circuit presented: a climb liked
        // then unliked leaves the circuit present with zero members.
        val bucket = """circuit_buckets["liked"]"""
        val parser = KilterCircuitSyncParser()
        feed(parser, listOf(
            checkpointLine(bucket to 3),
            dataLine(
                bucket,
                op("PUT", "circuits", "liked", mapOf("circuit_uuid" to "liked", "name" to "Liked Climbs")),
                op("PUT", "circuit_climbs", "liked.x", mapOf("circuit_uuid" to "liked", "climb_uuid" to "x", "sort_order" to 0)),
                op("REMOVE", "circuit_climbs", "liked.x", null),   // REMOVE has no data → object_id parse
            ),
        ))

        val c = parser.circuits().single()
        assertEquals("Liked Climbs", c.name)
        assertTrue(c.memberClimbUuids().isEmpty(), "added-then-removed climb nets to absent")
    }

    @Test
    fun members_ordered_by_sort_order() {
        val bucket = """circuit_buckets["c"]"""
        val parser = KilterCircuitSyncParser()
        feed(parser, listOf(
            checkpointLine(bucket to 3),
            dataLine(
                bucket,
                op("PUT", "circuits", "c", mapOf("circuit_uuid" to "c", "name" to "N")),
                op("PUT", "circuit_climbs", "c.b", mapOf("circuit_uuid" to "c", "climb_uuid" to "b", "sort_order" to 2)),
                op("PUT", "circuit_climbs", "c.a", mapOf("circuit_uuid" to "c", "climb_uuid" to "a", "sort_order" to 1)),
            ),
        ))
        assertEquals(listOf("a", "b"), parser.circuits().single().memberClimbUuids())
    }

    @Test
    fun remove_circuit_drops_it_and_its_members() {
        val bucket = """circuit_buckets["c"]"""
        val parser = KilterCircuitSyncParser()
        feed(parser, listOf(
            checkpointLine(bucket to 3),
            dataLine(
                bucket,
                op("PUT", "circuits", "c", mapOf("circuit_uuid" to "c", "name" to "N")),
                op("PUT", "circuit_climbs", "c.a", mapOf("circuit_uuid" to "c", "climb_uuid" to "a")),
                op("REMOVE", "circuits", "c", null),
            ),
        ))
        assertTrue(parser.circuits().isEmpty())
    }

    @Test
    fun no_circuit_buckets_stops_after_checkpoint_with_empty_result() {
        val parser = KilterCircuitSyncParser()
        val stopped = parser.accept(checkpointLine("global_climbs[]" to 31_000, "global_gyms[]" to 900))
        assertTrue(stopped, "no circuit buckets → done immediately, never touch global buckets")
        assertTrue(parser.circuits().isEmpty())
    }

    @Test
    fun tolerates_inline_object_data() {
        // Defensive: if a future server drops raw_data and inlines the row as
        // an object instead of a string, we still parse it.
        val bucket = """circuit_buckets["c"]"""
        val inlineOp = buildJsonObject {
            put("op", "PUT")
            put("object_type", "circuits")
            put("object_id", "c")
            put("data", buildJsonObject { put("circuit_uuid", "c"); put("name", "Inline") })
        }
        val parser = KilterCircuitSyncParser()
        feed(parser, listOf(checkpointLine(bucket to 1), dataLine(bucket, inlineOp)))
        assertEquals("Inline", parser.circuits().single().name)
    }

    @Test
    fun two_circuits_across_buckets_both_folded() {
        val b1 = """circuit_buckets["a"]"""
        val b2 = """circuit_buckets["b"]"""
        val parser = KilterCircuitSyncParser()
        feed(parser, listOf(
            checkpointLine(b1 to 1, b2 to 2),
            dataLine(b1, op("PUT", "circuits", "a", mapOf("circuit_uuid" to "a", "name" to "Alpha"))),
            dataLine(
                b2,
                op("PUT", "circuits", "b", mapOf("circuit_uuid" to "b", "name" to "Beta")),
                op("PUT", "circuit_climbs", "b.z", mapOf("circuit_uuid" to "b", "climb_uuid" to "z")),
            ),
        ))
        val circuits = parser.circuits()
        assertEquals(2, circuits.size)
        assertEquals(setOf("Alpha", "Beta"), circuits.map { it.name }.toSet())
        assertEquals(listOf("z"), circuits.single { it.circuitUuid == "b" }.memberClimbUuids())
    }

    @Test
    fun malformed_line_is_ignored() {
        val bucket = """circuit_buckets["c"]"""
        val parser = KilterCircuitSyncParser()
        parser.accept("this is not json {{{")
        feed(parser, listOf(
            checkpointLine(bucket to 1),
            dataLine(bucket, op("PUT", "circuits", "c", mapOf("circuit_uuid" to "c", "name" to "Survived"))),
        ))
        assertEquals("Survived", parser.circuits().single().name)
    }
}
