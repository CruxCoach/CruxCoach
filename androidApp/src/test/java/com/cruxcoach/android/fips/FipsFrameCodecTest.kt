package com.cruxcoach.android.fips

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class FipsFrameCodecTest {
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
}
