# ============================================================================
# CruxCoach ProGuard / R8 Rules
# ============================================================================
#
# Each rule is annotated with WHY it is needed. Rules are grouped by area.
# Last updated: 2026-03-19 — BLE release-build fix (Android 9 callback crash)

# ---------- General: crash report readability ----------
# Keep source file names and line numbers so crash reports are readable.
# renamesourcefileattribute strips the real file name to save space — the
# mapping file restores it during deobfuscation.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep annotations and inner-class metadata — needed by kotlinx-serialization,
# Hilt code generation, and sealed-class instanceof checks.
-keepattributes *Annotation*, InnerClasses, Signature

# ============================================================================
# 1. BLE CALLBACKS — ROOT CAUSE OF ANDROID 9 CRASH
# ============================================================================
# Android's Bluetooth stack dispatches GATT/scan/advertise callbacks by
# reflection on method names (onConnectionStateChange, onScanResult, etc.).
# R8 renames these methods, so the BLE stack silently fails to deliver
# callbacks — or worse, the JVM throws NoSuchMethodError.
#
# We must keep ALL overridden methods on every class/anonymous class that
# extends a BLE callback base class.

# --- BluetoothGattCallback (GATT client) ---
# Used by: AuroraBleConnection.gattCallback, SessionGattClient.gattCallback
# Methods: onConnectionStateChange, onServicesDiscovered, onCharacteristicWrite,
#          onCharacteristicRead, onCharacteristicChanged, onMtuChanged,
#          onDescriptorWrite
-keep class * extends android.bluetooth.BluetoothGattCallback {
    <methods>;
}

# --- BluetoothGattServerCallback (GATT server) ---
# Used by: SessionGattServer.gattCallback
# Methods: onConnectionStateChange, onCharacteristicReadRequest,
#          onCharacteristicWriteRequest, onDescriptorWriteRequest
-keep class * extends android.bluetooth.BluetoothGattServerCallback {
    <methods>;
}

# --- ScanCallback (BLE scanning) ---
# Used by: AuroraBleScanner.scanCallback, NearbyClimbScanner.scanCallback
# Methods: onScanResult, onScanFailed, onBatchScanResults
-keep class * extends android.bluetooth.le.ScanCallback {
    <methods>;
}

# --- AdvertisingSetCallback (BLE advertising, API 26+) ---
# Used by: ClimbBleAdvertiser.advertisingSetCallback, .sessionSetCallback
# Methods: onAdvertisingSetStarted, onAdvertisingDataSet,
#          onAdvertisingSetStopped, onAdvertisingEnabled
-keep class * extends android.bluetooth.le.AdvertisingSetCallback {
    <methods>;
}

# --- AdvertiseCallback (legacy BLE advertising) ---
# Not currently used but included for safety if legacy fallback is ever added.
-keep class * extends android.bluetooth.le.AdvertiseCallback {
    <methods>;
}

# --- BluetoothGatt.refresh() hidden API ---
# AuroraBleConnection uses reflection: BluetoothGatt::class.java.getMethod("refresh")
# Keep the Android framework class so getMethod() doesn't fail. R8 cannot strip
# framework methods, but this prevents any aggressive optimization that might
# inline the reflection path away.
-keepclassmembers class android.bluetooth.BluetoothGatt {
    boolean refresh();
}

# ============================================================================
# 2. ANDROID FRAMEWORK CALLBACKS (dispatched by name)
# ============================================================================

# --- BroadcastReceiver ---
# Anonymous BroadcastReceivers in AuroraBleScanner, NearbyClimbScanner,
# SessionGattBridge, and BoardBrowserScreen. The framework dispatches
# onReceive() by name.
-keep class * extends android.content.BroadcastReceiver {
    void onReceive(android.content.Context, android.content.Intent);
}

# --- WifiManager.LocalOnlyHotspotCallback ---
# Used in WifiDirectHotspot.kt as anonymous inner class.
# Android dispatches onStarted/onStopped/onFailed by name.
-keep class * extends android.net.wifi.WifiManager$LocalOnlyHotspotCallback {
    <methods>;
}

# --- WifiP2pManager.ActionListener ---
# Used in WifiDirectHotspot.kt for createGroup/removeGroup results.
-keep class * extends android.net.wifi.p2p.WifiP2pManager$ActionListener {
    <methods>;
}

