import CruxCoachCore
import Observation

/// Composition root: hands the four Swift-implemented platform services to the Kotlin core.
@Observable
final class AppEnvironment {
    enum Phase {
        case starting
        case ready(AppCore)
        case failed(code: String, detail: String)
    }

    private(set) var phase: Phase = .starting

    func start() {
        guard case .starting = phase else { return }
        let result = AppCore.companion.start(
            aead: CryptoKitAead(),
            secrets: KeychainSecretStore(),
            zstd: ZstdFileDecompressor(),
            deviceAuth: BiometricAuthenticator()
        )
        if let core = result.core {
            core.startConnectivity()
            phase = .ready(core)
        } else {
            phase = .failed(code: result.failureCode, detail: result.detail)
        }
    }
}
