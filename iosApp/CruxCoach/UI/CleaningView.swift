import CruxCoachCore
import SwiftUI

/// "Clean holds" (Android's `BoardCleaningSheet`): lights every hold sent to
/// the connected board today so they can be brushed, section by section on
/// older controllers. The collection lives in the shared board connection, so
/// closing this sheet keeps the lights on.
struct BoardCleaningSheet: View {
    let core: AppCore
    @Environment(\.dismiss) private var dismiss
    @State private var host: ScreenHost<BoardCleaningScreenModel, BoardCleaningScreenState>?
    @State private var ble: ScreenHost<BleScreenModel, BleScreenState>?
    @State private var showConnection = false

    var body: some View {
        Group {
            if let host {
                content(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("board_cleaning_title"))
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(L("action_close")) { dismiss() }
            }
        }
        .task {
            guard host == nil else { return }
            let model = core.makeBoardCleaningScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            // Re-read the day while the sheet is open: it may span midnight.
            model.startDayRefresh()
            let bleModel = core.bleScreen
            ble = ScreenHost(model: bleModel, initial: bleModel.currentState) { model, onState in
                model.watch(onState: onState)
            }
        }
        .sheet(isPresented: $showConnection) {
            if let ble {
                NavigationStack { BleView(ble: ble) }
            }
        }
    }

    @ViewBuilder
    private func content(model: BoardCleaningScreenModel, ui: BoardCleaningScreenState) -> some View {
        List {
            Section {
                if !ui.boardName.isEmpty {
                    Text(ui.boardName).font(.headline)
                }
                Text(L("board_cleaning_description"))
            }
            if !ui.connected {
                Section {
                    Text(L("board_cleaning_connect_hint"))
                    Button(L("board_cleaning_connect")) { showConnection = true }
                }
            } else if !ui.available {
                Section { Text(L("board_cleaning_unsupported")) }
            } else {
                Section {
                    Text(LP(ui.active ? "board_cleaning_remaining" : "board_cleaning_count", Int(ui.holdCount)))
                        .font(.headline)
                    if ui.holdCount == 0 {
                        Text(L("board_cleaning_empty")).foregroundStyle(.secondary)
                    }
                    if ui.sectioned {
                        Text(L("board_cleaning_sections", Int(ui.holdLimit))).font(.footnote)
                    }
                }
                if ui.active {
                    Section {
                        if ui.holdCount > ui.displayedHoldCount {
                            Text(L("board_cleaning_section_count", Int(ui.displayedHoldCount), Int(ui.holdCount)))
                        }
                        Text(L("board_cleaning_active_hint")).font(.footnote).foregroundStyle(.secondary)
                        Button(ui.holdCount > ui.displayedHoldCount ? L("board_cleaning_next") : L("board_cleaning_done")) {
                            model.finish(markCleaned: true)
                        }
                        .disabled(!ui.ready || ui.busy)
                        Button(L("board_cleaning_stop")) { model.finish(markCleaned: false) }
                            .disabled(!ui.ready || ui.busy)
                    }
                }
                Section {
                    Button {
                        model.start()
                    } label: {
                        Label(ui.active ? L("board_cleaning_retry") : L("board_cleaning_start"), systemImage: "sparkles")
                    }
                    .disabled(!ui.ready || ui.busy || ui.holdCount == 0)
                    if ui.busy {
                        ProgressView().frame(maxWidth: .infinity)
                    }
                }
            }
            if ui.error {
                Section { Text(L("board_cleaning_error")).foregroundStyle(.red) }
            }
        }
    }
}

/// Shown while cleaning owns the board (Android's `BoardCleaningBanner`):
/// other sends are paused until it is finished.
struct BoardCleaningBanner: View {
    let ui: BoardCleaningScreenState
    let onOpen: () -> Void

    var body: some View {
        if ui.active {
            Button(action: onOpen) {
                Label(L("board_cleaning_active"), systemImage: "sparkles")
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.borderedProminent)
        }
    }
}