# ============================================================================
# 3. SEALED CLASSES AND SUBCLASSES
# ============================================================================
# Sealed class subclasses are matched in `when` expressions. R8 can remove
# subclasses it considers unreachable (especially data object singletons),
# causing ClassNotFoundException at runtime.
#
# All sealed hierarchies in the BLE and data packages must be kept intact.

# NearbyPayload: BLE scan result decoding (6 subtypes including data objects)
-keep class com.cruxcoach.android.ble.NearbyPayload { *; }
-keep class * extends com.cruxcoach.android.ble.NearbyPayload { *; }

# SessionCommand: GATT command decoding (8 subtypes)
-keep class com.cruxcoach.android.ble.SessionCommand { *; }
-keep class * extends com.cruxcoach.android.ble.SessionCommand { *; }

# SessionEvent: GATT event decoding (6 subtypes)
-keep class com.cruxcoach.android.ble.SessionEvent { *; }
-keep class * extends com.cruxcoach.android.ble.SessionEvent { *; }

# GattConnectionEvent: connection state tracking (2 subtypes)
-keep class com.cruxcoach.android.ble.GattConnectionEvent { *; }
-keep class * extends com.cruxcoach.android.ble.GattConnectionEvent { *; }

# ImportStep: board sync progress UI (10 subtypes)
-keep class com.cruxcoach.android.data.BoardDatabaseImporter$ImportStep { *; }
-keep class * extends com.cruxcoach.android.data.BoardDatabaseImporter$ImportStep { *; }

# UpdateCheck: APK version check (3 subtypes)
-keep class com.cruxcoach.android.data.ApkDownloader$UpdateCheck { *; }
-keep class * extends com.cruxcoach.android.data.ApkDownloader$UpdateCheck { *; }

# ============================================================================
# 4. ENUMS
# ============================================================================
# Keep valueOf() and values() so enums survive R8 optimization. This is needed
# for when-expression dispatch, DataStore serialization, and toString() mapping.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============================================================================
# 5. KOTLINX-SERIALIZATION
# ============================================================================
# The serialization compiler plugin generates $$serializer companion classes.
# R8 must not strip them or their Companion objects.
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# App-level @Serializable classes (shared + android modules)
-keep,includedescriptorclasses class com.cruxcoach.**$$serializer { *; }
-keepclassmembers class com.cruxcoach.** {
    *** Companion;
}
-keepclasseswithmembers class com.cruxcoach.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ============================================================================
# 6. SQLDELIGHT
# ============================================================================
# SQLDelight's generated driver and adapter classes use reflection minimally.
# Keep the driver implementation and the generated database classes.
-keep class app.cash.sqldelight.driver.android.** { *; }
-keep class com.cruxcoach.db.** { *; }

# ============================================================================
# 6a. SQLCIPHER — native JNI bindings
# ============================================================================
-keep,includedescriptorclasses class net.zetetic.database.** { *; }
-keep,includedescriptorclasses interface net.zetetic.database.** { *; }

# ============================================================================
# 7. HILT / DAGGER
# ============================================================================
# The Hilt Gradle plugin ships consumer proguard rules that cover most cases.
# These additional rules protect generated components on edge cases.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager { *; }

# ============================================================================
# 8. WORKMANAGER + HILT WORKERS
# ============================================================================
# @HiltWorker classes are instantiated by HiltWorkerFactory using reflection
# on the class name. R8 must not rename or remove them.
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# WorkManager's SystemForegroundService is declared in AndroidManifest.
-keep class androidx.work.impl.foreground.SystemForegroundService { *; }

# ============================================================================
# 9. JETPACK COMPOSE
# ============================================================================
# The Compose compiler plugin handles keeping composable functions.
# We suppress warnings for internal Compose classes that R8 can't resolve
# (e.g., debug-only tooling classes stripped in release).
-dontwarn androidx.compose.**

# ============================================================================
# 10. DATASTORE PREFERENCES
# ============================================================================
# DataStore uses protobuf-lite internally. Keep generated message classes.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# ============================================================================
# 11. ZXING (QR code generation)
# ============================================================================
# ZXing uses reflection to load encoding/decoding modules. Keep the core.
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ============================================================================
# 12. COROUTINES
# ============================================================================
# Suppress warnings for coroutine internals that reference optional dependencies
# (e.g., kotlinx-atomicfu, kotlinx-lincheck). The core classes are kept by
# the consumer proguard rules shipped with kotlinx-coroutines-android.
-dontwarn kotlinx.coroutines.**

