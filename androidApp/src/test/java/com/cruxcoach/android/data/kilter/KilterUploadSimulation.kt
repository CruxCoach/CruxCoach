package com.cruxcoach.android.data.kilter

import com.cruxcoach.android.data.PendingImports
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.RawAscent
import com.cruxcoach.data.repository.RawBid
import com.cruxcoach.domain.board.ClimbUuid
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.toInstant
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/*
 * Building blocks of KilterUploadContractSimulationTest: real climb ids, an
 * in-memory Kilter that keeps the live contract of 2026-09-29
 * (docs/research/2026-09-29-kilter-upload-500.md), in-memory repositories the
 * real KilterSyncEngine runs against, and a logbook builder that knows what
 * should become of every entry. Nothing here talks to a network.
 */

/** A climb Kilter knows, in the spelling Kilter stores. */
internal data class FixtureClimb(val id: String, val layout: String = "10") {
    val key: String = ClimbUuid.normKey(id)
    val isNewWorld: Boolean get() = id.length == 36
    val isLegacy: Boolean get() = !isNewWorld

    /** How a logbook entry from [source] spells this climb. */
    fun spelled(source: LocalSpelling): String = when {
        isNewWorld -> id // every source has new-world climbs dashed lowercase
        source == LocalSpelling.CATALOGUE -> key
        source == LocalSpelling.CANONICAL -> id
        source == LocalSpelling.DASHED -> dashed(key)
        else -> key.uppercase()
    }

    companion object {
        fun dashed(hex: String): String =
            "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}

/** Where a logbook entry got its spelling of the climb id from. */
internal enum class LocalSpelling {
    /** Logged in the app or restored from a backup: the device catalogue stores every uuid lowercased. */
    CATALOGUE,
    /** Imported from Kilter: as Kilter stores it. */
    CANONICAL,
    /** Hyphenated spelling of a compact legacy id (exports, the BoardSesh sweep). */
    DASHED,
    /** Catalogue rows of older versions (`/climbs/curated`): compact uppercase. */
    CURATED,
}

/** Real ids sampled from Kilter's catalogue (scripts/kilter/sample_upload_fixture.py) and the bundled index. */
internal object KilterUploadFixture {
    private const val RESOURCE = "kilter/upload_simulation_climbs.txt"

    val climbs: List<FixtureClimb> by lazy {
        val stream = checkNotNull(KilterUploadFixture::class.java.classLoader?.getResourceAsStream(RESOURCE)) {
            "test resource $RESOURCE missing"
        }
        stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line -> line.split('\t').let { (layout, id) -> FixtureClimb(id, layout) } }
                .toList()
        }
    }

    private val indexFile: File by lazy {
        listOf(
            File("src/main/assets/${KilterLowercaseClimbIndex.ASSET_PATH}"),
            File("androidApp/src/main/assets/${KilterLowercaseClimbIndex.ASSET_PATH}"),
        ).first { it.exists() }
    }

    /** The index the app ships. */
    val bundledIndex: KilterLowercaseClimbIndex by lazy { indexFile.inputStream().use(KilterLowercaseClimbIndex::read) }

    /** The shipped index without [missing]: an index with a gap. */
    fun indexWithout(vararg missing: String): KilterLowercaseClimbIndex {
        val drop = missing.mapTo(HashSet()) { ClimbUuid.normKey(it).substring(0, 16).toULong(16).toLong() }
        DataInputStream(indexFile.inputStream().buffered()).use { data ->
            data.readFully(ByteArray(5)) // magic + version, checked by bundledIndex
            val keys = LongArray(data.readInt()) { data.readLong() }
            return KilterLowercaseClimbIndex(keys.filterNot { it in drop }.toLongArray())
        }
    }
}

/**
 * Kilter's log endpoints in memory, as probed live on 2026-09-29:
 * - a log's climb id must be the id Kilter stores, compared case-sensitively;
 *   the dashed spelling of a legacy compact climb is accepted, but files the
 *   log under a separate statistics identity (recorded in [dashedLegacyWrites]);
 * - `POST /logs/bulk` is atomic: one refused row fails the request with 500 and
 *   nothing is written; a log uuid Kilter holds already is refused (insert, not
 *   upsert); a timestamp that is not an ISO instant is answered with 400;
 * - `GET /logs` returns the account's logs as stored; `DELETE /logs/{uuid}` removes one.
 *
 * [fault] injects what the contract does not decide: transient statuses, a
 * lost session, lost responses, an unreachable network and Kilter's own 500s.
 */
internal class FakeKilterServer(climbs: Collection<FixtureClimb>) {
    enum class Op { BULK, FETCH, DELETE }

