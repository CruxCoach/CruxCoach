import CruxCoachCore
import XCTest
@testable import CruxCoach

/// Runs on the simulator in CI. Proves the security-relevant platform pieces with real libraries.
final class StorageTests: XCTestCase {
    private func randomKey() -> Data { Data((0..<32).map { _ in UInt8.random(in: 0...255) }) }

    func testSecureDatabaseIsEncryptedWithSQLCipher() throws {
        let name = "test_secure_\(UUID().uuidString).db"
        let result = IosDatabases.shared.open(secureDbKey: randomKey().toKotlinByteArray(), secureDbName: name)
        XCTAssertNil(result.failure, result.detail ?? "")
        XCTAssertFalse((result.cipherVersion ?? "").isEmpty, "SQLCipher must be the linked SQLite")
        XCTAssertNotNil(result.secure)

        // The file on disk must not be a plaintext SQLite database.
        let support = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: false)
        let file = support.appendingPathComponent("databases").appendingPathComponent(name)
        let header = try FileHandle(forReadingFrom: file).read(upToCount: 16) ?? Data()
        XCTAssertEqual(header.count, 16)
        XCTAssertNotEqual(String(data: header.prefix(15), encoding: .ascii), "SQLite format 3")
    }

    func testWrongKeySizeIsRefused() {
        let result = IosDatabases.shared.open(secureDbKey: Data([1, 2, 3]).toKotlinByteArray(), secureDbName: "x.db")
        XCTAssertEqual(result.failure, DatabaseFailure.badKey)
    }

    func testAesGcmMatchesAndroidLayoutAndRejectsTampering() throws {
        let aead = CryptoKitAead()
        let key = randomKey().toKotlinByteArray(), nonce = Data(count: 12).toKotlinByteArray()
        let plain = Data("CruxCoach backup".utf8)
        let sealed = try XCTUnwrap(aead.aesGcmSeal(key: key, nonce: nonce, plaintext: plain.toKotlinByteArray()))
        XCTAssertEqual(Int(sealed.size), plain.count + 16)
        XCTAssertEqual(aead.aesGcmOpen(key: key, nonce: nonce, ciphertextAndTag: sealed)?.toData(), plain)
        var tampered = sealed.toData(); tampered[0] ^= 1
        XCTAssertNil(aead.aesGcmOpen(key: key, nonce: nonce, ciphertextAndTag: tampered.toKotlinByteArray()))
    }

    func testKeychainRoundTrip() {
        let store = KeychainSecretStore(service: "org.cruxcoach.ios.tests.\(UUID().uuidString)")
        XCTAssertNil(store.read(name: "k"))
        XCTAssertTrue(store.write(name: "k", value: Data([9, 8, 7]).toKotlinByteArray()))
        XCTAssertTrue(store.write(name: "k", value: Data([1, 2]).toKotlinByteArray()))
        XCTAssertEqual(store.read(name: "k")?.toData(), Data([1, 2]))
        XCTAssertTrue(store.delete(name: "k"))
        XCTAssertNil(store.read(name: "k"))
    }

    func testZstdRejectsGarbageAndHonoursOutputCap() throws {
        let dir = FileManager.default.temporaryDirectory
        let src = dir.appendingPathComponent(UUID().uuidString), dst = dir.appendingPathComponent(UUID().uuidString)
        try Data("not zstd".utf8).write(to: src)
        XCTAssertEqual(ZstdFileDecompressor().decompressFile(sourcePath: src.path, destinationPath: dst.path, maxOutputBytes: 1 << 20), -1)
        XCTAssertFalse(FileManager.default.fileExists(atPath: dst.path))
    }
}
