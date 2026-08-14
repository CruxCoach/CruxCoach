package com.cruxcoach.android.fips

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Bounded fragmentation above FIPS. BLE advertisements never carry these bytes. */
object FipsFrameCodec {
    const val MAX_CHUNK_BYTES = 900
    const val MAX_MESSAGE_BYTES = 1_048_576
    private const val HEADER_BYTES = 61
    private const val MAGIC = 0x4343464D // CCFM

    fun fragment(payload: ByteArray, id: UUID = UUID.randomUUID()): List<ByteArray> {
        require(payload.size <= MAX_MESSAGE_BYTES)
        val count = maxOf(1, (payload.size + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES)
        require(count <= UShort.MAX_VALUE.toInt())
        val hash = MessageDigest.getInstance("SHA-256").digest(payload)
        return (0 until count).map { index ->
            val start = index * MAX_CHUNK_BYTES
            val end = minOf(payload.size, start + MAX_CHUNK_BYTES)
            ByteBuffer.allocate(HEADER_BYTES + end - start).apply {
                putInt(MAGIC); put(1); putLong(id.mostSignificantBits); putLong(id.leastSignificantBits)
                putShort(index.toShort()); putShort(count.toShort()); putInt(payload.size); put(hash)
                put(payload, start, end - start)
            }.array()
        }
    }

    data class Fragment(val id: UUID, val index: Int, val count: Int, val total: Int,
        val hash: ByteArray, val payload: ByteArray)

    fun decode(bytes: ByteArray): Fragment? = runCatching {
        if (bytes.size < HEADER_BYTES) return null
        val b = ByteBuffer.wrap(bytes)
        if (b.int != MAGIC || b.get().toInt() != 1) return null
        val id = UUID(b.long, b.long)
        val index = b.short.toInt() and 0xffff
        val count = b.short.toInt() and 0xffff
        val total = b.int
        val hash = ByteArray(32).also(b::get)
        val expectedCount = if (total >= 0) maxOf(1,
            (total + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES) else 0
        if (count !in 1..UShort.MAX_VALUE.toInt() || index !in 0 until count ||
            total !in 0..MAX_MESSAGE_BYTES || count != expectedCount) return null
        val expectedBytes = if (index == count - 1) total - index * MAX_CHUNK_BYTES
            else MAX_CHUNK_BYTES
        if (b.remaining() != expectedBytes) return null
        Fragment(id, index, count, total, hash, ByteArray(b.remaining()).also(b::get))
    }.getOrNull()
}

class FipsFrameAssembler(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxInflight: Int = 128,
    private val ttlMs: Long = 30_000,
) {
    private data class Pending(val created: Long, val count: Int, val total: Int, val hash: ByteArray,
        val pieces: MutableMap<Int, ByteArray> = mutableMapOf())
    private val pending = linkedMapOf<Pair<String, UUID>, Pending>()

    @Synchronized
    fun accept(authenticatedSender: String, bytes: ByteArray): ByteArray? {
        val fragment = FipsFrameCodec.decode(bytes) ?: return null
        val now = nowMs()
        pending.entries.removeAll { now - it.value.created > ttlMs }
        val key = authenticatedSender to fragment.id
        val value = pending[key] ?: run {
            while (pending.size >= maxInflight) pending.remove(pending.keys.first())
            Pending(now, fragment.count, fragment.total, fragment.hash).also { pending[key] = it }
        }
        if (value.count != fragment.count || value.total != fragment.total ||
            !value.hash.contentEquals(fragment.hash)) { pending.remove(key); return null }
        value.pieces.putIfAbsent(fragment.index, fragment.payload)
        if (value.pieces.size != value.count) return null
        val complete = ByteArray(value.total)
        var offset = 0
        for (index in 0 until value.count) {
            val part = value.pieces[index] ?: return null
            if (offset + part.size > complete.size) { pending.remove(key); return null }
            part.copyInto(complete, offset); offset += part.size
        }
        pending.remove(key)
        if (offset != complete.size || !MessageDigest.getInstance("SHA-256").digest(complete)
                .contentEquals(value.hash)) return null
        return complete
    }
}
