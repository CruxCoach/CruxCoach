package com.cruxcoach.android.ble

import android.util.Log

sealed class NearbyPayload {
    data class ClimbData(
        val climbUuid: String,
        val angle: Int,
        val projectionSurvivesDisconnect: Boolean = true,
        val acceptsDisconnect: Boolean = true,
        val supportsConcurrentConnections: Boolean = false,
    ) : NearbyPayload()
    /** The sender's last projection. [projectionSurvivesDisconnect] says whether its LEDs remain on. */
    data class LastClimb(
        val climbUuid: String,
        val angle: Int,
        val projectionSurvivesDisconnect: Boolean = true,
    ) : NearbyPayload()
    data object DisconnectRequest : NearbyPayload()
    /** Board connected without a specific climb. Bit 0 of flags byte = acceptsDisconnectRequests. */
    data class BoardConnected(
        val acceptsDisconnect: Boolean = true,
        val supportsConcurrentConnections: Boolean = false,
    ) : NearbyPayload()
    /** Signals that the sender is going away — scanner should remove entries immediately. */
    data object Gone : NearbyPayload()
    /** A nearby device is hosting a Session Queue that can be joined. */
    data class SessionAdvertisement(val sessionId: Int, val participantCount: Int, val hostName: String) : NearbyPayload()
    /** Response to a disconnect request — accepted or rejected. */
    data class DisconnectResponse(val accepted: Boolean) : NearbyPayload()
}

object NearbyClimbProtocol {
    private const val TAG = "CruxBLE/Protocol"
    const val COMPANY_ID = 0xFFFF
    /** Secondary company ID for climb data embedded in session scan response. */
    const val SESSION_CLIMB_COMPANY_ID = 0xFFFE
    val MAGIC = byteArrayOf(0x43, 0x52, 0x55, 0x58) // "CRUX"

    // Type 0x01: climb ID is a 16-byte raw UUID (most compact, for standard UUIDs)
    //   angle byte bit 7: 0 = no-hyphens uppercase, 1 = hyphens lowercase
    // Type 0x03: climb ID is a variable-length UTF-8 string (for numeric IDs)
    // Type 0x02: disconnect request (no ID)
    private const val TYPE_CLIMB_UUID: Byte = 0x01
    private const val TYPE_DISCONNECT_REQUEST: Byte = 0x02
    private const val TYPE_CLIMB_STRING: Byte = 0x03
    private const val TYPE_BOARD_CONNECTED: Byte = 0x04
    private const val TYPE_GONE: Byte = 0x05
    private const val TYPE_LAST_CLIMB_UUID: Byte = 0x06
    private const val TYPE_LAST_CLIMB_STRING: Byte = 0x07
    private const val TYPE_SESSION: Byte = 0x08
    private const val TYPE_DISCONNECT_RESPONSE: Byte = 0x09
    // UUID format: [4 magic][1 type][1 angle+format][16 uuid][optional 1 flags].
    // The extension carries projection retention and disconnect rejection; old
    // readers consume the stable 22-byte prefix and ignore it.
    private const val UUID_PAYLOAD_SIZE = 22
    private const val UUID_HYPHEN_FLAG = 0x80 // bit 7 of angle byte
    private const val PROJECTION_RETAINED_FLAG = 0x01
    private const val DISCONNECT_REJECTED_FLAG = 0x02
    private const val CONCURRENT_CONNECTIONS_FLAG = 0x04

    // String format: [4 magic][1 type][1 angle][1 len+flags][N utf8].
    private const val STRING_HEADER_SIZE = 7
    private const val MAX_STRING_ID_LENGTH = 17 // 24 budget - 7 header
    private const val STRING_VOLATILE_FLAG = 0x80
    private const val STRING_DISCONNECT_REJECTED_FLAG = 0x40
    private const val STRING_CONCURRENT_CONNECTIONS_FLAG = 0x20

    // Disconnect: [4 magic][1 type=0x02][1 zero] = 6 bytes
    private const val DISCONNECT_SIZE = 6

    fun encodeClimbData(
        climbId: String,
        angle: Int,
        projectionSurvivesDisconnect: Boolean = true,
        acceptsDisconnect: Boolean = true,
        supportsConcurrentConnections: Boolean = false,
    ): ByteArray {
        val uuid = parseUuid(climbId)
        return if (uuid != null) {
            val hasHyphens = climbId.contains('-')
            encodeAsUuid(
                uuid,
                angle,
                hasHyphens,
                TYPE_CLIMB_UUID,
                projectionSurvivesDisconnect,
                acceptsDisconnect,
                supportsConcurrentConnections,
            )
        } else {
            encodeAsString(
                climbId,
                angle,
                TYPE_CLIMB_STRING,
                projectionSurvivesDisconnect,
                acceptsDisconnect,
                supportsConcurrentConnections,
            )
        }
    }

