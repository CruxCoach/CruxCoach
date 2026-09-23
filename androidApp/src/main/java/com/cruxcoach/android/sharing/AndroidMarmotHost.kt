package com.cruxcoach.android.sharing

import android.content.Context
import com.cruxcoach.android.nostr.NostrSigner
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android wiring for the native host: app-private storage outside every backup
 * (`noBackupFilesDir/marmot-v2`), a domain-separated database key and the
 * account signer. No account private key crosses JNI.
 */
class AndroidMarmotHost(
    private val context: Context,
    private val databaseKey: (String) -> ByteArray,
    private val nostrSigner: NostrSigner,
    private val foregroundSigner: Nip01EventSigner,
) : SharingHost {
    val account: String = nostrSigner.getPublicKeyHex()
    private val directory = File(context.noBackupFilesDir, "marmot-v2")
    private val database = File(directory, "$account.db")
    private val interactive = AtomicInteger(0)
    private val json = Json { encodeDefaults = true }

    /** Whether this account ever used sharing on this installation. */
    fun exists(): Boolean = database.isFile

    /** Run a person-initiated action: an external signer may show its dialog. */
    override suspend fun <T> interactive(block: suspend () -> T): T {
        interactive.incrementAndGet()
        try {
            return block()
        } finally {
            interactive.decrementAndGet()
        }
    }

    private val signer = PrivateApplicationSigner(
        foreground = foregroundSigner,
        unattended = { interactive.get() == 0 },
        account = account,
        currentAccount = nostrSigner::getPublicKeyHex,
        provider = Nip01EventSigner { time, kind, tags, content ->
            val external = nostrSigner.signer as? NostrSignerExternal
            if (external == null) {
                foregroundSigner.sign(time, kind, tags, content)
            } else {
                runCatching { QuartzBackgroundSigner.sign(external, time, kind, tags, content) }.getOrNull()?.let { event ->
                    SignedNostrEvent(event.id, event.pubKey, event.createdAt, event.kind, event.tags.map { it.toList() }, event.content, event.sig)
                }
            }
        },
    )

    private val callback = MarmotAccountCallback { operation, argument ->
        runBlocking {
            check(nostrSigner.getPublicKeyHex() == account) { "native_account_changed" }
            val request = Json.parseToJsonElement(argument).jsonObject
            val external = (nostrSigner.signer as? NostrSignerExternal)?.takeIf { interactive.get() == 0 }
            when (operation) {
                "sign" -> {
                    require(request["pubkey"]!!.jsonPrimitive.content == account)
                    signer.sign(
                        request["created_at"]!!.jsonPrimitive.long,
                        request["kind"]!!.jsonPrimitive.int,
                        request["tags"]!!.jsonArray.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } },
                        request["content"]!!.jsonPrimitive.content,
                    )?.let { json.encodeToString(it) }
                }
                "nip44_encrypt" -> {
                    val content = request["content"]!!.jsonPrimitive.content
                    val peer = request["public"]!!.jsonPrimitive.content
                    if (external != null) runCatching { QuartzBackgroundSigner.encrypt(external, content, peer) }.getOrNull()
                    else nostrSigner.signer.nip44Encrypt(content, peer)
                }
                "nip44_decrypt" -> {
                    val content = request["content"]!!.jsonPrimitive.content
                    val peer = request["public"]!!.jsonPrimitive.content
                    if (external != null) runCatching { QuartzBackgroundSigner.decrypt(external, content, peer) }.getOrNull()
                    else nostrSigner.signer.nip44Decrypt(content, peer)
                }
                else -> null
            }
        }
    }

    /** An explicit v1 relay choice survives; a never-configured account gets
     * the native defaults. Corrupt legacy values are ignored, never widened. */
    private fun legacyRelays(): List<String> = runCatching {
        val raw = context.getSharedPreferences(LEGACY_RELAY_PREFERENCES, Context.MODE_PRIVATE)
            .getString("relays:$account", null) ?: return emptyList()
        Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
            .takeIf { list -> list.size in 1..16 && list.all { it.startsWith("wss://") && it.length <= 512 } }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun open(): Long {
        check(nostrSigner.getPublicKeyHex() == account) { "native_account_changed" }
        check(MarmotNative.loaded) { "native_unavailable" }
        check(directory.isDirectory || directory.mkdirs())
        val key = databaseKey(account)
        try {
            val config = buildJsonObject {
                put("account", account)
                put("local_test", false)
                put("relays", buildJsonArray { legacyRelays().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            }
            val handle = MarmotNative.open(database.absolutePath, key, config.toString(), callback)
            retireVersionOne()
            return handle
        } finally {
            key.fill(0)
        }
    }

    /** v1 friendships, native journal and relay preference are not carried
     * into v2 (owner decision); their plaintext rows must not linger. */
    private fun retireVersionOne() {
        File(context.noBackupFilesDir, "marmot-v1").takeIf { it.exists() }?.deleteRecursively()
        context.getSharedPreferences(LEGACY_RELAY_PREFERENCES, Context.MODE_PRIVATE).edit().remove("relays:$account").apply()
    }

    override val host = MarmotHost(open = ::open, trace = AndroidTrace)

    private object AndroidTrace : MarmotTrace {
        override fun <T> section(name: String, block: () -> T): T {
            android.os.Trace.beginSection(name.take(127))
            try {
                return block()
            } finally {
                android.os.Trace.endSection()
            }
        }
    }

    companion object {
        const val LEGACY_RELAY_PREFERENCES = "marmot-relays-v1"
    }
}