    enum class Fault {
        HTTP_503, HTTP_429, HTTP_401,
        /** Kilter fails on its own: 500 whatever the request holds. */
        HTTP_500,
        /** Kilter processes the request, the client never sees the answer. */
        LOST_RESPONSE,
        /** The request never reaches Kilter. */
        OFFLINE,
    }

    /** One bulk request as Kilter saw it. [status] is what the client got, null when no answer arrived. */
    class BulkRequest(val ordinal: Int, val rows: List<KilterLog>, val status: Int?, val written: Boolean, val fault: Fault?) {
        val accepted: Boolean get() = status == 200
    }

    private val canonical = HashMap<String, String>()
    private val legacy = HashSet<String>()

    /** The account's logs by log uuid, as `GET /logs` returns them. */
    val logs = LinkedHashMap<String, KilterLog>()
    val requests = ArrayList<BulkRequest>()
    val dashedLegacyWrites = ArrayList<KilterLog>()

    /** Requests refused because they named a log uuid Kilter holds already: a client resending a written log. */
    var knownUuidRefusals = 0
        private set
    var fetches = 0
        private set
    var deletes = 0
        private set

    /** Which fault, if any, hits the [Op] request with this ordinal (per op, from 0). */
    var fault: (Op, Int) -> Fault? = { _, _ -> null }

    init {
        climbs.forEach(::addClimb)
    }

    fun addClimb(climb: FixtureClimb) {
        canonical[climb.key] = climb.id
        if (climb.isLegacy) legacy += climb.key
    }

    fun knows(id: String): Boolean = ClimbUuid.normKey(id) in canonical

    /** The climb a log names: its stored id, or the dashed spelling of a legacy climb. Null: Kilter refuses it. */
    fun resolve(id: String): String? {
        val key = ClimbUuid.normKey(id)
        val stored = canonical[key] ?: return null
        if (id == stored) return stored
        if (id.length == 36 && key in legacy) return stored
        return null
    }

    /** The status Kilter answers [rows] with on its own, or null when it writes them. */
    fun refusal(rows: List<KilterLog>): Int? {
        if (rows.any { runCatching { Instant.parse(it.createdAt) }.isFailure }) return 400
        if (rows.any { resolve(it.climbUuid) == null }) return 500
        val inRequest = HashSet<String>()
        if (rows.any { it.logUuid in logs || !inRequest.add(it.logUuid) }) {
            knownUuidRefusals++
            return 500
        }
        return null
    }

    /** POST /logs/bulk, answered the way KilterApiClient.uploadLogs reports it. */
    fun bulk(rows: List<KilterLog>): Result<Unit> {
        val ordinal = requests.size
        val injected = fault(Op.BULK, ordinal)
        if (injected == Fault.OFFLINE) {
            requests += BulkRequest(ordinal, rows, null, written = false, fault = injected)
            return Result.failure(IOException("unreachable"))
        }
        val status = when (injected) {
            Fault.HTTP_503 -> 503
            Fault.HTTP_429 -> 429
            Fault.HTTP_401 -> 401
            Fault.HTTP_500 -> 500
            else -> refusal(rows) ?: 200
        }
        val written = status == 200
        if (written) rows.forEach(::write)
        val lost = injected == Fault.LOST_RESPONSE
        requests += BulkRequest(ordinal, rows, if (lost) null else status, written, injected)
        return when {
            lost -> Result.failure(IOException("response lost"))
            status == 200 -> Result.success(Unit)
            else -> Result.failure(KilterUploadException(status))
        }
    }

    /** GET /logs, failing the way KilterApiClient.fetchLogs does after its own retries. */
    fun fetch(): Result<List<KilterLog>> = when (val injected = fault(Op.FETCH, fetches++)) {
        null -> Result.success(logs.values.toList())
        Fault.OFFLINE, Fault.LOST_RESPONSE -> Result.failure(IOException("unreachable"))
        Fault.HTTP_401 -> Result.failure(KilterApiException(KilterAuthResult.Error.Reason.NotAuthenticated, "no valid token"))
        else -> Result.failure(Exception("HTTP ${injected.name.removePrefix("HTTP_")}: "))
    }

    /** DELETE /logs/{uuid}; an unknown uuid is a harmless no-op. */
    fun delete(logUuid: String): KilterPublishResult = when (fault(Op.DELETE, deletes++)) {
        null -> {
            logs.remove(logUuid)
            KilterPublishResult.Success(logUuid)
        }
        Fault.HTTP_401 -> KilterPublishResult.NotAuthenticated
        else -> KilterPublishResult.TransientError("HTTP 503")
    }

    /** A log Kilter already holds (written by the official app, or earlier). */
    fun seed(log: KilterLog) {
        check(resolve(log.climbUuid) != null) { "seeded log names an unknown climb" }
        write(log)
    }

