import CruxCoachCore
import SwiftUI

/// The board screens' Bluetooth status strip (Android `BleStatusArea`: a collapsed
/// chip between the top bar and the content, expandable into a panel).
///
/// On iOS it reports this phone's own board link: searching, connecting, connected
/// to a board, the link lost, Bluetooth off or not allowed. The state comes from
/// the shared `BleScreenModel` (`status` and friends); nothing is shown while the
/// link is idle and nothing went wrong. "Choose board" is the screen's own board
/// connection sheet, so the strip and the toolbar button open the same place.
struct BleStatusArea: View {
    let ble: ScreenHost<BleScreenModel, BleScreenState>
    let onChooseBoard: () -> Void
    @State private var expanded = false

    var body: some View {
        let ui: BleScreenState = ble.state
        if ui.status == "hidden" {
            // Keeps the inset's view alive (and its state) while there is nothing to say.
            Color.clear.frame(height: 0)
        } else if expanded {
            BleStatusPanel(ui: ui, model: ble.model,
                           onCollapse: { expanded = false },
                           onChooseBoard: onChooseBoard)
        } else {
            BleStatusChip(ui: ui) { expanded = true }
        }
    }
}

/// Places [BleStatusArea] under a screen's top bar, as Android does on the browser,
/// the climb detail and the logbook. A modifier keeps the screens' chains short.
/// Without [onChooseBoard] it brings its own board connection sheet.
struct BleStatusInset: ViewModifier {
    let ble: ScreenHost<BleScreenModel, BleScreenState>?
    let onChooseBoard: (() -> Void)?
    @State private var ownSheet = false

    init(ble: ScreenHost<BleScreenModel, BleScreenState>?, onChooseBoard: (() -> Void)? = nil) {
        self.ble = ble
        self.onChooseBoard = onChooseBoard
    }

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .top, spacing: 0) {
                if let ble {
                    BleStatusArea(ble: ble, onChooseBoard: { chooseBoard() })
                }
            }
            .sheet(isPresented: $ownSheet) {
                if let ble {
                    NavigationStack { BleView(ble: ble) }
                }
            }
    }

    private func chooseBoard() {
        if let onChooseBoard {
            onChooseBoard()
        } else {
            ownSheet = true
        }
    }
}

/// Wording and colours per status code, shared by the chip and the panel.
enum BleStatusStyle {
    static func summary(_ ui: BleScreenState) -> String {
        let name: String = ui.statusBoardName
        switch ui.status {
        case "connected": return name.isEmpty ? L("board_ble_connected") : L("ble_connected", name)
        case "sending": return L("board_ble_sending")
        case "connecting": return name.isEmpty ? LI("ble_connecting") : "\(LI("ble_connecting")) \(name)"
        case "scanning": return L("ble_scanning")
        case "lost": return name.isEmpty ? L("ble_disconnected") : "\(L("ble_disconnected")) · \(name)"
        case "failed": return L("ble_error_connect_failed")
        case "off": return L("board_ble_bt_disabled_title")
        case "unauthorized": return L("ble_permission_needed")
        case "unsupported": return LI("ble_unsupported")
        default: return ""
        }
    }

    static func icon(_ status: String) -> String {
        switch status {
        case "connected", "sending": return "antenna.radiowaves.left.and.right"
        case "connecting", "scanning": return "dot.radiowaves.left.and.right"
        default: return "exclamationmark.triangle"
        }
    }

    /// Android: green for a live link, orange while it is being made, red for a problem.
    static func tint(_ status: String) -> Color {
        switch status {
        case "connected", "sending": return Color.green
        case "connecting", "scanning": return Color.orange
        default: return Color.red
        }
    }
}

/// Android `BleStatusChip`: one line, tap to expand.
private struct BleStatusChip: View {
    let ui: BleScreenState
    let onExpand: () -> Void

