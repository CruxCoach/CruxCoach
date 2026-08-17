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
        // A matching locally observed nonce is useful hardening when scanning
        // is symmetric, but Android frequently establishes a valid inbound
        // L2CAP edge before the listener ever scans the initiator. FIPS direct
        // peer authentication + exact full scope + freshness are the admission
        // boundary; the nonce must not turn one-way discovery into a deadlock.
        // Do not turn old/local scan state into a rejection: a listener may
        // have seen the initiator earlier and still legitimately receive a new
        // inbound edge after that observation expired. A fresh match is only
        // positive corroboration; absence, malformed data, or staleness leaves
        // the authenticated direct-edge decision unchanged.
        hello.nonceHex.hexToBytes()?.let { nonce ->
            observedNonceTags[nonceTag(nonce).toHex()]?.let { seenAt ->
                seenAt <= nowMs && nowMs - seenAt <= MAX_AGE_MS
            }
        }
        return true
    }

    internal fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    internal fun String.hexToBytes(): ByteArray? = if (length % 2 != 0) null else runCatching {
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }.getOrNull()
}

/** When this node must (re-)assert its own CCJ1 scope towards a direct BLE peer. */
internal object DirectJoinHelloSchedule {
    const val RETRY_MS = 5_000L

    /**
     * [peerValidatedByUs] records only that the peer's hello proved *its* scope
     * to *us*. CCJ1 is not acknowledged, so it never reports that our own hello
     * reached the peer — and whichever side polls its peer set second observes
     * exactly that state, because the first side's hello already arrived.
     * Suppressing our hello there leaves admission one-directional: the peer
     * never adds us to its direct authenticated set, so it can neither sponsor
     * nor admit us and the join stalls at the membership snapshot. The flag is
     * therefore deliberately not a suppression input; only the local retry
     * throttle bounds how often an established edge is re-asserted.
     */
    fun shouldSend(lastSentAtMs: Long?, nowMs: Long, peerValidatedByUs: Boolean): Boolean =
        lastSentAtMs == null || nowMs - lastSentAtMs >= RETRY_MS
}
