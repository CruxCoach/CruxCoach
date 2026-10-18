import CruxCoachCore
import SwiftUI

/// A climb opened from the nearby area, at the angle the other phone climbs it at.
struct NearbyClimbTarget: Hashable {
    let uuid: String
    let angle: Int32
}

/// Android `BleStatusArea`'s sharing half under a board screen's top bar (browser, climb
/// detail, logbook), placed below the board link's chip (`BleStatusInset`): the climb another
/// phone has on the board, an occupied board, shared playlists to join, the joined playlist.
/// Nothing shows unless Bluetooth is already allowed and another phone is nearby; the board
/// screens never bring up the Bluetooth prompt (`AppCore.nearbyArea`).
///
/// With [onOpenClimb] (the detail page) a tapped climb is shown in place, as Android's
/// `switchClimb`; otherwise it is pushed onto the screen's navigation stack.
struct NearbyStatusInset: ViewModifier {
    let core: AppCore
    let ble: ScreenHost<BleScreenModel, BleScreenState>?
    /// The climb on screen (the detail page): not repeated as "on the board". Empty elsewhere.
    let currentClimbUuid: String
    let onOpenClimb: ((String, Int32) -> Void)?
    @State private var target: NearbyClimbTarget? = nil

    init(core: AppCore,
         ble: ScreenHost<BleScreenModel, BleScreenState>?,
         currentClimbUuid: String = "",
         onOpenClimb: ((String, Int32) -> Void)? = nil) {
        self.core = core
        self.ble = ble
        self.currentClimbUuid = currentClimbUuid
        self.onOpenClimb = onOpenClimb
    }

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .top, spacing: 0) {
                NearbyStatusArea(core: core, currentClimbUuid: currentClimbUuid,
                                 canOpen: onOpenClimb != nil || ble != nil,
                                 onOpenClimb: { uuid, angle in openClimb(uuid, angle) })
            }
            .navigationDestination(item: $target) { picked in
                destination(picked)
            }
    }

    private func openClimb(_ uuid: String, _ angle: Int32) {
        if let onOpenClimb {
            onOpenClimb(uuid, angle)
        } else {
            target = NearbyClimbTarget(uuid: uuid, angle: angle)
        }
    }

    @ViewBuilder
    private func destination(_ picked: NearbyClimbTarget) -> some View {
        if let ble {
            // Android opens it on its own, without a list to page through.
            ClimbDetailView(core: core, uuid: picked.uuid, angle: picked.angle, ble: ble) {}
        }
    }
}

/// The chip, or the panel once expanded; zero height while nothing is nearby.
struct NearbyStatusArea: View {
    let core: AppCore
    let currentClimbUuid: String
    let canOpen: Bool
    let onOpenClimb: (String, Int32) -> Void

    @State private var host: ScreenHost<NearbyAreaModel, NearbyAreaState>?
    @State private var expanded = false
    /// This view counted itself as a visible board screen (keeps shown/hidden balanced).
    @State private var counted = false

    var body: some View {
        let visible: Bool = host?.state.hasContent(currentClimbUuid: currentClimbUuid) ?? false
        // A stable container: the appear/disappear pair belongs to the screen, not to a branch.
        VStack(spacing: 0) {
            content
        }
        // Android forgets the expansion once there is nothing left to show.
        .onChange(of: visible) { _, shown in
            if !shown { expanded = false }
        }
        .task {
            guard host == nil else { return }
            let model = core.nearbyArea
            // App-wide: only the subscription ends with the view.
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) })
        }
        .onAppear {
            guard !counted else { return }
            counted = true
            core.nearbyArea.screenShown()
        }
        .onDisappear {
            guard counted else { return }
            counted = false
            core.nearbyArea.screenHidden()
        }
    }

    @ViewBuilder
    private var content: some View {
        if let host, host.state.hasContent(currentClimbUuid: currentClimbUuid) {
            if expanded {
                NearbyStatusPanel(core: core, model: host.model, ui: host.state,
                                  currentClimbUuid: currentClimbUuid, canOpen: canOpen,
                                  onOpenClimb: onOpenClimb, onCollapse: { expanded = false })
            } else if host.state.inSession {
                NearbySessionChip(core: core, model: host.model, ui: host.state, currentClimbUuid: currentClimbUuid)
            } else {
                NearbySummaryChip(ui: host.state, currentClimbUuid: currentClimbUuid) { expanded = true }
            }
        } else {
            Color.clear.frame(height: 0)
        }
    }
}