    private fun write(log: KilterLog) {
        val stored = log.copy(createdAt = kilterTime(Instant.parse(log.createdAt)))
        logs[log.logUuid] = stored
        if (stored.climbUuid != canonical[ClimbUuid.normKey(stored.climbUuid)]) dashedLegacyWrites += stored
    }

    companion object {
        private val KILTER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

        /** Kilter keeps microseconds and answers in this shape. */
        fun kilterTime(instant: Instant): String = KILTER_TIME.format(instant.plusNanos(500).truncatedTo(ChronoUnit.MICROS))
    }
}

/** The personal logbook in memory: what the upload reads and marks, and what a download inserts. */
internal class FakeLogbook(
    private val rest: PersonalBoardRepository = mockk(relaxed = true),
) : PersonalBoardRepository by rest {
    val ascents = LinkedHashMap<String, RawAscent>()
    val bids = LinkedHashMap<String, RawBid>()
    val deletions = LinkedHashSet<String>()

    /** Null when the entry is gone. */
    fun isSynced(uuid: String): Boolean? = ascents[uuid]?.synced ?: bids[uuid]?.synced

    /** The app's delete: the entry goes, a tombstone waits until Kilter confirmed. */
    fun deleteEntry(uuid: String) {
        ascents.remove(uuid)
        bids.remove(uuid)
        deletions += uuid
    }

    /** A local edit: new content, new row version, back to unsynced. */
    fun editAttempts(uuid: String, bidCount: Long) {
        ascents[uuid]?.let { ascents[uuid] = it.copy(bidCount = bidCount, rowVersion = it.rowVersion + 1, synced = false) }
        bids[uuid]?.let { bids[uuid] = it.copy(bidCount = bidCount, rowVersion = it.rowVersion + 1, synced = false) }
    }

    override fun getUnsyncedAscents(): List<RawAscent> = ascents.values.filter { !it.synced }
    override fun getUnsyncedBids(): List<RawBid> = bids.values.filter { !it.synced }

    override fun markAscentSyncedIfUnchanged(uuid: String, expectedRowVersion: Long): Boolean {
        val row = ascents[uuid]?.takeIf { it.rowVersion == expectedRowVersion } ?: return false
        ascents[uuid] = row.copy(synced = true)
        return true
    }

    override fun markBidSyncedIfUnchanged(uuid: String, expectedRowVersion: Long): Boolean {
        val row = bids[uuid]?.takeIf { it.rowVersion == expectedRowVersion } ?: return false
        bids[uuid] = row.copy(synced = true)
        return true
    }

    override fun runInTransaction(block: () -> Unit) = block()
    override fun pendingLogDeletions(): List<String> = deletions.toList()
    override fun clearLogDeletion(logUuid: String) {
        deletions -= logUuid
    }

    override fun getExistingLogUuids(): Set<String> = ascents.keys + bids.keys

    override fun insertAscent(
        uuid: String, climbUuid: String, angle: Long, isMirror: Boolean, attemptId: Long, bidCount: Long,
        quality: Long?, difficulty: Long?, isBenchmark: Boolean, comment: String?, climbedAt: String, synced: Boolean,
        gymUuid: String?, wallUuid: String?, productLayoutUuid: String?, climbName: String, difficultyAverage: Double?,
        climbFrames: String, framesCount: Long, boardBrand: String, layoutId: Long?, externalId: String?,
    ) {
        ascents[uuid] = RawAscent(
            uuid = uuid, climbUuid = climbUuid, angle = angle, isMirror = isMirror, attemptId = attemptId,
            bidCount = bidCount, quality = quality, difficulty = difficulty, isBenchmark = isBenchmark,
            comment = comment, climbedAt = climbedAt, synced = synced, gymUuid = gymUuid, wallUuid = wallUuid,
            productLayoutUuid = productLayoutUuid, externalId = externalId,
        )
    }

    override fun insertBid(
        uuid: String, climbUuid: String, angle: Long, isMirror: Boolean, bidCount: Long, comment: String?,
        climbedAt: String, synced: Boolean, gymUuid: String?, wallUuid: String?, productLayoutUuid: String?,
        climbName: String, difficultyAverage: Double?, boardBrand: String, layoutId: Long?, externalId: String?,
    ) {
        bids[uuid] = RawBid(
            uuid = uuid, climbUuid = climbUuid, angle = angle, isMirror = isMirror, bidCount = bidCount,
            comment = comment, climbedAt = climbedAt, synced = synced, gymUuid = gymUuid, wallUuid = wallUuid,
            productLayoutUuid = productLayoutUuid, externalId = externalId,
        )
    }
}

