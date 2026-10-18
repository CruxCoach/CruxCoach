import CruxCoachCore
import SwiftUI

/// The joined playlist (Android's participant `SessionQueueSheet`): status, the
/// host's rest, previous / next, the queue (tap to jump, swipe to remove, drag to
/// reorder — each sent to the host, which decides), and "Leave playlist". After the
/// host left: the successor question (Android `BleStatusArea`) or why it ended.
struct SharedSessionSections: View {
    let model: SharedSessionsModel
    let ui: SharedSessionsState

    var body: some View {
        statusSection
        if ui.hasSuccessor {
            successorSection
        }
        if ui.phaseCode == "joined" {
            transportSection
        }
        queueSection
        actionSection
    }

    // MARK: Status

    private var title: String {
        ui.hostName.isEmpty ? L("relay_host_title") : ui.hostName
    }

    @ViewBuilder
    private var statusSection: some View {
        Section(L("board_queue_title")) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.headline)
                if ui.phaseCode == "joined" {
                    Text(LI("shared_session_people", Int(ui.participantCount)))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            phaseRow
            if ui.restSeconds > 0 {
                restRow
            }
            if ui.externalBoardOverride {
                Label(L("ble_external_board_override"), systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if ui.pendingCommands > 0 {
                HStack(spacing: 8) {
                    ProgressView()
                    Text(L("board_queue_command_pending", Int(ui.pendingCommands)))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    @ViewBuilder
    private var phaseRow: some View {
        switch ui.phaseCode {
        case "connecting":
            HStack(spacing: 8) {
                ProgressView()
                Text(LI("shared_session_connecting"))
            }
        case "joined":
            Text(L("board_ble_session_active_message"))
                .font(.footnote)
                .foregroundStyle(.secondary)
        case "hostLost":
            HStack(spacing: 8) {
                ProgressView()
                Text(LI("shared_session_host_lost"))
            }
        default:
            Text(endText).foregroundStyle(.secondary)
        }
    }

    private var endText: String {
        switch ui.endCode {
        case "connectFailed": return L("ble_error_connect_failed")
        case "endedByHost": return LI("shared_session_ended_by_host")
        default: return LI("shared_session_host_left")
        }
    }

    private var restRow: some View {
        let seconds: Int = Int(ui.restSeconds)
        let clock: String = String(format: "%ld:%02ld", seconds / 60, seconds % 60)
        return Label("\(L("board_rest_timer_rest")) \(clock)", systemImage: "timer")
            .font(.body.monospacedDigit())
            .foregroundStyle(Color.orange)
    }

    // MARK: Successor (Android `ble_successor_join_*`)

    private var successorSection: some View {
        let name: String = ui.successorHostName.isEmpty ? L("ble_successor_join_unknown_name") : ui.successorHostName
        return Section(L("ble_successor_join_title")) {
            Text(L("ble_successor_join_message", name))
            Button(L("ble_successor_join_confirm")) { model.acceptSuccessor() }
                .accessibilityIdentifier("shared_successor_join")
            Button(LI("shared_successor_decline"), role: .cancel) { model.declineSuccessor() }
        }
    }

    // MARK: Transport

    private var transportSection: some View {
        let count: Int = ui.rows.count
        let position: Int = Int(ui.currentIndex) + 1
        return Section {
            HStack {
                Button { model.previous() } label: { Image(systemName: "backward.end.fill") }
                    .disabled(!ui.hasPrevious)
                    .accessibilityLabel(L("action_back"))
                Spacer()
                if count > 0 && position > 0 {
                    Text("\(position) / \(count)").font(.subheadline.monospacedDigit())
                }
                Spacer()
                // The host stays the only writer to the wall; this asks it to light the climb again.
                Button { model.resend() } label: { Image(systemName: "lightbulb") }
                    .disabled(ui.currentIndex < 0)
                    .accessibilityLabel(L("ble_queue_resend"))
                Spacer()
                Button { model.next() } label: { Image(systemName: "forward.end.fill") }
                    .disabled(!ui.hasNext)
                    .accessibilityLabel(L("cd_next"))
            }
            .buttonStyle(.borderless)
            .font(.title3)
        }
    }

    // MARK: Queue

    @ViewBuilder
    private var queueSection: some View {
        Section {
            if ui.rows.isEmpty {
                Text(L("board_queue_empty")).foregroundStyle(.secondary)
            } else if ui.canEdit {
                editableRows
            } else {
                ForEach(ui.rows, id: \.index) { row in
                    SharedQueueRow(row: row)
                        .listRowBackground(rowBackground(row))
                }
            }
        }
    }

    private var editableRows: some View {
        ForEach(ui.rows, id: \.index) { row in
            Button { model.select(index: row.index) } label: { SharedQueueRow(row: row) }
                .buttonStyle(.plain)
                .listRowBackground(rowBackground(row))
        }
        .onDelete { offsets in
            // The host applies each removal and broadcasts the queue; one at a time from the end.
            for offset in offsets.sorted(by: >) { model.remove(index: Int32(offset)) }
        }
        .onMove { source, destination in
            guard let from = source.first else { return }
            let to: Int = destination > from ? destination - 1 : destination
            model.move(from: Int32(from), to: Int32(to))
        }
    }

    private func rowBackground(_ row: SharedQueueRowUi) -> Color? {
        row.isCurrent ? Color.orange.opacity(0.15) : nil
    }

    // MARK: Leave / close

    @ViewBuilder
    private var actionSection: some View {
        Section {
            if ui.phaseCode == "ended" {
                Button(L("action_close")) { model.dismissEnded() }
                    .frame(maxWidth: .infinity)
            } else {
                Button(L("board_queue_leave_session"), role: .destructive) { model.leave() }
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("shared_session_leave")
            }
        }
    }
}

private struct SharedQueueRow: View {
    let row: SharedQueueRowUi

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: row.isCurrent ? "play.circle.fill" : "circle")
                .foregroundStyle(row.isCurrent ? Color.orange : Color.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.name)
                    .font(row.isCurrent ? Font.body.weight(.bold) : Font.body)
                    .lineLimit(1)
                Text(detail).font(.footnote).foregroundStyle(.secondary)
            }
            Spacer()
        }
        .contentShape(Rectangle())
        .accessibilityAddTraits(row.isCurrent ? AccessibilityTraits.isSelected : AccessibilityTraits())
    }

    private var detail: String {
        let angle: String = "\(row.angle)°"
        return row.grade.isEmpty ? angle : "\(row.grade) · \(angle)"
    }
}

/// The browser row's "+" while an Android-hosted playlist is joined: asks the host
/// to append the climb (Android routes `addClimb` of a participant the same way).
/// Not placed by this package — see the wiring note of the sessionjoin report.
struct SharedSessionAddButton: View {
    let model: SharedSessionsModel
    let ui: SharedSessionsState
    let climbUuid: String
    let angle: Int32

    var body: some View {
        if ui.canEdit && model.canAdd(uuid: climbUuid) {
            Button { model.add(uuid: climbUuid, angle: angle) } label: {
                Image(systemName: "plus.circle")
            }
            .buttonStyle(.borderless)
            .foregroundStyle(Color.orange)
            .accessibilityLabel(LI("shared_add_to_playlist"))
        }
    }
}
