package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom

internal object MarmotTestNative {
    init { System.loadLibrary("cruxcoach_marmot") }
    @JvmStatic external fun failCruxcoach(port: Int)
    @JvmStatic external fun createIdentity(): String
    @JvmStatic external fun restartIdentity(path: String, databaseKey: ByteArray): String
    @JvmStatic external fun wrappingKey(identity: Long, alias: String, operation: Int): ByteArray?
    @JvmStatic external fun destroyIdentity(identity: Long)
    @JvmStatic external fun signEvent(identity: Long, device: Boolean, unsigned: String): String
    @JvmStatic external fun signDigest(identity: Long, device: Boolean, hash: ByteArray): ByteArray
    @JvmStatic external fun verify(signature: ByteArray, hash: ByteArray, public: ByteArray): Boolean
    @JvmStatic external fun open(identity: Long, path: String, databaseKey: ByteArray, config: String): Long
}


/** Standalone reference endpoint with an isolated synthetic account, device,
 * permission database and native MLS state. The Android peer path has no
 * dependency on this harness. Test-only JNI identities are absent from APKs. */
/** Only the local process-restart test supplies this temporary encrypted store.
 * Its random master key arrives through a pipe and is destroyed with the run. */
data class SyntheticEndpointStorage(val directory: java.io.File, val key: ByteArray)

