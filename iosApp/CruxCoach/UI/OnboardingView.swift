import CruxCoachCore
import SwiftUI

/// Android's two-step first run: pick the board you climb on, then choose which
/// catalogues to download. Both steps are skippable, and the choices are stored
/// under the same preference keys Android uses.
struct OnboardingView: View {
    let core: AppCore
    let sync: ScreenHost<SyncScreenModel, SyncScreenState>
    let onFinished: () -> Void

    @State private var host: ScreenHost<OnboardingScreenModel, OnboardingScreenState>?
    @State private var showBoardPicker = false
    @State private var showNearbyGyms = false

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .task {
            guard host == nil else { return }
            let model = core.makeOnboardingScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
        .onChange(of: host?.state.completed ?? false) { _, done in
            if done { onFinished() }
        }
    }

    @ViewBuilder
    private func screen(model: OnboardingScreenModel, ui: OnboardingScreenState) -> some View {
        NavigationStack {
            Form {
                if ui.step == "board" {
                    Section {
                        Text(LI("onboarding_board_intro"))
                    }
                    Section(LI("onboarding_your_board")) {
                        Button {
                            showBoardPicker = true
                        } label: {
                            VStack(alignment: .leading) {
                                Text(ui.boardTitle).foregroundStyle(.primary)
                                if !ui.boardDetail.isEmpty {
                                    Text(ui.boardDetail).font(.footnote).foregroundStyle(.secondary)
                                }
                            }
                        }
                        // Android onboarding's "Find a gym near you": the quickest way to
                        // the right board when you stand in your gym.
                        Button {
                            showNearbyGyms = true
                        } label: {
                            Label(L("board_find_nearby_gym"), systemImage: "location")
                        }
                    }
                    Section {
                        ForEach(ui.brands, id: \.brandWire) { brand in
                            Toggle(brand.title, isOn: Binding(
                                get: { ui.downloadBrandWires.contains(brand.brandWire) },
                                set: { _ in model.toggleDownloadBrand(brandWire: brand.brandWire) }))
                        }
                        Button(LI("onboarding_toggle_all")) { model.toggleAllDownloadBrands() }
                    } header: {
                        Text(LI("onboarding_catalogues"))
                    } footer: {
                        Text(LI("onboarding_catalogues_hint"))
                    }
                    Section {
                        Button(LI("onboarding_continue")) {
                            model.confirmDownloads()
                            let chosen = ui.downloadBrandWires.map { $0 }
                            // Android's onboarding start: off Wi-Fi it asks; offline or declined it waits for Wi-Fi.
                            if !chosen.isEmpty { sync.model.requestStartQueued(brandWires: chosen) }
                        }
                        .disabled(ui.downloadBrandWires.isEmpty)
                    }
                } else {
                    Section { Text(LI("onboarding_data_intro")) }
                    CatalogueNetworkHints(state: sync.state)
                    Section(LI("onboarding_bring_data")) {
                        NavigationLink(LI("import_title")) { ImportView(core: core) }
                        NavigationLink(LI("kilter_title")) { KilterView(core: core) }
                        NavigationLink(LI("backup_open")) { BackupView(core: core) }
                    }
                    Section {
                        Button(LI("onboarding_finish")) { model.finish() }
                        Button(LI("onboarding_back")) { model.back() }
                    }
                }
            }
            .navigationTitle(LI("onboarding_title", Int(ui.stepNumber), Int(ui.stepCount)))
            .navigationBarTitleDisplayMode(.inline)
            .modifier(CatalogueNetworkPrompts(model: sync.model, state: sync.state))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(LI("onboarding_skip")) { model.skip() }
                }
            }
            .sheet(isPresented: $showBoardPicker) {
                NavigationStack {
                    // Android's onboarding picker offers "Find via Bluetooth" too; the picker hosts the link.
                    BoardPickerView(core: core, brandWires: sync.state.rows.map { $0.brandWire },
                                    onPick: { option in
                                        model.chooseBoard(brandWire: option.brandWire,
                                                          layoutId: option.layoutId,
                                                          productSizeId: option.productSizeId)
                                        showBoardPicker = false
                                    },
                                    offersBluetooth: true)
                }
            }
            .sheet(isPresented: $showNearbyGyms) {
                NavigationStack {
                    GymSearchView(core: core, availableBrandWires: sync.state.rows.map { $0.brandWire },
                                  startNearby: true,
                                  onPick: { option in
                                      model.chooseBoard(brandWire: option.brandWire,
                                                        layoutId: option.layoutId,
                                                        productSizeId: option.productSizeId)
                                      showNearbyGyms = false
                                  },
                                  onPickDirectly: { showNearbyGyms = false })
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(L("action_close")) { showNearbyGyms = false }
                        }
                    }
                }
            }
        }
    }
}
