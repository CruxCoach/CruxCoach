package com.cruxcoach.android.fips

import java.nio.ByteBuffer
import java.util.UUID

internal data class FipsAdvertisement(
    val psm: Int,
    val realmTag: ByteArray,
    val cellTag: ByteArray,
    val nonceTag: ByteArray,
    val joinableBoardCellId: String?,
)

/** Compact BLE service data. V2 carries the complete public BoardCell UUID so a
 * nearby CruxCoach install can join without first pairing with the board or a
 * separately advertised playlist session. */
internal object FipsAdvertisementCodec {
    const val LEGACY_VERSION: Byte = 1
    const val BOARD_CELL_VERSION: Byte = 2
    const val LEGACY_BYTES = 15
    const val BOARD_CELL_BYTES = 23

    fun encode(realm: FipsRealmContext, psm: Int, nonceTag: ByteArray): ByteArray {
        require(psm in 1..0xffff && nonceTag.size == FipsRealmContext.TAG_BYTES)
        if (realm.kind != FipsRealmKind.BOARD_CELL) return byteArrayOf(
            LEGACY_VERSION, psm.toByte(), (psm shr 8).toByte(),
            *realm.realmTag, *realm.cellTag, *nonceTag,
        )
        val uuid = UUID.fromString(realm.boardCellId)
        val id = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
        return byteArrayOf(BOARD_CELL_VERSION, psm.toByte(), (psm shr 8).toByte(), *id, *nonceTag)
    }

    fun decode(bytes: ByteArray): FipsAdvertisement? = when {
        bytes.size == LEGACY_BYTES && bytes[0] == LEGACY_VERSION -> FipsAdvertisement(
            psm = psm(bytes),
            realmTag = bytes.copyOfRange(3, 7),
            cellTag = bytes.copyOfRange(7, 11),
            nonceTag = bytes.copyOfRange(11, 15),
            joinableBoardCellId = null,
        )
        bytes.size == BOARD_CELL_BYTES && bytes[0] == BOARD_CELL_VERSION -> {
            val buffer = ByteBuffer.wrap(bytes, 3, 16)
            val cellId = UUID(buffer.long, buffer.long).toString()
            val realm = FipsRealmContext(cellId, cellId)
            FipsAdvertisement(
                psm = psm(bytes),
                realmTag = realm.realmTag,
                cellTag = realm.cellTag,
                nonceTag = bytes.copyOfRange(19, 23),
                joinableBoardCellId = cellId,
            )
        }
        else -> null
    }

    private fun psm(bytes: ByteArray) =
        (bytes[1].toInt() and 255) or ((bytes[2].toInt() and 255) shl 8)
}