# ============================================================================
# 13. KOTLINX-DATETIME
# ============================================================================
# kotlinx-datetime uses Java time classes via expect/actual. Suppress warnings
# for missing platform classes on older Android versions.
-dontwarn kotlinx.datetime.**

# ============================================================================
# 14. KTOR (shared module dependency)
# ============================================================================
# Ktor ships consumer rules, but suppress warnings for optional transitive
# dependencies (SLF4J, etc.) that may not be present.
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# ============================================================================
# 15. APPLICATION CLASS
# ============================================================================
# CruxCoachApp implements Configuration.Provider (WorkManager). The framework
# looks up workManagerConfiguration by name.
-keep class com.cruxcoach.android.CruxCoachApp { *; }

# ============================================================================
# 16. ENTIRE BLE PACKAGE — NUCLEAR KEEP
# ============================================================================
# On Android 9, R8 class merging and method inlining cause silent BLE failures:
# anonymous ScanCallback/GattCallback inner classes get merged into their parent,
# breaking the framework's reflection-based callback dispatch.
#
# Keep EVERY class in the BLE package and all inner/anonymous classes.
# This is broad but BLE is too sensitive to R8 optimization.
-keep class com.cruxcoach.android.ble.** { *; }
-keep class com.cruxcoach.android.ble.**$* { *; }

# ============================================================================
# 17. QUARTZ (Nostr NIP-17 crash reporting) — TARGETED KEEPS
# ============================================================================
# Only keep the specific quartz classes CruxCoach uses. R8 traces the rest.
# Jackson is excluded via Gradle; suppress its warnings.

# Core event model
-keep class com.vitorpamplona.quartz.nip01Core.core.Event { *; }
-keep class com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder { *; }
-keep class com.vitorpamplona.quartz.nip01Core.core.** extends com.vitorpamplona.quartz.nip01Core.core.Event { *; }

# Crypto (KeyPair, signing, verification)
-keep class com.vitorpamplona.quartz.nip01Core.crypto.** { *; }
-keep class com.vitorpamplona.quartz.nip01Core.signers.** { *; }

# Tags (PTag for recipient addressing)
-keep class com.vitorpamplona.quartz.nip01Core.tags.people.PTag { *; }

# NIP-17 gift wraps (encrypted DMs)
-keep class com.vitorpamplona.quartz.nip17Dm.** { *; }

# NIP-59 gift wrap envelope
-keep class com.vitorpamplona.quartz.nip59Giftwrap.** { *; }

# NIP-44 encryption (used internally by NIP-17)
-keep class com.vitorpamplona.quartz.nip44Encryption.** { *; }

# Suppress warnings for unused quartz NIPs and excluded Jackson
-dontwarn com.vitorpamplona.quartz.**
-dontwarn com.fasterxml.jackson.**

# secp256k1-kmp native crypto (Schnorr signing, ECDH)
-keep class fr.acinq.secp256k1.** { *; }
-dontwarn fr.acinq.secp256k1.**

# Lazysodium + JNA (ChaCha20 for NIP-44, loaded via JNI)
-keep class com.goterl.lazysodium.SodiumAndroid { *; }
-keep class com.goterl.lazysodium.interfaces.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.goterl.lazysodium.**
-dontwarn com.sun.jna.**

# Transitive warnings
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**
# Session data classes used across GATT boundaries
-keep class com.cruxcoach.android.data.SessionRole { *; }
-keep class com.cruxcoach.android.data.SessionParticipant { *; }
-keep class com.cruxcoach.android.data.SessionQueueState { *; }

# ============================================================================
# 18. ZSTD NATIVE (Blossom board DB decompression)
# ============================================================================
# JNI wrapper for zstd decompression. Keep the native method declarations.
-keep class com.cruxcoach.android.util.ZstdNative { *; }

# ============================================================================
# 19. APPCOMPAT PER-APP LANGUAGE
# ============================================================================
# AppCompat Per-App Language persistence service (declared enabled=false in manifest)
-keep class androidx.appcompat.app.AppLocalesMetadataHolderService { *; }

# ============================================================================
# 20. STRIP DEBUG/VERBOSE LOGS IN RELEASE
# ============================================================================
# Backstop for any Log.d / Log.v that escaped a BuildConfig.DEBUG guard. Even
# when the call site uses an `if (BuildConfig.DEBUG)`, R8 may keep the String
# concatenations (regex .replace, payload dumps) alive because of perceived
# side-effects. Treating these methods as no-ops lets R8 dead-code the entire
# diagnostic argument list. Errors and warnings stay live so crash reports
# still carry actionable signal.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