/** The board catalogue on the device: every uuid lowercased, CruxCoach's own climbs flagged. */
internal class FakeCatalogue(
    private val rest: BoardRepository = mockk(relaxed = true),
) : BoardRepository by rest {
    val rows = HashSet<String>()
    val cruxcoach = HashSet<String>()
    val communityOnly = HashSet<String>()

    /** The catalogue database is busy (e.g. right after an import): the spelling lookups throw. */
    var spellingLookupsFail = false

    fun add(climb: FixtureClimb) {
        rows += climb.id.lowercase()
    }

    override fun communityOnlyClimbUuids(uuids: Collection<String>): Set<String> = uuids.filterTo(HashSet()) { it in communityOnly }

    override fun cruxcoachClimbUuids(uuids: Collection<String>): Set<String> {
        if (spellingLookupsFail) throw IllegalStateException("database is locked")
        return uuids.filterTo(HashSet()) { it in cruxcoach }
    }

    override fun existingClimbUuids(uuids: Collection<String>): Set<String> {
        if (spellingLookupsFail) throw IllegalStateException("database is locked")
        return uuids.filterTo(HashSet()) { it in rows }
    }

    override fun getClimbsByUuidsAnyAngle(uuids: Collection<String>): List<ClimbWithStats> = emptyList()
    override fun getClimbDifficultiesForAngle(uuids: Collection<String>, angle: Int): Map<String, Double> = emptyMap()
}

/**
 * The real [KilterSyncEngine] wired to [kilter] and in-memory repositories.
 * The ledger survives [restartApp] (a new engine), as the preferences do.
 */
internal class UploadSimulation(
    val kilter: FakeKilterServer,
    private val index: KilterLowercaseClimbIndex = KilterUploadFixture.bundledIndex,
) {
    val logbook = FakeLogbook()
    val catalogue = FakeCatalogue()
    val ledger = InMemoryKilterUploadLedger()
    val push = MutableStateFlow(true)
    val statuses = ArrayList<KilterUploadStatus>()
    var now: Long = Instant.parse("2026-10-01T08:00:00Z").toEpochMilli()

    private val api = mockk<KilterApiClient>(relaxed = true)
    private val tokens = mockk<KilterTokenStore>(relaxed = true)
    private val prefs = mockk<UserPreferences>(relaxed = true)
    private val diagnostics = mockk<KilterUploadDiagnostics>(relaxed = true)
    private val pendingImports = mockk<PendingImports>(relaxed = true)

    var engine: KilterSyncEngine
        private set

    init {
        every { prefs.kilterPushEnabled } returns push
        every { prefs.kilterSyncEnabled } returns flowOf(true)
        every { tokens.hasCredentials() } returns true
        every { tokens.getUserUuid() } returns USER
        every { tokens.hasWallContext() } returns true
        every { tokens.getGymUuid() } returns GYM
        every { tokens.getWallUuid() } returns WALL
        every { tokens.getProductLayoutUuid() } returns "10"
        every { diagnostics.record(any()) } answers { statuses += firstArg<KilterUploadStatus>() }
        coEvery { api.uploadLogs(any()) } answers { kilter.bulk(firstArg()) }
        coEvery { api.fetchLogs() } answers { kilter.fetch() }
        coEvery { api.deleteLog(any()) } answers { kilter.delete(firstArg()) }
        coEvery { api.fetchLoggedClimbs() } returns Result.success(KilterLoggedClimbsResponse())
        coEvery { api.fetchOwnAuthoredClimbs() } returns Result.success(emptyList())
        coEvery { api.fetchCircuits() } returns Result.success(emptyList())
        engine = newEngine()
    }

    private fun newEngine() = KilterSyncEngine(
        api, tokens, catalogue, logbook, mockk(relaxed = true), prefs, diagnostics,
        dagger.Lazy { pendingImports }, ledger, KilterLowercaseClimbIndexSource { index },
    ).also { it.clock = { now } }

    /** The app is restarted: a fresh engine, the same stores. */
    fun restartApp() {
        engine = newEngine()
    }

    suspend fun upload(trigger: KilterUploadTrigger = KilterUploadTrigger.MANUAL, afterMs: Long = 20 * 60_000L): KilterUploadStatus {
        now += afterMs
        return engine.uploadPendingLogs(trigger)
    }

    suspend fun sync(afterMs: Long = 20 * 60_000L): KilterSyncReport {
        now += afterMs
        return engine.syncBidirectional().getOrThrow()
    }

    suspend fun parked(): Set<String> = ledger.rejections().filter { it.confirmed }.mapTo(HashSet()) { it.logUuid }

    companion object {
        const val USER = "sim-user"
        const val GYM = "sim-gym"
        const val WALL = "sim-wall"
    }
}

/** What should become of a logbook entry. */
internal enum class Fate {
    /** Kilter knows the climb: the entry belongs on Kilter, once. */
    VALID,
    /** Kilter holds this ascent under another log uuid: must not go up again. */
    TWIN,
    /** Kilter does not know the climb: refused whenever it is sent. */
    UNKNOWN,
    /** Kilter holds the entry's uuid with other content. */
    CONFLICT,
    /** The timestamp cannot be expressed for Kilter. */
    INVALID,
    /** A CruxCoach community climb Kilter never accepted: stays local. */
    COMMUNITY,
}

