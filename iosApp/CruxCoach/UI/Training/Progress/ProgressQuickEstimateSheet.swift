import CruxCoachCore
import SwiftUI

/// The quick estimate in two questions (Android `QuickEstimateSheet`): pull-ups, a two-hand hang
/// and, with a block, one-arm pick-ups per hand. Answers become values marked "estimated".
struct ProgressQuickEstimateSheet: View {
    let core: AppCore
    let onDone: () -> Void

    @State private var host: ScreenHost<QuickEstimateScreenModel, QuickEstimateScreenState>?

    init(core: AppCore, onDone: @escaping () -> Void) {
        self.core = core
        self.onDone = onDone
    }

    var body: some View {
        NavigationStack {
            Group {
                if let host {
                    QuickEstimateForm(model: host.model, ui: host.state, onDone: onDone)
                } else {
                    ProgressView()
                }
            }
            .navigationTitle(L("trc_estimate_title"))
            .navigationBarTitleDisplayMode(.inline)
        }
        .task {
            guard host == nil, let model = core.makeQuickEstimateScreen() else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }
}

private struct QuickEstimateForm: View {
    let model: QuickEstimateScreenModel
    let ui: QuickEstimateScreenState
    let onDone: () -> Void

    @State private var pull = ""
    @State private var weightedKg = ""
    @State private var weightedReps = ""
    @State private var hang = ""
    @State private var hangKg = ""
    @State private var leftKg = ""
    @State private var rightKg = ""
    @State private var bodyText = ""
    @State private var bodyInvalid = false

    var body: some View {
        Form {
            Section {
                Text(L("trc_estimate_intro") + " " + L("trc_estimate_note")).font(.subheadline).foregroundStyle(.secondary)
            }
            if ui.needsWeight {
                Section {
                    Text(L("trc_estimate_no_weight")).font(.caption).foregroundStyle(.secondary)
                    HStack {
                        TextField(L("tr_metric_weight"), text: $bodyText).keyboardType(.decimalPad)
                        Text(ui.massUnit).foregroundStyle(.secondary)
                        Button(L("tr_action_save")) {
                            if let v = TrRender.parse(bodyText), model.logWeight(display: v) { bodyInvalid = false } else { bodyInvalid = true }
                        }
                        .buttonStyle(.borderless)
                    }
                    if bodyInvalid { Text(LI("training_weight_invalid")).font(.caption).foregroundStyle(.red) }
                }
            }
            Section(L("trc_estimate_pull")) {
                TrainingFlow(spacing: 6) {
                    ForEach(ui.pullChoices, id: \.code) { c in
                        TrainingChoiceChip(title: TrRender.text(c.label), selected: pull == c.code) { pull = pull == c.code ? "" : c.code }
                    }
                }
                if pull == "WEIGHTED" {
                    HStack {
                        TextField(L("trc_estimate_added", ui.massUnit), text: $weightedKg).keyboardType(.decimalPad)
                        TextField(L("trc_estimate_reps"), text: $weightedReps).keyboardType(.numberPad)
                    }
                }
                if let hint = TrRender.optional(model.pullHint(pullCode: pull, weightedKg: weightedKg, weightedReps: weightedReps)) {
                    Text(hint).font(.caption).foregroundStyle(Color.accentColor)
                }
            }
            Section(L("trc_estimate_hang")) {
                if !ui.hangAllowed {
                    Text(L("trc_estimate_injury_skip")).font(.caption).foregroundStyle(.red)
                } else {
                    TrainingFlow(spacing: 6) {
                        ForEach(ui.hangChoices, id: \.code) { c in
                            TrainingChoiceChip(title: TrRender.text(c.label), selected: hang == c.code) { hang = hang == c.code ? "" : c.code }
                        }
                    }
                    if hang == "WEIGHTED" {
                        TextField(L("trc_estimate_added", ui.massUnit), text: $hangKg).keyboardType(.decimalPad)
                    }
                    if hang == "CANNOT" {
                        Text(L("trc_estimate_hang_cannot")).font(.caption).foregroundStyle(.secondary)
                    }
                    if let hint = TrRender.optional(model.hangHint(hangCode: hang, hangKg: hangKg)) {
                        Text(hint).font(.caption).foregroundStyle(Color.accentColor)
                    }
                }
            }
            if ui.showPickup {
                Section(L("trc_estimate_pickup")) {
                    HStack {
                        TextField(L("tr_side_left") + " (" + ui.massUnit + ")", text: $leftKg)
                            .keyboardType(.decimalPad)
                            .disabled(ui.pickupSide == "right")
                        TextField(L("tr_side_right") + " (" + ui.massUnit + ")", text: $rightKg)
                            .keyboardType(.decimalPad)
                            .disabled(ui.pickupSide == "left")
                    }
                    if !ui.pickupSide.isEmpty {
                        Text(L("trc_estimate_healthy_side")).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel"), action: onDone) }
            ToolbarItem(placement: .confirmationAction) {
                Button(L("tr_action_save")) {
                    _ = model.save(pullCode: pull, weightedKg: weightedKg, weightedReps: weightedReps, hangCode: hang,
                                   hangKg: hangKg, leftKg: leftKg, rightKg: rightKg)
                }
                .disabled(!model.canSave(pullCode: pull, hangCode: hang, leftKg: leftKg, rightKg: rightKg))
            }
        }
        .onChange(of: ui.saved) { _, saved in if saved { onDone() } }
    }
}
