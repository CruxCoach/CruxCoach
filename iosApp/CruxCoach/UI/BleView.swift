import CruxCoachCore
import SwiftUI

/// Scan for, connect to and disconnect from a board. The phone itself is the BLE central.
struct BleView: View {
    let ble: ScreenHost<BleScreenModel, BleScreenState>
    /// Hosts that own the active board pass this to get Android's guided fix for a board of another family.
    var fix: BoardMismatchRoute? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var showFix = false

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
                    if ui.connectedQuantum {
                        quantumRows
                    } else {
                        Button(LI("ble_clear")) { model.clearBoard() }.disabled(ui.sending)
                    }
                    Button(L("board_ble_disconnect"), role: .destructive) { model.disconnect() }
                }
                if let mismatch {
                    BleMismatchSection(mismatch: mismatch, canFix: fix != nil) { showFix = true }
                }
            } else {
                if ui.connection == "connecting" {
                    Section { HStack { ProgressView(); Text(LI("ble_connecting")) } }
                } else {
                    rememberedSection
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
        .modifier(BleMismatchPrompt(ble: ble, route: fix, showFix: $showFix))
    }

    /// Android `connectedBoardConfigurationMismatch` for the board on the link; nil when it matches.
    private var mismatch: BoardMismatchUi? {
        guard let fix, !ui.connectedBrandWire.isEmpty else { return nil }
        let result: BoardMismatchUi = model.connectionMismatch(activeBrandWire: fix.activeBrandWire)
        return result.active ? result : nil
    }

    /// Android's "Last used" card: reconnect a remembered board directly, without a scan.
    @ViewBuilder
    private var rememberedSection: some View {
        if !ui.remembered.isEmpty {
            Section(L("board_ble_last_used")) {
                ForEach(ui.remembered, id: \.identifier) { board in
                    RememberedBoardRow(board: board) { model.reconnectRemembered(brandWire: board.brandWire) }
                }
            }
        }
    }

    /// Quantum walls are driven through layers (climb detail); the controller refuses a plain clear.
    @ViewBuilder
    private var quantumRows: some View {
        if !ui.quantumModelName.isEmpty {
            LabeledContent(L("board_selection_quantum_model_label"), value: ui.quantumModelName)
        }
        Text(quantumWallText).font(.footnote)
    }

    /// Android's wall status strip, read-only: every app's routes count, ours included.
    private var quantumWallText: String {
        let active: Int = Int(ui.quantumWallActive)
        let capacity: Int = Int(ui.quantumWallCapacity)
        switch ui.quantumWall {
        case "live": return L("quantum_wall_occupancy", active, capacity)
        case "stale": return L("quantum_wall_occupancy_stale", active, capacity)
        case "syncing": return L("quantum_wall_syncing")
        default: return L("quantum_wall_unsynced")
        }
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

private struct RememberedBoardRow: View {
    let board: BleBoardRowUi
    let onReconnect: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(board.name).fontWeight(.semibold)
                Text(board.serial.isEmpty ? board.brandTitle : "\(board.brandTitle) · \(board.serial)")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Spacer()
            Button(L("board_ble_reconnect"), action: onReconnect)
                .buttonStyle(.borderedProminent)
                .tint(Color.orange)
                .accessibilityIdentifier("ble_reconnect_button")
        }
    }
}