open class SyntheticMarmotEndpoint(
    val role: SnapshotEndpointRole,
    val endpoints: List<String>,
    private val loopbackHarness: Boolean = true,
    private val wall: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    private val storage: SyntheticEndpointStorage? = null,
) : AutoCloseable {
    private val json = Json { encodeDefaults = true }
    private fun String.bytes() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private val bip340 = Bip340Verifier { signature, message, public -> MarmotTestNative.verify(signature, message, public) }
    lateinit var clock: SharingPermissionClock
        val directory = storage?.directory ?: Files.createTempDirectory("cc-native-e2e-").toFile()
        val key = storage?.key?.copyOf() ?: ByteArray(32).also(SecureRandom()::nextBytes)
        val generated = json.parseToJsonElement(if (storage == null) MarmotTestNative.createIdentity()
            else MarmotTestNative.restartIdentity(directory.resolve("identity.db").absolutePath, key)).jsonObject
        val identity = generated["handle"]!!.jsonPrimitive.long
        val account = generated["account"]!!.jsonPrimitive.content
        val device = generated["device"]!!.jsonPrimitive.content
        val vault = AeadSharingKeyVault(if (storage == null) InMemoryWrappingKeyStore() else object : WrappingKeyStore {
            override fun getOrCreate(handle: KeyHandle) = requireNotNull(MarmotTestNative.wrappingKey(identity, handle.toString(), 1))
            override fun get(handle: KeyHandle) = MarmotTestNative.wrappingKey(identity, handle.toString(), 0)
            override fun destroy(handle: KeyHandle) { MarmotTestNative.wrappingKey(identity, handle.toString(), 2)?.fill(0) }
        })
        var currentAccount = account
        var nativeHandle = 0L
        val eventSigner = Nip01EventSigner { time, kind, tags, content ->
            val unsigned = buildJsonObject {
                put("pubkey", account); put("created_at", time); put("kind", kind)
                put("tags", json.encodeToJsonElement(tags)); put("content", content)
            }
            val signed = json.decodeFromString<BindingEvent>(MarmotTestNative.signEvent(identity, false, unsigned.toString()))
            Nip01SignedEvent(signed.id, signed.pubkey, signed.created_at, signed.kind, signed.tags, signed.content, signed.sig)
        }
        fun crypto(domain: SigningDomain) = Nip55LedgerCrypto(domain, { currentAccount }, eventSigner, bip340)
        val deviceCrypto = object : LedgerCrypto {
            override fun hash(canonical: ByteArray) = MessageDigest.getInstance("SHA-256").digest(canonical)
            override fun sign(hash: ByteArray) = MarmotTestNative.signDigest(identity, true, hash)
            override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
                bip340.verify(signature, hash, signerNpub.bytes())
        }
        val signer = AsyncSharingLedgerSigner(crypto(SigningDomain.RELATIONSHIP_LEDGER))
        val policySigner = AsyncOwnerPolicySigner(crypto(SigningDomain.OWNER_POLICY_LEDGER))
        val manifestSigner = AsyncDeviceManifestSigner(crypto(SigningDomain.DEVICE_MANIFEST))
        val attestationSigner = AsyncAuthorityAttestationSigner(deviceCrypto.asAsync())
        lateinit var driver: JdbcSqliteDriver
        lateinit var database: SecureDatabase
        lateinit var repository: SecureDbSharingRepository
        lateinit var controller: SharingController
        lateinit var port: LiveMarmotSnapshotPort
        lateinit var exchange: SharingSnapshotExchange
        lateinit var policy: SharingPolicyTransport
        lateinit var continuous: ContinuousSharingExchange
        init { open(!directory.resolve("app.db").exists()) }
        fun open(create: Boolean) {
            driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("app.db").absolutePath}?foreign_keys=on")
            if (create) SecureDatabase.Schema.create(driver)
            database = SecureDatabase(driver)
            clock = SharingPermissionClock(database, account, wall, elapsed)
            repository = SecureDbSharingRepository(database, vault, signer.verifier(), policySigner.verifier(), account,
                deviceManifestVerifier = manifestSigner.verifier(), attestationVerifier = attestationSigner.verifier(),
                currentOwner = { currentAccount }, permissionClock = clock, nowEpochMillis = clock::now, localDeviceIdentity = { DeviceIdentity(AuthorityDeviceId(device), device) })
            port = LiveMarmotSnapshotPort(account, { DeviceIdentity(AuthorityDeviceId(device), device) }, { currentAccount },
                { repository.canUseSnapshots(DeviceCapability.READ) && !repository.administrativeWritesLocked() }, { MarmotNative.loaded },
                open = {
                    val config = buildJsonObject { put("account", account); put("local_test", loopbackHarness); put("relays", json.encodeToJsonElement(endpoints)) }
                    MarmotTestNative.open(identity, directory.resolve("mls.db").absolutePath, key, config.toString()).also { nativeHandle = it }
                }, invoke = MarmotNative::call, close = MarmotNative::close,
                accountSign = { time, kind, tags, text -> runBlocking { eventSigner.sign(time, kind, tags, text) } },
                deviceSign = deviceCrypto::sign, verify = bip340, now = clock::now)
            exchange = SharingSnapshotExchange(database, repository, account, role, port, nowEpochMillis = clock::now)
            policy = SharingPolicyTransport(database, repository, account, port, signer, now = clock::now)
            continuous = ContinuousSharingExchange(database, repository, account, role, port, ContinuousSourceAdapter(database), exchange::pinnedRole, clock::now, crypto(SigningDomain.FRIENDSHIP))
            controller = SharingController(repository, signer, policySigner, account,
                authorityDevice = AuthorityDeviceId(device), authorityDevicePublicKey = device,
                attestationSigner = attestationSigner, manifestSigner = manifestSigner,
                snapshotExchange = exchange, policyTransport = policy, continuous = continuous)
            if (create) check(runBlocking { controller.enrolGenesisDevice() }.isSuccess) { "synthetic_genesis_failed" }
        }
        fun privateStorageMatches(marker: String): Int {
            port.peers() // hydrate the lazy native store even immediately after restart; no network
            return json.parseToJsonElement(MarmotNative.call(nativeHandle,
            buildJsonObject { put("op", "private_storage_matches"); put("marker", marker) }.toString())).jsonObject.let {
                check(it["ok"]?.jsonPrimitive?.boolean == true); it["value"]!!.jsonPrimitive.int
            }
        }
        fun reopen() { port.shutdown(); driver.close(); open(false) }
        fun tick() { FriendshipTransportPreparation.run(port, continuous.pendingRequests()); port.refresh(); FriendshipTransportPreparation.run(port, continuous.pendingRequests()); policy.synchronize(); exchange.synchronize(refreshTransport = false); continuous.synchronize() }
        fun note() {
            val handle = SharingKeyHandles.ownerObject(ObjectId("synthetic-note"))
            val wrapped = repository.createDataKey(handle)
            repository.storeSealedItem("synthetic-note", SharingCategory.PRIVATE_NOTES, handle, wrapped, "synthetic native private note".encodeToByteArray())
        }
        override fun close() {
            port.shutdown(); driver.close(); key.fill(0)
            MarmotTestNative.destroyIdentity(identity)
            if (storage == null) directory.deleteRecursively()
        }
    }