    var body: some View {
        let tint: Color = BleStatusStyle.tint(ui.status)
        Button(action: onExpand) {
            HStack(spacing: 8) {
                Image(systemName: BleStatusStyle.icon(ui.status))
                    .font(.system(size: 15))
                    .foregroundStyle(tint)
                Text(BleStatusStyle.summary(ui))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.primary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Spacer(minLength: 4)
                Image(systemName: "chevron.down")
                    .font(.footnote)
                    .foregroundStyle(Color.secondary)
                    .accessibilityLabel(L("cd_expand"))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(tint.opacity(0.10), in: RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .accessibilityIdentifier("ble_status_chip")
    }
}

/// Android `BleStatusExpanded`: the status in words and what can be done about it.
private struct BleStatusPanel: View {
    let ui: BleScreenState
    let model: BleScreenModel
    let onCollapse: () -> Void
    let onChooseBoard: () -> Void

    var body: some View {
        let tint: Color = BleStatusStyle.tint(ui.status)
        VStack(alignment: .leading, spacing: 10) {
            header
            BleStatusDetail(ui: ui)
            BleStatusActions(ui: ui, model: model, onChooseBoard: onChooseBoard)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tint.opacity(0.10), in: RoundedRectangle(cornerRadius: 14))
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .accessibilityIdentifier("ble_status_panel")
    }

    private var header: some View {
        HStack {
            Text(L("board_ble_title"))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Color.secondary)
            Spacer()
            Button(action: onCollapse) {
                Image(systemName: "chevron.up")
                    .foregroundStyle(Color.orange)
                    .accessibilityLabel(L("cd_collapse"))
            }
            .accessibilityIdentifier("ble_status_collapse")
        }
    }
}

/// The panel's explanation, per status.
private struct BleStatusDetail: View {
    let ui: BleScreenState

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                if ui.status == "connecting" || ui.status == "scanning" || ui.status == "sending" {
                    ProgressView()
                } else {
                    Image(systemName: BleStatusStyle.icon(ui.status))
                        .foregroundStyle(BleStatusStyle.tint(ui.status))
                }
                Text(BleStatusStyle.summary(ui))
                    .font(.body.weight(.semibold))
            }
            if let hint = hint {
                Text(hint)
                    .font(.footnote)
                    .foregroundStyle(Color.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var hint: String? {
        switch ui.status {
        case "failed":
            return ui.connectFailure == "moonBoardGenerationUnsupported"
                ? L("board_ble_moonboard_generation_unsupported")
                : L("board_ble_connect_failed_hint")
        case "off": return "\(L("board_ble_bt_disabled_message")) \(LI("ble_powered_off_hint"))"
        case "unauthorized": return LI("ble_unauthorized")
        case "scanning": return L("board_ble_scanning")
        default: return nil
        }
    }
}

/// Connect, disconnect, reconnect, choose a board, or the system settings.
private struct BleStatusActions: View {
    let ui: BleScreenState
    let model: BleScreenModel
    let onChooseBoard: () -> Void

    var body: some View {
        // Three buttons do not fit a narrow phone in German: they stack then.
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) { buttons() }
            VStack(alignment: .leading, spacing: 8) { buttons() }
        }
        .buttonStyle(.bordered)
        .controlSize(.small)
        .tint(Color.orange)
    }

    @ViewBuilder
    private func buttons() -> some View {
        switch ui.status {
        case "connected", "sending":
            Button(LI("ble_clear")) { model.clearBoard() }
                .disabled(ui.sending)
            Button(L("board_ble_disconnect"), role: .destructive) { model.disconnect() }
                .accessibilityIdentifier("ble_status_disconnect")
            chooseBoard
        case "connecting":
            Button(L("action_cancel")) { model.disconnect() }
        case "scanning":
            Button(L("board_ble_stop_scan")) { model.stopScan() }
            Button(L("board_ble_title")) { onChooseBoard() }
        case "lost", "failed":
            if ui.canReconnect {
                Button(L("board_ble_reconnect")) { model.reconnect() }
                    .accessibilityIdentifier("ble_status_reconnect")
            }
            chooseBoard
            ignore
        case "unauthorized":
            Button(LI("ble_open_settings")) { openSettings() }
            ignore
        default:
            ignore
        }
    }

    private var chooseBoard: some View {
        Button(L("board_ble_search_other_boards")) { onChooseBoard() }
            .accessibilityIdentifier("ble_status_choose_board")
    }

    private var ignore: some View {
        Button(L("board_bt_off_ignore")) { model.dismissStatus() }
    }

    /// iOS has no public link to the Bluetooth page; the app's own settings hold its Bluetooth permission.
    private func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }
}
