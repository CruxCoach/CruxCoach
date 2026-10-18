package com.cruxcoach.app.ui

import com.cruxcoach.app.localshare.HttpStatusException
import com.cruxcoach.app.localshare.ImportFailedException
import com.cruxcoach.app.localshare.InsufficientSpace
import com.cruxcoach.app.localshare.InvalidManifestException
import com.cruxcoach.app.localshare.LimitExceeded
import com.cruxcoach.app.localshare.LocalShareClient
import com.cruxcoach.app.localshare.LocalShareIoException
import com.cruxcoach.app.localshare.LocalShareProtocol
import com.cruxcoach.app.localshare.LocalShareReceiver
import com.cruxcoach.app.localshare.NoSnapshotException
import com.cruxcoach.app.localshare.ShareSessionChangedException
import com.cruxcoach.app.localshare.VerificationFailed
import com.cruxcoach.app.sync.CatalogueImporter
import com.cruxcoach.app.sync.CatalogueSyncController
import com.cruxcoach.data.BoardDatabaseHandle
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One board family in the offer or the result. */
class ReceiveShareBrandRow(
    val brandWire: String,
    val title: String,
    /** The sender's climb count in an offer; the climbs now on this phone in a result. */
    val climbCount: Long,
    val selected: Boolean,
)

class ReceiveShareState(
    /** "enter", "probing", "joinWifi", "offer", "running", "done", "failed". */
    val phase: String,
    /** The sender's IP address, for the consent text (Android shows the host). */
    val host: String,
    /** Hotspot credentials when a code carried them; iOS cannot join a network for the app. */
    val wifiSsid: String,
    val wifiPassword: String,
    val offered: List<ReceiveShareBrandRow>,
    /** Families the sender does not have, fetched from the internet afterwards when ticked. */
    val others: List<ReceiveShareBrandRow>,
    val othersOpen: Boolean,
    val canConfirm: Boolean,
    /** "fetchManifest", "preparing", "checking", "download", "verify", "extract", "importClimbs", "importStats", "importLayout", "finalizing". */
    val stepCode: String,
    val stepDone: Long,
    val stepTotal: Long,
    val result: List<ReceiveShareBrandRow>,
    /** "none", "invalidLink", "notFound", "unreachable", "busy", "sessionChanged", "noSnapshot", "noSpace", "tooLarge", "verifyFailed", "rejected", "failed". */
    val errorCode: String,
)

/**
 * Receiving a nearby phone's board catalogues over its offline Wi-Fi share (Android
 * `BoardSyncManager` local-share lane + `DiscoveredShareDialog`). The sender is not
 * authenticated: nothing is transferred before the climber chose what to take, and every byte
 * is verified against the manifest before it is opened.
 */
