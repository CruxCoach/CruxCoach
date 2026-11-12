package com.cruxcoach.android.ble

import android.os.SystemClock
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.board.QuantumCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Switches and a send report for the wall test. Branch `feat/0.2.5-led-lab`, never merged.
 *
 * Every switch starts on what the release build does and is back there when the app starts
 * again: nothing here is stored. The switches act on PaintBox pictures only; climbs, hold
 * cleaning and the wall games send as in the release build whatever is set here.
 */
object LedLab {
    /** The pause the release build makes between the 20-byte writes of a MoonBoard frame. */
    const val MOON_GAP_RELEASE_MS = 100L

    /** The pauses the lab offers, the release build's first. */
    val MOON_GAPS_MS = listOf(MOON_GAP_RELEASE_MS, 20L, 5L)

    data class Switches(
        /** Pause between the 20-byte writes of a PaintBox picture to a MoonBoard. */
        val moonGapMs: Long = MOON_GAP_RELEASE_MS,
        /**
         * A PaintBox picture of more than 127 holds goes to an API-2 box in several packets
         * (first, middle, last) instead of being refused.
         */
        val api2SeveralPackets: Boolean = false,
        /**
         * A PaintBox picture with more than 92 LEDs of one colour goes to a Quantum board in several
         * messages of at most 92 diodes, the way the eWalls app splits long routes, instead of being refused.
         */
        val quantumSeveralMessages: Boolean = false,
    ) {
        /** True while every switch is where the release build is. */
        val release: Boolean get() = this == Switches()
    }

    private val mutableSwitches = MutableStateFlow(Switches())
    val switches: StateFlow<Switches> = mutableSwitches.asStateFlow()

    fun change(transform: (Switches) -> Switches) = mutableSwitches.update(transform)

    /** Why a write did not go through. */
    enum class FailureKind {
        /** The phone's Bluetooth stack did not take the write. */
        NOT_QUEUED,
        /** No answer from the box within the write timeout. */
        NO_ANSWER,
        /** The box answered with a GATT status other than success. */
        STATUS,
        /** The link was closed or replaced before the write. */
        LINK_GONE,
    }

    /** The write that failed, counted from 1, of [of] planned ones. */
    data class Failure(val write: Int, val of: Int, val kind: FailureKind, val status: Int? = null)

    /**
     * What one PaintBox send put on the link. LEDs and packets are read from the bytes handed
     * to the link, not from the picture, so the numbers are what the box was sent.
     */
    data class Report(
        /** LED entries in the frames: on a MoonBoard two per hold when both LEDs are lit. */
        val leds: Int,
        /** Bytes of all frames of this send. */
        val bytes: Int,
        /** Protocol packets: MoonBoard frames, Aurora packets, Quantum messages. */
        val packets: Int,
        /** Packets that carry LEDs (a Quantum send also switches off and reads back). */
        val ledPackets: Int,
        /** Bluetooth writes that came back as done (acknowledged by the box where the link writes with response). */
        val writes: Int,
        /** Bluetooth writes the send was made of. */
        val writesPlanned: Int,
        /** From the first write to the answer to the last one, pauses included. */
        val durationMs: Long,
        val failure: Failure?,
        /** The switches the send went out with. */
        val switches: Switches,
        /** Quantum only: the refusal the controller answered with, or null. */
        val boardAnswer: String? = null,
    ) {
        /** Nothing reached the link: the send was refused before its first write. */
        val nothingWritten: Boolean get() = writesPlanned == 0
    }

    private val mutableReport = MutableStateFlow<Report?>(null)
    /** The last PaintBox send, or null before the first one. */
    val report: StateFlow<Report?> = mutableReport.asStateFlow()

    /** Milliseconds that only go forward. Tests put their virtual time here. */
    @Volatile internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /** The writes of one PaintBox send. */
    class Recording internal constructor() {
        internal val frames = mutableListOf<List<ByteArray>>()
        internal var writes = 0
        internal var attempts = 0
        internal var failure: Pair<FailureKind, Int?>? = null
        internal var failedAttempt = 0
        internal var firstAt: Long? = null
        internal var lastAt: Long? = null
    }

    @Volatile private var recording: Recording? = null

    /** PaintBox starts a send: writes are recorded until [end]. */
    fun begin(): Recording = Recording().also { recording = it }

    /**
     * True only while a PaintBox send is being written with the Quantum switch on. The connection then
     * lets an activation of more than 92 diodes out in several messages. A climb, a list or a wall game
     * never sees this as true, whatever the switch says.
     */
    internal fun quantumSeveralMessagesNow(): Boolean = recording != null && switches.value.quantumSeveralMessages

    /** The connection is about to write [chunks], one frame or one group of packets. */
    internal fun frame(chunks: List<ByteArray>) {
        val current = recording ?: return
        synchronized(current) {
            current.frames.add(chunks.map { it.copyOf() })
            if (current.firstAt == null) current.firstAt = clock()
        }
    }

