import CruxCoachCore
import Foundation
import Observation

/// Composition root: owns the Swift-implemented platform services and the Kotlin core.
@Observable
final class AppEnvironment {
    enum StorageState: Equatable {
        case checking
        case ready(cipherVersion: String)
        case failed(DatabaseFailure, String)
    }

    let aead = CryptoKitAead()
    let secrets = KeychainSecretStore()
    let zstd = ZstdFileDecompressor()
    let deviceAuth = BiometricAuthenticator()

    private(set) var storage: StorageState = .checking

    /// Verifies on this device that personal data would really be encrypted before anything is stored.
    func runStorageSelfCheck() {
        var key = Data(count: 32)
        let status = key.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        guard status == errSecSuccess else { storage = .failed(.openFailed, "SecRandomCopyBytes \(status)"); return }
        let result = IosDatabases.shared.open(secureDbKey: key.toKotlinByteArray(), secureDbName: "selfcheck.db")
        if let failure = result.failure {
            storage = .failed(failure, result.detail ?? "")
        } else {
            storage = .ready(cipherVersion: result.cipherVersion ?? "")
        }
    }
}
