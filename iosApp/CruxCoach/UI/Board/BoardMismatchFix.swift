import CruxCoachCore
import SwiftUI

/// What a host that owns the active board hands the connection sheet so it can offer
/// Android's guided fix (BleConnectionSheet → BoardPickerDialog with a mismatch):
/// the configured board's family, the installed catalogues and how a pick is applied.
struct BoardMismatchRoute {
    let core: AppCore
    let activeBrandWire: String
    let brandWires: [String]
    let onPick: (BoardOption) -> Void
}

/// Android returns to the board browser after a guided fix (`onNavigateToBoardBrowser`, which pops
/// everything above it). The browser hands every page in its stack this action; a page outside
/// the browser's stack has none and just goes back.
struct BrowserRootAction {
    let run: () -> Void
}

private struct BrowserRootKey: EnvironmentKey {
    static let defaultValue: BrowserRootAction? = nil
}

extension EnvironmentValues {
    var browserRoot: BrowserRootAction? {
        get { self[BrowserRootKey.self] }
        set { self[BrowserRootKey.self] = newValue }
    }
}

/// Android `BoardMismatchExplanation`: title, why, where the preselection came from,
/// and that only a confirmed choice changes the active board.
struct BoardMismatchExplanation: View {
    let mismatch: BoardMismatchUi

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L("board_mismatch_title")).font(.subheadline.bold())
            Text(message).font(.footnote)
            Text(L(mismatch.sourceKey, mismatch.prefillBrandTitle)).font(.footnote)
            Text(L("board_mismatch_confirmation_notice")).font(.footnote.weight(.semibold))
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("board_mismatch_explanation")
    }

    private var message: String {
        let first: String = mismatch.messageArg1
        let second: String = mismatch.messageArg2
        switch mismatch.kindCode {
        case "connectedBrand", "activeBrand": return L(mismatch.messageKey, first, second)
        default: return L(mismatch.messageKey)
        }
    }
}

/// The connection sheet's mismatch block: the explanation and "Fix board configuration".
struct BleMismatchSection: View {
    let mismatch: BoardMismatchUi
    let canFix: Bool
    let onFix: () -> Void

    var body: some View {
        Section {
            BoardMismatchExplanation(mismatch: mismatch)
            if canFix {
                Button(L("board_mismatch_fix_action"), action: onFix)
                    .accessibilityIdentifier("board_mismatch_fix_action")
            }
        }
    }
}

/// Opens the guided picker by itself once per connection, as Android's sheet does when a
/// controller of another family connects; closing it without a choice dismisses the prompt
/// for this connection (the send fences still refuse the other board).
struct BleMismatchPrompt: ViewModifier {
    let ble: ScreenHost<BleScreenModel, BleScreenState>
    let route: BoardMismatchRoute?
    @Binding var showFix: Bool
    @State private var picked = false

    func body(content: Content) -> some View {
        let ui: BleScreenState = ble.state
        let trigger: String = ui.connectedBrandWire + (ui.mismatchPromptDismissed ? "|dismissed" : "")
        return content
            .navigationDestination(isPresented: $showFix) { picker() }
            .onAppear { evaluate() }
            .onChange(of: trigger) { _, _ in evaluate() }
            .onChange(of: showFix) { old, new in
                if old && !new && !picked { ble.model.dismissConnectionMismatch() }
            }
    }

    @ViewBuilder
    private func picker() -> some View {
        if let route {
            let mismatch: BoardMismatchUi = ble.model.connectionMismatch(activeBrandWire: route.activeBrandWire)
            BoardPickerView(core: route.core, brandWires: route.brandWires, onPick: { option in
                picked = true
                route.onPick(option)
            }, mismatch: mismatch.active ? mismatch : nil)
        }
    }

    private func evaluate() {
        guard let route, !showFix, !ble.state.mismatchPromptDismissed else { return }
        if ble.model.connectionMismatch(activeBrandWire: route.activeBrandWire).active {
            picked = false
            showFix = true
        }
    }
}

/// The board-match guard's refusal where one climb is lit (climb detail, Quantum rack):
/// Android's send error for the mismatch and its "Fix board configuration" action.
struct BoardFixRow: View {
    let mismatch: BoardMismatchUi
    let onFix: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L(BoardFixSupport.sendErrorKey(mismatch.kindCode)))
                .font(.footnote)
                .foregroundStyle(.red)
            Button(L("board_mismatch_fix_action"), action: onFix)
                .font(.footnote)
                .accessibilityIdentifier("board_mismatch_fix_action")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

enum BoardFixSupport {
    /// Android `BoardSendController.mismatchError`.
    static func sendErrorKey(_ kindCode: String) -> String {
        switch kindCode {
        case "connectedBrand", "connectedModel": return "board_send_error_connected_board_mismatch"
        case "activeSize": return "board_send_error_climb_off_board"
        default: return "board_send_error_brand_mismatch"
        }
    }

    /// The Quantum controller's refusals that are a board mismatch (its own guard ran first).
    static func isMismatchError(_ key: String) -> Bool {
        key == "board_send_error_connected_board_mismatch" || key == "board_send_error_brand_mismatch"
    }

    /// The catalogues the picker can offer outside the browser.
    static func installedBrandWires(_ core: AppCore) -> [String] {
        let rows: [SyncBrandRow] = core.syncScreen.currentState.rows
        return rows.filter { $0.installed }.map { $0.brandWire }
    }
}
