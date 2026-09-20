import CruxCoachCore
import Observation

/// Owns a Kotlin screen model and mirrors its state into SwiftUI observation.
///
/// SwiftUI re-runs a view's `init` on every render pass, so screen models are
/// created inside `.task` (once per view identity) and handed here — never in
/// `init`, which would leak a Kotlin presenter with its own coroutine scope on
/// every pass.
///
/// The Kotlin models publish on the main thread.
@Observable
final class ScreenHost<Model: AnyObject, State> {
    let model: Model
    private(set) var state: State
    @ObservationIgnored private var subscription: Subscription?
    @ObservationIgnored private let onClose: ((Model) -> Void)?
    @ObservationIgnored private var closed = false

    init(model: Model,
         initial: State,
         subscribe: (Model, @escaping (State) -> Void) -> Subscription,
         onClose: ((Model) -> Void)? = nil) {
        self.model = model
        self.state = initial
        self.onClose = onClose
        subscription = subscribe(model) { [weak self] next in self?.state = next }
    }

    func close() {
        guard !closed else { return }
        closed = true
        subscription?.cancel()
        subscription = nil
        onClose?(model)
    }

    deinit { close() }
}
