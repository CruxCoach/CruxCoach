import CruxCoachCore
import SwiftUI

/// The end of a training (Android `WorkoutSummaryScreen`): the reward, the keep question, recovery
/// and food after the training, bests, next steps, own loads, patterns and the logged sets.
struct WorkoutSummaryView: View {
    let core: AppCore
    let workoutId: String
    let onClose: () -> Void
    let onOpenNutrition: () -> Void

    @State private var host: ScreenHost<WorkoutSummaryScreenModel, WorkoutSummaryState>?

    var body: some View {
        NavigationStack {
            Group {
                if let host {
                    WorkoutSummaryContent(model: host.model, ui: host.state, onClose: onClose, onOpenNutrition: onOpenNutrition)
                } else {
                    ProgressView()
                }
            }
            .navigationTitle(L("trw_summary_title"))
            .navigationBarTitleDisplayMode(.inline)
        }
        .task {
            guard host == nil, let model = core.makeWorkoutSummaryScreen(language: TrainingStyle.catalogLanguage) else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.load(workoutId: workoutId)
        }
    }
}

private struct WorkoutSummaryContent: View {
    let model: WorkoutSummaryScreenModel
    let ui: WorkoutSummaryState
    let onClose: () -> Void
    let onOpenNutrition: () -> Void

    @State private var confirmDelete = false
    @State private var askName = false
    @State private var routineName = ""
    @State private var swap: (from: String, to: String)?
    @State private var snack: TrainingSnack?
    @State private var snackAction: (() -> Void)?
    @State private var scale: CGFloat = 0.4

