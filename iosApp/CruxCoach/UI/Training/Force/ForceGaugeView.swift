import Charts
import CruxCoachCore
import SwiftUI

/// The experimental force gauge (Android `ForceGaugeScreen`, Tindeq Progressor): connect, tare,
/// a live curve of the last ten seconds and a 7-s max pull with countdown; peak, best 5 s and
/// % body weight, saved as a pick-up test value. Push it inside a `NavigationStack`. Leaving the
/// screen stops the stream and disconnects (the model is closed with the host).
struct ForceGaugeView: View {
    let core: AppCore

    @State private var host: ScreenHost<ForceGaugeModel, ForceGaugeState>?

    var body: some View {
        Group {
            if let host {
                ForceGaugeContent(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("trd_force_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard host == nil, let model = core.makeForceGaugeScreen() else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }
}

private struct ForceGaugeContent: View {
    let model: ForceGaugeModel
    let ui: ForceGaugeState

    var body: some View {
        Form {
            Section {
                Text(L("trd_force_experimental")).font(.footnote)
            }
            if ui.loading {
                Section { ProgressView().frame(maxWidth: .infinity) }
            } else if !ui.enabled {
                enableSection
            } else {
                connectionSection
                if ui.statusCode == "connected" {
                    liveSection
                    choiceSection
                    pullSection
                }
            }
        }
    }

    private var enableSection: some View {
        Section {
            Text(L("trd_force_enable_hint"))
            Button(L("trd_force_enable")) { model.enable() }
                .accessibilityIdentifier("force_enable")
        }
    }

    // MARK: Connection

    private var connectionSection: some View {
        Section {
            Text(tr: ui.statusText).font(.subheadline.weight(.semibold))
            if ui.lowPower {
                Text(L("trd_force_low_power")).font(.footnote).foregroundStyle(.red)
            }
            connectionActions
        }
    }

    @ViewBuilder
    private var connectionActions: some View {
        switch ui.statusCode {
        case "connected":
            Button(L("trd_force_tare")) { model.tare() }
                .accessibilityIdentifier("force_tare")
            Button(L("trd_force_disconnect"), role: .destructive) { model.disconnect() }
        case "scanning", "connecting":
            ProgressView().frame(maxWidth: .infinity)
        default:
            Button(L("trd_force_connect")) { model.connect() }
                .accessibilityIdentifier("force_connect")
        }
    }

    // MARK: Live curve

    private var liveSection: some View {
        let peak: String? = TrRender.optional(ui.peakText)
        return Section {
            HStack(alignment: .lastTextBaseline, spacing: 12) {
                Text(tr: ui.liveText)
                    .font(.largeTitle.weight(.semibold))
                    .monospacedDigit()
                    .accessibilityIdentifier("force_live")
                if let peak {
                    Text(peak).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            ForceCurveChart(points: ui.curve, top: ui.curveMax, peak: ui.curvePeak,
                            description: TrRender.text(ui.curveDescription))
            Text(L("trd_force_setup_hint")).font(.footnote).foregroundStyle(.secondary)
        }
    }

    // MARK: Hand and edge

    private var choiceSection: some View {
        let pulling: Bool = ui.phaseCode == "pulling"
        let hand = Binding<String>(get: { ui.handCode }, set: { model.setHand(code: $0) })
        let edge: Int32 = ui.edgeMm
        return Section {
            Picker(L("trd_force_hand"), selection: hand) {
                Text(L("trd_force_hand_left")).tag("left")
                Text(L("trd_force_hand_right")).tag("right")
                Text(L("trd_force_hand_both")).tag("both")
            }
            .pickerStyle(.segmented)
            .disabled(pulling)
            Stepper(TrRender.text(ui.edgeText),
                    onIncrement: { model.setEdge(mm: edge + 1) },
                    onDecrement: { model.setEdge(mm: edge - 1) })
        } header: {
            Text(L("trd_force_hand"))
        }
    }

    // MARK: Pull

    private var pullSection: some View {
        Section {
            pullBody
        } header: {
            Text(tr: ui.pullTitle)
        }
    }

    @ViewBuilder
    private var pullBody: some View {
        switch ui.phaseCode {
        case "countdown":
            Text(tr: ui.countdownText).font(.title.weight(.semibold))
            Button(L("trd_force_cancel")) { model.cancelPull() }
        case "pulling":
            Text(tr: ui.pullingText)
                .font(.title.weight(.semibold))
                .foregroundStyle(Color.accentColor)
                .accessibilityIdentifier("force_pulling")
            Button(L("trd_force_cancel")) { model.cancelPull() }
        case "done":
            resultRows
            Button(L("trd_force_again")) { model.startPull() }
                .accessibilityIdentifier("force_again")
        default:
            Button(L("trd_force_start")) { model.startPull() }
                .accessibilityIdentifier("force_start")
        }
    }

    @ViewBuilder
    private var resultRows: some View {
        if !ui.hasResult {
            Text(L("trd_force_no_data"))
        } else {
            Text(tr: ui.resultPeak)
            Text(tr: ui.resultMean).accessibilityIdentifier("force_result")
            if let percent = TrRender.optional(ui.resultPercent) {
                Text(percent).font(.footnote).foregroundStyle(.secondary)
            }
            if ui.saved {
                Text(L("trd_force_saved"))
                    .foregroundStyle(Color.accentColor)
                    .accessibilityIdentifier("force_saved")
            } else {
                Button(L("trd_force_save")) { model.save() }
                    .disabled(!ui.canSave)
                    .accessibilityIdentifier("force_save")
            }
        }
    }
}

/// The last ten seconds of force (Android `ForceCurve`): the line and a dashed peak line.
private struct ForceCurveChart: View {
    let points: [ForcePointUi]
    let top: Double
    let peak: Double
    let description: String

    var body: some View {
        Chart {
            ForEach(points.indices, id: \.self) { i in
                LineMark(x: .value(L("trd_force_title"), points[i].seconds),
                         y: .value(L("trd_force_title"), points[i].value))
                    .interpolationMethod(.linear)
            }
            if peak >= 0 {
                RuleMark(y: .value(L("trd_force_title"), peak))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [6, 4]))
                    .foregroundStyle(Color.orange)
            }
        }
        .chartXScale(domain: -10.0...0.0)
        .chartYScale(domain: 0.0...max(top, 1.0))
        .chartXAxis(.hidden)
        .frame(height: 160)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(description)
    }
}
