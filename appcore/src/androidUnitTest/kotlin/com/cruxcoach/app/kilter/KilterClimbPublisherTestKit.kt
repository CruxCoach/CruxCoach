package com.cruxcoach.app.kilter

import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.HttpResponse
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.SecretStore
import com.cruxcoach.app.platform.WallClock
import java.util.Base64

/** In-memory Keychain stand-in. */
internal class PublishTestSecrets : SecretStore {
    val items = HashMap<String, ByteArray>()
    override fun read(name: String) = items[name]?.copyOf()
    override fun write(name: String, value: ByteArray): Boolean { items[name] = value.copyOf(); return true }
    override fun delete(name: String) = items.remove(name) != null
}

internal class PublishTestClock(var seconds: Long = 1_790_000_000L) : WallClock {
    override fun epochSeconds() = seconds
    override fun epochMillis() = seconds * 1000
}

/** One recorded request: method, url, headers, body. */
internal class RecordedRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String)

/**
 * Kilter portal stand-in. Nothing here reaches the network: every request is
 * recorded and answered by [responder]. Unanswered requests fail as OTHER.
 */
internal class PortalFake : HttpTransport {
    val requests = mutableListOf<RecordedRequest>()
    var responder: (RecordedRequest) -> HttpResult? = { null }

    override suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxResponseBytes: Long,
        timeoutSeconds: Int,
    ): HttpResult {
        val recorded = RecordedRequest(method, url, headers, body?.decodeToString() ?: "")
        requests += recorded
        return responder(recorded) ?: HttpResult.Failed(HttpFailure.OTHER, "no responder")
    }

    override suspend fun download(
        url: String,
        destinationPath: String,
        maxBytes: Long,
        timeoutSeconds: Int,
        onProgress: (Long, Long) -> Unit,
    ): HttpResult = HttpResult.Failed(HttpFailure.OTHER, "not used")

    fun climbRequests() = requests.filter { "/climbs/" in it.url }
}

internal fun portalOk(body: String, status: Int = 200, headers: Map<String, String> = emptyMap()) =
    HttpResult.Ok(HttpResponse(status, headers, body.encodeToByteArray()))

/** An unsigned JWT with the given claims; the client never verifies it. */
internal fun testJwt(vararg claims: Pair<String, String>): String {
    val enc = Base64.getUrlEncoder().withoutPadding()
    val payload = claims.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }
    return enc.encodeToString("{\"alg\":\"none\"}".toByteArray()) + "." +
        enc.encodeToString(payload.toByteArray()) + ".sig"
}

/** Signed-in Kilter session for one identity, on in-memory stores. */
internal class KilterTestSession(
    val http: PortalFake = PortalFake(),
    val clock: PublishTestClock = PublishTestClock(),
    val keyValues: MemoryKeyValueStore = MemoryKeyValueStore(),
    val secrets: PublishTestSecrets = PublishTestSecrets(),
) {
    val tokens = KilterTokens(secrets, keyValues, IDENTITY)
    val api = KilterApi(http, clock, tokens)
    val account = KilterClimbAccount(api, tokens, keyValues, IDENTITY)

    fun signIn(userUuid: String = USER_UUID, nameClaim: String = "") {
        val claims = if (nameClaim.isEmpty()) arrayOf("sub" to userUuid) else arrayOf("sub" to userUuid, "name" to nameClaim)
        tokens.store(testJwt(*claims), "refresh-1", clock.epochSeconds() + 3600)
        tokens.storeUserUuid(userUuid)
    }

    companion object {
        const val IDENTITY = "abcdef0123456789"
        const val USER_UUID = "user-uuid-1"
    }
}

/** Real in-memory board DB with the `:shared` repository, plus raw reads of the Kilter columns. */
internal class PublishTestDb {
    private val driver: app.cash.sqldelight.db.SqlDriver =
        app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
    private val framesAdapter = object : app.cash.sqldelight.ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = com.cruxcoach.domain.board.FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = com.cruxcoach.domain.board.FramesBinaryCodec.encode(value)
    }
    val board: com.cruxcoach.db.board.BoardDatabase
    val repo: com.cruxcoach.data.repository.BoardRepositoryImpl

    init {
        com.cruxcoach.db.board.BoardDatabase.Schema.create(driver)
        board = com.cruxcoach.db.board.BoardDatabase(
            driver, climbsAdapter = com.cruxcoach.db.board.Climbs.Adapter(framesAdapter = framesAdapter),
        )
        repo = com.cruxcoach.data.repository.BoardRepositoryImpl(board)
    }

    fun close() = driver.close()

    /** An own CruxCoach climb; [published] puts it past the Nostr publish (the retry queue's precondition). */
    fun ownClimb(
        uuid: String,
        pubkey: String = OWN_PUBKEY,
        layoutId: Long = 1L,
        frames: String = "p1r12p2r13p3r14",
        angle: Long = 40L,
        published: Boolean = true,
        edges: LongArray? = longArrayOf(10, 20, 10, 30),
    ) {
        board.boardQueries.insertLocalDraft(
            uuid = uuid, layout_id = layoutId, setter_username = null, name = "Name $uuid",
            frames = frames, edge_left = edges?.get(0), edge_right = edges?.get(1),
            edge_bottom = edges?.get(2), edge_top = edges?.get(3),
            created_at = "2026-10-01T00:00:00Z", description = "desc $uuid", move_count = 3L, hsm = 0L,
            created_by_pubkey = pubkey, frames_hash = "hash-$uuid", board_brand = "kilter",
        )
        board.boardQueries.upsertClimbStat(
            climb_uuid = uuid, angle = angle, display_difficulty = null, difficulty_average = null,
            quality_average = null, ascensionist_count = null, benchmark_difficulty = null,
            fa_username = null, fa_at = null, official_kilter_difficulty = null,
        )
        if (published) {
            repo.markClimbPublishedNostr(uuid, "event-$uuid", "d-$uuid", pubkey, "2026-10-01T00:00:00Z")
        }
    }

    /** Kilter Original size 10 with a board image on layout 1, so a row resolves its own size. */
    fun kilterSize(id: Long = 10L, layoutId: Long = 1L, edges: LongArray = longArrayOf(0, 144, 0, 156)) {
        repo.upsertProductSize(id, 1L, "Size $id", edges[0], edges[1], edges[2], edges[3], null)
        repo.upsertBoardImage(id * 100, id, layoutId, 1L, "img-$id.png")
    }

    fun placement(placementId: Long, holeId: Long) = repo.upsertPlacement(placementId, holeId, 1L, 0L, 0L)

    fun kilterError(uuid: String): String? = column(uuid, "kilter_error")
    fun kilterStatus(uuid: String): String? = column(uuid, "kilter_status")

    private fun column(uuid: String, name: String): String? = driver.executeQuery(
        null, "SELECT $name FROM climbs WHERE uuid = ?",
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        1,
    ) { bindString(0, uuid) }.value

    companion object {
        val OWN_PUBKEY = "a".repeat(64)
    }
}