internal class UploadEntry(
    val uuid: String,
    val isAscent: Boolean,
    val fate: Fate,
    val imported: Boolean,
    val climb: FixtureClimb?,
    val localClimbUuid: String,
    val angle: Int,
    /** Kilter's log of the same ascent under another uuid (TWIN). */
    val twinLog: String? = null,
)

/** How an imported twin's time relates to Kilter's copy. */
internal enum class TwinShift { EXACT, SAME_DAY, LOCAL_DAY_AFTER, LOCAL_DAY_BEFORE }

/**
 * A logbook and the matching Kilter account, built entry by entry, with the
 * expected fate of every entry ([Fate]) so each run can be checked against it.
 * Climbs of imported entries are used by nothing else, so the day-window twin
 * matching never meets a second candidate; every ascent has its own second.
 */
internal class UploadWorld(val sim: UploadSimulation, seed: Long, climbs: List<FixtureClimb> = KilterUploadFixture.climbs) {
    val rng = java.util.Random(seed)
    val entries = LinkedHashMap<String, UploadEntry>()
    private val shuffled = climbs.shuffled(rng)
    private val reserved = ArrayDeque(shuffled.take(climbs.size / 4))
    private val shared = shuffled.drop(climbs.size / 4)
    private var second = Instant.parse("2025-02-03T16:00:00Z").epochSecond
    private var serial = 0

    val logbook get() = sim.logbook
    val kilter get() = sim.kilter

    init {
        climbs.forEach(sim.catalogue::add)
    }

    fun uuid(): String = UUID(rng.nextLong(), rng.nextLong()).toString()

    fun sharedClimb(): FixtureClimb = shared[rng.nextInt(shared.size)]
    fun reservedClimb(): FixtureClimb = reserved.removeFirst()

    /** A climb authored in CruxCoach and accepted by Kilter: lowercase compact on both sides. */
    fun cruxcoachClimb(): FixtureClimb {
        val climb = FixtureClimb(hex32().lowercase())
        kilter.addClimb(climb)
        sim.catalogue.add(climb)
        sim.catalogue.cruxcoach += climb.id
        return climb
    }

    private fun hex32() = "%016X%016X".format(rng.nextLong(), rng.nextLong())

    /** A climb id Kilter does not know, in one of the shapes the probes used. */
    fun unknownClimbId(): String {
        while (true) {
            val hex = hex32()
            val id = when (rng.nextInt(3)) {
                0 -> hex
                1 -> hex.lowercase()
                else -> FixtureClimb.dashed(hex.lowercase())
            }
            if (!kilter.knows(id)) return id
        }
    }

    private fun communityClimbId(): String {
        val id = hex32().lowercase()
        sim.catalogue.rows += id
        sim.catalogue.cruxcoach += id
        sim.catalogue.communityOnly += id
        return id
    }

    fun randomSpelling(): LocalSpelling {
        val r = rng.nextInt(100)
        return when {
            r < 45 -> LocalSpelling.CATALOGUE
            r < 75 -> LocalSpelling.CANONICAL
            r < 90 -> LocalSpelling.DASHED
            else -> LocalSpelling.CURATED
        }
    }

    /** The next ascent time; [local] keeps it in daytime so no DST transition can merge two seconds. */
    fun nextInstant(local: Boolean = true): Instant {
        while (true) {
            second += 240 + rng.nextInt(7200)
            val at = Instant.ofEpochSecond(second, rng.nextInt(1000) * 1_000_000L)
            if (!local || LocalDateTime.ofInstant(at, ZoneId.systemDefault()).hour in 10..21) return at
        }
    }

    /** Device-local time without a zone (DateTimeUtil.nowIso) or a UTC instant as Kilter writes it. */
    private fun stamp(at: Instant, local: Boolean): String =
        if (local) LocalDateTime.ofInstant(at, ZoneId.systemDefault()).format(LOCAL_TIME) else FakeKilterServer.kilterTime(at)

    /** The instant the app will send for a local timestamp. */
    fun sentInstant(climbedAt: String): Instant? = KilterLogUploadPlan.kilterTimestamp(climbedAt)?.let(Instant::parse)

