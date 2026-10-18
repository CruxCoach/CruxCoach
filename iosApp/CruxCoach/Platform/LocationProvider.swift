import CoreLocation
import UIKit

/// What a "My location" / "Gyms near me" tap produced (Android `LocateResult`).
enum LocateOutcome: Equatable {
    case found(latitude: Double, longitude: Double)
    /// The user said no to the prompt this tap showed.
    case denied
    /// Access was refused earlier (or is restricted): only the Settings app can allow it now.
    case deniedSettings
    /// Location Services are off for the whole phone.
    case disabled
    /// Allowed and on, but no position arrived in time.
    case unavailable

    /// The message for a failed tap; nil for a found position.
    var failureText: String? {
        switch self {
        case .found: return nil
        case .denied: return L("locate_me_denied")
        case .deniedSettings: return L("locate_me_denied_settings")
        case .disabled: return L("locate_me_disabled")
        case .unavailable: return L("locate_me_unavailable")
        }
    }

    /// True when the app's page in Settings can fix it.
    var opensSettings: Bool { self == .deniedSettings }
}

/// One position on request, for Android's "locate me" (`LocateMe.kt`,
/// `DeviceLocation.kt`): when-in-use permission, asked only when the user taps,
/// one fix (a recent cached one counts), 15 s at most. The position is handed
/// to the caller and never stored or sent anywhere.
///
/// Create it on the tap, on the main thread: CoreLocation calls the delegate on
/// the thread that made the manager.
final class LocationProvider: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var onResult: ((LocateOutcome) -> Void)?
    private var promptShown = false
    private var timeout: DispatchWorkItem?

    /// Android `FRESH_MS`: a fix this young is good enough.
    private static let freshSeconds: TimeInterval = 120
    private static let timeoutSeconds: TimeInterval = 15

    override init() {
        super.init()
        manager.delegate = self
        // Gyms and boards are compared at city scale; precise location is not needed.
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    /// True when a tap would locate without a permission prompt.
    static var hasPermission: Bool {
        let status = CLLocationManager().authorizationStatus
        return status == .authorizedWhenInUse || status == .authorizedAlways
    }

    var isLocating: Bool { onResult != nil }

    /// The tap. Asks for permission only now, then fetches one position. A
    /// second tap while one is running is ignored.
    func locate(_ completion: @escaping (LocateOutcome) -> Void) {
        guard onResult == nil else { return }
        onResult = completion
        switch manager.authorizationStatus {
        case .notDetermined:
            promptShown = true
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            fetch()
        default:
            finishRefused(askedNow: false)
        }
    }

    private func fetch() {
        if let cached = manager.location, -cached.timestamp.timeIntervalSinceNow < Self.freshSeconds {
            finish(.found(latitude: cached.coordinate.latitude, longitude: cached.coordinate.longitude))
            return
        }
        let work = DispatchWorkItem { [weak self] in
            self?.manager.stopUpdatingLocation()
            self?.finish(.unavailable)
        }
        timeout = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.timeoutSeconds, execute: work)
        manager.requestLocation()
    }

    /// Refused, restricted, or Location Services switched off: tell them apart.
    /// `locationServicesEnabled` can block, so it is read off the main thread.
    private func finishRefused(askedNow: Bool) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let servicesOn = CLLocationManager.locationServicesEnabled()
            DispatchQueue.main.async {
                guard let self else { return }
                if !servicesOn {
                    self.finish(.disabled)
                } else {
                    self.finish(askedNow ? .denied : .deniedSettings)
                }
            }
        }
    }

    private func finish(_ outcome: LocateOutcome) {
        timeout?.cancel()
        timeout = nil
        promptShown = false
        guard let callback = onResult else { return }
        onResult = nil
        callback(outcome)
    }

    // MARK: CLLocationManagerDelegate

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        // Also called once when the manager is created; only a pending tap reacts.
        guard onResult != nil, timeout == nil else { return }
        switch manager.authorizationStatus {
        case .notDetermined:
            return
        case .authorizedWhenInUse, .authorizedAlways:
            fetch()
        default:
            finishRefused(askedNow: promptShown)
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }
        finish(.found(latitude: last.coordinate.latitude, longitude: last.coordinate.longitude))
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if let clError = error as? CLError, clError.code == .denied {
            finishRefused(askedNow: false)
        } else {
            finish(.unavailable)
        }
    }
}

/// Opens CruxCoach's page in the Settings app, where location access is allowed.
enum LocationSettings {
    static func open() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }
}
