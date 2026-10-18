import CruxCoachCore
import SwiftUI

/// Android's "Send to board" settings (`BoardSendModeSection`): automatic or tap to send,
/// separately for boards that take one app connection and boards that take several.
struct BoardSendModeSettingsSection: View {
    let core: AppCore
    @State private var host: ScreenHost<BoardSendModeSettingsModel, BoardSendModeSettingsState>?

    var body: some View {
        Section {
            if let host {
                rows(model: host.model, ui: host.state)
            } else {
                ProgressView().task { load() }
            }
        } header: {
            Text(L("settings_board_send_mode_title"))
        } footer: {
            Text(footerText)
        }
    }

    private var footerText: String {
        L("settings_board_send_mode_desc",
          L("settings_board_send_mode_automatic"),
          L("settings_board_send_mode_explicit"))
    }

    private func load() {
        guard host == nil else { return }
        let model = core.makeBoardSendModeSettings()
        host = ScreenHost(model: model, initial: model.currentState,
                          subscribe: { model, onState in model.watch(onState: onState) },
                          onClose: { model in model.close() })
    }

    @ViewBuilder
    private func rows(model: BoardSendModeSettingsModel, ui: BoardSendModeSettingsState) -> some View {
        Picker(L("settings_board_send_mode_single"),
               selection: Binding(get: { ui.singleConnection }, set: { model.setSingleConnection(code: $0) })) {
            Text(L("settings_board_send_mode_automatic")).tag("automatic")
            Text(L("settings_board_send_mode_explicit")).tag("explicit")
        }
        .accessibilityIdentifier("settings_board_send_mode_single")
        Picker(L("settings_board_send_mode_multi"),
               selection: Binding(get: { ui.multiConnection }, set: { model.setMultiConnection(code: $0) })) {
            Text(L("settings_board_send_mode_automatic")).tag("automatic")
            Text(L("settings_board_send_mode_explicit")).tag("explicit")
        }
        .accessibilityIdentifier("settings_board_send_mode_multi")
    }
}

/// Android "Send to board: automatic" in the climb detail: the climb on screen goes on the
/// wall once its page settled (open, swipe, angle, mirror) and when a board connects. The
/// core decides (the mode for the connected controller, Quantum, sessions, playlists);
/// [send] is the detail's guarded send, so the board-match check runs as for the lamp.
struct DetailAutoSendTrigger: ViewModifier {
    let core: AppCore
    let model: DetailScreenModel
    let ui: DetailScreenState
    let connected: Bool
    let send: () -> Void
    @State private var autoSend: DetailAutoSendModel?

    func body(content: Content) -> some View {
        content
            .onChange(of: pageKey, initial: true) { _, _ in fire() }
            .onChange(of: connected) { _, isConnected in
                if isConnected { fire() }
            }
    }

    private var pageKey: String {
        "\(ui.uuid)|\(ui.status)|\(ui.angle)|\(ui.isMirrored)"
    }

    private func fire() {
        guard ui.status == "ready" else { return }
        let auto: DetailAutoSendModel = autoSend ?? core.makeDetailAutoSend(detail: model)
        if autoSend == nil { autoSend = auto }
        if auto.shouldSendNow(climbUuid: ui.uuid) { send() }
    }
}