    fun add(
        kind: Fate = Fate.VALID,
        imported: Boolean = false,
        isAscent: Boolean = rng.nextInt(4) != 0,
        climb: FixtureClimb? = null,
        spelling: LocalSpelling = randomSpelling(),
        localTime: Boolean = !imported && rng.nextBoolean(),
        at: Instant? = null,
        twinAt: Instant? = null,
        angle: Int = ANGLES[rng.nextInt(ANGLES.size)],
    ): UploadEntry {
        val uuid = uuid()
        val target = when (kind) {
            Fate.UNKNOWN, Fate.COMMUNITY -> null
            else -> climb ?: if (imported) reservedClimb() else sharedClimb()
        }
        val local = when (kind) {
            Fate.UNKNOWN -> unknownClimbId()
            Fate.COMMUNITY -> communityClimbId()
            else -> target!!.spelled(spelling)
        }
        val time = at ?: nextInstant(localTime)
        val climbedAt = if (kind == Fate.INVALID) INVALID_TIMES[rng.nextInt(INVALID_TIMES.size)] else stamp(time, localTime)
        val tries = (if (isAscent) 1 + rng.nextInt(6) else 1 + rng.nextInt(15)).toLong()
        val externalId = if (imported) "aurora-json:${if (isAscent) "ascent" else "bid"}:${serial++}" else null
        val fromKilter = spelling == LocalSpelling.CANONICAL
        if (isAscent) {
            logbook.ascents[uuid] = RawAscent(
                uuid = uuid, climbUuid = local, angle = angle.toLong(), isMirror = false,
                attemptId = if (tries <= 1) 0 else 1, bidCount = tries, quality = null, difficulty = null,
                isBenchmark = false, comment = null, climbedAt = climbedAt, synced = false,
                gymUuid = if (fromKilter) GYM_ELSEWHERE else null, wallUuid = if (fromKilter) WALL_ELSEWHERE else null,
                productLayoutUuid = if (fromKilter) target?.layout else null, externalId = externalId,
            )
        } else {
            logbook.bids[uuid] = RawBid(
                uuid = uuid, climbUuid = local, angle = angle.toLong(), isMirror = false, bidCount = tries,
                comment = null, climbedAt = climbedAt, synced = false, externalId = externalId,
            )
        }
        var twinLog: String? = null
        val sent = sentInstant(climbedAt)
        when (kind) {
            Fate.TWIN -> {
                val remoteAt = twinAt ?: sent!!.truncatedTo(ChronoUnit.SECONDS).plusMillis(rng.nextInt(1000).toLong())
                twinLog = uuid()
                kilter.seed(remoteLog(twinLog, target!!, angle, isAscent, tries + rng.nextInt(3), remoteAt))
            }
            Fate.CONFLICT -> kilter.seed(remoteLog(uuid, target!!, angle, isAscent, tries + 2, sent!!))
            else -> Unit
        }
        return UploadEntry(uuid, isAscent, kind, imported, target, local, angle, twinLog).also { entries[uuid] = it }
    }

    /** An imported entry whose ascent Kilter holds under another uuid, at [shift] from Kilter's copy. */
    fun addImportedTwin(shift: TwinShift, isAscent: Boolean = rng.nextInt(4) != 0): UploadEntry {
        second += 2 * 86_400L
        val day = Instant.ofEpochSecond(second).atOffset(ZoneOffset.UTC).toLocalDate()
        fun at(dayOffset: Long, hour: Int, minute: Int) = day.plusDays(dayOffset).atTime(hour, minute).toInstant(ZoneOffset.UTC)
        val (remote, local) = when (shift) {
            TwinShift.EXACT -> at(0, 18, 5).plusMillis(250) to at(0, 18, 5).plusMillis(730)
            TwinShift.SAME_DAY -> at(0, 9, 10) to at(0, 19, 40)
            TwinShift.LOCAL_DAY_AFTER -> at(0, 23, 30) to at(1, 1, 30)
            TwinShift.LOCAL_DAY_BEFORE -> at(0, 0, 30) to at(-1, 22, 30)
        }
        return add(Fate.TWIN, imported = true, isAscent = isAscent, localTime = false, at = local, twinAt = remote)
    }

    /** The account's history: logs Kilter holds that the device downloaded earlier. */
    fun seedHistory(count: Int) {
        repeat(count) {
            val climb = sharedClimb()
            val isAscent = rng.nextInt(4) != 0
            val log = remoteLog(uuid(), climb, ANGLES[rng.nextInt(ANGLES.size)], isAscent, 1L + rng.nextInt(5), nextInstant(false))
            kilter.seed(log)
            val stored = kilter.logs.getValue(log.logUuid)
            if (isAscent) {
                logbook.ascents[log.logUuid] = RawAscent(
                    uuid = log.logUuid, climbUuid = log.climbUuid, angle = log.angle.toLong(), isMirror = false,
                    attemptId = 1, bidCount = log.attempts.toLong(), quality = null, difficulty = null,
                    isBenchmark = false, comment = null, climbedAt = stored.createdAt, synced = true,
                )
            } else {
                logbook.bids[log.logUuid] = RawBid(
                    uuid = log.logUuid, climbUuid = log.climbUuid, angle = log.angle.toLong(), isMirror = false,
                    bidCount = log.attempts.toLong(), comment = null, climbedAt = stored.createdAt, synced = true,
                )
            }
        }
    }

