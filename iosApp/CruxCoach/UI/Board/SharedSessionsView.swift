import CruxCoachCore
import SwiftUI

/// The iPhone as a participant in an Android-hosted shared playlist (Android's
/// `SessionHostItem` list, the participant's `SessionQueueSheet`, the successor
/// question of `BleStatusArea`) and Android's "someone is climbing nearby" card.
/// Hosting and advertising need an Android phone; the screen says so instead of
/// offering a dead button. The model is app-wide (`AppCore.sharedSessions`), so a
/// joined playlist survives leaving this screen.
struct SharedSessionsView: View {
    let core: AppCore

    @State private var host: ScreenHost<SharedSessionsModel, SharedSessionsState>?
    @State private var feedback: String?

    var body: some View {
        Group {
            if let host {
                SharedSessionsList(model: host.model, ui: host.state)
                    .modifier(SharedFeedbackAlert(feedbackSeq: host.state.feedbackSeq,
                                                  feedbackCode: host.state.feedbackCode,
                                                  message: $feedback))
            } else {
                ProgressView()
            }
        }
        .navigationTitle(LI("shared_sessions_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // Reordering the joined queue (each move is sent to the host).
            ToolbarItem(placement: .primaryAction) { EditButton() }
        }
        .task {
            guard host == nil else { return }
            let model = core.sharedSessions
            // App-wide: only the subscription ends with the screen.
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) })
            model.startScan()
        }
        .onAppear { host?.model.startScan() }
        .onDisappear { host?.model.stopScan() }
    }
}

/// Command feedback (Android's snackbar), shown once per `feedbackSeq`.
private struct SharedFeedbackAlert: ViewModifier {
    let feedbackSeq: Int32
    let feedbackCode: String
    @Binding var message: String?

    func body(content: Content) -> some View {
        content
            .onChange(of: feedbackSeq) { _, _ in message = text(feedbackCode) }
            .alert(L("board_queue_title"), isPresented: presented) {
                Button(L("action_ok")) { message = nil }
            } message: {
                Text(message ?? "")
            }
    }

    private var presented: Binding<Bool> {
        Binding(get: { message != nil }, set: { shown in if !shown { message = nil } })
    }

    private func text(_ code: String) -> String? {
        switch code {
        case "conflict": return L("board_queue_command_conflict")
        case "unavailable": return L("board_queue_command_unavailable")
        case "failed": return L("board_queue_command_failed")
        default: return nil
        }
    }
}

private struct SharedSessionsList: View {
    let model: SharedSessionsModel
    let ui: SharedSessionsState

    var body: some View {
        List {
            if ui.phaseCode != "idle" {
                SharedSessionSections(model: model, ui: ui)
            }
            nearbySessions
            NearbyClimbsSection(model: model, ui: ui)
            Section {
                Text(LI("shared_hosting_needs_android"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var canJoin: Bool { ui.phaseCode == "idle" || ui.phaseCode == "ended" }

    @ViewBuilder
    private var nearbySessions: some View {
        Section(LI("shared_sessions_header")) {
            bluetoothRow
            ForEach(ui.sessions, id: \.identifier) { session in
                NearbySessionRow(session: session) { model.join(identifier: session.identifier) }
                    .disabled(!canJoin)
            }
            if ui.sessions.isEmpty && ui.bluetoothCode == "on" {
                emptyRow
            }
        }
    }

    @ViewBuilder
    private var bluetoothRow: some View {
        switch ui.bluetoothCode {
        case "off": Text(LI("shared_bt_off")).foregroundStyle(.secondary)
        case "unauthorized": Text(LI("shared_bt_unauthorized")).foregroundStyle(.secondary)
        case "unsupported": Text(LI("shared_bt_unsupported")).foregroundStyle(.secondary)
        default: EmptyView()
        }
    }

    @ViewBuilder
    private var emptyRow: some View {
        if ui.scanning {
            HStack(spacing: 8) {
                ProgressView()
                Text(LI("shared_sessions_searching")).foregroundStyle(.secondary)
            }
        }
        Text(LI("shared_sessions_none"))
            .font(.footnote)
            .foregroundStyle(.secondary)
    }
}

/// Android `SessionHostItem`.
private struct NearbySessionRow: View {
    let session: NearbySessionUi
    let onJoin: () -> Void

    var body: some View {
        Button(action: onJoin) {
            HStack(spacing: 12) {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .foregroundStyle(Color.orange)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body.weight(.bold))
                    Text(L("relay_host_join_subtitle", Int(session.participantCount)))
                        .font(.footnote)
                        .foregroundStyle(Color.orange)
                    if session.hasClimb {
                        Text(climbLine).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("shared_session_host_item")
    }

    private var title: String {
        session.hostName.isEmpty ? L("relay_host_title") : session.hostName
    }

    private var climbLine: String {
        let name: String = session.climbName.isEmpty ? L("board_ble_unknown_climb") : session.climbName
        let angle: String = "\(session.climbAngle)°"
        return session.climbGrade.isEmpty ? "\(name) · \(angle)" : "\(name) · \(session.climbGrade) · \(angle)"
    }
}

/// Android `NearbyActiveClimbCard`: someone climbing nearby, or an occupied board.
private struct NearbyClimbsSection: View {
    let model: SharedSessionsModel
    let ui: SharedSessionsState

    var body: some View {
        if !ui.climbs.isEmpty {
            Section {
                ForEach(ui.climbs, id: \.key) { climb in
                    NearbyClimbRow(model: model, climb: climb, canAdd: ui.canEdit)
                }
            } footer: {
                Text(LI("shared_sharing_needs_android"))
            }
        }
    }
}

private struct NearbyClimbRow: View {
    let model: SharedSessionsModel
    let climb: NearbyClimbUi
    let canAdd: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(title, systemImage: "antenna.radiowaves.left.and.right")
                .font(.subheadline.weight(.bold))
            if climb.kindCode == "active" {
                climbLine
                if canAdd && model.canAdd(uuid: climb.uuid) {
                    Button(LI("shared_add_to_playlist")) { model.add(uuid: climb.uuid, angle: climb.angle) }
                        .buttonStyle(.borderless)
                        .foregroundStyle(Color.orange)
                }
            }
        }
    }

    private var title: String {
        climb.kindCode == "occupied" ? L("board_ble_board_occupied") : L("board_ble_someone_climbing")
    }

    @ViewBuilder
    private var climbLine: some View {
        let name: String = climb.name.isEmpty ? L("board_ble_unknown_climb") : climb.name
        let detail: String = climb.grade.isEmpty ? "\(climb.angle)°" : "\(climb.grade) · \(climb.angle)°"
        if climb.known {
            // Resolved by the browser's navigation stack (`navigationDestination(for: String.self)`).
            NavigationLink(value: climb.uuid) {
                HStack {
                    Text(name).foregroundStyle(Color.orange)
                    Text(detail).foregroundStyle(.secondary)
                }
            }
        } else {
            HStack {
                Text(name)
                Text(detail).foregroundStyle(.secondary)
            }
        }
    }
}
