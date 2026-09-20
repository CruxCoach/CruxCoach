import CruxCoachCore
import NostrSDK
import XCTest
@testable import CruxCoach

/// The official NIP-44 vectors, run against the implementation that actually
/// ships on the phone.
///
/// The Kotlin suite already runs these vectors on the JVM, but since the app
/// takes its NIP-44 from a library through `Nip44Cipher`, the JVM run no
/// longer proves anything about the binary a climber installs. This does.
final class Nip44VectorTests: XCTestCase {
    private let cipher = LibraryNip44Cipher()

    private struct Vectors: Decodable {
        struct V2: Decodable {
            let valid: Valid
            let invalid: Invalid
        }
        struct Valid: Decodable {
            let encrypt_decrypt: [Case]
        }
        struct Invalid: Decodable {
            let decrypt: [BadCase]
        }
        struct Case: Decodable {
            let sec1: String
            let sec2: String
            let plaintext: String
            let payload: String
        }
        struct BadCase: Decodable {
            let conversation_key: String
            let payload: String
            let note: String
        }
        let v2: V2
    }

    private func load() throws -> Vectors {
        let url = try XCTUnwrap(
            Bundle(for: Nip44VectorTests.self).url(forResource: "nip44.vectors", withExtension: "json"),
            "the vector file is not in the test bundle"
        )
        return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: url))
    }

    /// Each vector's payload was produced by a third party; decrypting it is
    /// the only check that says anything about interoperability.
    func testDecryptsEveryPublishedPayload() throws {
        let vectors = try load()
        XCTAssertFalse(vectors.v2.valid.encrypt_decrypt.isEmpty)
        for (index, item) in vectors.v2.valid.encrypt_decrypt.enumerated() {
            let secret = try XCTUnwrap(Data(hex: item.sec1))
            let peer = try publicKeyHex(ofSecretHex: item.sec2)
            let decrypted = cipher.decrypt(
                secretKey: secret.toKotlinByteArray(), peerPublicKeyHex: peer, payload: item.payload
            )
            XCTAssertEqual(decrypted, item.plaintext, "vector \(index) did not decrypt")
        }
    }

    func testRoundTripsInBothDirectionsOfAConversation() throws {
        let vectors = try load()
        for (index, item) in vectors.v2.valid.encrypt_decrypt.enumerated() {
            let secret1 = try XCTUnwrap(Data(hex: item.sec1))
            let secret2 = try XCTUnwrap(Data(hex: item.sec2))
            let pub1 = try publicKeyHex(ofSecretHex: item.sec1)
            let pub2 = try publicKeyHex(ofSecretHex: item.sec2)

            let sent = try XCTUnwrap(
                cipher.encrypt(secretKey: secret1.toKotlinByteArray(),
                               peerPublicKeyHex: pub2, plaintext: item.plaintext),
                "vector \(index) failed to encrypt"
            )
            // The reply direction is what a conversation actually needs.
            XCTAssertEqual(
                cipher.decrypt(secretKey: secret2.toKotlinByteArray(),
                               peerPublicKeyHex: pub1, payload: sent),
                item.plaintext,
                "vector \(index) did not round-trip"
            )
        }
    }

    /// A payload that fails its MAC, or is malformed, must come back nil —
    /// never as bytes a caller might go on to trust.
    func testRejectsEveryInvalidPayload() throws {
        let vectors = try load()
        XCTAssertFalse(vectors.v2.invalid.decrypt.isEmpty)
        let secret = try XCTUnwrap(Data(hex: String(repeating: "11", count: 32)))
        let peer = try publicKeyHex(ofSecretHex: String(repeating: "22", count: 32))
        for item in vectors.v2.invalid.decrypt {
            XCTAssertNil(
                cipher.decrypt(secretKey: secret.toKotlinByteArray(),
                               peerPublicKeyHex: peer, payload: item.payload),
                "accepted an invalid payload: \(item.note)"
            )
        }
    }

    private func publicKeyHex(ofSecretHex hex: String) throws -> String {
        let secret = try SecretKey.parse(secretKey: hex)
        return Keys(secretKey: secret).publicKey().toHex()
    }
}

private extension Data {
    init?(hex: String) {
        guard hex.count % 2 == 0 else { return nil }
        var data = Data(capacity: hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else { return nil }
            data.append(byte)
            index = next
        }
        self = data
    }
}