    private fun remoteLog(logUuid: String, climb: FixtureClimb, angle: Int, topped: Boolean, attempts: Long, at: Instant) = KilterLog(
        logUuid = logUuid, userUuid = UploadSimulation.USER, climbUuid = climb.id, gymUuid = GYM_ELSEWHERE,
        wallUuid = WALL_ELSEWHERE, productLayoutUuid = climb.layout, angle = angle, flashed = topped && attempts <= 1,
        topped = topped, attempts = attempts.toInt(), createdAt = at.toString(),
    )

    /**
     * Checks one finished run against the expected fate of every entry.
     * [optedIn]: the imported opt-in as it stood when the run started;
     * [firstRequest]: Kilter's request count before the run.
     */
    suspend fun verifyRun(status: KilterUploadStatus, optedIn: Boolean, firstRequest: Int, label: String) {
        fun fail(message: String): Nothing =
            throw AssertionError("$label: $message\n  ${KilterUploadDiagnostics.diagnosticLine(status)}")

        val sent = kilter.requests.subList(firstRequest, kilter.requests.size)
        if (status.requests > MAX_REQUESTS) fail("requests=${status.requests} exceeds $MAX_REQUESTS")
        if (sent.size != status.requests) fail("status counts ${status.requests} requests, Kilter saw ${sent.size}")
        if (kilter.knownUuidRefusals > 0) fail("a log Kilter already holds was sent again (${kilter.knownUuidRefusals} requests)")
        if (kilter.dashedLegacyWrites.isNotEmpty()) {
            fail("a legacy climb was written under its dashed id (separate statistics identity): ${kilter.dashedLegacyWrites.first().climbUuid}")
        }
        for (request in sent) for (row in request.rows) {
            val entry = entries[row.logUuid] ?: fail("sent a log that is no logbook entry: ${row.logUuid}")
            if (entry.fate in NEVER_SENT) fail("sent a ${entry.fate} entry (${entry.uuid})")
            if (entry.imported && !optedIn) fail("sent an imported entry without the opt-in (${entry.uuid})")
            if (row.userUuid != UploadSimulation.USER) fail("sent a log for another user")
        }

        // A log the user deleted here is on its way out of Kilter; it may coexist with a re-logged twin.
        val ascentsOnKilter = HashMap<String, String>()
        for (log in kilter.logs.values) {
            if (kilter.resolve(log.climbUuid) != log.climbUuid) fail("Kilter holds a non-canonical climb id ${log.climbUuid}")
            if (log.logUuid in logbook.deletions) continue
            val ascent = "${ClimbUuid.normKey(log.climbUuid)}|${log.angle}|${log.topped}|${Instant.parse(log.createdAt).epochSecond}"
            ascentsOnKilter.put(ascent, log.logUuid)?.let { fail("the same ascent twice on Kilter: $it and ${log.logUuid}") }
        }
        for (entry in entries.values) {
            val synced = logbook.isSynced(entry.uuid) ?: continue
            val remote = kilter.logs[entry.uuid]
            when (entry.fate) {
                Fate.VALID -> {
                    if (synced && remote == null) fail("${entry.uuid} is marked synced, but Kilter does not have it (lost)")
                    if (remote != null && (remote.climbUuid != entry.climb!!.id || remote.angle != entry.angle || remote.topped != entry.isAscent)) {
                        fail("${entry.uuid} is on Kilter with other content: ${remote.climbUuid}@${remote.angle}")
                    }
                }
                Fate.TWIN -> {
                    if (remote != null) fail("twin ${entry.uuid} was uploaded again (Kilter has ${entry.twinLog})")
                    if (synced && entry.twinLog !in kilter.logs) fail("twin ${entry.uuid} is marked synced, but its Kilter copy is gone")
                }
                else -> if (synced) fail("${entry.fate} entry ${entry.uuid} is marked synced")
            }
        }

        val parked = sim.parked().filter { uuid ->
            val entry = entries[uuid]
            entry != null && entry.fate in BELONGS_ON_KILTER && logbook.isSynced(uuid) == false
        }
        if (parked.isNotEmpty()) fail("${parked.size} entries Kilter would take are held back, e.g. ${parked.first()}")

        val unsynced = entries.values.filter { logbook.isSynced(it.uuid) == false && it.fate != Fate.COMMUNITY }
        val eligible = unsynced.count { !it.imported || optedIn }
        if (status.pending + status.rejected != eligible) {
            fail("pending=${status.pending} + rejected=${status.rejected} != $eligible unsynced entries the run covered")
        }
        if (!optedIn && status.heldImported != unsynced.count { it.imported }) {
            fail("heldImported=${status.heldImported}, logbook has ${unsynced.count { it.imported }} unsynced imported entries")
        }
        if (status.uploaded > status.attempted) fail("uploaded > attempted")
    }

