package com.cruxcoach.android.nostr

import kotlinx.coroutines.flow.StateFlow

/**
 * Quartz-free identity facade over [NostrSigner].
 *
 * WHY an interface: Quartz is compiled for Java 21 while JVM unit tests run
 * on Java 17 — classes whose declared API references Quartz types (like
 * [NostrSigner], whose `keyPair` / `signer` members do) can neither be
 * loaded nor instrumented by mockk on the test JVM. Consumers that only
 * need the identity surface (current pubkey + key-rotation signal) depend
 * on this interface so they stay plain-JVM-testable with simple fakes.
 *
 * Bound to [NostrSigner] in AppModule.
 */
interface NostrIdentity {
    /**
     * Incremented on every key switch (local ↔ Amber, key import).
     * Observers (e.g. relay subscriptions) collect this to detect identity
     * changes and restart.
     */
    val keyVersion: StateFlow<Long>

    /** Hex public key of the active identity. */
    fun getPublicKeyHex(): String
}
