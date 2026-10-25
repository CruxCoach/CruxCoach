package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.db.secure.SecureDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.security.SecureRandom

/** Test-only JNI of the loopback harness library. Absent from Android builds. */
internal object MarmotTestNative {
    init { System.loadLibrary("cruxcoach_marmot") }
    @JvmStatic external fun failCruxcoach(port: Int)
    @JvmStatic external fun createIdentity(): String
    @JvmStatic external fun restartIdentity(path: String, databaseKey: ByteArray): String
    @JvmStatic external fun destroyIdentity(identity: Long)
    @JvmStatic external fun signEvent(identity: Long, unsigned: String): String
    @JvmStatic external fun verify(signature: ByteArray, hash: ByteArray, public: ByteArray): Boolean
    @JvmStatic external fun open(identity: Long, path: String, databaseKey: ByteArray, config: String): Long
    @JvmStatic external fun startRelay(): String
    @JvmStatic external fun stopRelay(handle: Long)

    fun value(envelope: String): JsonElement {
        val response = Json.parseToJsonElement(envelope).jsonObject
        check(response["ok"]!!.jsonPrimitive.boolean) { "harness_refused" }
        return response["value"]!!
    }
}

/** A loopback nostr-relay-builder relay (live subscriptions, NIP-77). */
class SyntheticRelay : AutoCloseable {
    private val started = MarmotTestNative.value(MarmotTestNative.startRelay()).jsonObject
    private val handle = started["handle"]!!.jsonPrimitive.long
    val url: String = started["url"]!!.jsonPrimitive.content
    override fun close() = MarmotTestNative.stopRelay(handle)
}

/** Only the process-restart test supplies this temporary encrypted store. Its
 * random master key arrives through a pipe and is destroyed with the run. */
data class SyntheticEndpointStorage(val directory: java.io.File, val key: ByteArray)

/**
 * A standalone endpoint with a synthetic account, its own app database and
 * native MLS state, driving the production [MarmotHost] chokepoint. No
 * production identity or key can be supplied.
 */
open class SyntheticMarmotEndpoint(
    val endpoints: List<String>,
    private val loopbackHarness: Boolean = true,
    private val storage: SyntheticEndpointStorage? = null,
) : AutoCloseable {
    val directory: java.io.File = storage?.directory ?: Files.createTempDirectory("cc-native-e2e-").toFile()
    private val key = storage?.key?.copyOf() ?: ByteArray(32).also(SecureRandom()::nextBytes)
    private val identity = MarmotTestNative.value(
        if (storage == null) MarmotTestNative.createIdentity()
        else MarmotTestNative.restartIdentity(directory.resolve("identity.db").absolutePath, key),
    ).jsonObject
    private val identityHandle = identity["handle"]!!.jsonPrimitive.long
    val account: String = identity["account"]!!.jsonPrimitive.content
    /** Optional op timings for diagnosing slow process tests (test-only, no payloads). */
    private val traceFile = System.getenv("CC_TRACE_DIR")?.let { java.io.File(it, "trace-${account.take(8)}.log") }
    private val timing = object : MarmotTrace {
        override fun <T> section(name: String, block: () -> T): T {
            val start = System.currentTimeMillis()
            try { return block() } finally { note("$name ${System.currentTimeMillis() - start}ms") }
        }
    }

    fun note(line: String) {
        traceFile?.appendText("${System.currentTimeMillis()} ${Thread.currentThread().name} $line\n")
    }

    val host = MarmotHost(trace = timing, open = {
        val config = buildJsonObject {
            put("account", account)
            put("local_test", loopbackHarness)
            put("relays", buildJsonArray { endpoints.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }
        MarmotTestNative.open(identityHandle, directory.resolve("mls.db").absolutePath, key, config.toString())
    })
    private val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("app.db").absolutePath}?foreign_keys=on")
    val database: SecureDatabase = SecureDatabase(driver).also {
        if (driver.executeQuery(null, "SELECT count(*) FROM sqlite_master", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)) }, 0).value == 0L) {
            SecureDatabase.Schema.create(driver)
        }
    }

    val store = SharingStore(database)
    val source = SharingSource(database)
    val sync = SharingSync(store, source)
    val access = object : SharingHost {
        override val host get() = this@SyntheticMarmotEndpoint.host
        override suspend fun <T> interactive(block: suspend () -> T): T = block()
    }
    val service = SharingService(access, store, sync, source)

    /** One production pass: withdrawals, online, native sync, app step. */
    fun pass(): Boolean = runBlocking {
        note("pass start")
        SharingPass(host, sync).run(stayOnline = true).also { note("pass end $it") }
    }

    fun <T> blocking(block: suspend MarmotHost.() -> T): T = runBlocking { host.block() }

    fun harness(op: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonElement = host.harness(op, fields)

    override fun close() {
        host.close()
        driver.close()
        key.fill(0)
        MarmotTestNative.destroyIdentity(identityHandle)
        if (storage == null) directory.deleteRecursively()
    }
}
