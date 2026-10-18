// Port of androidApp/.../ble/NearbyClimbProtocol.kt (0e1bad0c6): the manufacturer-data payloads
// Android phones advertise (company id 0xFFFF, session climb 0xFFFE, "CRUX" magic). The iPhone
// only decodes them (iOS cannot put manufacturer data into its own advertisements); the encoders
// stay so Android's protocol tests run unchanged here.
package com.cruxcoach.app.nearby

import com.cruxcoach.app.sharedsession.ClimbUuidBytes

internal sealed class NearbyPayload {
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

    /** Board connected without a specific climb. */
    data class BoardConnected(
        val acceptsDisconnect: Boolean = true,
        val supportsConcurrentConnections: Boolean = false,
        /** Per-process sender identity used only to suppress a scanner's own advertisement. */
        val senderToken: Int? = null,
    ) : NearbyPayload()

    /** The sender is going away — scanners remove its entries immediately. */
    data object Gone : NearbyPayload()

    /** A nearby device is hosting a session that can be joined. */
    data class SessionAdvertisement(val sessionId: Int, val participantCount: Int, val hostName: String) : NearbyPayload()

    data class DisconnectResponse(val accepted: Boolean) : NearbyPayload()
}

internal object NearbyClimbProtocol {
    const val COMPANY_ID = 0xFFFF