    fun encodeLastClimb(
        climbId: String,
        angle: Int,
        projectionSurvivesDisconnect: Boolean = true,
    ): ByteArray {
        val uuid = parseUuid(climbId)
        return if (uuid != null) {
            val hasHyphens = climbId.contains('-')
            encodeAsUuid(
                uuid,
                angle,
                hasHyphens,
                TYPE_LAST_CLIMB_UUID,
                projectionSurvivesDisconnect,
                true,
                false,
            )
        } else {
            encodeAsString(
                climbId,
                angle,
                TYPE_LAST_CLIMB_STRING,
                projectionSurvivesDisconnect,
                true,
                false,
            )
        }
    }

    fun encodeDisconnectRequest(): ByteArray {
        val buf = ByteArray(DISCONNECT_SIZE)
        MAGIC.copyInto(buf, 0)
        buf[4] = TYPE_DISCONNECT_REQUEST
        buf[5] = 0
        return buf
    }

    fun encodeBoardConnected(
        acceptsDisconnect: Boolean = true,
        supportsConcurrentConnections: Boolean = false,
    ): ByteArray {
        val buf = ByteArray(DISCONNECT_SIZE) // same size: [4 magic][1 type][1 flags]
        MAGIC.copyInto(buf, 0)
        buf[4] = TYPE_BOARD_CONNECTED
        // 0x00 remains the legacy "unspecified/accept" value. Use the
        // explicit 0x02 rejection flag so modern peers do not disconnect a
        // multi-client controller unnecessarily.
        val disconnectFlag = if (acceptsDisconnect) 0x01 else 0x02
        val capacityFlag = if (supportsConcurrentConnections) CONCURRENT_CONNECTIONS_FLAG else 0
        buf[5] = (disconnectFlag or capacityFlag).toByte()
        return buf
    }

    fun encodeDisconnectResponse(accepted: Boolean): ByteArray {
        val buf = ByteArray(DISCONNECT_SIZE)
        MAGIC.copyInto(buf, 0)
        buf[4] = TYPE_DISCONNECT_RESPONSE
        buf[5] = if (accepted) 0x01 else 0x00
        return buf
    }

    fun encodeGone(): ByteArray {
        val buf = ByteArray(DISCONNECT_SIZE)
        MAGIC.copyInto(buf, 0)
        buf[4] = TYPE_GONE
        buf[5] = 0
        return buf
    }

    /**
     * Encodes a session advertisement.
     * Format: [4 CRUX magic][1 type=0x08][4 sessionId LE][1 participantCount][1 nameLen][N name UTF-8]
     * Total: 11 + N bytes (max N=13 for 24-byte manufacturer data limit)
     */
    fun encodeSessionAdvertisement(sessionId: Int, participantCount: Int, hostName: String): ByteArray {
        val nameBytes = hostName.toByteArray(Charsets.UTF_8)
        // Max 13 bytes for name to fit in 24-byte manufacturer data
        val nameLen = nameBytes.size.coerceAtMost(13)
        val buf = ByteArray(11 + nameLen)
        MAGIC.copyInto(buf, 0)
        buf[4] = TYPE_SESSION
        // sessionId as 4 bytes little-endian
        buf[5] = (sessionId and 0xFF).toByte()
        buf[6] = ((sessionId shr 8) and 0xFF).toByte()
        buf[7] = ((sessionId shr 16) and 0xFF).toByte()
        buf[8] = ((sessionId shr 24) and 0xFF).toByte()
        buf[9] = participantCount.coerceIn(0, 255).toByte()
        buf[10] = nameLen.toByte()
        nameBytes.copyInto(buf, 11, 0, nameLen)
        return buf
    }

