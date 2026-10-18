import CruxCoachCore
import SwiftUI

/// Android's `RestTimerBannerSlot` on the climb detail: the running rest with
/// its countdown and progress, or "Rest done!", and a close button that
/// cancels a running rest or dismisses a finished one.
struct RestTimerBanner: View {
    let model: RestTimerModel
    let ui: RestTimerUiState

    var body: some View {
        if ui.phase != "idle" {
            banner
        }
    }

    /// Android: orange, yellow from 30 s, red from 10 s, green when finished.
    static func color(_ tone: String) -> Color {
        switch tone {
        case "finished": return .green
        case "critical": return .red
        case "warning": return .yellow
        default: return .orange
        }
    }

    private var banner: some View {
        let finished: Bool = ui.phase == "finished"
        let tint: Color = RestTimerBanner.color(ui.tone)
        return VStack(spacing: 4) {
            header(finished: finished, tint: tint)
            if !finished {
                ProgressView(value: Double(ui.progress))
                    .tint(tint)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
        .accessibilityIdentifier("detail_rest_timer")
    }

    private func header(finished: Bool, tint: Color) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "timer").foregroundStyle(tint)
            if finished {
                Text(L("rest_timer_done")).font(.subheadline.bold()).foregroundStyle(tint)
            } else {
                Text(ui.countdown).font(.subheadline.bold().monospacedDigit()).foregroundStyle(tint)
                Text(L("rest_timer_label")).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Button {
                model.closeBanner(finished: finished)
            } label: {
                Image(systemName: "xmark").font(.footnote)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(finished ? L("cd_dismiss") : L("cd_cancel_timer"))
        }
    }
}

/// Android's `RestTimerStartDialog`: a per-use rest length, pre-filled with the
/// settings default and not saved back to it (the setting stays the default and
/// the auto-start length). Minutes and seconds step like Android's
/// `DurationStepper`, between 5 s and one hour.
struct RestTimerStartSheet: View {
    let model: RestTimerModel
    let initialSeconds: Int32
    let onDone: () -> Void
    @State private var seconds: Int32 = 0
    @State private var loaded = false

    var body: some View {
        Form {
            Section {
                minuteRow
                secondRow
            } footer: {
                Text(model.countdownText(seconds: seconds)).font(.title2.monospacedDigit())
            }
        }
        .navigationTitle(L("rest_timer_set_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(L("action_cancel")) { onDone() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(L("rest_timer_start")) {
                    model.start(seconds: seconds)
                    onDone()
                }
                .accessibilityIdentifier("detail_rest_timer_start")
            }
        }
        .onAppear {
            if !loaded {
                seconds = model.clamp(seconds: initialSeconds)
                loaded = true
            }
        }
    }

    private var minuteRow: some View {
        let minutes: Int = Int(seconds / 60)
        return stepRow(label: L("settings_duration_minutes_label"), value: minutes, delta: 60,
                       decLabel: L("playlist_rest_decrease_minute"), incLabel: L("playlist_rest_increase_minute"))
    }

    private var secondRow: some View {
        let secs: Int = Int(seconds % 60)
        return stepRow(label: L("settings_duration_seconds_label"), value: secs, delta: 1,
                       decLabel: L("playlist_rest_decrease_second"), incLabel: L("playlist_rest_increase_second"))
    }

    private func stepRow(label: String, value: Int, delta: Int32, decLabel: String, incLabel: String) -> some View {
        let canDec: Bool = model.canStep(current: seconds, delta: -delta)
        let canInc: Bool = model.canStep(current: seconds, delta: delta)
        return HStack(spacing: 12) {
            Text(label)
            Spacer()
            Button {
                seconds = model.step(current: seconds, delta: -delta)
            } label: {
                Image(systemName: "minus.circle").font(.title3)
            }
            .disabled(!canDec)
            .accessibilityLabel(decLabel)
            Text("\(value)").font(.title3.monospacedDigit()).frame(minWidth: 36)
            Button {
                seconds = model.step(current: seconds, delta: delta)
            } label: {
                Image(systemName: "plus.circle").font(.title3)
            }
            .disabled(!canInc)
            .accessibilityLabel(incLabel)
        }
        .buttonStyle(.borderless)
    }
}

/// Android's `RestTimerBannerSlot` on the other board screens: the app's one running rest shows
/// on top of the screen it is attached to (nothing while no rest runs). Each screen observes the
/// shared timer only while it is visible; leaving never stops the rest.
struct RestTimerBannerSlot: ViewModifier {
    let core: AppCore
    @Environment(\.scenePhase) private var scenePhase
    @State private var rest: ScreenHost<RestTimerModel, RestTimerUiState>?

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .top, spacing: 0) {
                if let rest, rest.state.phase != "idle" {
                    RestTimerBanner(model: rest.model, ui: rest.state)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 4)
                }
            }
            .task {
                guard rest == nil else { return }
                let timer = core.makeBoardRestTimer()
                rest = ScreenHost(model: timer, initial: timer.currentState,
                                  subscribe: { model, onState in model.watch(onState: onState) },
                                  onClose: { model in model.detach() })
            }
            .onDisappear {
                rest?.close()
                rest = nil
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { rest?.model.refresh() }
            }
    }
}
