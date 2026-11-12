package com.cruxcoach.android.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import com.cruxcoach.android.data.CruxRelayManager
import com.cruxcoach.android.data.CruxRelayState
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionQueueState
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.ui.paintbox.PaintBoard
import com.cruxcoach.android.ui.paintbox.PaintDocument
import com.cruxcoach.android.ui.paintbox.PaintPalette
import com.cruxcoach.android.ui.paintbox.PaintProjectionException
import com.cruxcoach.android.ui.paintbox.PaintProjector
import com.cruxcoach.android.ui.paintbox.moonHoldIds
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.MoonBoardVariant
import io.mockk.every
import io.mockk.mockk
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment

/**
 * A [BoardBleConnection] on a fake GATT link (as in BoardBleFrameCancelTest) that keeps every write
 * and the virtual time it was made at. The LED lab tests read the bytes on the wire from it.
 *
 * @param refuseWrite the write (counted from 0) the fake stack does not queue, or null.
 */
internal class FakeBoardLink(
    brand: BoardBrand,
    apiLevel: Int = 3,
    private val refuseWrite: Int? = null,
    private val now: () -> Long = { 0L },
) {
    val connection = BoardBleConnection(RuntimeEnvironment.getApplication())
    val board = DiscoveredBoard("Test board", "123", apiLevel, "00:11:22:33:44:55", -40, brand)
    val writes = mutableListOf<ByteArray>()
    val writeTimes = mutableListOf<Long>()
    private var attempts = 0
    private var unacknowledged = 0
    private val gatt = mockk<BluetoothGatt>(relaxed = true)
    private val characteristic = BluetoothGattCharacteristic(UUID.randomUUID(), 8, 16)
    private val callback: BluetoothGattCallback

    init {
        get<CoroutineScope>("scope").cancel()
        set("currentBoard", board)
        get<MutableStateFlow<DiscoveredBoard?>>("_connectedBoardDescriptor").value = board
        set("gatt", gatt)
        set("writeCharacteristic", characteristic)
        get<MutableStateFlow<ConnectionState>>("_connectionState").value = ConnectionState.CONNECTED
        get<MutableStateFlow<BoardBrand?>>("_connectedBoardBrand").value = brand
        set("encoder", BoardPacketEncoder(apiLevel, BoardPacketEncoder.ledsPerHoldFor(brand)))
        get<BoardCleaningSession>("cleaning").attach(BoardCleaningStorage.key(board))
        callback = get("gattCallback")
        every { gatt.writeCharacteristic(any()) } answers {
            if (attempts++ == refuseWrite) false else {
                writes += characteristic.value.copyOf()
                writeTimes += now()
                unacknowledged++
                true
            }
        }
    }

    val waitsForAnswer: Boolean get() = unacknowledged > 0

    /** Answers the write in flight, as the box would. */
    fun acknowledge() {
        if (unacknowledged == 0) return
        unacknowledged--
        callback.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
    }

    fun bytes(): ByteArray = writes.flatMap { it.toList() }.toByteArray()

    /** Milliseconds between one write and the next. */
    fun gaps(): List<Long> = writeTimes.zipWithNext { a, b -> b - a }

    private fun set(name: String, value: Any) {
        BoardBleConnection::class.java.getDeclaredField(name).apply { isAccessible = true }.set(connection, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> get(name: String): T =
        BoardBleConnection::class.java.getDeclaredField(name).apply { isAccessible = true }.get(connection) as T
}

/** A link whose write times are this test's virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.fakeLink(brand: BoardBrand, apiLevel: Int = 3, refuseWrite: Int? = null) =
    FakeBoardLink(brand, apiLevel, refuseWrite) { currentTime }

/**
 * Runs [send] to its end. Every write is answered at once, so the time between two writes is
 * the pause the connection makes on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.sendOver(link: FakeBoardLink, send: suspend () -> Unit) {
    val job = launch { send() }
    runCurrent()
    var steps = 0
    while (!job.isCompleted && steps++ < 200_000) {
        if (link.waitsForAnswer) link.acknowledge() else advanceTimeBy(1)
        runCurrent()
    }
    assertTrue("the send ended", job.isCompleted)
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** PaintBox's projector on [link], with the app's MoonBoard LED setting on [moonLedMode] and nothing else using the board. */
internal fun paintProjector(link: FakeBoardLink, moonLedMode: MoonBoardLedMode = MoonBoardLedMode.BELOW): PaintProjector {
    val preferences = mockk<UserPreferences>(relaxed = true) { every { moonBoardLedMode } returns flowOf(moonLedMode) }
    val sessions = mockk<SessionQueueManager> { every { state } returns MutableStateFlow(SessionQueueState()) }
    val relay = mockk<CruxRelayManager> { every { state } returns MutableStateFlow(CruxRelayState()) }
    return PaintProjector(link.connection, mockk(relaxed = true), preferences, sessions, relay)
}

/**
 * Fixed pictures PaintBox sends in the wire tests, each from the picture to the bytes on the link.
 * The hashes the tests expect with every switch off were printed by the release head (e89875335)
 * running these same cases.
 */
internal enum class LedLabCase(val brand: BoardBrand, val apiLevel: Int, val moonLedMode: MoonBoardLedMode = MoonBoardLedMode.BELOW) {
    MOON_BELOW(BoardBrand.MOONBOARD, 3, MoonBoardLedMode.BELOW),
    MOON_ABOVE(BoardBrand.MOONBOARD, 3, MoonBoardLedMode.ABOVE),
    MOON_BOTH(BoardBrand.MOONBOARD, 3, MoonBoardLedMode.BOTH),
    /** The most holds an API-2 box gets from the release build: one packet. */
    API2_127(BoardBrand.KILTER, 2),
    /** Three packets on API 3. */
    API3_200(BoardBrand.KILTER, 3);

    val picture: PaintDocument get() = when (this) {
        MOON_BELOW, MOON_ABOVE, MOON_BOTH -> LedLabCases.moonPicture(60)
        API2_127 -> LedLabCases.kilterPicture(127)
        API3_200 -> PaintDocument(board = LedLabCases.kilterBoard,
            colors = (1..200).associateWith { listOf(0xe0, 0x1c, 0x03, 0xff)[it % 4] })
    }
}

internal object LedLabCases {
    val moonBoard = PaintBoard("moonboard", MoonBoardVariant.MOONBOARD_2016.layoutId.toInt(), 0)
    val kilterBoard = PaintBoard("kilter", 1, 10)

    /** [holds] mounted holds of a MoonBoard 2016, lowest ids first, in PaintBox's three colours in turn. */
    fun moonPicture(holds: Int): PaintDocument = PaintDocument(board = moonBoard,
        colors = moonBoard.moonHoldIds().sorted().take(holds).withIndex().associate { (index, id) -> id to PaintPalette.moon[index % 3] })

    /** [holds] green holds of a Kilter board. */
    fun kilterPicture(holds: Int): PaintDocument = PaintDocument(board = kilterBoard, colors = (1..holds).associateWith { 0x1c })

    /** Placement to LED position for the first [holds] placements. */
    fun ledMap(holds: Int): Map<Int, Int> = (1..holds).associateWith { it + 100 }
}

/** Sends [picture] through PaintBox's projector over [link] and tells whether it went out. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.paint(link: FakeBoardLink, picture: PaintDocument, moonLedMode: MoonBoardLedMode = MoonBoardLedMode.BELOW): Boolean {
    val projector = paintProjector(link, moonLedMode)
    var sent = false
    sendOver(link) { sent = projector.send(picture, LedLabCases.ledMap(picture.colors.size)) { true } }
    return sent
}

/** Tries to send [picture] over [link]: the message PaintBox refused it with, or null when it did not refuse. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.refusal(link: FakeBoardLink, picture: PaintDocument): Int? {
    val projector = paintProjector(link)
    var refused: Int? = null
    sendOver(link) {
        try {
            projector.send(picture, LedLabCases.ledMap(picture.colors.size)) { true }
        } catch (e: PaintProjectionException) {
            refused = e.messageRes
        }
    }
    return refused
}

/** Sends [case] over a new link and returns the link. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.paint(case: LedLabCase): FakeBoardLink {
    val link = fakeLink(case.brand, case.apiLevel)
    assertTrue("$case went out", paint(link, case.picture, case.moonLedMode))
    return link
}
