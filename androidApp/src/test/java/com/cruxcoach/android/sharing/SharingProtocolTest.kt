package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.SharingCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharingProtocolTest {
    private val note = SharingRecord("note:x", SharingCategory.PRIVATE_NOTES, 3, linkedMapOf("climbId" to "x", "note" to "beta"))
    private val scope = ShareScope(listOf(SharingCategory.TRAINING_HISTORY, SharingCategory.PRIVATE_NOTES), "2026-08-24")

    @Test fun every_message_type_round_trips_canonically() {
        val messages = listOf(
            ShareProtocol.manifest(1, 0, scope, listOf(note)),
            ShareProtocol.full(1, listOf(note)).single(),
            ShareProtocol.delta(1, 1, scope, listOf(note), listOf("note:y"), listOf(note)),
            ShareMessage(type = "ack", gen = 1, seq = 1, digest = SharingRecords.digest(listOf(note))),
            ShareMessage(type = "resync", gen = 1, seq = -1),
            ShareMessage(type = "end"),
        )
        for (message in messages) {
            val raw = ShareProtocol.encode(message)
            assertEquals(message, ShareProtocol.decode(raw), raw)
            assertTrue(raw.startsWith("{\"v\":2,\"type\":"))
        }
    }

    @Test fun non_canonical_unknown_or_misshaped_input_is_refused() {
        val raw = ShareProtocol.encode(ShareProtocol.manifest(1, 0, scope, listOf(note)))
        assertNull(ShareProtocol.decode(raw.replace("{\"v\":2", "{ \"v\":2")), "whitespace changes the canonical form")
        assertNull(ShareProtocol.decode(raw.replace("\"v\":2", "\"v\":3")))
        assertNull(ShareProtocol.decode(raw.replace("}", ",\"extra\":1}")))
        assertNull(ShareProtocol.decode(ShareProtocol.encode(ShareMessage(type = "manifest", gen = 0, scope = scope, digest = "a".repeat(64)))))
        assertNull(ShareProtocol.decode(ShareProtocol.encode(ShareMessage(type = "end", gen = 1))))
        assertNull(ShareProtocol.decode(ShareProtocol.encode(ShareMessage(type = "delta", gen = 1, seq = 1,
            scope = ShareScope(listOf(SharingCategory.PRIVATE_NOTES, SharingCategory.TRAINING_HISTORY)), digest = "a".repeat(64)))),
            "unsorted categories are not canonical")
        assertNull(ShareProtocol.decode(ShareProtocol.encode(ShareMessage(type = "manifest", gen = 1,
            scope = ShareScope(listOf(SharingCategory.PRIVATE_NOTES), "2026-08-24"), digest = "a".repeat(64)))),
            "a cutoff without training is not a valid scope")
        assertNull(ShareProtocol.decode("x".repeat(ShareProtocol.MAX_BYTES + 1)))
    }

    @Test fun full_pages_respect_record_and_byte_limits() {
        val many = (0 until 40).map { SharingRecord("note:$it", SharingCategory.PRIVATE_NOTES, 1, linkedMapOf("climbId" to "$it", "note" to "x".repeat(4000))) }
        val pages = ShareProtocol.full(2, many)
        assertTrue(pages.size > 3)
        pages.forEach { page ->
            assertTrue(page.records.size <= ShareProtocol.MAX_PAGE_RECORDS)
            assertNotNull(ShareProtocol.decode(ShareProtocol.encode(page)))
        }
        assertEquals(many.map { it.id }.sorted(), pages.flatMap { p -> p.records.map { it.id } })
        assertEquals(1, ShareProtocol.full(1, emptyList()).single().parts)
    }
}
