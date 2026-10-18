import CruxCoachCore
import SwiftUI

/// Performance values (Android `BenchmarksScreen`): the key exercises and every other one with a
/// value, per hand where it matters; enter, delete, or start a short test (which opens the player
/// and then the summary), or answer the quick estimate. Push it inside a `NavigationStack`; it has
/// none of its own.
struct ProgressBenchmarksView: View {
    let core: AppCore

    @State private var host: ScreenHost<BenchmarksScreenModel, BenchmarksScreenState>?

    init(core: AppCore) {
        self.core = core
    }

    var body: some View {
        Group {
            if let host {
                BenchmarksContent(core: core, model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("trbm_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard host == nil, let model = core.makeBenchmarksScreen(language: TrainingStyle.catalogLanguage) else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }
}

private struct BenchmarkEntryTarget: Identifiable {
    let row: BenchmarkRowUi
    var id: String { row.slug }
}

private struct BenchmarkDeleteTarget: Identifiable {
    let side: BenchmarkSideUi
    var id: String { side.id }
}

private struct BenchmarkSummaryTarget: Identifiable {
    let id: String
}

private struct BenchmarksContent: View {
    let core: AppCore
    let model: BenchmarksScreenModel
    let ui: BenchmarksScreenState

    @State private var entry: BenchmarkEntryTarget?
    @State private var deleting: BenchmarkDeleteTarget?
    @State private var showWeight = false
    @State private var estimating = false
    @State private var playerOpen = false
    @State private var finishedId: String?
    @State private var summary: BenchmarkSummaryTarget?

    var body: some View {
        Group {
            if ui.loading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        if ui.needsWeight { weightCard }
                        Button(L("trc_quick_estimate")) { estimating = true }
                            .buttonStyle(.bordered)
                        Text(L("trc_quick_estimate_hint")).font(.caption).foregroundStyle(.secondary)
                        Text(L("trbm_key_title")).font(.headline).padding(.top, 8)
                        ForEach(ui.key, id: \.slug) { row in card(row) }
                        if !ui.others.isEmpty {
                            Text(L("trbm_other_title")).font(.headline).padding(.top, 8)
                            ForEach(ui.others, id: \.slug) { row in card(row) }
                        }
                        Spacer(minLength: 24)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                ProgressInfoButton(title: L("trbm_title"), text: L("trbm_info"))
            }
        }
        .sheet(item: $entry) { target in
            BenchmarkEntrySheet(model: model, row: target.row, grips: ui.grips) { entry = nil }
        }
        .sheet(isPresented: $estimating) {
            ProgressQuickEstimateSheet(core: core) { estimating = false }
        }
        .sheet(isPresented: $showWeight) {
            BenchmarkWeightSheet(unitsCode: ui.unitsCode, model: model) { showWeight = false }
                .presentationDetents([.medium])
        }
        .alert(L("trbm_delete_title"), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button(L("tr_action_delete"), role: .destructive) {
                if let target = deleting { model.deleteValue(id: target.side.id) }
                deleting = nil
            }
            Button(L("tr_action_cancel"), role: .cancel) { deleting = nil }
        } message: {
            Text((deleting.map { TrRender.text($0.side.summary) } ?? "") + "\n\n" + L("trbm_delete_text"))
        }
        .onChange(of: ui.testStarted) { _, started in
            guard started else { return }
            model.consumeTestStarted()
            playerOpen = true
        }
        .fullScreenCover(isPresented: $playerOpen, onDismiss: {
            // The summary comes up once the player is gone: two presentations at once drop the second.
            if let id = finishedId {
                finishedId = nil
                summary = BenchmarkSummaryTarget(id: id)
            }
        }) {
            WorkoutPlayerView(core: core, onClose: { playerOpen = false }, onFinished: { id in
                finishedId = id
                playerOpen = false
            }, onOpenNutrition: { playerOpen = false })
        }
        .sheet(item: $summary) { target in
            WorkoutSummaryView(core: core, workoutId: target.id, onClose: { summary = nil },
                               onOpenNutrition: { summary = nil })
        }
    }

    private var weightCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(L("tru_setup_weight"), systemImage: "scalemass").font(.subheadline.weight(.semibold))
            Text(L("trbm_needs_weight")).font(.caption).foregroundStyle(.secondary)
            Button(L("tru_enter_weight")) { showWeight = true }.buttonStyle(.borderedProminent)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(TrainingStyle.caution.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
    }

    private func card(_ row: BenchmarkRowUi) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(row.name).font(.headline)
                Spacer()
                if let learning = TrRender.optional(row.learning) {
                    Text(learning)
                        .font(.caption)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Color(.tertiarySystemFill), in: Capsule())
                }
            }
            ForEach(row.sides, id: \.sideCode) { side in
                VStack(alignment: .leading, spacing: 2) {
                    Text(TrRender.text(side.prefix) + TrRender.text(side.summary)).font(.subheadline)
                    if side.hasValue {
                        HStack {
                            Text(tr: side.meta).font(.caption).foregroundStyle(.secondary)
                            Spacer()
                            Button { deleting = BenchmarkDeleteTarget(side: side) } label: {
                                Image(systemName: "trash").font(.caption)
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(L("trbm_delete"))
                        }
                        if let hint = TrRender.optional(side.plausibility) {
                            Text(hint).font(.caption).foregroundStyle(Color.accentColor)
                        }
                    }
                }
            }
            if row.paused {
                Text(L("trbm_paused_injury")).font(.caption).foregroundStyle(.red)
            }
            HStack(spacing: 8) {
                Button(L("trbm_enter")) { entry = BenchmarkEntryTarget(row: row) }
                    .buttonStyle(.borderedProminent)
                Button { model.startTest(slug: row.slug) } label: {
                    Label(L("trbm_test"), systemImage: "speedometer")
                }
                .buttonStyle(.bordered)
                .disabled(row.paused)
            }
            .padding(.top, 4)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }
}

/// "What can you do?" for one exercise with a live preview (Android `BenchmarkEntryDialog`).
private struct BenchmarkEntrySheet: View {
    let model: BenchmarksScreenModel
    let row: BenchmarkRowUi
    let grips: [BenchmarkGripUi]
    let onDone: () -> Void

    // Owner 2026-10-15: sets with reserve are the default.
    @State private var several = true
    @State private var sets = "3"
    @State private var load = ""
    @State private var reps = ""
    @State private var seconds = ""
    @State private var edge = ""
    @State private var grip = ""
    @State private var side = ""
    @State private var started = false

    private var preview: BenchmarkPreviewUi {
        model.preview(slug: row.slug, several: several, sets: sets, load: load, reps: reps, seconds: seconds,
                      edge: edge, gripCode: grip, sideCode: side)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("", selection: $several) {
                        Text(L("trbm_mode_sets")).tag(true)
                        Text(L("trbm_mode_max")).tag(false)
                    }
                    .pickerStyle(.segmented)
                    Text(tr: several ? row.hintSets : row.hintMax).font(.caption).foregroundStyle(.secondary)
                }
                Section {
                    if several {
                        LabeledContent(L("trbm_sets")) {
                            TextField("", text: $sets).keyboardType(.numberPad).multilineTextAlignment(.trailing)
                        }
                    }
                    if row.unilateral {
                        Picker("", selection: $side) {
                            Text(L("tr_side_left")).tag("left")
                            Text(L("tr_side_right")).tag("right")
                        }
                        .pickerStyle(.segmented)
                        .disabled(!row.allowedSide.isEmpty)
                    }
                    if row.needsLoad {
                        VStack(alignment: .leading, spacing: 2) {
                            LabeledContent(TrRender.text(row.loadLabel)) {
                                TextField("", text: $load).keyboardType(.numbersAndPunctuation).multilineTextAlignment(.trailing)
                            }
                            if row.loadIsAdded {
                                Text(L("trbm_load_added_hint")).font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                    }
                    if row.kindCode == "e1rm" || row.kindCode == "reps" {
                        LabeledContent(L("trbm_reps")) {
                            TextField("", text: $reps).keyboardType(.numberPad).multilineTextAlignment(.trailing)
                        }
                    }
                    if row.kindCode == "ten" || row.kindCode == "seconds" {
                        LabeledContent(L("trbm_seconds")) {
                            TextField("", text: $seconds).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                        }
                    }
                    if row.showEdge {
                        LabeledContent(L("trbm_edge")) {
                            TextField("", text: $edge).keyboardType(.numberPad).multilineTextAlignment(.trailing)
                        }
                        TrainingFlow(spacing: 6) {
                            ForEach(grips, id: \.code) { g in
                                TrainingChoiceChip(title: TrRender.text(g.label), selected: grip == g.code) {
                                    grip = grip == g.code ? "" : g.code
                                }
                            }
                        }
                    }
                }
                let p = preview
                if p.valid {
                    Section {
                        Text(tr: p.summary).font(.subheadline.weight(.semibold))
                        if let planned = TrRender.optional(p.planned) {
                            Text(planned).font(.caption).foregroundStyle(.secondary)
                        }
                        if let hint = TrRender.optional(p.plausibility) {
                            Text(hint).font(.caption).foregroundStyle(Color.accentColor)
                        }
                    }
                }
            }
            .navigationTitle(row.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel"), action: onDone) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("tr_action_save")) {
                        if model.saveEntry(slug: row.slug, several: several, sets: sets, load: load, reps: reps,
                                           seconds: seconds, edge: edge, gripCode: grip, sideCode: side) {
                            onDone()
                        }
                    }
                    .disabled(!preview.valid)
                }
            }
        }
        .onAppear {
            guard !started else { return }
            started = true
            seconds = row.defaultSeconds
            edge = row.defaultEdge
            grip = row.defaultGrip
            side = row.unilateral ? (row.allowedSide.isEmpty ? "right" : row.allowedSide) : ""
        }
    }
}

/// Body weight first, where the page asks for it (Android `WeightSheet`).
private struct BenchmarkWeightSheet: View {
    let unitsCode: String
    let model: BenchmarksScreenModel
    let onDone: () -> Void
    @State private var text = ""
    @State private var invalid = false

    var body: some View {
        NavigationStack {
            Form {
                Text(L("tru_weight_reason_setup")).font(.caption).foregroundStyle(.secondary)
                HStack {
                    TextField(L("trq_weight"), text: $text).keyboardType(.decimalPad)
                    Text(unitsCode == "imperial" ? "lb" : "kg").foregroundStyle(.secondary)
                }
                if invalid { Text(LI("training_weight_invalid")).font(.caption).foregroundStyle(.red) }
            }
            .navigationTitle(L("tru_enter_weight"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel"), action: onDone) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("tr_action_save")) {
                        if let v = TrRender.parse(text), model.logWeight(display: v) { onDone() } else { invalid = true }
                    }
                }
            }
        }
    }
}
