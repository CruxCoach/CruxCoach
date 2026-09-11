package com.cruxcoach.android.sharing

import android.content.Context
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.domain.sharing.DeviceCapability
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

object MarmotRelayDefaults {
    val urls = listOf(
        "wss://relay.primal.net", "wss://relay.damus.io", "wss://nostr-pub.wellorder.net",
        "wss://nos.lol", "wss://nostr.oxtr.dev", "wss://blossom.cruxcoach.org/nostr",
    )

    internal fun validate(relays: List<String>) {
        require(relays.size in 1..16 && relays.distinct().size == relays.size)
        relays.forEach { raw ->
            val uri = java.net.URI(raw)
            require(raw.length <= 512 && uri.scheme == "wss" && uri.host != null && uri.userInfo == null && uri.fragment == null && uri.query == null)
        }
    }

    internal fun fromStored(raw: String?): List<String> {
        if (raw == null) return urls // Only a never-configured account gets defaults.
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.map {
                require(it.jsonPrimitive.isString)
                it.jsonPrimitive.content
            }.also(::validate)
        }.getOrDefault(emptyList()) // Unknown/corrupt configuration pauses transport.
    }
}

/** Account-scoped relay configuration, also used by the live port's admission
 * guard. Unknown storage types cannot silently enable a public relay pool. */
class MarmotRelayConfiguration(
    private val preferences: android.content.SharedPreferences,
    private val account: String,
) {
    fun read(): List<String> = runCatching {
        MarmotRelayDefaults.fromStored(preferences.getString("relays:$account", null))
    }.getOrDefault(emptyList())

    fun write(relays: List<String>) {
        MarmotRelayDefaults.validate(relays)
        check(preferences.edit().putString("relays:$account", buildJsonArray { relays.forEach { add(it) } }.toString()).commit())
    }
}

/** App-private native state, excluded from Android/cloud backup. The active
 * account signer supplies NIP-01/NIP-44 operations; no account key is exported. */
