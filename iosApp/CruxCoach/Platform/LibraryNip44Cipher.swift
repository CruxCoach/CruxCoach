import CruxCoachCore
import Foundation
import NostrSDK

/// NIP-44 v2 for the shipped app, from rust-nostr's Swift bindings.
///
/// The Kotlin core deliberately does not do this itself: a hand-written
/// ChaCha20 is the kind of code that passes its own tests and still gets a
/// detail wrong that only shows up as an unreadable message on someone else's
/// client. The Kotlin implementation survives as the reference the JVM tests
/// exercise and cross-check against Quartz; what runs on the phone is this.
///
/// Failures come back as nil rather than as a thrown error, because every
/// caller treats "could not decrypt" as data it must not use — there is no
/// partially decrypted result to hand on.
final class LibraryNip44Cipher: NSObject, Nip44Cipher {
    func encrypt(secretKey: KotlinByteArray, peerPublicKeyHex: String, plaintext: String) -> String? {
        guard let (secret, peer) = keys(secretKey, peerPublicKeyHex) else { return nil }
        return try? nip44Encrypt(secretKey: secret, publicKey: peer, content: plaintext, version: .v2)
    }

    func decrypt(secretKey: KotlinByteArray, peerPublicKeyHex: String, payload: String) -> String? {
        guard let (secret, peer) = keys(secretKey, peerPublicKeyHex) else { return nil }
        return try? nip44Decrypt(secretKey: secret, publicKey: peer, payload: payload)
    }

    /// Parses both keys, or nothing. The secret's bytes are copied into the
    /// library's own object; the intermediate `Data` is zeroed before it is
    /// released so a private key does not linger in a freed buffer.
    private func keys(_ secretKey: KotlinByteArray, _ peerPublicKeyHex: String)
        -> (SecretKey, PublicKey)? {
        var bytes = secretKey.toData()
        defer { bytes.resetBytes(in: 0..<bytes.count) }
        guard let secret = try? SecretKey.fromBytes(bytes: bytes),
              let peer = try? PublicKey.parse(publicKey: peerPublicKeyHex) else { return nil }
        return (secret, peer)
    }
}