    /** Every entry that belongs on Kilter (and was not left out as imported) is there and marked; nothing else is. */
    fun allDelivered(includeImported: Boolean): Boolean = entries.values.all { entry ->
        if (entry.imported && !includeImported) return@all true
        when (entry.fate) {
            Fate.VALID -> logbook.isSynced(entry.uuid) == true && entry.uuid in kilter.logs
            Fate.TWIN -> logbook.isSynced(entry.uuid) == true
            else -> true
        }
    }

    companion object {
        const val MAX_REQUESTS = 40
        const val GYM_ELSEWHERE = "kilter-gym"
        const val WALL_ELSEWHERE = "kilter-wall"
        val ANGLES = listOf(20, 25, 30, 35, 40, 45, 50)
        val NEVER_SENT = setOf(Fate.TWIN, Fate.CONFLICT, Fate.INVALID, Fate.COMMUNITY)
        val BELONGS_ON_KILTER = setOf(Fate.VALID, Fate.TWIN)
        private val INVALID_TIMES = listOf("2026-05-01Z", "", "yesterday")
        private val LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    }
}

/**
 * The 0.2.3 upload (KilterSyncEngine.uploadPendingLogs and KilterApiClient.uploadLogs
 * at v0.2.3), reduced to what decided its outcome: every compact 32-hex id
 * uppercased, ascents then bids in table order, 200 per request, the logbook
 * read before each request, and the run stopping at the first failure.
 */
internal object Upload023 {
    private val COMPACT_CLIMB_UUID = Regex("[0-9a-fA-F]{32}")

    fun run(logbook: FakeLogbook, catalogue: FakeCatalogue, kilter: FakeKilterServer): KilterUploadStatus {
        val rows = (logbook.getUnsyncedAscents().map { Triple(it.uuid, true, it) } + logbook.getUnsyncedBids().map { Triple(it.uuid, false, it) })
            .filter { (_, _, row) -> climbOf(row) !in catalogue.communityOnly }
        var uploaded = 0
        var attempted = 0
        var pending = rows.size
        for (batch in rows.chunked(200)) {
            attempted += batch.size
            val logs = batch.map { (uuid, isAscent, row) -> wireLog(uuid, isAscent, row) }
            val existing = kilter.fetch().getOrThrow().associateBy { it.logUuid }
            val missing = logs.filter { existing[it.logUuid] == null }
            val result = if (missing.isEmpty()) Result.success(Unit) else kilter.bulk(missing)
            val status = (result.exceptionOrNull() as? KilterUploadException)?.status
            if (result.isFailure) {
                return KilterUploadStatus(uploaded, pending, attempted, KilterUploadReason.HTTP, status, durationMs = 0)
            }
            for ((uuid, isAscent, _) in batch) {
                if (isAscent) logbook.markAscentSyncedIfUnchanged(uuid, 0) else logbook.markBidSyncedIfUnchanged(uuid, 0)
            }
            uploaded += batch.size
            pending -= batch.size
        }
        return KilterUploadStatus(uploaded, pending, attempted, durationMs = 0)
    }

    private fun climbOf(row: Any) = if (row is RawAscent) row.climbUuid else (row as RawBid).climbUuid

    private fun wireLog(uuid: String, isAscent: Boolean, row: Any): KilterLog {
        val climb = climbOf(row)
        val (angle, bidCount, climbedAt) = if (row is RawAscent) Triple(row.angle, row.bidCount, row.climbedAt)
            else (row as RawBid).let { Triple(it.angle, it.bidCount, it.climbedAt) }
        return KilterLog(
            logUuid = uuid, userUuid = UploadSimulation.USER,
            climbUuid = if (COMPACT_CLIMB_UUID.matches(climb)) climb.uppercase() else climb,
            gymUuid = UploadSimulation.GYM, wallUuid = UploadSimulation.WALL, productLayoutUuid = "10",
            angle = angle.toInt(), flashed = isAscent && bidCount <= 1L, topped = isAscent,
            attempts = bidCount.toInt().coerceAtLeast(1), createdAt = ensureUtcSuffix(climbedAt),
        )
    }

    /** KilterSyncEngine.ensureUtcSuffix at v0.2.3. */
    private fun ensureUtcSuffix(timestamp: String): String {
        if (timestamp.endsWith("Z")) return timestamp
        return try {
            kotlinx.datetime.LocalDateTime.parse(timestamp).toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toString()
        } catch (_: Exception) {
            "${timestamp}Z"
        }
    }
}