    /** Secondary company ID for climb data embedded in a session's scan response. */
    const val SESSION_CLIMB_COMPANY_ID = 0xFFFE
    val MAGIC = byteArrayOf(0x43, 0x52, 0x55, 0x58) // "CRUX"

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
        val uuid = ClimbUuidBytes.parse(climbId)
        return if (uuid != null) {
            encodeAsUuid(uuid, angle, climbId.contains('-'), TYPE_CLIMB_UUID,
                projectionSurvivesDisconnect, acceptsDisconnect, supportsConcurrentConnections)
        } else {
            encodeAsString(climbId, angle, TYPE_CLIMB_STRING,
                projectionSurvivesDisconnect, acceptsDisconnect, supportsConcurrentConnections)
        }
    }

    fun encodeLastClimb(climbId: String, angle: Int, projectionSurvivesDisconnect: Boolean = true): ByteArray {
        val uuid = ClimbUuidBytes.parse(climbId)
        return if (uuid != null) {
            encodeAsUuid(uuid, angle, climbId.contains('-'), TYPE_LAST_CLIMB_UUID,
                projectionSurvivesDisconnect, true, false)
        } else {
            encodeAsString(climbId, angle, TYPE_LAST_CLIMB_STRING, projectionSurvivesDisconnect, true, false)
        }
    }

    fun encodeDisconnectRequest(): ByteArray = sixBytes(TYPE_DISCONNECT_REQUEST, 0)

    fun encodeBoardConnected(
        acceptsDisconnect: Boolean = true,
        supportsConcurrentConnections: Boolean = false,
        senderToken: Int? = null,
    ): ByteArray {
        val buf = ByteArray(if (senderToken == null) DISCONNECT_SIZE else DISCONNECT_SIZE + 4)
        MAGIC.copyInto(buf, 0)
        buf[4] = TYPE_BOARD_CONNECTED
        val disconnectFlag = if (acceptsDisconnect) 0x01 else 0x02
        val capacityFlag = if (supportsConcurrentConnections) CONCURRENT_CONNECTIONS_FLAG else 0
        buf[5] = (disconnectFlag or capacityFlag).toByte()
        senderToken?.let { token ->
            buf[6] = (token and 0xFF).toByte()
            buf[7] = ((token ushr 8) and 0xFF).toByte()
            buf[8] = ((token ushr 16) and 0xFF).toByte()
            buf[9] = ((token ushr 24) and 0xFF).toByte()
        }
        return buf
    }

    fun encodeDisconnectResponse(accepted: Boolean): ByteArray =
        sixBytes(TYPE_DISCONNECT_RESPONSE, if (accepted) 0x01 else 0x00)

    fun encodeGone(): ByteArray = sixBytes(TYPE_GONE, 0)

    private fun sixBytes(type: Byte, value: Int): ByteArray {
        val buf = ByteArray(DISCONNECT_SIZE)
        MAGIC.copyInto(buf, 0)
        buf[4] = type
        buf[5] = value.toByte()
        return buf
    }

    /** [4 CRUX][1 type=0x08][4 sessionId LE][1 participantCount][1 nameLen][N name UTF-8], N ≤ 13. */
    fun encodeSessionAdvertisement(sessionId: Int, participantCount: Int, hostName: String): ByteArray {
        val nameBytes = hostName.encodeToByteArray()
        val nameLen = nameBytes.size.coerceAtMost(13)
        val buf = ByteArray(11 + nameLen)
        MAGIC.copyInto(buf, 0)
        buf[4] = TYPE_SESSION
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
        return when (data[4]) {
            TYPE_CLIMB_UUID -> decodeUuid(data, last = false)
            TYPE_CLIMB_STRING -> decodeString(data, last = false)
            TYPE_LAST_CLIMB_UUID -> decodeUuid(data, last = true)
            TYPE_LAST_CLIMB_STRING -> decodeString(data, last = true)
            TYPE_DISCONNECT_REQUEST -> NearbyPayload.DisconnectRequest
            TYPE_BOARD_CONNECTED -> {
                val flags = if (data.size > 5) data[5].toInt() and 0xFF else 0
                NearbyPayload.BoardConnected(
                    acceptsDisconnect = (flags and DISCONNECT_REJECTED_FLAG) == 0,
                    supportsConcurrentConnections = (flags and CONCURRENT_CONNECTIONS_FLAG) != 0,
                    senderToken = if (data.size >= DISCONNECT_SIZE + 4) {
                        (data[6].toInt() and 0xFF) or
                            ((data[7].toInt() and 0xFF) shl 8) or
                            ((data[8].toInt() and 0xFF) shl 16) or
                            ((data[9].toInt() and 0xFF) shl 24)
                    } else {
                        null
                    },
                )
            }
            TYPE_GONE -> NearbyPayload.Gone
            TYPE_SESSION -> decodeSessionAdvertisement(data)
            TYPE_DISCONNECT_RESPONSE -> NearbyPayload.DisconnectResponse(
                accepted = data.size > 5 && (data[5].toInt() and 0xFF) == 0x01,
            )
            else -> null
        }
    }

    private fun decodeSessionAdvertisement(data: ByteArray): NearbyPayload.SessionAdvertisement? {
        if (data.size < 11) return null
        val sessionId = (data[5].toInt() and 0xFF) or
            ((data[6].toInt() and 0xFF) shl 8) or
            ((data[7].toInt() and 0xFF) shl 16) or
            ((data[8].toInt() and 0xFF) shl 24)
        val participantCount = data[9].toInt() and 0xFF
        val nameLen = (data[10].toInt() and 0xFF).coerceAtMost(data.size - 11)
        val hostName = if (nameLen > 0) data.decodeToString(11, 11 + nameLen) else ""
        return NearbyPayload.SessionAdvertisement(sessionId, participantCount, hostName)
    }

    private fun encodeAsUuid(
        uuid: ByteArray,
        angle: Int,
        hasHyphens: Boolean,
        type: Byte,
        projectionSurvivesDisconnect: Boolean,
        acceptsDisconnect: Boolean,
        supportsConcurrentConnections: Boolean,
    ): ByteArray {
        val hasFlags = !projectionSurvivesDisconnect || !acceptsDisconnect || supportsConcurrentConnections
        val buf = ByteArray(UUID_PAYLOAD_SIZE + if (hasFlags) 1 else 0)
        MAGIC.copyInto(buf, 0)
        buf[4] = type
        val angleBits = angle.coerceIn(0, 70)
        val flagBit = if (hasHyphens) UUID_HYPHEN_FLAG else 0
        buf[5] = (angleBits or flagBit).toByte()
        uuid.copyInto(buf, 6)
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
        val formatted = if (hasHyphens) ClimbUuidBytes.hyphenated(data, 6) else ClimbUuidBytes.hex(data, 6).uppercase()
        val projectionSurvivesDisconnect = data.size == UUID_PAYLOAD_SIZE ||
            (data[UUID_PAYLOAD_SIZE].toInt() and PROJECTION_RETAINED_FLAG) != 0
        val acceptsDisconnect = data.size == UUID_PAYLOAD_SIZE ||
            (data[UUID_PAYLOAD_SIZE].toInt() and DISCONNECT_REJECTED_FLAG) == 0
        val supportsConcurrentConnections = data.size > UUID_PAYLOAD_SIZE &&
            (data[UUID_PAYLOAD_SIZE].toInt() and CONCURRENT_CONNECTIONS_FLAG) != 0
        return if (last) {
            NearbyPayload.LastClimb(formatted, angle, projectionSurvivesDisconnect)
        } else {
            NearbyPayload.ClimbData(formatted, angle, projectionSurvivesDisconnect, acceptsDisconnect,
                supportsConcurrentConnections)
        }
    }

    private fun encodeAsString(
        climbId: String,
        angle: Int,
        type: Byte,
        projectionSurvivesDisconnect: Boolean,
        acceptsDisconnect: Boolean,
        supportsConcurrentConnections: Boolean,
    ): ByteArray {
        val idBytes = climbId.encodeToByteArray()
        val len = idBytes.size.coerceAtMost(MAX_STRING_ID_LENGTH)
        val buf = ByteArray(STRING_HEADER_SIZE + len)
        MAGIC.copyInto(buf, 0)
        buf[4] = type
        buf[5] = angle.coerceIn(0, 70).toByte()
        val volatileFlag = if (projectionSurvivesDisconnect) 0 else STRING_VOLATILE_FLAG
        val rejectFlag = if (acceptsDisconnect) 0 else STRING_DISCONNECT_REJECTED_FLAG
        val concurrentFlag = if (supportsConcurrentConnections) STRING_CONCURRENT_CONNECTIONS_FLAG else 0
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
        val supportsConcurrentConnections = (rawLength and STRING_CONCURRENT_CONNECTIONS_FLAG) != 0
        val idLen = (rawLength and 0x1F).coerceAtMost(data.size - STRING_HEADER_SIZE)
        if (idLen == 0) return null
        val climbId = data.decodeToString(STRING_HEADER_SIZE, STRING_HEADER_SIZE + idLen)
        return if (last) {
            NearbyPayload.LastClimb(climbId, angle, projectionSurvivesDisconnect)
        } else {
            NearbyPayload.ClimbData(climbId, angle, projectionSurvivesDisconnect, acceptsDisconnect,
                supportsConcurrentConnections)
        }
    }
}
