package com.cruxcoach.android.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame boundary, which had no test.
 *
 * A GATT attribute stops at 512 bytes and a notification at ATT_MTU-3 (509
 * here). At 17 bytes an item that is 29 items per frame, and the generator
 * builds sessions of up to 38 — so the flat encoding produced frames nobody
 * could receive, and the participant dropped them without a word.
 */
class SessionQueueProtocolPagingTest {

    private fun items(n: Int) = (0 until n).map {
        QueueItem("550E8400E29B41D4A716%012X".format(it), 40)
    }

    private companion object {
        /** ATT_MTU (512, requested by SessionGattClient) minus the 3-byte header. */
        const val NOTIFICATION_LIMIT = 509
    }

    @Test
    fun `a single page still fits one notification`() {
        val encoded = SessionQueueProtocol.encodeQueueState(0, items(29))
        assertTrue("frame was ${encoded.size} bytes", encoded.size <= NOTIFICATION_LIMIT)
        val decoded = SessionQueueProtocol.decodeQueueState(encoded)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.pageCount)
        assertEquals(29, decoded.items.size)
    }

    @Test
    fun `a generated-size queue pages instead of overflowing`() {
        val queue = items(38)
        val pageCount = SessionQueueProtocol.queueStatePageCount(queue.size)
        assertEquals(2, pageCount)
        val rebuilt = (0 until pageCount).flatMap { page ->
            val frame = SessionQueueProtocol.encodeQueueState(7, queue, page)
            assertTrue("page $page was ${frame.size} bytes", frame.size <= NOTIFICATION_LIMIT)
            val decoded = SessionQueueProtocol.decodeQueueState(frame)
            assertNotNull(decoded)
            assertEquals(pageCount, decoded!!.pageCount)
            assertEquals(page, decoded.page)
            assertEquals(7, decoded.currentIndex)
            decoded.items
        }
        assertEquals(queue.map { it.climbUuid }, rebuilt.map { it.climbUuid })
    }

    @Test
    fun `an empty queue still sends one page`() {
        assertEquals(1, SessionQueueProtocol.queueStatePageCount(0))
        val decoded = SessionQueueProtocol.decodeQueueState(
            SessionQueueProtocol.encodeQueueState(0, emptyList())
        )
        assertNotNull(decoded)
        assertEquals(0, decoded!!.items.size)
        assertEquals(1, decoded.pageCount)
    }

    @Test
    fun `a truncated frame is refused rather than half-read`() {
        val full = SessionQueueProtocol.encodeQueueState(0, items(10))
        assertNull(SessionQueueProtocol.decodeQueueState(full.copyOf(full.size - 5)))
    }
}
