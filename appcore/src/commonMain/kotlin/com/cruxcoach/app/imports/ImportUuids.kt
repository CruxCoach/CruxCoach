package com.cruxcoach.app.imports

import com.cruxcoach.app.util.toHex
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The two name-based UUID schemes the Android importers already committed to,
 * plus random ids for new rows.
 *
 * Both are reproduced byte for byte: an id computed here has to equal the one
 * an Android device computed for the same input, or a re-import on iOS would
 * duplicate every row the user already has.
 */
internal object ImportUuids {
    /** RFC 4122 DNS namespace, as `MoonBoardUuid` on Android uses it. */
    private val DNS_NAMESPACE = byteArrayOf(
        0x6b, 0xa7.toByte(), 0xb8.toByte(), 0x10, 0x9d.toByte(), 0xad.toByte(),
        0x11, 0xd1.toByte(), 0x80.toByte(), 0xb4.toByte(), 0x00, 0xc0.toByte(),
        0x4f, 0xd4.toByte(), 0x30, 0xc8.toByte(),
    )

    /** UUIDv5 in the DNS namespace — the bundled MoonBoard catalogue's identity scheme. */
    fun v5Dns(name: String): String {
        val bytes = Sha1.digest(DNS_NAMESPACE + name.encodeToByteArray()).copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return format(bytes)
    }

    /** `java.util.UUID.nameUUIDFromBytes`: MD5, version 3, IETF variant. */
    fun v3FromName(name: String): String {
        val bytes = Md5.digest(name.encodeToByteArray())
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x30).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return format(bytes)
    }

    @OptIn(ExperimentalUuidApi::class)
    fun random(): String = Uuid.random().toString()

    private fun format(bytes: ByteArray): String {
        val hex = bytes.toHex()
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) +
            "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32)
    }
}
