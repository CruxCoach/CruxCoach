import CruxCoachCore
import SwiftUI

/// Every CruxCoach community setter the local catalogue knows, most problems
/// first. The counts are scoped to the active board, so they match the
/// setter's own page.
struct SettersView: View {
    let core: AppCore
    @State private var host: ScreenHost<SettersScreenModel, SettersScreenState>?

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("setters_list_title"))
        .task {
            guard host == nil else { return }
            let model = core.makeSettersScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.refresh()
        }
    }

    @ViewBuilder
    private func screen(model: SettersScreenModel, ui: SettersScreenState) -> some View {
        List {
            if ui.isLoading && ui.setters.isEmpty {
                ProgressView().frame(maxWidth: .infinity)
            } else if ui.failed {
                VStack(alignment: .leading, spacing: 8) {
                    Text(L("setters_list_load_failed"))
                    Button(L("action_retry")) { model.refresh() }
                }
            } else if ui.setters.isEmpty {
                Text(L("setters_list_empty")).foregroundStyle(.secondary)
            }
            ForEach(ui.setters, id: \.pubkey) { setter in
                NavigationLink {
                    SetterDetailView(core: core, pubkey: setter.pubkey, fallbackName: setter.displayName)
                } label: {
                    HStack {
                        Text(setter.displayName).lineLimit(1)
                        Spacer()
                        Text(L("setters_list_count", Int(setter.climbCount)))
                            .font(.footnote.monospacedDigit()).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .refreshable { model.refresh() }
    }
}

/// One setter: their profile header and their problems on this board.
struct SetterDetailView: View {
    let core: AppCore
    let pubkey: String
    let fallbackName: String
    @State private var host: ScreenHost<SetterDetailScreenModel, SetterDetailScreenState>?
    /// The app's one BLE screen model, so a climb opened from here can be
    /// sent to the board like one opened from the browser.
    @State private var ble: ScreenHost<BleScreenModel, BleScreenState>?

    var body: some View {
        Group {
            if let host, let ble {
                screen(model: host.model, ui: host.state, ble: ble)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(host?.state.displayName ?? fallbackName)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard host == nil else { return }
            let model = core.makeSetterDetailScreen(pubkey: pubkey)
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            let bleModel = core.bleScreen
            ble = ScreenHost(model: bleModel, initial: bleModel.currentState) { model, onState in
                model.watch(onState: onState)
            }
        }
    }

    @ViewBuilder
    private func screen(model: SetterDetailScreenModel, ui: SetterDetailScreenState,
                        ble: ScreenHost<BleScreenModel, BleScreenState>) -> some View {
        List {
            Section {
                if !ui.about.isEmpty { Text(ui.about) }
                if !ui.lightningAddress.isEmpty {
                    LabeledContent(L("nostr_profile_lightning")) {
                        Text(ui.lightningAddress).font(.caption).textSelection(.enabled)
                    }
                }
                Text(ui.pubkey).font(.caption2.monospaced()).foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
            Section(header: Text(L("setter_detail_climb_count", ui.climbs.count))) {
                if ui.isLoading && ui.climbs.isEmpty {
                    ProgressView().frame(maxWidth: .infinity)
                } else if ui.failed {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(L("setter_detail_load_failed"))
                        Button(L("action_retry")) { model.refresh() }
                    }
                } else if ui.climbs.isEmpty {
                    Text(L("setter_detail_no_climbs")).foregroundStyle(.secondary)
                }
                ForEach(ui.climbs, id: \.uuid) { climb in
                    NavigationLink {
                        ClimbDetailView(core: core, uuid: climb.uuid,
                                        angle: Int32(climb.angle), ble: ble) { model.refresh() }
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(climb.name).lineLimit(1)
                            Text(LI("setter_climb_meta", Int(climb.angle), climb.grade,
                                    climb.quality.isEmpty ? "—" : climb.quality, Int(climb.sends)))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .refreshable { model.refresh() }
    }
}
