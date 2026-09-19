import CruxCoachCore
import SwiftUI

/// Scan for, connect to and disconnect from a board. The phone itself is the BLE central.
struct BleView: View {
    let core: AppCore
    let ble: Observed<BoardConnectionUiState>
    @Environment(\.dismiss) private var dismiss

    private var ui: BoardConnectionUiState { ble.value }
    private var presenter: BoardConnectionPresenter { core.boardConnection }

    var body: some View {
        List {
            if let problem = adapterProblem {
                Section { Label(problem, systemImage: "exclamationmark.triangle").foregroundStyle(.orange) }
            }
            if let board = ui.connectedBoard, ui.connection == .connected || ui.connection == .sending {
                Section(L("board_ble_connected")) {
                    Label(board.displayName, systemImage: "lightbulb.fill")
                    Button(LI("ble_clear")) { presenter.clearBoard() }.disabled(ui.sending)
                    Button(L("board_ble_disconnect"), role: .destructive) { presenter.disconnect() }
                }
            } else {
                if ui.connection == .connecting { Section { HStack { ProgressView(); Text(LI("ble_connecting")) } } }
                if let failure = ui.connectFailure {
                    Section { Text(failureText(failure)).foregroundStyle(.red) }
                }
                Section(ui.scanning ? L("board_ble_scanning") : LI("ble_boards")) {
                    if ui.boards.isEmpty && ui.scanning { Text(LI("ble_none_yet")).foregroundStyle(.secondary) }
                    ForEach(ui.boards, id: \.identifier) { board in
                        Button { presenter.connect(identifier: board.identifier) } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(board.displayName).foregroundStyle(.primary)
                                    Text(board.boardBrand.displayNameForUi + (board.serial.isEmpty ? "" : " · \(board.serial)"))
                                        .font(.footnote).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if ui.lastUsedIdentifiers.contains(board.identifier) { Image(systemName: "clock.arrow.circlepath").accessibilityLabel(LI("ble_last_used")) }
                                Text("\(board.rssi) dBm").font(.footnote.monospacedDigit()).foregroundStyle(.secondary)
                            }
                        }
                        .disabled(ui.connection == .connecting)
                    }
                }
            }
            Section { Text(LI("ble_untested_notice")).font(.footnote).foregroundStyle(.secondary) }
        }
        .navigationTitle(L("board_ble_title"))
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("action_close")) { dismiss() } } }
        .onAppear { presenter.activate(); presenter.startScan() }
        .onDisappear { presenter.stopScan() }
    }

    private var adapterProblem: String? {
        switch ui.adapter {
        case .poweredOff: return LI("ble_powered_off")
        case .unauthorized: return LI("ble_unauthorized")
        case .unsupported: return LI("ble_unsupported")
        default: return nil
        }
    }

    private func failureText(_ failure: BoardConnectFailure) -> String {
        failure == .moonboardGenerationUnsupported ? L("board_ble_moonboard_generation_unsupported") : L("board_ble_connect_failed_hint")
    }
}
