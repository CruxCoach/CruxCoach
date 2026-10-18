import CruxCoachCore
import SwiftUI

/// Playlist player: walks a list climb by climb, lights each one on the board
/// and counts down the rests. Local playback only — no shared sessions. The
/// playlist outlives this screen, as on Android ("Back = leave the screen; the
/// playlist keeps running"); Stop ends it.
struct PlayerView: View {
    let core: AppCore
    let listName: String
    let climbUuids: [String]
    let angles: [Int32]
    let restsAfter: [Int32]
    let orderCode: String
    let advanceCode: String
    let defaultRestSeconds: Int32

    @State private var host: ScreenHost<PlayerScreenModel, PlayerScreenState>?
    @State private var confirmEnd = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(listName)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard host == nil else { return }
            // Android's single playback coordinator: the app's one running playlist, with the
            // end-of-session summary. Leaving this screen keeps it running (the board screens
            // show its banner); opening the list that plays again resumes it. Never closed here.
            let hub = core.boardSessionHub
            let model = hub.player
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) })
            _ = hub.play(listName: listName,
                         climbUuids: climbUuids,
                         angles: angles.map { KotlinInt(int: $0) },
                         restsAfter: restsAfter.map { KotlinInt(int: $0) },
                         orderCode: orderCode,
                         advanceCode: advanceCode,
                         defaultRestSeconds: defaultRestSeconds)
        }
        .alert(L("board_session_end_title"), isPresented: $confirmEnd) {
            Button(L("action_cancel"), role: .cancel) {}
            Button(L("board_session_end_confirm"), role: .destructive) { host?.model.endSession() }
        }
        .sheet(isPresented: summaryBinding) {
            if let host {
                SessionSummarySheet(summary: host.state.summary) { closeSummary() }
            }
        }
    }

    private var summaryBinding: Binding<Bool> {
        Binding(get: { host?.state.showSummary ?? false },
                set: { if !$0 { closeSummary() } })
    }

    private func closeSummary() {
        host?.model.dismissSummary()
        dismiss()
    }

    @ViewBuilder
    private func screen(model: PlayerScreenModel, ui: PlayerScreenState) -> some View {
        VStack(spacing: 20) {
            if ui.sessionRunning {
                SessionBar(model: model)
            }
            if !ui.isActive {
                ContentUnavailableView(LI("player_finished"), systemImage: "checkmark.circle")
            } else {
                VStack(spacing: 6) {
                    Text(ui.currentClimbName).font(.title2).multilineTextAlignment(.center)
                    HStack(spacing: 10) {
                        if !ui.currentClimbGrade.isEmpty { Text(ui.currentClimbGrade) }
                        Text("\(ui.currentAngle)°")
                        if ui.attemptTotal > 1 {
                            Text(LI("player_attempt", Int(ui.attemptNumber), Int(ui.attemptTotal)))
                        }
                    }
                    .font(.subheadline).foregroundStyle(.secondary)
                    Text(LI("player_position", Int(ui.currentIndex) + 1, ui.queue.count))
                        .font(.footnote).foregroundStyle(.secondary)
                }

                if ui.phaseCode == "resting" {
                    VStack(spacing: 8) {
                        Text(timeString(Int(ui.restSecondsRemaining)))
                            .font(.system(size: 56, weight: .semibold, design: .rounded).monospacedDigit())
                            .accessibilityLabel(LI("player_rest_a11y", Int(ui.restSecondsRemaining)))
                        ProgressView(value: Double(max(ui.restTotalSeconds - ui.restSecondsRemaining, 0)),
                                     total: Double(max(ui.restTotalSeconds, 1)))
                        Button(LI("player_skip_rest")) { model.skipRest() }
                    }
                }

                if !ui.boardConnected {
                    Label(LI("player_no_board"), systemImage: "lightbulb.slash")
                        .font(.footnote).foregroundStyle(.orange)
                }

                HStack(spacing: 12) {
                    Button { model.climbLogged(isSend: false) } label: {
                        Label(LI("detail_attempt"), systemImage: "plus").frame(maxWidth: .infinity)
                    }
                    Button { model.climbLogged(isSend: true) } label: {
                        Label(LI("detail_sent"), systemImage: "checkmark").frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.bordered)

                HStack(spacing: 24) {
                    Button { model.previous() } label: { Image(systemName: "backward.fill") }
                        .disabled(!ui.hasPrevious)
                    Button { model.resendCurrentClimb() } label: {
                        Label(LI("detail_light_up"), systemImage: "lightbulb.max")
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!ui.boardConnected)
                    Button { model.next() } label: { Image(systemName: "forward.fill") }
                        .disabled(!ui.hasNext)
                }
                .font(.title3)
            }
            Spacer()
            if ui.sessionRunning {
                Button(LI("player_stop"), role: .destructive) { confirmEnd = true }
            } else {
                Button(LI("player_stop"), role: .destructive) { model.stop(); dismiss() }
            }
        }
        .padding()
    }

    private func timeString(_ seconds: Int) -> String {
        String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}
