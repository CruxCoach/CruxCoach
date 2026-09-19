import CruxCoachCore
import Foundation
import LocalAuthentication

/// Face ID / Touch ID with passcode fallback; the counterpart of Android's
/// BIOMETRIC_STRONG or DEVICE_CREDENTIAL gate before the private key is shown.
final class BiometricAuthenticator: DeviceAuthenticator {
    func authenticate(reason: String, onResult: @escaping (KotlinBoolean) -> Void) {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            // No passcode set: there is nothing to authenticate against, so refuse.
            DispatchQueue.main.async { onResult(KotlinBoolean(bool: false)) }
            return
        }
        context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { success, _ in
            DispatchQueue.main.async { onResult(KotlinBoolean(bool: success)) }
        }
    }
}