    /** One write came back: [failure] is null when it came back as done. */
    internal fun wrote(failure: FailureKind?, status: Int? = null) {
        val current = recording ?: return
        synchronized(current) {
            current.attempts++
            current.lastAt = clock()
            if (failure == null) current.writes++
            else if (current.failure == null) {
                current.failure = failure to status
                current.failedAttempt = current.attempts
            }
        }
    }

    /**
     * PaintBox ends the send it began with [recording]; the report is for a [brand] board.
     * [boardAnswer] is what a Quantum controller refused the send with, if it did.
     */
    fun end(recording: Recording, brand: BoardBrand?, boardAnswer: String? = null) {
        if (this.recording === recording) this.recording = null
        mutableReport.value = synchronized(recording) {
            val counted = count(brand, recording.frames)
            val planned = recording.frames.sumOf { it.size }
            Report(
                leds = counted.leds,
                bytes = recording.frames.sumOf { frame -> frame.sumOf { it.size } },
                packets = counted.packets,
                ledPackets = counted.ledPackets,
                writes = recording.writes,
                writesPlanned = planned,
                durationMs = (recording.lastAt ?: 0L) - (recording.firstAt ?: recording.lastAt ?: 0L),
                failure = recording.failure?.let { (kind, status) -> Failure(recording.failedAttempt, planned, kind, status) },
                switches = switches.value,
                boardAnswer = boardAnswer,
            )
        }
    }

    /** Back to the release build's behaviour, and no report. For the lab's reset button and for tests. */
    fun reset() {
        mutableSwitches.value = Switches()
        mutableReport.value = null
        recording = null
        clock = { SystemClock.elapsedRealtime() }
    }

    internal data class Counted(val leds: Int, val packets: Int, val ledPackets: Int)

    /** LEDs and protocol packets in [frames] as a [brand] board reads them. */
    internal fun count(brand: BoardBrand?, frames: List<List<ByteArray>>): Counted {
        var leds = 0
        var packets = 0
        var ledPackets = 0
        fun packet(ledsInIt: Int) {
            packets++
            leds += ledsInIt
            if (ledsInIt > 0) ledPackets++
        }
        for (frame in frames) {
            val bytes = frame.flatMap { it.toList() }.toByteArray()
            when {
                brand == BoardBrand.MOONBOARD -> packet(moonLeds(bytes))
                brand == BoardBrand.QUANTUM -> packet(quantumLeds(bytes))
                brand?.usesAuroraProtocol == true -> auroraPackets(bytes).forEach(::packet)
                else -> packet(0)
            }
        }
        return Counted(leds, packets, ledPackets)
    }

    /** LED entries of one `l#S12,P103,…#` frame. */
    private fun moonLeds(bytes: ByteArray): Int {
        val text = bytes.decodeToString()
        if (!text.startsWith("l#") || !text.endsWith("#") || text.length < 3) return 0
        return text.substring(2, text.length - 1).split(',').count { it.isNotBlank() }
    }

    /** LEDs of each Aurora packet `01 len checksum 02 type … 03` in [bytes]: three bytes an LED on API 3, two on API 2. */
    private fun auroraPackets(bytes: ByteArray): List<Int> {
        val leds = mutableListOf<Int>()
        var at = 0
        while (at + 5 <= bytes.size && bytes[at] == 0x01.toByte() && bytes[at + 3] == 0x02.toByte()) {
            val length = bytes[at + 1].toInt() and 0xff
            if (length == 0 || at + 4 + length >= bytes.size) break
            val perLed = when (bytes[at + 4]) {
                BoardPacketEncoder.API3_FIRST, BoardPacketEncoder.API3_MIDDLE,
                BoardPacketEncoder.API3_LAST, BoardPacketEncoder.API3_ONLY -> 3
                BoardPacketEncoder.API2_FIRST, BoardPacketEncoder.API2_MIDDLE,
                BoardPacketEncoder.API2_LAST, BoardPacketEncoder.API2_ONLY -> 2
                else -> break
            }
            leds += (length - 1) / perLed
            at += length + 5
        }
        return leds
    }

    /** Diodes of one Quantum message; only an activation carries any. */
    private fun quantumLeds(bytes: ByteArray): Int {
        val command = bytes.getOrNull(1)?.toInt()?.and(0xff) ?: return 0
        if (command != QuantumCommand.ACTIVATE_WALL.byte && command != QuantumCommand.BOARD_SWIPE.byte) return 0
        // 01, command, route id (16), user id (16), colour (3), duration (2), animation (1), then the length in bytes.
        return (bytes.getOrNull(QUANTUM_LENGTH_AT)?.toInt()?.and(0xff) ?: 0) / 2
    }

    private const val QUANTUM_LENGTH_AT = 2 + 16 + 16 + 3 + 2 + 1
}