/// Android's wording (`buildChipSummary`, `OnBoardClimbSection`, the session chip's warning line).
enum NearbyStatusText {
    static func climbName(_ ui: NearbyAreaState) -> String {
        ui.onBoardName.isEmpty ? L("ble_unknown_climb") : ui.onBoardName
    }

    /// Collapsed: `"Name" 6b 40° · climbing now`, else `Board occupied · 2 playlists`.
    static func summary(_ ui: NearbyAreaState, _ currentClimbUuid: String) -> String {
        if ui.showsOnBoard(currentClimbUuid: currentClimbUuid) {
            let grade: String = ui.onBoardGrade.isEmpty ? "" : " \(ui.onBoardGrade)"
            let angle: Int = Int(ui.onBoardAngle)
            return "\"\(climbName(ui))\"\(grade) \(angle)° · \(chipStatus(ui))"
        }
        var parts: [String] = []
        if ui.boardOccupiedCount > 0 {
            parts.append(L("ble_board_occupied"))
        }
        let playlists: Int = ui.playlists.count
        if playlists > 0 {
            parts.append(LP("ble_nearby_playlists", playlists))
        }
        return parts.joined(separator: " · ")
    }

    static func chipStatus(_ ui: NearbyAreaState) -> String {
        switch ui.onBoardSourceCode {
        case "remoteActive": return L("ble_climbing_now")
        case "sessionRemote": return L("ble_session_climb")
        default: return ui.onBoardStillProjected ? L("ble_still_visible") : L("ble_last_climb")
        }
    }

    /// Expanded: a last climb whose lights went out is one that can be sent again.
    static func panelStatus(_ ui: NearbyAreaState) -> String {
        switch ui.onBoardSourceCode {
        case "remoteActive": return L("ble_climbing_now")
        case "sessionRemote": return L("ble_session_climb")
        default: return ui.onBoardStillProjected ? L("ble_still_visible") : L("ble_ready_to_resend")
        }
    }

    static func panelDetail(_ ui: NearbyAreaState) -> String {
        let grade: String = ui.onBoardGrade.isEmpty ? "" : "\(ui.onBoardGrade) · "
        let angle: Int = Int(ui.onBoardAngle)
        return "\(grade)\(angle)° · \(panelStatus(ui))"
    }

    static func sessionTitle(_ ui: NearbyAreaState) -> String {
        ui.sessionHostName.isEmpty ? L("relay_host_title") : ui.sessionHostName
    }

    /// `3/10 Name 6b`, or Android's "Empty" for an empty queue.
    static func sessionLine(_ ui: NearbyAreaState) -> String {
        let count: Int = Int(ui.sessionCount)
        let position: Int = Int(ui.sessionPosition)
        if count == 0 {
            return L("common_empty")
        }
        let name: String = ui.sessionClimbName.isEmpty ? L("ble_unknown") : ui.sessionClimbName
        let grade: String = ui.sessionClimbGrade.isEmpty ? "" : " \(ui.sessionClimbGrade)"
        let place: String = position > 0 ? "\(position)/\(count) " : ""
        return "\(place)\(name)\(grade)"
    }

    /// The wall disagrees with the joined queue: someone else's climb, or another board app.
    static func boardWarning(_ ui: NearbyAreaState, _ currentClimbUuid: String) -> String? {
        let shows: Bool = ui.showsOnBoard(currentClimbUuid: currentClimbUuid)
        if ui.externalBoardOverride || !shows {
            return ui.externalBoardOverride ? L("ble_external_board_override") : nil
        }
        guard ui.boardDiffers(currentClimbUuid: currentClimbUuid) else { return nil }
        let name: String = ui.onBoardName.isEmpty ? L("ble_unknown") : ui.onBoardName
        let grade: String = ui.onBoardGrade.isEmpty ? "" : " \(ui.onBoardGrade)"
        let angle: Int = Int(ui.onBoardAngle)
        return L("ble_board_shows_instead", "\(name)\(grade) \(angle)°")
    }

    /// While joining, or after the host left: what the playlist is doing.
    static func sessionPhase(_ ui: NearbyAreaState) -> String? {
        switch ui.sessionPhaseCode {
        case "connecting": return LI("shared_session_connecting")
        case "hostLost": return LI("shared_session_host_lost")
        default: return nil
        }
    }
}

/// Android `BleStatusChip` without an own session: one line, tap to expand.
private struct NearbySummaryChip: View {
    let ui: NearbyAreaState
    let currentClimbUuid: String
    let onExpand: () -> Void

