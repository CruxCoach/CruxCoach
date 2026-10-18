import SwiftUI
import UIKit

/// Android asks the shared-session successor question from any screen. A SwiftUI alert hosted at
/// the root cannot come up while a sheet or the training cover is open (the root controller is
/// already presenting one), so the question gets a window of its own above everything and goes
/// away with it. Used by [SharedSuccessorPrompt]; all calls arrive on the main thread from SwiftUI.
final class SuccessorAlertWindow {
    private var window: UIWindow?
    private weak var previousKey: UIWindow?

    /// The question is on screen: a repeated offer does not stack a second one.
    var showing: Bool { window != nil }

    func show(name: String, onJoin: @escaping () -> Void, onKeep: @escaping () -> Void) {
        guard window == nil, let scene = SuccessorAlertWindow.scene() else { return }
        let overlay = UIWindow(windowScene: scene)
        overlay.windowLevel = UIWindow.Level.alert + 1
        overlay.backgroundColor = .clear
        let root = UIViewController()
        root.view.backgroundColor = .clear
        overlay.rootViewController = root
        previousKey = scene.windows.first { $0.isKeyWindow }
        overlay.makeKeyAndVisible()
        window = overlay
        let alert = UIAlertController(title: L("ble_successor_join_title"),
                                      message: L("ble_successor_join_message", name),
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: L("ble_successor_join_confirm"), style: .default) { [weak self] _ in
            self?.close()
            onJoin()
        })
        // Android's dismiss without a choice keeps the queue; here that is the cancel button.
        alert.addAction(UIAlertAction(title: LI("shared_successor_decline"), style: .cancel) { [weak self] _ in
            self?.close()
            onKeep()
        })
        // Next turn of the run loop: the new window has laid out its root by then.
        DispatchQueue.main.async { [weak self] in
            guard let self, self.window === overlay else { return }
            root.present(alert, animated: true)
        }
    }

    /// The offer went away by itself (the successor is gone, or it was answered elsewhere).
    func hide() {
        guard let window else { return }
        window.rootViewController?.dismiss(animated: false)
        close()
    }

    private func close() {
        window?.isHidden = true
        window = nil
        previousKey?.makeKey()
        previousKey = nil
    }

    private static func scene() -> UIWindowScene? {
        let scenes: [UIWindowScene] = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
    }
}
