import CruxCoachCore
import Observation

/// Bridges a Kotlin `StateFlow` into SwiftUI observation. Values arrive on the main thread.
@Observable
final class Observed<State: AnyObject> {
    private(set) var value: State
    @ObservationIgnored private var subscription: Cancellable?

    init(_ flow: Kotlinx_coroutines_coreStateFlow, initial: State) {
        value = initial
        subscription = FlowWatchKt.watch(flow) { [weak self] next in
            if let next = next as? State { self?.value = next }
        }
    }

    deinit { subscription?.cancel() }
}