class AndroidMarmotFactory(
    private val context: Context,
    private val databaseKey: (String) -> ByteArray,
    private val nostrSigner: NostrSigner,
    private val repository: SecureDbSharingRepository,
    private val identity: SecureDbDeviceIdentity,
    private val eventSigner: QuartzNip01EventSigner,
    private val verifier: QuartzBip340Verifier,
    private val database: com.cruxcoach.db.secure.SecureDatabase,
) {
    private val account = nostrSigner.getPublicKeyHex()
    private val relayConfiguration = MarmotRelayConfiguration(context.getSharedPreferences("marmot-relays-v1", Context.MODE_PRIVATE), account)
    fun relayPool(): List<String> = relayConfiguration.read()

    fun configureRelays(relays: List<String>) {
        MarmotRelayDefaults.validate(relays)
        check(nostrSigner.getPublicKeyHex() == account)
        relayConfiguration.write(relays)
        port.shutdown()
    }

    fun archiveAfterWithdrawal(): Boolean {
        check(nostrSigner.getPublicKeyHex() == account && repository.canUseSnapshots(DeviceCapability.MUTATE_PERMISSIONS))
        check(repository.loadProjection().relationships.values.all { it.status.isTerminal || it.offeredCategories.isEmpty() })
        port.shutdown()
        return MarmotNative.archive(File(context.noBackupFilesDir, "marmot-v1/$account.db").absolutePath)
    }

    @Volatile private var unattended = false
    @Volatile var unattendedSignerRequired: Boolean = false
        private set

    /** Automatic work must not launch an external approval UI. Cached MLS
     * sessions can still exchange ordinary data without a new account signature. */
    fun <T> withoutInteractiveSigning(block: () -> T): T = SharingSessionCoordination.withTransport(database, port::exclusively) {
        unattendedSignerRequired = false
        unattended = true
        try { block() } finally { unattended = false }
    }
    private fun <T> providerOnly(block: () -> T): T = try { block() } catch (_: Exception) {
        unattendedSignerRequired = true
        error("open_app_for_signer")
    }

    /** One Quartz route for permission certificates, leaf-device proofs and
     * native account signing. Refusal never falls back to an interactive UI. */
    val permissionEventSigner: Nip01EventSigner = PrivateApplicationSigner(
        foreground = eventSigner,
        unattended = { unattended },
        account = account,
        currentAccount = nostrSigner::getPublicKeyHex,
        authorised = { repository.canUseSnapshots(DeviceCapability.READ) },
        provider = Nip01EventSigner { time, kind, tags, content ->
            val external = nostrSigner.signer as? com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
            if (external == null) eventSigner.sign(time, kind, tags, content)
            else providerOnly { QuartzBackgroundSigner.sign(external, time, kind, tags, content) }.let { e ->
                com.cruxcoach.domain.sharing.Nip01SignedEvent(e.id, e.pubKey, e.createdAt, e.kind, e.tags.map { it.toList() }, e.content, e.sig)
            }
        },
    )

    private val callback = MarmotAccountCallback { operation, argument ->
        runBlocking {
            check(nostrSigner.getPublicKeyHex() == account && repository.canUseSnapshots(DeviceCapability.READ))
            val activeSigner = nostrSigner.signer
            val external = if (unattended) activeSigner as? com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal else null
            val request = Json.parseToJsonElement(argument).jsonObject
            val result = when (operation) {
                "sign" -> {
                    require(request["pubkey"]!!.jsonPrimitive.content == account)
                    val event = permissionEventSigner.sign(request["created_at"]!!.jsonPrimitive.long,
                        request["kind"]!!.jsonPrimitive.int,
                        request["tags"]!!.jsonArray.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } },
                        request["content"]!!.jsonPrimitive.content) ?: error("account_signer_refused")
                    Json.encodeToString(event.toNativeEvent())
                }
                "nip44_encrypt" -> if (external != null) providerOnly { QuartzBackgroundSigner.encrypt(external, request["content"]!!.jsonPrimitive.content, request["public"]!!.jsonPrimitive.content) }
                    else activeSigner.nip44Encrypt(request["content"]!!.jsonPrimitive.content, request["public"]!!.jsonPrimitive.content)
                "nip44_decrypt" -> if (external != null) providerOnly { QuartzBackgroundSigner.decrypt(external, request["content"]!!.jsonPrimitive.content, request["public"]!!.jsonPrimitive.content) }
                    else activeSigner.nip44Decrypt(request["content"]!!.jsonPrimitive.content, request["public"]!!.jsonPrimitive.content)
                else -> error("unsupported_native_signer_operation")
            }
            check(nostrSigner.getPublicKeyHex() == account && repository.canUseSnapshots(DeviceCapability.READ))
            result
        }
    }

    val port = LiveMarmotSnapshotPort(
        account = account, device = { identity.loadOrCreate() }, currentAccount = nostrSigner::getPublicKeyHex,
        authorised = { relayPool().isNotEmpty() && repository.canUseSnapshots(DeviceCapability.READ) && !repository.administrativeWritesLocked() },
        nativeLoaded = { MarmotNative.loaded },
        open = {
            val directory = File(context.noBackupFilesDir, "marmot-v1").also { check(it.isDirectory || it.mkdirs()) }
            val key = databaseKey(account)
            try {
                val config = buildJsonObject {
                    put("account", account); put("local_test", false)
                    put("relays", buildJsonArray { relayPool().forEach { add(it) } })
                }
                MarmotNative.open(File(directory, "$account.db").absolutePath, key, config.toString(), callback)
            } finally { key.fill(0) }
        },
        invoke = MarmotNative::call, close = MarmotNative::close,
        accountSign = { time, kind, tags, content -> runBlocking { permissionEventSigner.sign(time, kind, tags, content) } },
        deviceSign = { digest -> identity.crypto()?.sign(digest) }, verify = verifier,
        now = { repository.permissionClock?.now() ?: System.currentTimeMillis() },
    )
}
