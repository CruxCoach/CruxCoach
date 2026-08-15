package com.cruxcoach.android.fips

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class FipsFrameCodecTest {
    @Test fun `message id is stable for retries and changes with payload`() {
        assertEquals(FipsFrameCodec.messageId(byteArrayOf(1, 2)), FipsFrameCodec.messageId(byteArrayOf(1, 2)))
        assertNotEquals(FipsFrameCodec.messageId(byteArrayOf(1, 2)), FipsFrameCodec.messageId(byteArrayOf(1, 3)))
    }

    @Test fun `large snapshot reassembles out of order with duplicates`() {
        val payload = ByteArray(40_000) { (it % 251).toByte() }
        val frames = FipsFrameCodec.fragment(payload, UUID(1, 2))
        val assembler = FipsFrameAssembler()
        var result: ByteArray? = null
        (frames.reversed() + frames.first()).forEach { result = assembler.accept("npub-a", it) ?: result }
        assertArrayEquals(payload, result)
    }

    @Test fun `fragments from authenticated senders cannot be mixed`() {
        val frames = FipsFrameCodec.fragment(ByteArray(2_000) { 7 }, UUID(3, 4))
        val assembler = FipsFrameAssembler()
        assertNull(assembler.accept("npub-a", frames[0]))
        assertNull(assembler.accept("npub-b", frames[1]))
    }

    @Test fun `corrupt completed message is discarded`() {
        val frames = FipsFrameCodec.fragment(ByteArray(1_500) { 9 }).toMutableList()
        frames[1] = frames[1].clone().also { it[it.lastIndex] = 8 }
        val assembler = FipsFrameAssembler()
        assertNull(frames.mapNotNull { assembler.accept("npub", it) }.singleOrNull())
    }

    @Test fun `declared fragment fanout cannot amplify bounded memory`() {
        val frame = FipsFrameCodec.fragment(byteArrayOf(1)).single().clone()
        frame[23] = 100 // count=100 for a one-byte message
        assertNull(FipsFrameCodec.decode(frame))
    }

    @Test fun `one sender cannot retain unlimited incomplete messages`() {
        val assembler = FipsFrameAssembler(maxInflightPerSender = 2)
        val payload = ByteArray(1_800) { 4 }
        val first = FipsFrameCodec.fragment(payload, UUID(1, 1))
        val second = FipsFrameCodec.fragment(payload, UUID(2, 2))
        val third = FipsFrameCodec.fragment(payload, UUID(3, 3))
        assertNull(assembler.accept("peer", first[0]))
        assertNull(assembler.accept("peer", second[0]))
        assertNull(assembler.accept("peer", third[0]))

        // Starting the third message evicts the oldest incomplete assembly;
        // its tail can no longer complete anything by itself.
        assertNull(assembler.accept("peer", first[1]))
        assertArrayEquals(payload, assembler.accept("peer", third[1]))
    }
}
