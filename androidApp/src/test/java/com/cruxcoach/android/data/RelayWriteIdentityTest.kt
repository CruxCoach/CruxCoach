package com.cruxcoach.android.data

import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.relay.CompleteClimb
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the relay concluded about a guest's bytes, and what it does with a
 * conclusion it could not reach.
 *
 * Identification used to answer with a climb or with `null`, and the relay
 * forwarded both. Now the answer decides whether a wall is written at all, so
 * the four outcomes have to be told apart — and "nothing is known" has to stay
 * retryable, because one kind of it is a catalogue that was momentarily unable
 * to answer rather than a write that will never be readable.
 */
class RelayWriteIdentityTest {

    private val encoder = BoardPacketEncoder(apiLevel = 3)

    /** A complete climb exactly as the official app puts it on the wire. */
    private fun climb(leds: List<Int> = listOf(10, 11, 12, 13, 14)): CompleteClimb {
        val raw = encoder.encodeClimb(leds.map { it to 0x1C })
            .flatMap { it.toList() }.toByteArray()
        return CompleteClimb(
            rawBytes = raw,
            chunks = listOf(raw),
            framesHash = leds.fold(17L) { acc, led -> acc * 31 + led },
            holdCount = leds.size,
        )
    }

    private fun identifier(
        ledMap: () -> Map<Int, Int>,
        candidates: List<com.cruxcoach.data.repository.RelayClimbCandidate> = emptyList(),
    ): RelayClimbIdentifier {
        val repository = mockk<BoardRepository>(relaxed = true) {
            every { getPlacementLedMap(any(), any()) } answers { ledMap() }
            every { getRoleColorMapForBrand(any()) } returns emptyMap()
            every { ensureRelayLookupIndex() } returns true
            every {
                findClimbCandidatesByFrames(any(), any(), any(), any(), any(), any())
            } returns candidates
        }
        val preferences = mockk<UserPreferences>(relaxed = true) {
            every { boardBrand } returns MutableStateFlow("kilter")
            every { boardProductSizeId } returns MutableStateFlow(1)
            every { boardLayoutId } returns MutableStateFlow(1)
            every { boardAngle } returns MutableStateFlow(40)
        }
        return RelayClimbIdentifier(repository, preferences)
    }

    /** placement→LED for exactly the LEDs this board has. */
    private fun mapFor(leds: List<Int>): Map<Int, Int> =
        leds.withIndex().associate { (index, led) -> 100 + index to led }

    @Test
    fun `a climb this board can show but the catalogue does not know is anonymous`() = runTest {
        val leds = listOf(10, 11, 12, 13, 14)
        val identity = identifier(ledMap = { mapFor(leds) }).identify(climb(leds))

        assertEquals(RelayWriteIdentity.Anonymous, identity)
    }

    /** LEDs the configured board does not have were written for another wall. */
    @Test
    fun `a climb whose leds are not on this board is a foreign board`() = runTest {
        val identity = identifier(ledMap = { mapFor(listOf(90, 91, 92)) })
            .identify(climb(listOf(10, 11, 12, 13, 14)))

        assertEquals(RelayWriteIdentity.ForeignBoard, identity)
    }

    /** Bytes that are not a climb packet at all say nothing about anything. */
    @Test
    fun `bytes that do not decode are undecidable`() = runTest {
        val garbage = CompleteClimb(
            rawBytes = byteArrayOf(0x7F, 0x7E, 0x7D),
            chunks = listOf(byteArrayOf(0x7F, 0x7E, 0x7D)),
            framesHash = 4242L, holdCount = 0,
        )

        val identity = identifier(ledMap = { emptyMap() }).identify(garbage)

        assertEquals(RelayWriteIdentity.Undecidable, identity)
    }

    /** A board with no LED map cannot be checked, so nothing is known about it. */
    @Test
    fun `a catalogue with no led map is undecidable rather than nameless`() = runTest {
        val identity = identifier(ledMap = { emptyMap() }).identify(climb())

        assertEquals(RelayWriteIdentity.Undecidable, identity)
    }

    /**
     * The one that matters most. "Nothing is known" is reached both by bytes
     * that will never decode and by a catalogue that could not answer *this
     * once* — and caching the second kind would turn one transient failure into
     * a climb this relay refuses for the rest of the cache's life. That was
     * harmless while a miss meant "forward it anyway"; it decides the write now.
     */
    @Test
    fun `a catalogue failure is not remembered as a verdict`() = runTest {
        val leds = listOf(10, 11, 12, 13, 14)
        var failNext = true
        val subject = identifier(ledMap = {
            if (failNext) {
                failNext = false
                throw IllegalStateException("catalogue unavailable")
            }
            mapFor(leds)
        })
        val write = climb(leds)

        val first = subject.identify(write)
        val second = subject.identify(write)

        assertEquals(RelayWriteIdentity.Undecidable, first)
        assertNotEquals("the failure was cached as a refusal", first, second)
        assertEquals(RelayWriteIdentity.Anonymous, second)
    }

    /** A verdict it really did reach is cached; the catalogue is not re-read. */
    @Test
    fun `a reached verdict is answered from the cache`() = runTest {
        val leds = listOf(10, 11, 12, 13, 14)
        var reads = 0
        val subject = identifier(ledMap = { reads += 1; mapFor(leds) })
        val write = climb(leds)

        subject.identify(write)
        subject.identify(write)

        assertTrue("the second answer came from the cache", reads <= 1)
    }
}