    fun decode(data: ByteArray): NearbyPayload? {
        if (data.size < DISCONNECT_SIZE) return null
        for (i in MAGIC.indices) {
            if (data[i] != MAGIC[i]) return null
        }
        val result = when (data[4]) {
            TYPE_CLIMB_UUID -> decodeUuid(data, last = false)
            TYPE_CLIMB_STRING -> decodeString(data, last = false)
            TYPE_LAST_CLIMB_UUID -> decodeUuid(data, last = true)
            TYPE_LAST_CLIMB_STRING -> decodeString(data, last = true)
            TYPE_DISCONNECT_REQUEST -> NearbyPayload.DisconnectRequest
            TYPE_BOARD_CONNECTED -> {
                val flags = if (data.size > 5) data[5].toInt() and 0xFF else 0
                // 0x00 = legacy (main sends no flag) or missing byte → default true for backward compat
                // 0x01 = explicitly accepts, 0x02 = explicitly rejects
                NearbyPayload.BoardConnected(
                    acceptsDisconnect = (flags and DISCONNECT_REJECTED_FLAG) == 0,
                    supportsConcurrentConnections =
                        (flags and CONCURRENT_CONNECTIONS_FLAG) != 0,
                )
            }
            TYPE_GONE -> NearbyPayload.Gone
            TYPE_SESSION -> decodeSessionAdvertisement(data)
            TYPE_DISCONNECT_RESPONSE -> {
                val accepted = data.size > 5 && (data[5].toInt() and 0xFF) == 0x01
                NearbyPayload.DisconnectResponse(accepted = accepted)
            }
            else -> null
        }
        if (result != null) {
            Log.d(TAG, "DECODE ${data.size}B → $result")
        }
        return result
    }

    // --- Session advertisement decoding ---

    private fun decodeSessionAdvertisement(data: ByteArray): NearbyPayload.SessionAdvertisement? {
        if (data.size < 11) return null
        val sessionId = (data[5].toInt() and 0xFF) or
            ((data[6].toInt() and 0xFF) shl 8) or
            ((data[7].toInt() and 0xFF) shl 16) or
            ((data[8].toInt() and 0xFF) shl 24)
        val participantCount = data[9].toInt() and 0xFF
        val nameLen = (data[10].toInt() and 0xFF).coerceAtMost(data.size - 11)
        val hostName = if (nameLen > 0) String(data, 11, nameLen, Charsets.UTF_8) else ""
        return NearbyPayload.SessionAdvertisement(sessionId, participantCount, hostName)
    }

    // --- UUID binary encoding (16 bytes) ---

    private fun encodeAsUuid(
        uuid: java.util.UUID,
        angle: Int,
        hasHyphens: Boolean,
        type: Byte,
        projectionSurvivesDisconnect: Boolean,
        acceptsDisconnect: Boolean,
        supportsConcurrentConnections: Boolean,
    ): ByteArray {
        // Non-default projection/ownership capabilities append one flags byte.
        // Legacy decoders require only the first 22 bytes and ignore the
        // extension, so they continue to discover the climb.
        val hasFlags = !projectionSurvivesDisconnect ||
            !acceptsDisconnect ||
            supportsConcurrentConnections
        val buf = ByteArray(UUID_PAYLOAD_SIZE + if (hasFlags) 1 else 0)
        MAGIC.copyInto(buf, 0)
        buf[4] = type
        val angleBits = angle.coerceIn(0, 70)
        val flagBit = if (hasHyphens) UUID_HYPHEN_FLAG else 0
        buf[5] = (angleBits or flagBit).toByte()
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        for (i in 0 until 8) {
            buf[6 + i] = (msb shr (56 - i * 8)).toByte()
            buf[14 + i] = (lsb shr (56 - i * 8)).toByte()
        }
        if (hasFlags) {
            val retained = if (projectionSurvivesDisconnect) PROJECTION_RETAINED_FLAG else 0
            val rejects = if (acceptsDisconnect) 0 else DISCONNECT_REJECTED_FLAG
            val concurrent = if (supportsConcurrentConnections) CONCURRENT_CONNECTIONS_FLAG else 0
            buf[UUID_PAYLOAD_SIZE] = (retained or rejects or concurrent).toByte()
        }
        return buf
    }