    var body: some View {
        ZStack(alignment: .bottom) {
            if ui.loading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if !ui.found {
                Text(L("trw_no_sets")).foregroundStyle(.secondary).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    Section { reward }
                    if ui.hasKeep { Section { keepCard } }
                    if ui.hasAfter { Section(L("tre_after_title")) { after } }
                    if !ui.records.isEmpty {
                        Section(L("trw_records_title")) {
                            ForEach(ui.records, id: \.slug) { record in
                                HStack {
                                    Image(systemName: "trophy.fill").foregroundStyle(Color.accentColor).font(.title2)
                                    VStack(alignment: .leading) {
                                        Text(record.name).font(.subheadline.weight(.semibold))
                                        Text(tr: record.text)
                                    }
                                }
                            }
                        }
                    }
                    if !ui.ownLoads.isEmpty {
                        Section {
                            ForEach(Array(ui.ownLoads.enumerated()), id: \.offset) { _, line in Text(tr: line) }
                        } header: {
                            Text(L("trw_own_loads_title"))
                        } footer: {
                            Text(L("trw_own_loads_info"))
                        }
                    }
                    if !ui.suggestions.isEmpty {
                        Section(L("trw_suggestions_title")) {
                            ForEach(ui.suggestions, id: \.slug) { s in
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(s.name).font(.subheadline.weight(.semibold))
                                    Text(tr: s.verdict).font(.caption)
                                    if let load = TrRender.optional(s.load) { Text(load) }
                                    if let variant = TrRender.optional(s.variantText) {
                                        Text(variant).font(.caption).foregroundStyle(Color.accentColor)
                                    }
                                }
                            }
                        }
                    }
                    if !ui.trends.isEmpty {
                        Section(L("tre_trend_title")) {
                            ForEach(ui.trends, id: \.slug) { t in trend(t) }
                        }
                    }
                    if ui.hasSideBalance {
                        Section(L("trw_side_title")) {
                            Text(tr: ui.sideValues)
                            if let diff = TrRender.optional(ui.sideDiff) { Text(diff).font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                    Section(L("trw_sets_title")) {
                        ForEach(ui.blocks, id: \.slug) { block in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(block.name).font(.subheadline.weight(.semibold))
                                ForEach(Array(block.lines.enumerated()), id: \.offset) { _, line in
                                    Text(tr: line).font(.caption)
                                }
                            }
                        }
                    }
                    Section {
                        Button {
                            routineName = TrRender.text(ui.defaultRoutineName)
                            askName = true
                        } label: { Label(L("trw_save_routine"), systemImage: "bookmark") }
                            .disabled(!ui.canSaveRoutine)
                    }
                }
            }
            TrainingSnackBar(snack: $snack) { snackAction?() }
        }
        .animation(.default, value: snack)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) { Button(L("tr_action_done"), action: onClose) }
            ToolbarItem(placement: .topBarLeading) {
                if ui.found {
                    Button(role: .destructive) { confirmDelete = true } label: { Image(systemName: "trash") }
                        .accessibilityLabel(L("trw_delete_training"))
                }
            }
        }
        .onChange(of: ui.deleted) { _, deleted in if deleted { onClose() } }
        .onChange(of: ui.routineSaved) { _, saved in if saved { show(L("trw_routine_saved")) } }
        .onChange(of: TrRender.text(ui.messageText)) { _, text in
            guard !text.isEmpty else { return }
            model.consumeMessage()
            show(text, action: L("trpg_undo")) { model.undoLastAction() }
        }
        .onChange(of: TrRender.text(ui.keepAnswerText)) { _, text in
            guard !text.isEmpty else { return }
            show(text, action: L("trpg_undo")) { model.undoKeep() }
            Task {
                try? await Task.sleep(nanoseconds: 6_500_000_000)
                model.consumeKeepAnswer()
            }
        }
        .alert(L("trw_delete_training_title"), isPresented: $confirmDelete) {
            Button(L("tr_action_delete"), role: .destructive) { model.delete() }
            Button(L("tr_action_cancel"), role: .cancel) {}
        } message: { Text(tr: ui.deleteText) }
        .alert(L("trw_save_routine"), isPresented: $askName) {
            TextField(L("trw_routine_name"), text: $routineName)
            Button(L("tr_action_cancel"), role: .cancel) {}
            Button(L("tr_action_save")) { model.saveRoutine(name: routineName) }
        }
        .alert(L("tre_swap_confirm_title"), isPresented: Binding(get: { swap != nil }, set: { if !$0 { swap = nil } })) {
            if let s = swap, model.routineCount(from: s.from) > 0 {
                Button(L("tre_swap_action")) { model.swapInRoutines(from: s.from, to: s.to); swap = nil }
            }
            Button(L("tr_action_cancel"), role: .cancel) { swap = nil }
        } message: {
            if let s = swap { Text(tr: model.swapConfirmText(from: s.from, to: s.to)) }
        }
    }

    private func show(_ text: String, action: String? = nil, perform: (() -> Void)? = nil) {
        snackAction = perform
        snack = TrainingSnack(text: text, action: action)
    }

    /// The end of a training as a small celebration: a check that pops in, "done", the key numbers, the week.
    private var reward: some View {
        VStack(spacing: 8) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 88)).foregroundStyle(TrainingStyle.positive)
                .scaleEffect(scale)
                .onAppear { withAnimation(.spring(response: 0.5, dampingFraction: 0.5)) { scale = 1 } }
            Text(L("trp2_reward_title")).font(.title2.weight(.bold)).accessibilityIdentifier("summary_reward")
            Text(tr: ui.headline).font(.subheadline).foregroundStyle(.secondary)
            HStack(spacing: 8) {
                tile(L("trw_summary_duration"), TrRender.text(ui.durationValue))
                tile(L("trw_summary_sets"), String(ui.setsValue))
                tile(TrRender.text(ui.thirdLabel), TrRender.text(ui.thirdValue))
            }
            if ui.hasStreak {
                VStack(alignment: .leading, spacing: 6) {
                    Text(tr: ui.weekGoal).font(.subheadline.weight(.semibold))
                    ProgressView(value: ui.weekProgress).tint(TrainingStyle.positive)
                    if let streak = TrRender.optional(ui.streakLine) { Text(streak).font(.caption).foregroundStyle(.secondary) }
                }
                .padding(.top, 8)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func tile(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            Text(value).font(.title3.weight(.bold)).lineLimit(1).minimumScaleFactor(0.7)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
    }

    private var keepCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(tr: ui.keepQuestion)
            Text(tr: ui.keepDetails).font(.caption).foregroundStyle(.secondary)
            HStack {
                Spacer()
                Button(L("trk_today_only")) { model.answerKeep(keep: false) }
                Button(L("trc_yes")) { model.answerKeep(keep: true) }.buttonStyle(.borderedProminent)
            }
        }
    }

    @ViewBuilder
    private var after: some View {
        if let dayForm = TrRender.optional(ui.afterDayForm) { Text(dayForm) }
        if let fingers = TrRender.optional(ui.afterFingers) { Text(fingers) }
        if let fuel = TrRender.optional(ui.afterFuel) {
            Text(fuel)
            Button(L("tre_after_fuel_open"), action: onOpenNutrition)
        }
    }

    @ViewBuilder
    private func trend(_ t: SummaryTrendUi) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(t.name).font(.subheadline.weight(.semibold))
            Text(tr: t.text).font(.caption)
            if let note = TrRender.optional(t.note) { Text(note).font(.caption) }
            switch t.actionCode {
            case "deload":
                Button(TrRender.text(t.actionText)) { model.startDeload() }
            case "swap":
                Button(TrRender.text(t.actionText)) { swap = (t.slug, t.actionSlug) }
            case "open":
                Text(tr: t.actionText).font(.caption).foregroundStyle(Color.accentColor)
            default:
                EmptyView()
            }
        }
    }
}