    var body: some View {
        Button(action: onExpand) {
            HStack(spacing: 8) {
                Image(systemName: "dot.radiowaves.left.and.right")
                    .font(.system(size: 15))
                    .foregroundStyle(Color.orange)
                Text(NearbyStatusText.summary(ui, currentClimbUuid))
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
            .background(Color.orange.opacity(0.10), in: RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .accessibilityIdentifier("nearby_status_chip")
    }
}

/// Android `SessionChipContent` for the joined playlist: position, climb, Next; a tap opens it.
private struct NearbySessionChip: View {
    let core: AppCore
    let model: NearbyAreaModel
    let ui: NearbyAreaState
    let currentClimbUuid: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                NavigationLink {
                    SharedSessionsView(core: core)
                } label: {
                    titleRow
                }
                .buttonStyle(.plain)
                Button { model.next() } label: {
                    Image(systemName: "forward.end.fill").frame(width: 32, height: 32)
                }
                .disabled(!ui.sessionHasNext)
                .accessibilityLabel(L("cd_next"))
                Image(systemName: "chevron.right")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            NearbySessionNotes(ui: ui, currentClimbUuid: currentClimbUuid)
        }
        .padding(.leading, 10)
        .padding(.trailing, 6)
        .padding(.vertical, 6)
        .background(Color.orange.opacity(0.10), in: RoundedRectangle(cornerRadius: 14))
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .accessibilityIdentifier("nearby_session_chip")
    }

    private var titleRow: some View {
        HStack(spacing: 8) {
            Image(systemName: "music.note.list")
                .foregroundStyle(Color.orange)
            Text(NearbyStatusText.sessionLine(ui))
                .font(.footnote.weight(.semibold))
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer(minLength: 4)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint(L("cd_open"))
    }
}

/// The joined playlist's phase and the wall's disagreement with it, one short line each.
private struct NearbySessionNotes: View {
    let ui: NearbyAreaState
    let currentClimbUuid: String

    var body: some View {
        if let phase = NearbyStatusText.sessionPhase(ui) {
            Text(phase)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
        if let warning = NearbyStatusText.boardWarning(ui, currentClimbUuid) {
            Label(warning, systemImage: "exclamationmark.triangle")
                .font(.caption)
                .foregroundStyle(Color.orange)
                .lineLimit(1)
        }
    }
}

/// Android `BleStatusExpanded`.
private struct NearbyStatusPanel: View {
    let core: AppCore
    let model: NearbyAreaModel
    let ui: NearbyAreaState
    let currentClimbUuid: String
    let canOpen: Bool
    let onOpenClimb: (String, Int32) -> Void
    let onCollapse: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            header
            if ui.inSession {
                NearbySessionLink(core: core, ui: ui, currentClimbUuid: currentClimbUuid)
            }
            if ui.showsOnBoard(currentClimbUuid: currentClimbUuid) {
                NearbyOnBoardRow(ui: ui, canOpen: canOpen, onOpenClimb: onOpenClimb)
            }
            occupied
            if ui.disconnectRequestable {
                NearbyDisconnectNote()
            }
            if !ui.inSession && !ui.playlists.isEmpty {
                NearbyPlaylistRows(model: model, ui: ui)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.orange.opacity(0.10), in: RoundedRectangle(cornerRadius: 14))
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .accessibilityIdentifier("nearby_status_panel")
    }

    private var header: some View {
        HStack {
            Text(L("settings_sharing_title"))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Color.secondary)
            Spacer()
            Button(action: onCollapse) {
                Image(systemName: "chevron.up")
                    .foregroundStyle(Color.orange)
                    .accessibilityLabel(L("cd_collapse"))
            }
            .accessibilityIdentifier("nearby_status_collapse")
        }
    }

    @ViewBuilder
    private var occupied: some View {
        if ui.boardOccupiedCount > 0 {
            let count: Int = Int(ui.boardOccupiedCount)
            Text(L("ble_board_occupied_detail", count))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }
}

/// Android `SessionQueueSection`: the joined playlist; a tap opens it.
private struct NearbySessionLink: View {
    let core: AppCore
    let ui: NearbyAreaState
    let currentClimbUuid: String

    var body: some View {
        NavigationLink {
            SharedSessionsView(core: core)
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "music.note.list")
                    .foregroundStyle(Color.orange)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("ble_session_label"))
                        .font(.caption.weight(.bold))
                        .foregroundStyle(Color.orange)
                    Text(NearbyStatusText.sessionTitle(ui))
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    Text(NearbyStatusText.sessionLine(ui))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    NearbySessionNotes(ui: ui, currentClimbUuid: currentClimbUuid)
                }
                Spacer(minLength: 4)
                Image(systemName: "chevron.right")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .accessibilityLabel(L("cd_open"))
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("nearby_session_link")
    }
}