    private fun decodeUuid(data: ByteArray, last: Boolean): NearbyPayload? {
        if (data.size < UUID_PAYLOAD_SIZE) return null
        val raw = data[5].toInt() and 0xFF
        val angle = raw and 0x7F
        val hasHyphens = (raw and UUID_HYPHEN_FLAG) != 0
        var msb = 0L
        var lsb = 0L
        for (i in 0 until 8) {
            msb = (msb shl 8) or (data[6 + i].toLong() and 0xFF)
            lsb = (lsb shl 8) or (data[14 + i].toLong() and 0xFF)
        }
        val uuid = java.util.UUID(msb, lsb)
        val formatted = if (hasHyphens) {
            uuid.toString()
        } else {
            uuid.toString().replace("-", "").uppercase()
        }
        val projectionSurvivesDisconnect = data.size == UUID_PAYLOAD_SIZE ||
            (data[UUID_PAYLOAD_SIZE].toInt() and PROJECTION_RETAINED_FLAG) != 0
        val acceptsDisconnect = data.size == UUID_PAYLOAD_SIZE ||
            (data[UUID_PAYLOAD_SIZE].toInt() and DISCONNECT_REJECTED_FLAG) == 0
        val supportsConcurrentConnections = data.size > UUID_PAYLOAD_SIZE &&
            (data[UUID_PAYLOAD_SIZE].toInt() and CONCURRENT_CONNECTIONS_FLAG) != 0
        return if (last) {
            NearbyPayload.LastClimb(formatted, angle, projectionSurvivesDisconnect)
        } else {
            NearbyPayload.ClimbData(
                formatted,
                angle,
                projectionSurvivesDisconnect,
                acceptsDisconnect,
                supportsConcurrentConnections,
            )
        }
    }

    // --- UTF-8 string encoding (for numeric IDs) ---

    private fun encodeAsString(
        climbId: String,
        angle: Int,
        type: Byte,
        projectionSurvivesDisconnect: Boolean,
        acceptsDisconnect: Boolean,
        supportsConcurrentConnections: Boolean,
    ): ByteArray {
        val idBytes = climbId.toByteArray(Charsets.UTF_8)
        val len = idBytes.size.coerceAtMost(MAX_STRING_ID_LENGTH)
        val buf = ByteArray(STRING_HEADER_SIZE + len)
        MAGIC.copyInto(buf, 0)
        buf[4] = type
        buf[5] = angle.coerceIn(0, 70).toByte()
        // Bit 7 is outside the valid length range (max 17), so it can mark a
        // volatile projection without spending another advertising byte.
        // Legacy decoders coerce the flagged length to the available bytes and
        // therefore still recover the full climb ID.
        val volatileFlag = if (projectionSurvivesDisconnect) 0 else STRING_VOLATILE_FLAG
        val rejectFlag = if (acceptsDisconnect) 0 else STRING_DISCONNECT_REJECTED_FLAG
        val concurrentFlag =
            if (supportsConcurrentConnections) STRING_CONCURRENT_CONNECTIONS_FLAG else 0
        buf[6] = (len or volatileFlag or rejectFlag or concurrentFlag).toByte()
        idBytes.copyInto(buf, STRING_HEADER_SIZE, 0, len)
        return buf
    }

    private fun decodeString(data: ByteArray, last: Boolean): NearbyPayload? {
        if (data.size < STRING_HEADER_SIZE) return null
        val angle = data[5].toInt() and 0xFF
        val rawLength = data[6].toInt() and 0xFF
        val projectionSurvivesDisconnect = (rawLength and STRING_VOLATILE_FLAG) == 0
        val acceptsDisconnect = (rawLength and STRING_DISCONNECT_REJECTED_FLAG) == 0
        val supportsConcurrentConnections =
            (rawLength and STRING_CONCURRENT_CONNECTIONS_FLAG) != 0
        val idLen = (rawLength and 0x1F).coerceAtMost(data.size - STRING_HEADER_SIZE)
        if (idLen == 0) return null
        val climbId = String(data, STRING_HEADER_SIZE, idLen, Charsets.UTF_8)
        return if (last) {
            NearbyPayload.LastClimb(climbId, angle, projectionSurvivesDisconnect)
        } else {
            NearbyPayload.ClimbData(
                climbId,
                angle,
                projectionSurvivesDisconnect,
                acceptsDisconnect,
                supportsConcurrentConnections,
            )
        }
    }

    // --- Helpers ---

    /** Tries to parse a string as UUID. Accepts with/without hyphens, case-insensitive. */
    private fun parseUuid(s: String): java.util.UUID? {
        return try {
            java.util.UUID.fromString(s)
        } catch (_: IllegalArgumentException) {
            // Try without hyphens (32 hex chars)
            if (s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                try {
                    val withHyphens = "${s.substring(0, 8)}-${s.substring(8, 12)}-" +
                        "${s.substring(12, 16)}-${s.substring(16, 20)}-${s.substring(20)}"
                    java.util.UUID.fromString(withHyphens)
                } catch (_: IllegalArgumentException) {
                    null
                }
            } else {
                null
            }
        }
    }
}
