package com.cruxcoach.board

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.ShinyWallFrameEncoder
import com.cruxcoach.domain.board.ShinyWallName
import com.cruxcoach.domain.board.ShinyWallVariant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShinyWallTest {

    private fun hold(variant: ShinyWallVariant, index: Int, role: Int) =
        BoardHold(variant.placementId(index), role)

    @Test
    fun variantIdsAreUniqueAndOutsideOtherFamilies() {
        val layouts = ShinyWallVariant.entries.map { it.layoutId }
        val sizes = ShinyWallVariant.entries.map { it.productSizeId }
        assertEquals(layouts.size, layouts.toSet().size)
        assertEquals(sizes.size, sizes.toSet().size)
        assertTrue(layouts.all { it in 9301L..9306L })
        assertTrue(sizes.all { it in 9401L..9406L })
        // The backup restore validator caps layout ids at 100 000.
        assertTrue(layouts.all { it < 100_000L })
    }

    @Test
    fun variantsResolveByCodeLayoutAndSize() {
        assertEquals(ShinyWallVariant.ST1, ShinyWallVariant.fromCode("st1"))
        assertEquals(ShinyWallVariant.ST5, ShinyWallVariant.fromCode("ST5"))
        assertNull(ShinyWallVariant.fromCode("st9"))
        assertEquals(ShinyWallVariant.ST2, ShinyWallVariant.fromLayoutId(9303L))
        assertEquals(ShinyWallVariant.ST4, ShinyWallVariant.fromProductSizeId(9405L))
        assertEquals(ShinyWallVariant.ST1, ShinyWallVariant.fromBoardSelection(9302L, BoardBrand.SHINYWALL))
        assertNull(ShinyWallVariant.fromBoardSelection(9302L, BoardBrand.KILTER))
    }

    @Test
    fun st3StaysOutUntilItsLayoutIsKnown() {
        assertEquals(false, ShinyWallVariant.ST3.supported)
        assertEquals(
            listOf(ShinyWallVariant.ST0, ShinyWallVariant.ST1, ShinyWallVariant.ST2, ShinyWallVariant.ST4, ShinyWallVariant.ST5),
            ShinyWallVariant.SUPPORTED,
        )
        assertNull(ShinyWallFrameEncoder.encodeRoute(emptyList(), ShinyWallVariant.ST3))
    }

    @Test
    fun anglesAreZeroToSixtyInFiveDegreeSteps() {
        assertEquals((0..60 step 5).toList(), ShinyWallVariant.ST1.angles)
    }

    @Test
    fun placementIdsCarryTheVariant() {
        assertEquals(2_000_000, ShinyWallVariant.ST1.placementId(0))
        assertEquals(2_000_310, ShinyWallVariant.ST1.placementId(310))
        assertEquals(6_000_610, ShinyWallVariant.ST5.placementId(610))
        assertEquals(310, ShinyWallVariant.ST1.indexOf(2_000_310))
        // Another variant's hold, or a number past the wall, is not on st1.
        assertNull(ShinyWallVariant.ST1.indexOf(ShinyWallVariant.ST4.placementId(5)))
        assertNull(ShinyWallVariant.ST1.indexOf(2_000_311))
        assertNull(ShinyWallVariant.ST1.indexOf(42))
        assertFailsWith<IllegalArgumentException> { ShinyWallVariant.ST1.placementId(311) }
        assertFailsWith<IllegalArgumentException> { ShinyWallVariant.ST1.placementId(-1) }
    }

    @Test
    fun rolesTranslateToControllerCodes() {
        assertEquals(2, ShinyWallFrameEncoder.roleCode(HoldRole.START))
        assertEquals(4, ShinyWallFrameEncoder.roleCode(HoldRole.HAND))
        assertEquals(6, ShinyWallFrameEncoder.roleCode(HoldRole.FOOT))
        assertEquals(8, ShinyWallFrameEncoder.roleCode(HoldRole.FINISH))
        // Route-variant ids fold onto the same roles.
        assertEquals(2, ShinyWallFrameEncoder.roleCode(HoldRole.ROUTE_START))
        assertEquals(8, ShinyWallFrameEncoder.roleCode(HoldRole.ROUTE_FINISH))
        assertEquals(0, ShinyWallFrameEncoder.roleCode(99))
    }

    @Test
    fun routeIsOneCodePerPhysicalHold() {
        val v = ShinyWallVariant.ST1
        val message = ShinyWallFrameEncoder.encodeRoute(
            listOf(
                hold(v, 0, HoldRole.START),
                hold(v, 2, HoldRole.HAND),
                hold(v, 3, HoldRole.FOOT),
                hold(v, 310, HoldRole.FINISH),
            ),
            v,
        )!!
        assertTrue(message.startsWith("+20460"))
        assertTrue(message.endsWith("08."))
        assertEquals(v.holdCount + 2, message.length)
        val codes = message.removePrefix("+").removeSuffix(".")
        assertTrue(codes.all { it in "02468" })
        assertEquals(4, codes.count { it != '0' })
    }

    @Test
    fun emptyRouteIsAllOff() {
        val message = ShinyWallFrameEncoder.encodeRoute(emptyList(), ShinyWallVariant.ST0)!!
        assertEquals("+" + "0".repeat(ShinyWallVariant.ST0.holdCount) + ".", message)
    }

    @Test
    fun foreignOrUnknownHoldsRefuseTheWholeRoute() {
        val st1 = ShinyWallVariant.ST1
        assertNull(
            ShinyWallFrameEncoder.encodeRoute(
                listOf(hold(st1, 1, HoldRole.START), hold(ShinyWallVariant.ST2, 1, HoldRole.HAND)),
                st1,
            ),
        )
        assertNull(ShinyWallFrameEncoder.encodeRoute(listOf(BoardHold(1091, HoldRole.HAND)), st1))
        assertNull(ShinyWallFrameEncoder.encodeRoute(listOf(hold(st1, 1, 99)), st1))
    }

    @Test
    fun duplicateHoldKeepsItsFirstRole() {
        val v = ShinyWallVariant.ST1
        val codes = ShinyWallFrameEncoder.stateVector(
            listOf(hold(v, 7, HoldRole.START), hold(v, 7, HoldRole.HAND)),
            v,
        )!!
        assertEquals(2, codes[7])
    }

    @Test
    fun chunksFollowTheNegotiatedMtu() {
        val message = ShinyWallFrameEncoder.encodeRoute(emptyList(), ShinyWallVariant.ST5)!!
        for (mtu in listOf(23, 185, 247, 512)) {
            val chunks = ShinyWallFrameEncoder.chunks(message, mtu)
            assertTrue(chunks.all { it.size <= mtu - 5 }, "mtu $mtu")
            assertContentEquals(message.encodeToByteArray(), chunks.fold(ByteArray(0)) { acc, c -> acc + c })
        }
        assertEquals(1, ShinyWallFrameEncoder.chunks(ShinyWallFrameEncoder.CLEAR, 23).size)
        // A bogus MTU never yields chunks smaller than the default ATT payload allows.
        val small = ShinyWallFrameEncoder.chunks(message, 0)
        assertTrue(small.dropLast(1).all { it.size == 18 })
    }

    @Test
    fun routeWritesClearFirstThenSendTheVector() {
        val v = ShinyWallVariant.ST1
        val writes = ShinyWallFrameEncoder.routeWrites(listOf(hold(v, 4, HoldRole.START)), v, 247)!!
        assertEquals("-", writes.first().decodeToString())
        val route = writes.drop(1).joinToString("") { it.decodeToString() }
        assertEquals(ShinyWallFrameEncoder.encodeRoute(listOf(hold(v, 4, HoldRole.START)), v), route)
        assertNull(ShinyWallFrameEncoder.routeWrites(listOf(BoardHold(5, HoldRole.START)), v, 247))
        assertEquals(listOf("-"), ShinyWallFrameEncoder.clearWrites(23).map { it.decodeToString() })
    }

    @Test
    fun highlightLightsTheGivenHoldsAsHands() {
        val v = ShinyWallVariant.ST4
        val ids = setOf(v.placementId(3), v.placementId(1))
        val writes = ShinyWallFrameEncoder.highlightWrites(ids, 512)!!
        val codes = writes.drop(1).joinToString("") { it.decodeToString() }.removePrefix("+").removeSuffix(".")
        assertEquals(v.holdCount, codes.length)
        assertEquals('4', codes[1])
        assertEquals('4', codes[3])
        assertEquals(2, codes.count { it != '0' })
        assertEquals(v, ShinyWallVariant.fromPlacementId(v.placementId(3)))
        assertNull(ShinyWallFrameEncoder.highlightWrites(emptySet(), 512))
        assertNull(ShinyWallFrameEncoder.highlightWrites(setOf(v.placementId(1), ShinyWallVariant.ST1.placementId(1)), 512))
        assertNull(ShinyWallFrameEncoder.highlightWrites(setOf(77), 512))
    }

    @Test
    fun messageStartsAreRecognisedForPacing() {
        val v = ShinyWallVariant.ST5
        val writes = ShinyWallFrameEncoder.routeWrites(listOf(hold(v, 600, HoldRole.FINISH)), v, 23)!!
        assertEquals(listOf(true, true), writes.take(2).map(ShinyWallFrameEncoder::startsMessage))
        assertTrue(writes.drop(2).none(ShinyWallFrameEncoder::startsMessage))
        assertEquals(false, ShinyWallFrameEncoder.startsMessage(ByteArray(0)))
    }

    @Test
    fun advertisedNamesGiveAVariantHint() {
        assertEquals(ShinyWallVariant.ST1, ShinyWallName.parse("ShinyWall_STP_A1B2C3")?.variantHint)
        assertEquals("A1B2C3", ShinyWallName.parse("ShinyWall_STP_A1B2C3")?.serial)
        assertEquals(ShinyWallVariant.ST4, ShinyWallName.parse("ShinyWall_STF_x")?.variantHint)
        assertEquals(ShinyWallVariant.ST2, ShinyWallName.parse("ShinyWall_STM_x")?.variantHint)
        assertEquals(ShinyWallVariant.ST5, ShinyWallName.parse("ShinyWall_STMF_x")?.variantHint)
        assertEquals(ShinyWallVariant.ST0, ShinyWallName.parse("Shiny Wall ST0 (ShinyMini)")?.variantHint)
        // An unknown Standard code is still a Standard wall, without a hint.
        val unknown = ShinyWallName.parse("ShinyWall_STX_1")
        assertEquals("1", unknown?.serial)
        assertNull(unknown?.variantHint)
    }

    @Test
    fun customAndUnrelatedNamesAreNotStandardWalls() {
        assertNull(ShinyWallName.parse("ShinyWall_CTM_1234"))
        assertNull(ShinyWallName.parse("ShinyWall_1234"))
        assertNull(ShinyWallName.parse("Kilter Board#12345@3"))
        assertNull(ShinyWallName.parse("MoonBoard A"))
        assertNull(ShinyWallName.parse("ESP32"))
    }
}
