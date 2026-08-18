package com.cruxcoach.android.mesh

/**
 * The realm/protocol envelope every application frame is wrapped in.
 *
 * Framing exists so routing never has to guess: before this, BoardCell and
 * Competition both saw every payload and each decided by "does my decoder
 * accept it", which is exactly how a foreign feature's frame reaches the wrong
 * reducer. The tag makes the realm and the protocol part of the wire.
 *
 * ```
 * "CCM1" | realmLen u8 | protocolLen u8 | realm utf8 | protocol utf8 | payload
 * ```
 *
 * The magic differs from the transport's link-local CCJ1 admission prefix, so
 * a control frame can never be mistaken for an application envelope.
 */
internal object MeshWireCodec {
    /** "CCM1" — CruxCoach mesh envelope, version 1. */
    private val MAGIC = byteArrayOf(0x43, 0x43, 0x4d, 0x31)
    private const val HEADER_BYTES = 6
    const val MAX_FIELD_BYTES = 255

    data class Frame(val realmId: MeshRealmId, val protocol: String, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean = this === other || (other is Frame &&
            realmId == other.realmId && protocol == other.protocol &&
            payload.contentEquals(other.payload))

        override fun hashCode(): Int =
            (31 * (31 * realmId.hashCode() + protocol.hashCode())) + payload.contentHashCode()
    }

    fun encode(realmId: MeshRealmId, protocol: String, payload: ByteArray): ByteArray {
        val realm = realmId.value.encodeToByteArray()
        val tag = protocol.encodeToByteArray()
        require(realm.size in 1..MAX_FIELD_BYTES) { "realm id does not fit the mesh envelope" }
        require(tag.size in 1..MAX_FIELD_BYTES) { "protocol does not fit the mesh envelope" }
        val out = ByteArray(HEADER_BYTES + realm.size + tag.size + payload.size)
        MAGIC.copyInto(out)
        out[4] = realm.size.toByte()
        out[5] = tag.size.toByte()
        realm.copyInto(out, HEADER_BYTES)
        tag.copyInto(out, HEADER_BYTES + realm.size)
        payload.copyInto(out, HEADER_BYTES + realm.size + tag.size)
        return out
    }

    /** Null for anything that is not a well-formed envelope of this version. */
    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size < HEADER_BYTES) return null
        if (MAGIC.indices.any { bytes[it] != MAGIC[it] }) return null
        val realmLength = bytes[4].toInt() and 0xff
        val protocolLength = bytes[5].toInt() and 0xff
        if (realmLength == 0 || protocolLength == 0) return null
        val protocolStart = HEADER_BYTES + realmLength
        val payloadStart = protocolStart + protocolLength
        if (bytes.size < payloadStart) return null
        val realm = bytes.decodeUtf8(HEADER_BYTES, protocolStart) ?: return null
        val protocol = bytes.decodeUtf8(protocolStart, payloadStart) ?: return null
        if (realm.isBlank() || !MeshProtocols.isWellFormed(protocol)) return null
        return Frame(MeshRealmId(realm), protocol, bytes.copyOfRange(payloadStart, bytes.size))
    }

    /** Rejects a mis-tagged length that would otherwise decode as replacement characters. */
    private fun ByteArray.decodeUtf8(from: Int, to: Int): String? {
        val text = runCatching { copyOfRange(from, to).decodeToString(throwOnInvalidSequence = true) }
        return text.getOrNull()
    }
}
