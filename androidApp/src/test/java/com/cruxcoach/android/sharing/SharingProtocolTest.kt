package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.ShareCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharingProtocolTest {
    private val note = SharingRecord("note:x", ShareCategory.CLIMB_NOTES, 3, linkedMapOf("climbId" to "x", "note" to "beta"))
    private val expiring = SharingRecord("note:z", ShareCategory.CLIMB_NOTES, 4, linkedMapOf("climbId" to "z", "note" to "until"), until = 1_900_000_000_000)
    private val scope = ShareScope(listOf(ShareCategory.SENDS, ShareCategory.ATTEMPTS, ShareCategory.CLIMB_NOTES), "2026-08-24")

    private val stream = "0123456789abcdef".repeat(2)

    @Test fun every_message_type_round_trips_canonically() {
        val messages = listOf(
            ShareProtocol.manifest(stream, 0, 1, 0, scope, listOf(note)),
            ShareProtocol.manifest(stream, 2, 1, 7, scope, listOf(note)),
            ShareProtocol.full(stream, 0, 1, 0, listOf(note, expiring)).single(),
            ShareProtocol.full(stream, 2, 1, 7, listOf(note, expiring)).single(),
            ShareProtocol.delta(stream, 1, 1, scope, listOf(note), listOf("note:y"), listOf(note)),
            ShareMessage(type = "ack", stream = stream, assign = 1, gen = 1, seq = 1, digest = SharingRecords.digest(listOf(note))),
            ShareMessage(type = "resync", gen = 1, seq = -1),
            ShareMessage(type = "resync", stream = stream, assign = 3, gen = 1, seq = 4),
            ShareMessage(type = "end"),
        )
        for (message in messages) {
            val raw = ShareProtocol.encode(message)
            assertEquals(message, ShareProtocol.decode(raw), raw)
            assertTrue(raw.startsWith("{\"v\":3,\"type\":"))
        }
    }

    @Test fun times_on_the_wire_are_whole_minutes_and_only_where_a_state_is_described() {
        val at = 1_790_000_123_456L
        val manifest = ShareProtocol.manifest(stream, 0, 1, 0, scope, listOf(note), at)
        assertEquals(1_790_000_100_000L, manifest.at)
        assertNotNull(ShareProtocol.decode(ShareProtocol.encode(manifest)))
        val delta = ShareProtocol.delta(stream, 1, 1, scope, listOf(note), emptyList(), listOf(note))
        assertEquals(0, delta.at, "a delta resent after a crash must stay the same message")
        assertNull(ShareProtocol.decode(ShareProtocol.encode(delta.copy(at = 1_790_000_100_000L))))
        assertNull(ShareProtocol.decode(ShareProtocol.encode(manifest.copy(at = at))), "seconds say too much")
        assertNull(ShareProtocol.decode(ShareProtocol.encode(ShareProtocol.full(stream, 0, 1, 0, listOf(note)).single().copy(at = 60_000))))
        assertNull(ShareProtocol.decode(ShareProtocol.encode(ShareMessage(type = "ack", stream = stream, assign = 1, gen = 1, at = 60_000,
            digest = SharingRecords.digest(listOf(note))))))
    }

    @Test fun streams_and_assignments_keep_their_shape() {
        val digest = SharingRecords.digest(listOf(note))
        fun refused(m: ShareMessage, why: String) = assertNull(ShareProtocol.decode(ShareProtocol.encode(m)), why)
        refused(ShareProtocol.manifest("", 0, 1, 0, scope, listOf(note)), "data needs a stream")
        refused(ShareProtocol.manifest(stream.uppercase(), 0, 1, 0, scope, listOf(note)), "lowercase hex only")
        refused(ShareProtocol.manifest(stream.take(31), 0, 1, 0, scope, listOf(note)), "32 hex")
        refused(ShareProtocol.full(stream, 0, 1, 3, listOf(note)).single(), "a generation for everyone starts at sequence 0")
        refused(ShareProtocol.delta(stream, 1, 1, scope, listOf(note), emptyList(), listOf(note)).copy(assign = 1),
            "deltas go to the whole stream")
        refused(ShareMessage(type = "ack", stream = stream, gen = 1, seq = 1, digest = digest), "an ack names the snapshot it follows")
        refused(ShareMessage(type = "manifest", stream = stream, assign = -1, gen = 1, scope = scope, digest = digest), "no negative assignment")
        refused(ShareMessage(type = "end", stream = stream), "end is per friendship, not per stream")
    }

    @Test fun non_canonical_unknown_or_misshaped_input_is_refused() {
        val raw = ShareProtocol.encode(ShareProtocol.manifest(stream, 0, 1, 0, scope, listOf(note)))
        assertNull(ShareProtocol.decode(raw.replace("{\"v\":3", "{ \"v\":3")), "whitespace changes the canonical form")
        assertNull(ShareProtocol.decode(raw.replace("\"v\":3", "\"v\":2")), "v2 messages are not understood any more")
        assertNull(ShareProtocol.decode(raw.replace("}", ",\"extra\":1}")))
        assertNull(ShareProtocol.decode(ShareProtocol.encode(ShareMessage(type = "manifest", stream = stream, gen = 0, scope = scope, digest = "a".repeat(64)))))
        assertNull(ShareProtocol.decode(ShareProtocol.encode(ShareMessage(type = "end", gen = 1))))
        assertNull(ShareProtocol.decode(ShareProtocol.encode(ShareMessage(type = "delta", stream = stream, gen = 1, seq = 1,
            scope = ShareScope(listOf(ShareCategory.CLIMB_NOTES, ShareCategory.SENDS)), digest = "a".repeat(64)))),
            "unsorted categories are not canonical")
        assertNull(ShareProtocol.decode(ShareProtocol.encode(ShareMessage(type = "manifest", stream = stream, gen = 1,
            scope = ShareScope(listOf(ShareCategory.CLIMB_NOTES), "2026-08-24"), digest = "a".repeat(64)))),
            "a cutoff without a dated category is not a valid scope")
        assertNull(ShareProtocol.decode(ShareProtocol.encode(ShareMessage(type = "manifest", stream = stream, gen = 1,
            scope = ShareScope(listOf(ShareCategory.BODY)), digest = "a".repeat(64)))),
            "a category without a source here is not a valid scope")
        assertNull(ShareProtocol.decode("x".repeat(ShareProtocol.MAX_BYTES + 1)))
    }

    @Test fun full_pages_respect_record_and_byte_limits() {
        val many = (0 until 40).map { SharingRecord("note:$it", ShareCategory.CLIMB_NOTES, 1, linkedMapOf("climbId" to "$it", "note" to "x".repeat(4000))) }
        val pages = ShareProtocol.full(stream, 0, 2, 0, many)
        assertTrue(pages.size > 3)
        pages.forEach { page ->
            assertTrue(page.records.size <= ShareProtocol.MAX_PAGE_RECORDS)
            assertNotNull(ShareProtocol.decode(ShareProtocol.encode(page)))
        }
        assertEquals(many.map { it.id }.sorted(), pages.flatMap { p -> p.records.map { it.id } })
        assertEquals(1, ShareProtocol.full(stream, 0, 1, 0, emptyList()).single().parts)
        // A snapshot page carries its friend and position; the size limit still holds.
        ShareProtocol.full(stream, 9, 2, 1_000_000, many).forEach { assertNotNull(ShareProtocol.decode(ShareProtocol.encode(it))) }
    }

    @Test fun the_expiry_is_part_of_the_record_and_its_revision() {
        val fields = linkedMapOf("climbId" to "x", "note" to "beta")
        val plain = SharingRecords.revision("note:x", ShareCategory.CLIMB_NOTES, fields)
        val until = SharingRecords.revision("note:x", ShareCategory.CLIMB_NOTES, fields, 1_900_000_000_000)
        assertTrue(plain != until, "a new expiry is a change the friend must receive")
        val record = SharingRecords.withUntil(SharingRecord("note:x", ShareCategory.CLIMB_NOTES, plain, fields), 1_900_000_000_000)
        assertEquals(until, record.revision)
        assertEquals(plain, SharingRecords.withUntil(record, null).revision)
        assertTrue(!ShareProtocol.encode(ShareProtocol.full(stream, 0, 1, 0, listOf(note)).single()).contains("until"), "absent, not null")
    }
}
