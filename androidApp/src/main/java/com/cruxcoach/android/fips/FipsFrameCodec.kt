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

    /** Stable across outbox retries, so a retry finishes/replaces the same
     * in-flight assembly instead of allocating another one for 30 seconds. */
    fun messageId(payload: ByteArray): UUID {
        val hash = MessageDigest.getInstance("SHA-256").digest(payload)
        return ByteBuffer.wrap(hash).let { UUID(it.long, it.long) }
    }

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
    private val maxInflight: Int = 32,
    private val maxBufferedBytes: Int = 8 * 1_048_576,
    private val maxInflightPerSender: Int = 4,
    private val ttlMs: Long = 30_000,
) {
    private data class Pending(val created: Long, val count: Int, val total: Int, val hash: ByteArray,
        val pieces: MutableMap<Int, ByteArray> = mutableMapOf(), var bufferedBytes: Int = 0)
    private val pending = linkedMapOf<Pair<String, UUID>, Pending>()
    private var bufferedBytes = 0

    private fun remove(key: Pair<String, UUID>) {
        pending.remove(key)?.let { bufferedBytes -= it.bufferedBytes }
    }

    private fun evictExpired(now: Long) {
        pending.entries.filter { now - it.value.created > ttlMs }
            .map { it.key }
            .forEach(::remove)
    }

    private fun evictOldest(except: Pair<String, UUID>? = null): Boolean {
        val key = pending.keys.firstOrNull { it != except } ?: return false
        remove(key)
        return true
    }

    @Synchronized
    fun accept(authenticatedSender: String, bytes: ByteArray): ByteArray? {
        val fragment = FipsFrameCodec.decode(bytes) ?: return null
        val now = nowMs()
        evictExpired(now)
        val key = authenticatedSender to fragment.id
        val value = pending[key] ?: run {
            while (pending.size >= maxInflight) evictOldest()
            while (pending.keys.count { it.first == authenticatedSender } >= maxInflightPerSender) {
                val oldest = pending.keys.firstOrNull { it.first == authenticatedSender } ?: break
                remove(oldest)
            }
            Pending(now, fragment.count, fragment.total, fragment.hash).also { pending[key] = it }
        }
        if (value.count != fragment.count || value.total != fragment.total ||
            !value.hash.contentEquals(fragment.hash)) { remove(key); return null }
        if (fragment.index !in value.pieces) {
            while (bufferedBytes + fragment.payload.size > maxBufferedBytes) {
                if (!evictOldest(except = key)) {
                    remove(key)
                    return null
                }
            }
            value.pieces[fragment.index] = fragment.payload
            value.bufferedBytes += fragment.payload.size
            bufferedBytes += fragment.payload.size
        }
        if (value.pieces.size != value.count) return null
        val complete = ByteArray(value.total)
        var offset = 0
        for (index in 0 until value.count) {
            val part = value.pieces[index] ?: return null
            if (offset + part.size > complete.size) { remove(key); return null }
            part.copyInto(complete, offset); offset += part.size
        }
        remove(key)
        if (offset != complete.size || !MessageDigest.getInstance("SHA-256").digest(complete)
                .contentEquals(value.hash)) return null
        return complete
    }
}
