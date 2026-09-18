package com.cruxcoach.app.platform

/**
 * Everything the portable application core needs from the host platform.
 *
 * Implemented in Kotlin (`iosMain`) wherever Kotlin/Native can reach the Apple
 * API, because that code is compile-checked on Linux. Only [AeadCipher],
 * [SecretStore], [ZstdDecompressor] and [DeviceAuthenticator] are implemented
 * in Swift: CryptoKit and libzstd have no Objective-C surface and Keychain /
 * LocalAuthentication are far less error-prone there.
 *
 * None of these may throw across the Swift boundary; failures are return values.
 */
class PlatformServices(
    val hashing: Hashing,
    val aead: AeadCipher,
    val secrets: SecretStore,
    val keyValues: KeyValueStore,
    val files: FileSystem,
    val http: HttpTransport,
    val webSockets: WebSocketConnector,
    val zstd: ZstdDecompressor,
    val gzip: Gzip,
    val deviceAuth: DeviceAuthenticator,
    val clock: WallClock,
)

interface Hashing {
    fun sha256(data: ByteArray): ByteArray
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /** Cryptographically secure random bytes (SecRandomCopyBytes on iOS). */
    fun randomBytes(count: Int): ByteArray

    /** Streams the file; never loads it whole. Null when it cannot be read. */
    fun sha256OfFile(path: String): ByteArray?
}

/** AES-256-GCM, 12-byte nonce, 128-bit tag, no AAD: the Android backup wire format. */
interface AeadCipher {
    /** Returns `ciphertext || tag(16)`, or null on failure. */
    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray?

    /** Takes `ciphertext || tag(16)`. Null when authentication fails. */
    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray?
}

/**
 * Keychain-backed secret storage. Items are device-only
 * (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`), never synced to iCloud.
 */
interface SecretStore {
    fun read(name: String): ByteArray?

    /** False when the Keychain refused the write; callers must not continue as if it were stored. */
    fun write(name: String, value: ByteArray): Boolean
    fun delete(name: String): Boolean
}

/** Non-secret preferences (NSUserDefaults suite on iOS). Null value removes the key. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun keys(): Set<String>
}

interface FileSystem {
    /** Persistent, backed-up-excluded working directory for downloads and staging. */
    fun workDirectory(): String
    fun exists(path: String): Boolean
    fun size(path: String): Long
    fun delete(path: String): Boolean
    fun move(from: String, to: String): Boolean
    fun readBytes(path: String, maxBytes: Long): ByteArray?
    fun writeBytes(path: String, data: ByteArray): Boolean
    fun freeSpaceBytes(): Long
}

class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

sealed class HttpResult {
    class Ok(val response: HttpResponse) : HttpResult()
    class Failed(val reason: HttpFailure, val detail: String) : HttpResult()
}

enum class HttpFailure { OFFLINE, TIMEOUT, TOO_LARGE, INSECURE_URL, CANCELLED, OTHER }

interface HttpTransport {
    /** https only. The body is capped at [maxResponseBytes]; larger responses fail with TOO_LARGE. */
    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxResponseBytes: Long,
        timeoutSeconds: Int,
    ): HttpResult

    /** Streams to [destinationPath] without buffering the body in memory. Ok.body is empty. */
    suspend fun download(
        url: String,
        destinationPath: String,
        maxBytes: Long,
        timeoutSeconds: Int,
        onProgress: (receivedBytes: Long, expectedBytes: Long) -> Unit,
    ): HttpResult
}

interface WebSocketListener {
    fun onOpen()
    fun onText(text: String)
    fun onClosed(reason: String)
}

interface WebSocketHandle {
    fun send(text: String)
    fun close()
}

interface WebSocketConnector {
    /** wss only. Inbound frames above [maxFrameBytes] close the socket. */
    fun connect(url: String, maxFrameBytes: Int, listener: WebSocketListener): WebSocketHandle?
}

interface ZstdDecompressor {
    /** Returns bytes written, or -1 on corrupt input / when output would exceed [maxOutputBytes]. */
    fun decompressFile(sourcePath: String, destinationPath: String, maxOutputBytes: Long): Long
}

interface Gzip {
    fun compress(data: ByteArray): ByteArray?

    /** Null on corrupt input or when the output would exceed [maxOutputBytes]. */
    fun decompress(data: ByteArray, maxOutputBytes: Long): ByteArray?
}

/** Face ID / Touch ID / passcode gate before revealing the private key. */
interface DeviceAuthenticator {
    fun authenticate(reason: String, onResult: (Boolean) -> Unit)
}

interface WallClock {
    fun epochSeconds(): Long
    fun epochMillis(): Long
}
