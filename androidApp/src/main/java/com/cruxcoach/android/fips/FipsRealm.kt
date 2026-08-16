package com.cruxcoach.android.fips

import java.security.MessageDigest
import kotlinx.serialization.Serializable

enum class FipsRealmKind { BOARD_CELL, COMPETITION }

/** Transport realm is explicit and never inferred from a nearby device name or RSSI. */
data class FipsRealmContext(
    val realmId: String,
    val boardCellId: String,
    val kind: FipsRealmKind = FipsRealmKind.BOARD_CELL,
    val meshName: String? = null,
) {
    init {
        require(realmId.isNotBlank())
        require(boardCellId.isNotBlank())
        if (kind == FipsRealmKind.BOARD_CELL) require(realmId == boardCellId) {
            "a normal board realm must equal its BoardCell id"
        }
    }

    val realmTag: ByteArray get() = shortTag("realm", realmId)
    val cellTag: ByteArray get() = shortTag("cell", boardCellId)

    companion object {
        const val TAG_BYTES = 4
        fun shortTag(domain: String, value: String): ByteArray = MessageDigest.getInstance("SHA-256")
            .digest("cruxcoach-fips-v1|$domain|$value".encodeToByteArray()).copyOf(TAG_BYTES)
    }
}

@Serializable
internal data class DirectJoinHello(
    val realmId: String,
    val boardCellId: String,
    val nonceHex: String,
    val issuedAtMs: Long,
)

internal object DirectJoinProof {
    const val MAX_AGE_MS = 45_000L
    fun nonceTag(nonce: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest("cruxcoach-direct-join-v1|".encodeToByteArray() + nonce).copyOf(FipsRealmContext.TAG_BYTES)

    fun isFresh(issuedAtMs: Long, nowMs: Long): Boolean =
        issuedAtMs <= nowMs + 5_000L && nowMs - issuedAtMs <= MAX_AGE_MS

    fun validate(
        expected: FipsRealmContext,
        hello: DirectJoinHello,
        observedNonceTags: Map<String, Long>,
        directBleEdge: Boolean,
        nowMs: Long,
    ): Boolean {
        if (!directBleEdge || hello.realmId != expected.realmId ||
            hello.boardCellId != expected.boardCellId || !isFresh(hello.issuedAtMs, nowMs)) return false
        val nonce = hello.nonceHex.hexToBytes() ?: return false
        val seenAt = observedNonceTags[nonceTag(nonce).toHex()] ?: return false
        return seenAt <= nowMs && nowMs - seenAt <= MAX_AGE_MS
    }

    internal fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    internal fun String.hexToBytes(): ByteArray? = if (length % 2 != 0) null else runCatching {
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }.getOrNull()
}