/// Android `OnBoardClimbSection`: the climb on the board; a tap opens it when this phone has it.
private struct NearbyOnBoardRow: View {
    let ui: NearbyAreaState
    let canOpen: Bool
    let onOpenClimb: (String, Int32) -> Void

    var body: some View {
        let openable: Bool = canOpen && !ui.onBoardOpenUuid.isEmpty
        Button {
            onOpenClimb(ui.onBoardOpenUuid, ui.onBoardAngle)
        } label: {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L(ui.onBoardStillProjected ? "ble_on_board" : "ble_last_climb"))
                        .font(.caption.weight(.bold))
                        .foregroundStyle(Color.orange)
                    Text(NearbyStatusText.climbName(ui))
                        .font(.body.weight(.semibold))
                    Text(NearbyStatusText.panelDetail(ui))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 4)
                if openable {
                    Image(systemName: "chevron.right")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .accessibilityLabel(L("cd_open"))
                }
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!openable)
        .accessibilityIdentifier("nearby_on_board")
    }
}

/// Android's "Request disconnect": asking needs an advertisement an iPhone app cannot send.
private struct NearbyDisconnectNote: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button(L("common_request_disconnect")) {}
                .buttonStyle(.bordered)
                .controlSize(.small)
                .disabled(true)
            Text(LI("nearby_disconnect_needs_android"))
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Android's `SessionHostItem` rows of the board connection sheet: a tap joins the playlist.
private struct NearbyPlaylistRows: View {
    let model: NearbyAreaModel
    let ui: NearbyAreaState

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(LI("shared_sessions_header"))
                .font(.caption.weight(.bold))
                .foregroundStyle(.secondary)
            ForEach(ui.playlists, id: \.identifier) { playlist in
                NearbyPlaylistRow(playlist: playlist) { model.join(identifier: playlist.identifier) }
                    .disabled(!ui.canJoin)
            }
        }
    }
}

private struct NearbyPlaylistRow: View {
    let playlist: NearbySessionUi
    let onJoin: () -> Void

    var body: some View {
        Button(action: onJoin) {
            HStack(spacing: 10) {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .foregroundStyle(Color.orange)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.subheadline.weight(.bold))
                    Text(L("relay_host_join_subtitle", Int(playlist.participantCount)))
                        .font(.caption)
                        .foregroundStyle(Color.orange)
                }
                Spacer(minLength: 4)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("nearby_playlist_join")
    }

    private var title: String {
        playlist.hostName.isEmpty ? L("relay_host_title") : playlist.hostName
    }
}

/// Android's successor question ("Continue with nearby group?"), asked from any screen once the
/// joined host left and another session appeared. Hosted once at the root.
struct SharedSuccessorPrompt: ViewModifier {
    let core: AppCore
    @State private var host: ScreenHost<SharedSessionsModel, SharedSessionsState>?

    func body(content: Content) -> some View {
        // Read here, in the body, so the observation of the Kotlin state re-renders the prompt.
        let offered: Bool = host?.state.hasSuccessor ?? false
        let name: String = successorName
        return content
            .task {
                guard host == nil else { return }
                let model = core.sharedSessions
                // App-wide: only the subscription ends with the view.
                host = ScreenHost(model: model, initial: model.currentState,
                                  subscribe: { model, onState in model.watch(onState: onState) })
            }
            .alert(L("ble_successor_join_title"), isPresented: presented(offered)) {
                Button(L("ble_successor_join_confirm")) { host?.model.acceptSuccessor() }
                    .accessibilityIdentifier("successor_join_confirm")
                Button(LI("shared_successor_decline"), role: .cancel) { host?.model.declineSuccessor() }
                    .accessibilityIdentifier("successor_join_keep_queue")
            } message: {
                Text(L("ble_successor_join_message", name))
            }
    }

    private var successorName: String {
        let name: String = host?.state.successorHostName ?? ""
        return name.isEmpty ? L("ble_successor_join_unknown_name") : name
    }

    /// Dismissing without a choice is Android's `onDismissRequest`: keep the queue, do not join.
    private func presented(_ offered: Bool) -> Binding<Bool> {
        Binding(
            get: { offered },
            set: { shown in
                guard !shown, let host, host.state.hasSuccessor else { return }
                host.model.declineSuccessor()
            })
    }
}
