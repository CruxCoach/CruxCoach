import CruxCoachCore
import SwiftUI

/// Android `formatSpeed`: "5s", "2.5s".
func routeSpeedText(_ seconds: Double) -> String {
    if seconds == seconds.rounded() {
        return "\(Int(seconds))s"
    }
    return String(format: "%.1fs", seconds)
}

/// Android `RoutePlaybackControls`: a route's hold sections one after another,
/// on screen and — while no playlist owns the wall — on the board. Shown only
/// for a route (more than one section).
struct RoutePlaybackControls: View {
    let model: RoutePlaybackModel
    let ui: RoutePlaybackUiState

    var body: some View {
        VStack(spacing: 8) {
            header
            if !ui.canReachBoard {
                Text(L("route_playback_board_busy"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            transport
            if ui.showDots { dots }
            speed
        }
        .padding(12)
        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityIdentifier("route_playback_controls")
    }

    private var header: some View {
        let frame: Int = Int(ui.frameNumber)
        let total: Int = Int(ui.totalFrames)
        return HStack {
            Button { model.togglePreview() } label: {
                Image(systemName: ui.showPreview ? "eye.slash" : "eye").frame(width: 36, height: 36)
            }
            .foregroundStyle(ui.showPreview ? Color.orange : Color.secondary)
            .accessibilityLabel(L(ui.showPreview ? "cd_preview_off" : "cd_preview_on"))
            Spacer()
            Text(L("board_playback_frame", frame, total))
                .font(.subheadline.weight(.bold).monospacedDigit())
            Spacer()
            Button { model.toggleLoop() } label: {
                Image(systemName: "repeat").frame(width: 36, height: 36)
            }
            .foregroundStyle(ui.isLooping ? Color.orange : Color.secondary)
            .accessibilityLabel(L(ui.isLooping ? "cd_loop_off" : "cd_loop_on"))
        }
        .buttonStyle(.borderless)
    }

    private var transport: some View {
        HStack(spacing: 4) {
            step("backward.end.fill", L("cd_first_frame"), enabled: ui.canFirst) { model.firstFrame() }
            step("arrow.left", L("cd_previous_frame"), enabled: ui.canPrevious) { model.previousFrame() }
            playButton
            step("arrow.right", L("cd_next_frame"), enabled: ui.canNext) { model.nextFrame() }
            step("forward.end.fill", L("cd_last_frame"), enabled: ui.canLast) { model.lastFrame() }
        }
        .buttonStyle(.borderless)
        .font(.title3)
    }

    private func step(_ symbol: String, _ label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).frame(width: 40, height: 40)
        }
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    private var playButton: some View {
        Button { model.togglePlay() } label: {
            Image(systemName: ui.isPlaying ? "stop.fill" : "play.fill")
                .font(.title2)
                .frame(width: 48, height: 48)
                .foregroundStyle(Color.white)
                .background(Circle().fill(Color.orange))
        }
        .disabled(!ui.canTogglePlay)
        .opacity(ui.canTogglePlay ? 1 : 0.4)
        .accessibilityLabel(L(ui.isPlaying ? "cd_stop" : "cd_play"))
        .accessibilityIdentifier("route_playback_play")
    }

    private var dots: some View {
        let total: Int = Int(ui.totalFrames)
        let current: Int = Int(ui.currentFrameIndex)
        let indices: [Int] = Array(0..<max(total, 0))
        return HStack(spacing: 4) {
            ForEach(indices, id: \.self) { index in
                dot(index: index, current: current)
            }
        }
    }

    private func dot(index: Int, current: Int) -> some View {
        let selected: Bool = index == current
        let size: CGFloat = selected ? 10 : 8
        return Circle()
            .fill(selected ? Color.orange : Color.secondary.opacity(0.3))
            .frame(width: size, height: size)
            .contentShape(Rectangle().inset(by: -4))
            .onTapGesture {
                if !ui.isPlaying { model.goToFrame(index: Int32(index)) }
            }
            .accessibilityHidden(true)
    }

    private var speed: some View {
        let value: Double = ui.speedSeconds
        let range: ClosedRange<Double> = ui.minSpeedSeconds...ui.maxSpeedSeconds
        return HStack(spacing: 8) {
            Text(L("board_playback_speed")).font(.caption).foregroundStyle(.secondary)
            Slider(value: Binding(get: { value }, set: { model.setSpeed(seconds: $0) }), in: range)
                .tint(Color.orange)
            Text(routeSpeedText(value))
                .font(.caption.weight(.bold).monospacedDigit())
                .foregroundStyle(Color.orange)
        }
    }
}

/// Android's countdown over the board before the sections start changing.
struct RoutePlaybackCountdown: View {
    let seconds: Int32

    var body: some View {
        if seconds > 0 {
            Text("\(seconds)")
                .font(.system(size: 72, weight: .bold, design: .rounded).monospacedDigit())
                .foregroundStyle(Color.white)
                .padding(24)
                .background(Circle().fill(Color.black.opacity(0.55)))
                .accessibilityLabel(L("settings_playback_countdown"))
                .accessibilityValue("\(seconds)")
        }
    }
}

/// Android `RoutePlaybackSection` of the settings screen.
struct RoutePlaybackSettingsSection: View {
    let core: AppCore
    @State private var host: ScreenHost<RoutePlaybackSettingsModel, RoutePlaybackSettingsState>?

    var body: some View {
        Section {
            if let host {
                rows(model: host.model, ui: host.state)
            } else {
                ProgressView().task { load() }
            }
        } header: {
            Text(L("settings_playback_title"))
        } footer: {
            Text(L("settings_playback_desc"))
        }
    }

    private func load() {
        guard host == nil else { return }
        let model = core.makeRoutePlaybackSettings()
        host = ScreenHost(model: model, initial: model.currentState,
                          subscribe: { model, onState in model.watch(onState: onState) },
                          onClose: { model in model.close() })
    }

    @ViewBuilder
    private func rows(model: RoutePlaybackSettingsModel, ui: RoutePlaybackSettingsState) -> some View {
        Toggle(isOn: Binding(get: { ui.useSetterSpeed }, set: { model.setUseSetterSpeed(value: $0) })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(L("settings_playback_setter_speed"))
                Text(L("settings_playback_setter_speed_desc")).font(.caption).foregroundStyle(.secondary)
            }
        }
        speedRow(model: model, ui: ui)
        Toggle(isOn: Binding(get: { ui.countdown }, set: { model.setCountdown(value: $0) })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(L("settings_playback_countdown"))
                Text(L("settings_playback_countdown_desc")).font(.caption).foregroundStyle(.secondary)
            }
        }
        if ui.countdown { countdownRow(model: model, ui: ui) }
        Toggle(isOn: Binding(get: { ui.autoLoop }, set: { model.setAutoLoop(value: $0) })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(L("settings_playback_auto_loop"))
                Text(L("settings_playback_auto_loop_desc")).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func speedRow(model: RoutePlaybackSettingsModel, ui: RoutePlaybackSettingsState) -> some View {
        let label: String = routeSpeedText(ui.frameSpeedSeconds)
        let title: String = ui.useSetterSpeed
            ? L("settings_playback_fallback_speed", label)
            : L("settings_playback_frame_speed", label)
        let hint: String = ui.useSetterSpeed
            ? L("settings_playback_fallback_speed_desc")
            : L("settings_playback_frame_speed_desc")
        let range: ClosedRange<Double> = ui.minSpeedSeconds...ui.maxSpeedSeconds
        return VStack(alignment: .leading, spacing: 4) {
            Text(title)
            Text(hint).font(.caption).foregroundStyle(.secondary)
            Slider(value: Binding(get: { ui.frameSpeedSeconds }, set: { model.setFrameSpeed(seconds: $0) }), in: range)
                .accessibilityLabel(title)
        }
    }

    private func countdownRow(model: RoutePlaybackSettingsModel, ui: RoutePlaybackSettingsState) -> some View {
        let seconds: Int = Int(ui.countdownSeconds)
        let title: String = L("settings_playback_countdown_duration", seconds)
        let low: Int = Int(ui.minCountdownSeconds)
        let high: Int = Int(ui.maxCountdownSeconds)
        return Stepper(title,
                       value: Binding(get: { seconds }, set: { model.setCountdownSeconds(seconds: Int32($0)) }),
                       in: low...high)
    }
}
