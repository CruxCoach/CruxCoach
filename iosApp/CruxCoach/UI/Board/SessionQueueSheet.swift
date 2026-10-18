import CruxCoachCore
import SwiftUI

/// Android `SessionQueueSheet` for the running playlist: the queued climbs,
/// the current one highlighted; tap to jump there, swipe to remove, drag to
/// reorder (Edit), previous / next, and "End playlist", which goes through
/// the player so the summary appears.
struct SessionQueueSheet: View {
    let core: AppCore
    let onEndPlaylist: () -> Void
    let onClose: () -> Void

    @State private var host: ScreenHost<BoardSessionHub, SessionQueueSheetState>?

    var body: some View {
        Group {
            if let host {
                content(hub: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("board_queue_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(L("action_close")) { onClose() }
            }
            ToolbarItem(placement: .primaryAction) { EditButton() }
        }
        .task {
            guard host == nil else { return }
            let hub = core.boardSessionHub
            // App-wide hub: only the subscription ends with the sheet.
            host = ScreenHost(model: hub, initial: hub.queueState,
                              subscribe: { model, onState in model.watchQueue(onState: onState) })
        }
    }

    @ViewBuilder
    private func content(hub: BoardSessionHub, ui: SessionQueueSheetState) -> some View {
        List {
            Section { transport(hub: hub, ui: ui) }
            Section { rows(hub: hub, ui: ui) }
            Section {
                Button(L("board_queue_end_session"), role: .destructive) { onEndPlaylist() }
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("board_queue_end")
            }
        }
    }

    private func transport(hub: BoardSessionHub, ui: SessionQueueSheetState) -> some View {
        let count: Int = ui.rows.count
        let position: Int = Int(ui.currentIndex) + 1
        return HStack {
            Button { hub.previous() } label: { Image(systemName: "backward.end.fill") }
                .disabled(!ui.hasPrevious)
                .accessibilityLabel(L("action_back"))
            Spacer()
            if count > 0 && position > 0 {
                Text("\(position) / \(count)").font(.subheadline.monospacedDigit())
            }
            Spacer()
            Button { hub.next() } label: { Image(systemName: "forward.end.fill") }
                .disabled(!ui.hasNext)
                .accessibilityLabel(L("cd_next"))
        }
        .buttonStyle(.borderless)
        .font(.title3)
    }

    @ViewBuilder
    private func rows(hub: BoardSessionHub, ui: SessionQueueSheetState) -> some View {
        if ui.rows.isEmpty {
            Text(L("board_queue_empty_editor")).foregroundStyle(.secondary)
        } else {
            ForEach(ui.rows, id: \.index) { row in
                SessionQueueRow(row: row) { hub.selectQueueClimb(index: row.index) }
            }
            .onDelete { offsets in
                // One at a time from the end, so earlier indices stay valid.
                for offset in offsets.sorted(by: >) { hub.removeQueueClimb(index: Int32(offset)) }
            }
            .onMove { source, destination in
                guard let from = source.first else { return }
                // SwiftUI's destination counts the gap before the item; Android moves to an index.
                let to: Int = destination > from ? destination - 1 : destination
                hub.moveQueueClimb(from: Int32(from), to: Int32(to))
            }
        }
    }
}

private struct SessionQueueRow: View {
    let row: SessionQueueRowUi
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
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
        }
        .buttonStyle(.plain)
        .listRowBackground(row.isCurrent ? Color.orange.opacity(0.15) : nil)
        .accessibilityAddTraits(row.isCurrent ? AccessibilityTraits.isSelected : AccessibilityTraits())
    }

    private var detail: String {
        let angle: String = "\(row.angle)°"
        return row.grade.isEmpty ? angle : "\(row.grade) · \(angle)"
    }
}

/// The player's way into the queue sheet (Android `PlayerControls` → `onOpenQueue`).
/// "End playlist" in the sheet ends the run once the sheet has closed, so the
/// summary sheet does not collide with the one being dismissed.
struct PlayerQueueAccess: ViewModifier {
    let core: AppCore
    let isActive: Bool
    let onEndPlaylist: () -> Void

    @State private var showQueue = false
    @State private var endWhenClosed = false

    func body(content: Content) -> some View {
        content
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { showQueue = true } label: {
                        Label(L("board_queue_title"), systemImage: "list.bullet")
                    }
                    .disabled(!isActive)
                    .accessibilityIdentifier("player_queue")
                }
            }
            .sheet(isPresented: $showQueue, onDismiss: endIfAsked) {
                NavigationStack {
                    SessionQueueSheet(core: core,
                                      onEndPlaylist: { endWhenClosed = true; showQueue = false },
                                      onClose: { showQueue = false })
                }
            }
    }

    private func endIfAsked() {
        guard endWhenClosed else { return }
        endWhenClosed = false
        onEndPlaylist()
    }
}
