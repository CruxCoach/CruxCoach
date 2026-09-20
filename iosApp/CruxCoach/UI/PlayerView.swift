import CruxCoachCore
import SwiftUI

/// Playlist player: walks a list climb by climb, lights each one on the board
/// and counts down the rests. Local playback only — no shared sessions.
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
            let model = core.makePlayerScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.play(listName: listName,
                       climbUuids: climbUuids,
                       angles: angles.map { KotlinInt(int: $0) },
                       restsAfter: restsAfter.map { KotlinInt(int: $0) },
                       orderCode: orderCode,
                       advanceCode: advanceCode,
                       defaultRestSeconds: defaultRestSeconds)
        }
        .onDisappear { host?.model.stop() }
    }

    @ViewBuilder
    private func screen(model: PlayerScreenModel, ui: PlayerScreenState) -> some View {
        VStack(spacing: 20) {
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
            Button(LI("player_stop"), role: .destructive) { model.stop(); dismiss() }
        }
        .padding()
    }

    private func timeString(_ seconds: Int) -> String {
        String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}
