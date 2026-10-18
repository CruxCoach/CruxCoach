import CruxCoachCore
import SwiftUI

/// The training settings' entry for the experimental force gauge (Android
/// `ForceGaugeSettingsCard`): the switch and, when on, the way to the measuring screen.
/// A `Section`, for a `Form` inside a `NavigationStack`.
struct ForceGaugeSettingsCard: View {
    let core: AppCore

    @State private var host: ScreenHost<ForceGaugeSettingsModel, ForceGaugeSettingsState>?

    var body: some View {
        let enabled: Bool = host?.state.enabled ?? false
        Section {
            Toggle(isOn: Binding(get: { enabled }, set: { host?.model.setEnabled(on: $0) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("trd_force_card_title"))
                    Text(L("trd_force_card_summary")).font(.caption).foregroundStyle(.secondary)
                }
            }
            .disabled(host == nil)
            .accessibilityIdentifier("force_gauge_switch")
            .task {
                guard host == nil, let model = core.makeForceGaugeSettings() else { return }
                host = ScreenHost(model: model, initial: model.currentState,
                                  subscribe: { model, onState in model.watch(onState: onState) },
                                  onClose: { model in model.close() })
            }
            if enabled {
                NavigationLink(L("trd_force_card_open")) { ForceGaugeView(core: core) }
                    .accessibilityIdentifier("force_gauge_open")
            }
        }
    }
}
