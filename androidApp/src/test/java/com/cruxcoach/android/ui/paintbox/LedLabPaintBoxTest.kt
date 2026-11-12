package com.cruxcoach.android.ui.paintbox

import android.app.Application
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.LedLab
import com.cruxcoach.android.ble.LedLabCase
import com.cruxcoach.android.ble.LedLabCases
import com.cruxcoach.android.ble.fakeLink
import com.cruxcoach.android.ble.paint
import com.cruxcoach.android.ble.refusal
import com.cruxcoach.android.ble.sha256
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardPacketEncoder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The LED lab's switches, from a PaintBox picture to the bytes on a fake link.
 *
 * Off means byte for byte what the release build sends: [RELEASE_SHA256] holds the hashes the
 * release head (e89875335) printed for the same pictures, sent the same way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LedLabPaintBoxTest {
    @After fun reset() = LedLab.reset()

    @Test fun `with every switch off a picture goes out byte for byte as in the release build`() = runTest {
        assertTrue(LedLab.switches.value.release)
        for (case in LedLabCase.entries) {
            val link = paint(case)
            assertEquals("$case", RELEASE_SHA256.getValue(case), sha256(link.bytes()))
            assertEquals("the pauses of $case", RELEASE_GAPS_MS.getValue(case), link.gaps().distinct())
        }
    }

    @Test fun `the MoonBoard pause follows the switch and the bytes stay the release build's`() = runTest {
        LedLab.clock = { currentTime }
        for (gap in listOf(20L, 5L)) {
            LedLab.change { it.copy(moonGapMs = gap) }
            val link = paint(LedLabCase.MOON_BELOW)
            assertEquals(RELEASE_SHA256.getValue(LedLabCase.MOON_BELOW), sha256(link.bytes()))
            assertEquals(listOf(gap), link.gaps().distinct())
            val report = LedLab.report.value!!
            assertEquals(60, report.leds)
            assertEquals(link.bytes().size, report.bytes)
            assertEquals(1, report.packets)
            assertEquals(link.writes.size, report.writes)
            assertEquals(link.writes.size, report.writesPlanned)
            assertEquals(gap * (link.writes.size - 1), report.durationMs)
            assertNull(report.failure)
            assertEquals(gap, report.switches.moonGapMs)
        }
    }

    @Test fun `both MoonBoard LEDs are two entries a hold, and the report counts the entries of the frame`() = runTest {
        val link = paint(LedLabCase.MOON_BOTH)
        val entries = link.bytes().decodeToString().removePrefix("l#").removeSuffix("#").split(',')
        assertTrue("more entries than holds", entries.size in 61..120)
        assertEquals(entries.size, LedLab.report.value!!.leds)
    }

    @Test fun `an API-2 picture above 127 holds is refused as in the release build`() = runTest {
        val link = fakeLink(BoardBrand.KILTER, apiLevel = 2)
        assertEquals(R.string.paintbox_api2_limit, refusal(link, LedLabCases.kilterPicture(128)))
        assertTrue(link.writes.isEmpty())
        assertTrue(LedLab.report.value!!.nothingWritten)
    }

    @Test fun `with the switch an API-2 picture above 127 holds goes out as a first and a last packet`() = runTest {
        LedLab.clock = { currentTime }
        LedLab.change { it.copy(api2SeveralPackets = true) }
        val link = fakeLink(BoardBrand.KILTER, apiLevel = 2)
        assertTrue(paint(link, LedLabCases.kilterPicture(130)))

        val packets = api2Packets(link.bytes())
        assertEquals(listOf(BoardPacketEncoder.API2_FIRST to 127, BoardPacketEncoder.API2_LAST to 3),
            packets.map { it.type to it.leds.size })
        val leds = packets.flatMap { it.leds }
        assertEquals("every hold once, in order, at its LED", (101..230).toList(), leds.map { it.first })
        assertEquals("one colour for the whole picture", 1, leds.map { it.second }.distinct().size)
        assertNotEquals("and it is lit", 0, leds.first().second)

        val report = LedLab.report.value!!
        assertEquals(130, report.leds)
        assertEquals(2, report.packets)
        // 01 len checksum 02 type, two bytes an LED, 03.
        assertEquals((5 + 127 * 2 + 1) + (5 + 3 * 2 + 1), report.bytes)
        assertEquals(13 + 1, report.writes)
        assertNull(report.failure)
        assertTrue(report.switches.api2SeveralPackets)

        // A picture that fits one packet goes out exactly as without the switch.
        val small = fakeLink(BoardBrand.KILTER, apiLevel = 2)
        assertTrue(paint(small, LedLabCase.API2_127.picture))
        assertEquals(RELEASE_SHA256.getValue(LedLabCase.API2_127), sha256(small.bytes()))
    }

    @Test fun `the switch does not lift the power limit of an API-2 box`() = runTest {
        LedLab.change { it.copy(api2SeveralPackets = true) }
        val link = fakeLink(BoardBrand.KILTER, apiLevel = 2)
        // 300 green holds are 600 LEDs: more than the picture budget lights at the lowest step.
        assertEquals(R.string.paintbox_too_bright, refusal(link, LedLabCases.kilterPicture(300)))
        assertTrue(link.writes.isEmpty())
    }

    @Test fun `a write the phone does not take is the report's write error`() = runTest {
        LedLab.clock = { currentTime }
        val link = fakeLink(BoardBrand.MOONBOARD, refuseWrite = 3)
        assertFalse(paint(link, LedLabCases.moonPicture(60)))
        val report = LedLab.report.value!!
        assertEquals(3, report.writes)
        assertTrue(report.writesPlanned > 4)
        assertEquals(LedLab.Failure(write = 4, of = report.writesPlanned, kind = LedLab.FailureKind.NOT_QUEUED), report.failure)
        assertEquals("the LEDs of the picture, not of what arrived", 60, report.leds)
    }

    private class Api2Packet(val type: Byte, val leds: List<Pair<Int, Int>>)

    /** Reads API-2 packets strictly: `01 len checksum 02 type (posLo, colour and posHi)… 03`, nothing between or after. */
    private fun api2Packets(bytes: ByteArray): List<Api2Packet> {
        val packets = mutableListOf<Api2Packet>()
        var at = 0
        while (at < bytes.size) {
            assertEquals("start of a packet at $at", 0x01, bytes[at].toInt())
            val length = bytes[at + 1].toInt() and 0xff
            assertEquals(0x02, bytes[at + 3].toInt())
            val payload = bytes.copyOfRange(at + 4, at + 4 + length)
            assertEquals("checksum", payload.sumOf { it.toInt() and 0xff }.inv() and 0xff, bytes[at + 2].toInt() and 0xff)
            assertEquals("end of the packet", 0x03, bytes[at + 4 + length].toInt())
            assertEquals("two bytes an LED", 0, (length - 1) % 2)
            packets += Api2Packet(payload[0], (1 until length step 2).map { i ->
                val low = payload[i].toInt() and 0xff
                val high = payload[i + 1].toInt() and 0xff
                (low or ((high and 0x03) shl 8)) to (high shr 2)
            })
            at += length + 5
        }
        return packets
    }

    private companion object {
        /** SHA-256 of the bytes on the link, printed by the release head e89875335 (see the PR text). */
        val RELEASE_SHA256 = mapOf(
            LedLabCase.MOON_BELOW to "3b0bff742b7b706581b7c3c11ba42cf2612a8eb30bea96b1ea4648dd0407228f",
            LedLabCase.MOON_ABOVE to "faf1836793fb3c440d9e7eb57cfdd0c66a2a74b4304043716f4a9ff8eaf69941",
            LedLabCase.MOON_BOTH to "407017b1348a0987651ad56ec7206cdf9148db27714654fb097fcd707a30df7c",
            LedLabCase.API2_127 to "04411c15b7398c8c4539b1f0ec5c685bef9dd54d0a716a4239a9609ae2833748",
            LedLabCase.API3_200 to "526777560f3a6bc3b4de9d369704774776c904d8c99919968e0f337f2a0ea4f4",
        )
        /** The distinct pauses between two writes, as the release head made them. */
        val RELEASE_GAPS_MS = mapOf(
            LedLabCase.MOON_BELOW to listOf(100L),
            LedLabCase.MOON_ABOVE to listOf(100L),
            LedLabCase.MOON_BOTH to listOf(100L),
            LedLabCase.API2_127 to listOf(0L),
            LedLabCase.API3_200 to listOf(0L),
        )
    }
}
