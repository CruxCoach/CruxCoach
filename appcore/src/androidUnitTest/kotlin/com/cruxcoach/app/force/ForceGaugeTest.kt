package com.cruxcoach.app.force

import com.cruxcoach.app.ble.BleAdapterState
import com.cruxcoach.app.ble.BleCentral
import com.cruxcoach.app.ble.BleCentralListener
import com.cruxcoach.app.ble.BleCharacteristicInfo
import com.cruxcoach.app.ble.BleServiceInfo
import com.cruxcoach.app.training.TrTextCheck
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.Side
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The force gauge state machine (Android ProgressorClient + ForceGaugeViewModel) against a fake
 * central on virtual time: scan and pick the strongest Progressor, connect, subscribe, battery,
 * streaming, tare, the countdown and the 7-s pull, peak / best 5 s / % body weight, saving as a
 * pick-up test value, and every failure status. No hardware is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForceGaugeTest {
    private lateinit var kit: TrainingKit
    private val scheduler = TestCoroutineScheduler()
    private val main = StandardTestDispatcher(scheduler)
    private val scopes = mutableListOf<CoroutineScope>()
    private lateinit var central: FakeCentral

    @BeforeTest
    fun setUp() {
        kit = TrainingKit()
        kit.standardAthlete()
        central = FakeCentral()
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        kit.close()
    }

    private fun scope() = CoroutineScope(SupervisorJob() + main).also { scopes += it }

    private fun run(ms: Long = 0) {
        if (ms > 0) scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    private fun gauge(): Triple<ProgressorClient, ForceGaugePresenter, ForceGaugeModel> {
        val client = ProgressorClient(central, scope()) { scheduler.currentTime }
        val presenter = ForceGaugePresenter(kit.service, client, scope(), kit.dispatcher)
        run()
        kit.until("profile loaded") { !presenter.state.value.loading }
        run()
        return Triple(client, presenter, ForceGaugeModel(presenter))
    }

    private class FakeCentral : BleCentral {
        var registered: BleCentralListener? = null
        var adapter = BleAdapterState.POWERED_ON
        val calls = mutableListOf<String>()
        val writes = mutableListOf<Byte>()
        var writeWithResponse: Boolean? = null

        override fun setListener(listener: BleCentralListener?) { registered = listener }
        override fun adapterState() = adapter
        override fun activate() { calls += "activate"; registered?.onAdapterState(adapter) }
        override fun startScan() { calls += "startScan" }
        override fun stopScan() { calls += "stopScan" }
        override fun connect(identifier: String) { calls += "connect $identifier" }
        override fun cancelConnection(identifier: String) { calls += "cancel $identifier" }
        override fun discoverServices(identifier: String) { calls += "discover $identifier" }
        override fun maximumWriteLength(identifier: String, withResponse: Boolean) = 20
        override fun write(identifier: String, serviceUuid: String, characteristicUuid: String, value: ByteArray, withResponse: Boolean) {
            calls += "write ${characteristicUuid.lowercase()}"
            writes += value.single()
            writeWithResponse = withResponse
        }
        override fun read(identifier: String, serviceUuid: String, characteristicUuid: String) { calls += "read" }
        override fun setNotify(identifier: String, serviceUuid: String, characteristicUuid: String, enabled: Boolean) {
            calls += "notify ${characteristicUuid.lowercase()} $enabled"
        }
    }

    // CoreBluetooth reports custom 128-bit UUIDs in upper case.
    private val services = listOf(
        BleServiceInfo(ProgressorProtocol.SERVICE.uppercase(), listOf(
            BleCharacteristicInfo(ProgressorProtocol.DATA_POINT.uppercase(), canRead = false, canWrite = false, canWriteWithoutResponse = false, canNotify = true),
            BleCharacteristicInfo(ProgressorProtocol.CONTROL_POINT.uppercase(), canRead = false, canWrite = true, canWriteWithoutResponse = true, canNotify = false),
        )),
    )

    private fun ack() {
        central.registered!!.onWriteCompleted("b", ProgressorProtocol.CONTROL_POINT.uppercase(), true)
        run()
    }

    private fun weightFrames(samples: List<Pair<Float, Long>>): List<ByteArray> = samples.chunked(10).map { chunk ->
        val payload = ByteBuffer.allocate(chunk.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        chunk.forEach { (kg, us) -> payload.putFloat(kg); payload.putInt(us.toInt()) }
        byteArrayOf(ProgressorProtocol.RES_WEIGHT.toByte(), (chunk.size * 8).toByte()) + payload.array()
    }

    private fun notify(frame: ByteArray) {
        central.registered!!.onNotification("b", ProgressorProtocol.DATA_POINT.uppercase(), frame)
    }

    /** Scan, connect "b" (the strongest Progressor), subscribe, battery and start acknowledged. */
    private fun connected(): Triple<ProgressorClient, ForceGaugePresenter, ForceGaugeModel> {
        val g = gauge()
        g.third.connect()
        run()
        assertEquals("scanning", g.third.currentState.statusCode)
        val l = central.registered!!
        l.onAdvertisement("a", "Progressor_1111", null, -80)
        l.onAdvertisement("b", null, "Progressor_2222", -50)
        l.onAdvertisement("c", "Kilter Board", null, -20)
        run()
        run(2_400)
        assertTrue("connect b" in central.calls, central.calls.toString())
        assertTrue("stopScan" in central.calls)
        assertEquals("connecting", g.third.currentState.statusCode)
        l.onConnected("b")
        run()
        assertTrue("discover b" in central.calls)
        l.onServicesDiscovered("b", services)
        run()
        assertTrue("notify ${ProgressorProtocol.DATA_POINT} true" in central.calls)
        l.onNotifyStateChanged("b", ProgressorProtocol.DATA_POINT.uppercase(), enabled = true, success = true)
        run()
        assertEquals("connected", g.third.currentState.statusCode)
        assertEquals(ProgressorProtocol.CMD_GET_BATTERY, central.writes.last())
        assertEquals(true, central.writeWithResponse)
        notify(byteArrayOf(0x00, 0x04, 0x68, 0x10, 0x00, 0x00))
        run()
        ack()
        assertEquals(ProgressorProtocol.CMD_START_WEIGHT, central.writes.last())
        ack()
        return g
    }

    @Test
    fun `connect picks the strongest Progressor and streams a live curve`() {
        val (_, presenter, model) = connected()
        assertEquals("Progressor_2222", presenter.state.value.deviceName)
        assertEquals(4200, presenter.state.value.batteryMv)
        val status = model.currentState.statusText
        assertEquals(listOf("trd_force_status_connected", "trd_force_battery"), status.args.map { it.nested?.key })
        TrTextCheck.check(status, "status")

        weightFrames((0 until 40).map { i -> (10f + i) to (i * 12_500L) }).forEach(::notify)
        run(100)
        val s = model.currentState
        assertEquals(49.0, presenter.state.value.liveKg)
        assertEquals(40, presenter.state.value.recent.size)
        assertEquals(49.0 * 1.15, s.curveMax, 1e-9)
        assertEquals(49.0, s.curvePeak)
        assertEquals(0.0, s.curve.last().seconds)
        assertTrue(s.curve.first().seconds < 0)
        assertEquals("trd_force_peak_recent", s.peakText.key)
        TrTextCheck.checkAll(s)

        model.tare()
        run()
        assertEquals(ProgressorProtocol.CMD_TARE, central.writes.last())
        ack()
    }

    @Test
    fun `a seven second pull gives peak best five seconds and body weight percent and saves a test value`() {
        val (_, presenter, model) = connected()
        model.setHand("both")
        model.setEdge(18)
        model.startPull()
        run()
        assertEquals("countdown", model.currentState.phaseCode)
        assertEquals(3, presenter.state.value.countdown)
        run(1_000)
        assertEquals(2, presenter.state.value.countdown)
        run(2_000)
        assertEquals("pulling", model.currentState.phaseCode)
        assertEquals(7, presenter.state.value.secondsLeft)
        // 80 Hz for 7 s: ramp to 42 kg in the first second, hold, dip to 30 in the last second.
        val samples = (0 until 560).map { i ->
            val t = 5_000_000L + i * 12_500L
            val rel = t - 5_000_000L
            val kg = when {
                rel < 1_000_000L -> 42.0 * rel / 1_000_000L
                rel > 6_000_000L -> 30.0
                else -> 42.0
            }
            kg.toFloat() to t
        }
        weightFrames(samples).forEach(::notify)
        run(7_000)
        val done = model.currentState
        assertEquals("done", done.phaseCode)
        assertTrue(done.hasResult)
        val r = presenter.state.value.result!!
        assertEquals(42.0, r.peakKg)
        assertTrue(r.bestMeanKg!! in 41.5..42.0, "mean ${r.bestMeanKg}")
        assertEquals("trd_force_result_mean", done.resultMean.key)
        // 70 kg athlete: ~60 % of body weight.
        assertEquals("trd_force_result_pct", done.resultPercent.key)
        assertEquals(60, done.resultPercent.args.single().number.toInt())
        assertTrue(done.canSave)
        TrTextCheck.checkAll(done)

        model.save()
        kit.until("saved") { presenter.state.value.saved }
        run()
        assertFalse(model.currentState.canSave)
        val saved = kit.db { kit.repo.benchmarks(ForceGaugePresenter.SLUG_TWO_ARM) }.single()
        assertEquals(BenchmarkSource.TEST, saved.source)
        assertEquals(18.0, saved.edgeMm)
        assertEquals(5.0, saved.durationS)
        assertNull(saved.side)
        assertEquals((r.bestMeanKg!! * 10).let { kotlin.math.round(it) } / 10.0, saved.loadKg)
        assertEquals("Tindeq Progressor · 7 s pull", saved.note)
    }

    @Test
    fun `a short pull has no five second value and one hand saves the one arm pick up`() {
        val (_, presenter, model) = connected()
        model.setHand("left")
        model.startPull()
        run(3_000)
        // Only 3 s of data arrive during the pull.
        weightFrames((0 until 240).map { i -> 25f to (i * 12_500L) }).forEach(::notify)
        run(7_000)
        val s = model.currentState
        assertEquals("done", s.phaseCode)
        assertEquals("trd_force_result_too_short", s.resultMean.key)
        assertFalse(s.canSave)
        assertEquals("", s.resultPercent.key)
        model.save()
        run()
        assertTrue(kit.db { kit.repo.benchmarks(ForceGaugePresenter.SLUG_ONE_ARM) }.isEmpty())

        // Left with a full pull: one-arm pick-up with the side.
        model.startPull()
        run(3_000)
        weightFrames((0 until 560).map { i -> 20f to (10_000_000L + i * 12_500L) }).forEach(::notify)
        run(7_000)
        model.save()
        kit.until("saved") { presenter.state.value.saved }
        assertEquals(Side.LEFT, kit.db { kit.repo.benchmarks(ForceGaugePresenter.SLUG_ONE_ARM) }.single().side)
    }

    @Test
    fun `no data during the pull and cancel`() {
        val (_, _, model) = connected()
        model.startPull()
        run(1_500)
        model.cancelPull()
        run()
        assertEquals("idle", model.currentState.phaseCode)
        model.startPull()
        run(10_000)
        assertEquals("done", model.currentState.phaseCode)
        assertFalse(model.currentState.hasResult)
        // A pull needs a connection.
        model.disconnect()
        run()
        assertTrue("cancel b" in central.calls)
        assertEquals("idle", model.currentState.statusCode)
        model.startPull()
        run()
        assertEquals("idle", model.currentState.phaseCode)
    }

    @Test
    fun `adapter states and timeouts end in Android's statuses`() {
        central.adapter = BleAdapterState.UNAUTHORIZED
        val (_, _, model) = gauge()
        model.connect()
        run()
        assertEquals("noPermission", model.currentState.statusCode)
        central.adapter = BleAdapterState.POWERED_OFF
        model.connect()
        run()
        assertEquals("bluetoothOff", model.currentState.statusCode)

        central.adapter = BleAdapterState.POWERED_ON
        model.connect()
        run(ProgressorClient.SCAN_TIMEOUT_MS + 500)
        assertEquals("notFound", model.currentState.statusCode)

        // Found but never connects: 15 s budget, then FAILED and the attempt cancelled.
        model.connect()
        run()
        central.registered!!.onAdvertisement("b", "Progressor_2222", null, -60)
        run(2_400)
        assertEquals("connecting", model.currentState.statusCode)
        run(ProgressorClient.CONNECT_TIMEOUT_MS)
        assertEquals("failed", model.currentState.statusCode)
        assertTrue("cancel b" in central.calls)

        // A device without the Progressor service.
        model.connect()
        run()
        central.registered!!.onAdvertisement("b", "Progressor_2222", null, -60)
        run(2_400)
        central.registered!!.onConnected("b")
        run()
        central.registered!!.onServicesDiscovered("b", listOf(BleServiceInfo("180F", emptyList())))
        run()
        assertEquals("failed", model.currentState.statusCode)
        TrTextCheck.checkAll(model.currentState)
    }

    @Test
    fun `the link dropping returns to not connected and low power is reported`() {
        val (_, _, model) = connected()
        notify(byteArrayOf(0x04, 0x00))
        run()
        assertTrue(model.currentState.lowPower)
        central.registered!!.onDisconnected("other")
        run()
        assertEquals("connected", model.currentState.statusCode)
        central.registered!!.onDisconnected("b")
        run()
        assertEquals("idle", model.currentState.statusCode)
    }

    @Test
    fun `the gauge starts off and the settings switch turns it on`() {
        val (_, presenter, model) = gauge()
        assertFalse(model.currentState.enabled)
        model.enable()
        kit.until("enabled") { presenter.state.value.enabled }
        assertTrue(kit.db { kit.repo.profile().coach.forceGaugeEnabled })

        val settings = ForceGaugeSettingsPresenter(kit.service, CoroutineScope(SupervisorJob() + kit.dispatcher).also { scopes += it }, kit.dispatcher)
        val card = ForceGaugeSettingsModel(settings)
        kit.until("settings loaded") { card.currentState.enabled }
        card.setEnabled(false)
        kit.until("switched off") { !card.currentState.enabled }
        assertFalse(kit.db { kit.repo.profile().coach.forceGaugeEnabled })
    }

    @Test
    fun `edge and hand choices follow Android's limits`() {
        val (_, presenter, model) = gauge()
        model.setEdge(3)
        assertEquals(6, presenter.state.value.edgeMm)
        model.setEdge(80)
        assertEquals(45, presenter.state.value.edgeMm)
        model.setHand("right")
        assertEquals("right", model.currentState.handCode)
        model.setHand("nonsense")
        assertEquals("right", model.currentState.handCode)
        assertEquals("trd_force_edge", model.currentState.edgeText.key)
        assertEquals(7, model.pullSeconds)
    }
}
