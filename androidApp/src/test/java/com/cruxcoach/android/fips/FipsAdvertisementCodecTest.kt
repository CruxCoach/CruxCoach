package com.cruxcoach.android.fips

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FipsAdvertisementCodecTest {
    @Test fun `board advertisement exposes complete joinable cell id`() {
        val cell = "01234567-89ab-cdef-0123-456789abcdef"
        val realm = FipsRealmContext(cell, cell)
        val nonce = byteArrayOf(1, 2, 3, 4)
        val decoded = FipsAdvertisementCodec.decode(FipsAdvertisementCodec.encode(realm, 0x1234, nonce))!!

        assertEquals(0x1234, decoded.psm)
        assertEquals(cell, decoded.joinableBoardCellId)
        assertContentEquals(realm.realmTag, decoded.realmTag)
        assertContentEquals(realm.cellTag, decoded.cellTag)
        assertContentEquals(nonce, decoded.nonceTag)
    }

    @Test fun `competition advertisement remains private legacy format`() {
        val realm = FipsRealmContext("competition-1", "cell-1", FipsRealmKind.COMPETITION)
        val decoded = FipsAdvertisementCodec.decode(
            FipsAdvertisementCodec.encode(realm, 7, byteArrayOf(4, 3, 2, 1)),
        )!!

        assertEquals(7, decoded.psm)
        assertNull(decoded.joinableBoardCellId)
        assertContentEquals(realm.realmTag, decoded.realmTag)
        assertContentEquals(realm.cellTag, decoded.cellTag)
    }
}
