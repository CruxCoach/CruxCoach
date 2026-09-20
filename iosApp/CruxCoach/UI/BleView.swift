import CruxCoachCore
import SwiftUI

/// Scan for, connect to and disconnect from a board. The phone itself is the BLE central.
struct BleView: View {
    let ble: ScreenHost<BleScreenModel, BleScreenState>
    @Environment(\.dismiss) private var dismiss

    private var ui: BleScreenState { ble.state }
    private var model: BleScreenModel { ble.model }

    var body: some View {
        List {
            if let problem = adapterProblem {
                Section { Label(problem, systemImage: "exclamationmark.triangle").foregroundStyle(.orange) }
            }
            if ui.connected {
                Section(L("board_ble_connected")) {
                    Label(ui.connectedName, systemImage: "lightbulb.fill")
                    Button(LI("ble_clear")) { model.clearBoard() }.disabled(ui.sending)
                    Button(L("board_ble_disconnect"), role: .destructive) { model.disconnect() }
                }
            } else {
                if ui.connection == "connecting" {
                    Section { HStack { ProgressView(); Text(LI("ble_connecting")) } }
                }
                if ui.connectFailure != "none" {
                    Section {
                        Text(ui.connectFailure == "moonBoardGenerationUnsupported"
                             ? L("board_ble_moonboard_generation_unsupported")
                             : L("board_ble_connect_failed_hint"))
                            .foregroundStyle(.red)
                    }
                }
                Section(ui.scanning ? L("board_ble_scanning") : LI("ble_boards")) {
                    if ui.boards.isEmpty && ui.scanning {
                        Text(LI("ble_none_yet")).foregroundStyle(.secondary)
                    }
                    ForEach(ui.boards, id: \.identifier) { board in
                        Button { model.connect(identifier: board.identifier) } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(board.name).foregroundStyle(.primary)
                                    Text(board.serial.isEmpty ? board.brandTitle : "\(board.brandTitle) · \(board.serial)")
                                        .font(.footnote).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if board.lastUsed {
                                    Image(systemName: "clock.arrow.circlepath")
                                        .accessibilityLabel(LI("ble_last_used"))
                                }
                                Text("\(board.rssi) dBm").font(.footnote.monospacedDigit()).foregroundStyle(.secondary)
                            }
                        }
                        .disabled(ui.connection == "connecting")
                    }
                }
            }
            Section { Text(LI("ble_untested_notice")).font(.footnote).foregroundStyle(.secondary) }
        }
        .navigationTitle(L("board_ble_title"))
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("action_close")) { dismiss() } } }
        .onAppear { model.activate(); model.startScan() }
        .onDisappear { model.stopScan() }
    }

    private var adapterProblem: String? {
        switch ui.adapter {
        case "poweredOff": return LI("ble_powered_off")
        case "unauthorized": return LI("ble_unauthorized")
        case "unsupported": return LI("ble_unsupported")
        default: return nil
        }
    }
}