class ReceiveShareScreenModel internal constructor(
    private val client: LocalShareClient,
    private val receiver: LocalShareReceiver,
    private val catalogueSync: CatalogueSyncController,
    private val database: BoardDatabaseHandle,
    main: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
    private val probeAttempts: Int = PROBE_ATTEMPTS,
    private val probeRetryMs: Long = PROBE_RETRY_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val state = MutableStateFlow(initial())
    private var job: Job? = null
    private var baseUrl: String = ""
    private var manifest: LocalShareProtocol.Manifest? = null
    private var chosen: Set<String> = emptySet()
    private var chosenOthers: Set<String> = emptySet()
    private var pendingOnline: List<BoardBrand>? = null

    val currentState: ReceiveShareState get() = state.value

    fun watch(onState: (ReceiveShareState) -> Unit): Subscription {
        val watcher = scope.launch { state.collect { onState(it) } }
        return Subscription { watcher.cancel() }
    }

    /** A scanned or pasted code: the sender's landing address, its "open in app" link or its Wi-Fi code. */
    fun submitLink(text: String) {
        if (state.value.phase == "running" || state.value.phase == "probing") return
        when (val link = parseLink(text)) {
            is ShareLink.Origin -> probe(link.baseUrl)
            is ShareLink.Credentials -> {
                baseUrl = link.baseUrl
                state.value = initial().copyWith(phase = "joinWifi", wifiSsid = link.ssid, wifiPassword = link.password)
            }
            is ShareLink.Wifi -> {
                baseUrl = ""
                state.value = initial().copyWith(phase = "joinWifi", wifiSsid = link.ssid, wifiPassword = link.password)
            }
            null -> state.value = initial().copyWith(phase = "enter", errorCode = "invalidLink")
        }
    }

    /** After joining the sender's Wi-Fi in Settings; without a known address the Wi-Fi is searched. */
    fun continueAfterWifi(gateways: List<String>) {
        if (state.value.phase != "joinWifi") return
        if (baseUrl.isEmpty()) searchWifi(gateways) else probe(baseUrl)
    }

    /**
     * Android `LocalShareDiscovery.discover`: the sender listens on a fixed port, on the Wi-Fi's
     * gateway or one of the two hotspot addresses Android uses. Only a valid manifest counts as
     * found; nothing is transferred before the offer is accepted. [gateways]: the IPv4 gateways
     * iOS reports for the current Wi-Fi path.
     */
    fun searchWifi(gateways: List<String>) {
        val phase = state.value.phase
        if (phase == "running" || phase == "probing") return
        state.value = initial().copyWith(phase = "probing")
        job = scope.launch {
            val found = withContext(io) { discover(gateways) }
            if (found == null) {
                state.value = initial().copyWith(phase = "failed", errorCode = "notFound")
                return@launch
            }
            baseUrl = found.first
            showOffer(found.second)
        }
    }

    private suspend fun discover(gateways: List<String>): Pair<String, LocalShareProtocol.Manifest>? {
        repeat(probeAttempts) { attempt ->
            val hosts = (gateways + GROUP_OWNER_IP + LOCAL_ONLY_HOTSPOT_LEGACY_IP)
                .filter { LocalShareProtocol.isPrivateIpv4(it) }
                .distinct()
            for (host in hosts) {
                val origin = "http://$host:$LOCAL_SHARE_PORT"
                try {
                    return origin to client.fetchManifest(origin, timeoutSeconds = PROBE_TIMEOUT_SECONDS)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Not this address; the next candidate.
                }
            }
            if (attempt + 1 < probeAttempts) delay(probeRetryMs)
        }
        return null
    }

    fun toggle(brandWire: String) {
        val s = state.value
        if (s.phase != "offer") return
        if (s.offered.any { it.brandWire == brandWire }) {
            chosen = if (brandWire in chosen) chosen - brandWire else chosen + brandWire
        } else if (s.others.any { it.brandWire == brandWire }) {
            chosenOthers = if (brandWire in chosenOthers) chosenOthers - brandWire else chosenOthers + brandWire
        }
        publishOffer(s.othersOpen)
    }

    fun toggleOthers() {
        val s = state.value
        if (s.phase == "offer") publishOffer(!s.othersOpen)
    }

    /** The consent: take the ticked families from the sender, then fetch the ticked others online. */
    fun confirm() {
        val s = state.value
        val first = manifest ?: return
        if (s.phase != "offer" || !s.canConfirm) return
        if (!catalogueSync.claimLocalShare()) {
            state.value = s.copyWith(errorCode = "busy")
            return
        }
        val take = chosen
        val online = chosenOthers
        step("fetchManifest", 0, 0)
        job = scope.launch {
            try {
                val brands = withContext(io) {
                    receiver.receive(baseUrl, first, take) { s -> step(s.code, s.done, s.total) }
                }
                step("finalizing", 0, 0)
                val counts = withContext(io) {
                    CatalogueImporter(database).analyze()
                    climbCounts()
                }
                state.value = state.value.copyWith(
                    phase = "done",
                    result = brands.map { wire -> row(wire, counts[wire] ?: 0L, selected = true) },
                )
                val missing = online.mapNotNull { BoardBrand.fromWireOrNull(it) }.filter { (counts[it.wireValue] ?: 0L) == 0L }
                if (missing.isNotEmpty()) pendingOnline = missing
            } catch (e: CancellationException) {
                state.value = initial()
                throw e
            } catch (e: Exception) {
                state.value = state.value.copyWith(phase = "failed", errorCode = errorCode(e))
            } finally {
                // The import is atomic, so a failure changed nothing; re-reading is still harmless
                // and also covers a cancellation that arrived after the import had committed.
                withContext(NonCancellable + io) { catalogueSync.releaseLocalShare(changed = true) }
                pendingOnline?.let { catalogueSync.start(it) }
                pendingOnline = null
            }
        }
    }

    /** Leaves an offer, a Wi-Fi hint or a result; a running transfer is cancelled (its partial file stays for a resume). */
    fun cancel() {
        job?.cancel()
        job = null
        manifest = null
        state.value = initial()
    }

    /** Opening the screen again starts over after a result; a transfer or search in progress stays. */
    fun reset() {
        val phase = state.value.phase
        if (phase == "done" || phase == "failed") {
            manifest = null
            state.value = initial()
        }
    }

    fun close() {
        job?.cancel()
        scope.cancel()
    }

    private fun probe(origin: String) {
        baseUrl = origin
        state.value = initial().copyWith(phase = "probing", host = hostOf(origin))
        job = scope.launch {
            try {
                showOffer(withContext(io) { fetchWithRetries(origin) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = initial().copyWith(phase = "failed", host = hostOf(origin), errorCode = errorCode(e))
            }
        }
    }

    private fun showOffer(found: LocalShareProtocol.Manifest) {
        manifest = found
        chosen = LocalShareReceiver.offeredBrands(found).toSet()
        chosenOthers = emptySet()
        publishOffer(othersOpen = false)
    }

    /**
     * The first request to a local address can fail while iOS asks for the local-network
     * permission, so a few short attempts (Android's connected-share probe answers within
     * seconds). A peer that answers with something else is not retried.
     */
    private suspend fun fetchWithRetries(origin: String): LocalShareProtocol.Manifest {
        var attempt = 0
        while (true) {
            try {
                return client.fetchManifest(origin, timeoutSeconds = PROBE_TIMEOUT_SECONDS)
            } catch (e: ShareSessionChangedException) {
                throw e
            } catch (e: InvalidManifestException) {
                throw e
            } catch (e: HttpStatusException) {
                throw e
            } catch (e: LocalShareIoException) {
                if (++attempt >= probeAttempts) throw e
                delay(probeRetryMs)
            }
        }
    }

    private fun publishOffer(othersOpen: Boolean) {
        val found = manifest ?: return
        val declared = found.declaredCatalogues.associate { it.boardBrand to it.climbCount }
        val offered = LocalShareReceiver.offeredBrands(found)
        val others = BoardBrand.entries.filter { it.isInteractive && it.wireValue !in offered }
        state.value = initial().copyWith(
            phase = "offer",
            host = hostOf(baseUrl),
            offered = offered.map { wire -> row(wire, declared[wire] ?: 0L, wire in chosen) },
            others = others.map { row(it.wireValue, 0L, it.wireValue in chosenOthers) },
            othersOpen = othersOpen,
            // An old sender that names no catalogues is the plain yes/no, taken whole (Android).
            canConfirm = offered.isEmpty() || offered.any { it in chosen },
        )
    }

    private fun step(code: String, done: Long, total: Long) {
        state.update { it.copyWith(phase = "running", stepCode = code, stepDone = done, stepTotal = total, errorCode = "none") }
    }

    private fun climbCounts(): Map<String, Long> =
        database.database.boardQueries.countClimbsByBrand().executeAsList().associate { it.boardBrand to it.climbCount }

    private fun row(wire: String, count: Long, selected: Boolean): ReceiveShareBrandRow {
        val brand = BoardBrand.fromWireOrNull(wire)
        return ReceiveShareBrandRow(wire, brand?.let { UiCodes.brandTitle(it) } ?: wire, count, selected)
    }

    private fun hostOf(origin: String): String = LocalShareProtocol.hostOf(origin).substringBefore(':')

    private fun errorCode(e: Exception): String = when (e) {
        is InsufficientSpace -> "noSpace"
        is LimitExceeded -> "tooLarge"
        is ShareSessionChangedException -> "sessionChanged"
        is NoSnapshotException -> "noSnapshot"
        is VerificationFailed -> "verifyFailed"
        is ImportFailedException -> if (e.sourceRejected) "rejected" else "failed"
        is InvalidManifestException -> "rejected"
        is LocalShareIoException -> "unreachable"
        else -> "failed"
    }

    private fun initial() = ReceiveShareState(
        phase = "enter", host = "", wifiSsid = "", wifiPassword = "", offered = emptyList(), others = emptyList(),
        othersOpen = false, canConfirm = false, stepCode = "", stepDone = 0, stepTotal = 0, result = emptyList(), errorCode = "none",
    )

    private fun ReceiveShareState.copyWith(
        phase: String = this.phase,
        host: String = this.host,
        wifiSsid: String = this.wifiSsid,
        wifiPassword: String = this.wifiPassword,
        offered: List<ReceiveShareBrandRow> = this.offered,
        others: List<ReceiveShareBrandRow> = this.others,
        othersOpen: Boolean = this.othersOpen,
        canConfirm: Boolean = this.canConfirm,
        stepCode: String = this.stepCode,
        stepDone: Long = this.stepDone,
        stepTotal: Long = this.stepTotal,
        result: List<ReceiveShareBrandRow> = this.result,
        errorCode: String = this.errorCode,
    ) = ReceiveShareState(
        phase, host, wifiSsid, wifiPassword, offered, others, othersOpen, canConfirm, stepCode, stepDone, stepTotal, result, errorCode,
    )

    internal sealed class ShareLink {
        class Origin(val baseUrl: String) : ShareLink()
        class Credentials(val baseUrl: String, val ssid: String, val password: String) : ShareLink()
        class Wifi(val ssid: String, val password: String) : ShareLink()
    }

    internal companion object {
        const val PROBE_ATTEMPTS = 6
        const val PROBE_RETRY_MS = 2_500L
        const val PROBE_TIMEOUT_SECONDS = 4
        const val LOCAL_SHARE_PORT = 4949
        const val GROUP_OWNER_IP = "192.168.49.1"
        const val LOCAL_ONLY_HOTSPOT_LEGACY_IP = "192.168.43.1"

        /**
         * What the sender shows: a Wi-Fi code (`WIFI:T:WPA;S:…;P:…;;`), its landing address
         * (`http://<private IPv4>:<port>`), the landing page's "open in app" link, or the older
         * credential invitation. Anything else is not a share.
         */
        fun parseLink(text: String): ShareLink? {
            val value = text.trim()
            if (value.isEmpty() || value.length > 2048) return null
            if (value.startsWith("WIFI:", ignoreCase = true)) return parseWifi(value)
            LocalShareProtocol.parseInvitation(value)?.let { invitation ->
                if (!LocalShareProtocol.isPrivateIpv4(hostPart(invitation.baseUrl))) return null
                return ShareLink.Credentials(invitation.baseUrl, invitation.ssid, invitation.password)
            }
            LocalShareProtocol.parseConnectedInvitation(value)?.let { return ShareLink.Origin(it.baseUrl) }
            val origin = LocalShareProtocol.normalizeHttpOrigin(value) ?: return null
            return if (LocalShareProtocol.isPrivateIpv4(hostPart(origin))) ShareLink.Origin(origin) else null
        }

        private fun hostPart(origin: String): String = origin.removePrefix("http://").substringBefore(':')

        /** The Wi-Fi QR format with its backslash escapes; only WPA/WPA2 hotspots, as Android emits. */
        private fun parseWifi(value: String): ShareLink? {
            val fields = HashMap<String, String>()
            var key = StringBuilder()
            var current = StringBuilder()
            var inValue = false
            var escaped = false
            for (c in value.substring(5)) {
                when {
                    escaped -> { current.append(c); escaped = false }
                    c == '\\' -> escaped = true
                    !inValue && c == ':' -> { key = current; current = StringBuilder(); inValue = true }
                    c == ';' -> {
                        if (inValue) fields[key.toString().uppercase()] = current.toString()
                        key = StringBuilder(); current = StringBuilder(); inValue = false
                    }
                    else -> current.append(c)
                }
            }
            val ssid = fields["S"] ?: return null
            val password = fields["P"] ?: return null
            if (ssid.isBlank() || ssid.length > 32 || password.length !in 8..63) return null
            return ShareLink.Wifi(ssid, password)
        }
    }
}
