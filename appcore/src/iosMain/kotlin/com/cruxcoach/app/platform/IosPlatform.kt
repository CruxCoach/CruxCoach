package com.cruxcoach.app.platform

/**
 * Assembles [PlatformServices] for the iOS app. Swift supplies the four
 * services Kotlin/Native cannot reach (CryptoKit AES-GCM, Keychain, libzstd,
 * LocalAuthentication); everything else is the Kotlin implementation in this package.
 *
 * [userDefaultsSuite] names the NSUserDefaults suite behind [PlatformServices.keyValues].
 * It must not be the app's main bundle identifier: NSUserDefaults rejects that name.
 */
fun createIosPlatformServices(
    aead: AeadCipher,
    secrets: SecretStore,
    zstd: ZstdDecompressor,
    deviceAuth: DeviceAuthenticator,
    userDefaultsSuite: String,
): PlatformServices = PlatformServices(
    hashing = IosHashing(),
    aead = aead,
    secrets = secrets,
    keyValues = IosKeyValueStore(userDefaultsSuite),
    files = IosFileSystem(),
    http = IosHttpTransport(),
    webSockets = IosWebSocketConnector(),
    zstd = zstd,
    gzip = IosGzip(),
    deviceAuth = deviceAuth,
    clock = IosWallClock(),
)
