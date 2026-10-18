import CruxCoachCore
import SwiftUI

/// Android's board-screen session strip. While a playlist runs: the
/// `LocalPlaylistBrowserCard` banner (current climb, position, previous /
/// light / next, tap to reopen the player). While nothing runs and
/// [onRandomClimb] is given: Android's `SessionTimerBar` with its one action,
/// "Random climb". The playlist is the app's one running board session
/// (`AppCore.boardSessionHub`), so leaving the player does not end it.
///
/// Place it with `.safeAreaInset(edge: .top)` inside a NavigationStack: the
/// tap pushes the player onto that stack.
struct SessionPlaylistBanner: View {
    let core: AppCore
    var onRandomClimb: (() -> Void)? = nil

    @State private var host: ScreenHost<BoardSessionHub, SessionBannerState>?

    var body: some View {
        content
            .task {
                guard host == nil else { return }
                let hub = core.boardSessionHub
                // App-wide: never closed here, only the subscription ends with the view.
                host = ScreenHost(model: hub, initial: hub.bannerState,
                                  subscribe: { model, onState in model.watchBanner(onState: onState) })
            }
    }

    @ViewBuilder
    private var content: some View {
        if let host, host.state.isActive {
            SessionPlaylistCard(core: core, hub: host.model, ui: host.state)
        } else if let onRandomClimb {
            randomBar(onRandomClimb)
        } else {
            // Keeps the view (and its .task) alive while nothing runs.
            Color.clear.frame(height: 0)
        }
    }

    private func randomBar(_ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(L("board_random_climb"), systemImage: "shuffle")
                .font(.subheadline.weight(.bold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
        }
        .foregroundStyle(Color.orange)
        .background(Color.orange.opacity(0.06))
        .accessibilityIdentifier("board_random_climb")
    }
}

/// Android `LocalPlaylistBrowserCard`.
private struct SessionPlaylistCard: View {
    let core: AppCore
    let hub: BoardSessionHub
    let ui: SessionBannerState

    var body: some View {
        HStack(spacing: 6) {
            NavigationLink {
                runningPlayer
            } label: {
                titleRow
            }
            .buttonStyle(.plain)
            transport
        }
        .padding(.leading, 10)
        .padding(.trailing, 6)
        .padding(.vertical, 6)
        .background(cardBackground)
        .padding(.horizontal, 16)
        .padding(.vertical, 2)
        .accessibilityIdentifier("board_playlist_browser_card")
    }

    private var cardBackground: some View {
        RoundedRectangle(cornerRadius: 14)
            .fill(LinearGradient(colors: [Color.orange.opacity(0.22), Color.orange.opacity(0.06)],
                                 startPoint: .leading, endPoint: .trailing))
    }

    /// The running player; an empty list starts nothing, it only shows what plays.
    private var runningPlayer: some View {
        PlayerView(core: core, listName: ui.listName, climbUuids: [], angles: [], restsAfter: [],
                   orderCode: "", advanceCode: "", defaultRestSeconds: 0)
    }

    private var titleRow: some View {
        let name: String = ui.currentClimbName.isEmpty ? L("playlist_player_title") : ui.currentClimbName
        let position: Int = Int(ui.position)
        let total: Int = Int(ui.total)
        return HStack(spacing: 8) {
            Image(systemName: "music.note.list")
                .foregroundStyle(Color.orange)
                .padding(5)
                .background(Color.orange.opacity(0.16), in: RoundedRectangle(cornerRadius: 8))
            Text(name)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer(minLength: 4)
            if position > 0 && total > 0 {
                Text("\(position)/\(total)")
                    .font(.caption.weight(.bold).monospacedDigit())
                    .foregroundStyle(Color.orange)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint(L("cd_open"))
    }

    private var transport: some View {
        HStack(spacing: 2) {
            Button { hub.previous() } label: {
                Image(systemName: "backward.end.fill").frame(width: 32, height: 32)
            }
            .disabled(!ui.hasPrevious)
            .accessibilityLabel(L("cd_previous"))
            .accessibilityIdentifier("playlist_banner_previous")
            Button { hub.light() } label: {
                Image(systemName: "lightbulb.fill").frame(width: 32, height: 32)
            }
            .foregroundStyle(ui.canLight ? Color.orange : Color.secondary)
            .disabled(!ui.canLight)
            .accessibilityLabel(L("playlist_send_to_board"))
            .accessibilityIdentifier("playlist_banner_light")
            Button { hub.next() } label: {
                Image(systemName: "forward.end.fill").frame(width: 32, height: 32)
            }
            .disabled(!ui.hasNext)
            .accessibilityLabel(L("cd_next"))
            .accessibilityIdentifier("playlist_banner_next")
            Image(systemName: "chevron.right")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
        }
        .buttonStyle(.borderless)
        .font(.subheadline)
    }
}

/// Android `ClimbCard`'s add-to-playlist button: queues the climb at the browsed
/// angle into the running playlist; the badge counts the entries already queued.
struct SessionQueueAddButton: View {
    let hub: BoardSessionHub
    let ui: SessionBannerState
    let climbUuid: String
    let angle: Int32

    var body: some View {
        let count: Int = Int(hub.queuedCount(state: ui, climbUuid: climbUuid, angle: angle))
        return Button { hub.addToQueue(climbUuid: climbUuid, angle: angle) } label: {
            Image(systemName: "plus.circle.fill")
                .font(.title2)
                .foregroundStyle(Color.orange)
                .overlay(alignment: .topTrailing) { badge(count) }
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(L("board_playlist_add"))
        .accessibilityValue(count > 0 ? LP("board_playlist_already_queued", count) : "")
        .accessibilityIdentifier("board_climb_add_to_playlist")
    }

    @ViewBuilder private func badge(_ count: Int) -> some View {
        if count > 0 {
            Text("\(count)")
                .font(.caption2.weight(.bold))
                .foregroundStyle(Color.black)
                .padding(.horizontal, 4)
                .background(Color.orange, in: RoundedRectangle(cornerRadius: 6))
                .offset(x: 6, y: -6)
        }
    }
}
