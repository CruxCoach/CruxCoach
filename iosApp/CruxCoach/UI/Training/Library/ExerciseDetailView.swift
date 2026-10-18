import CruxCoachCore
import SwiftUI

/// One exercise's page (Android `ExerciseDetailScreen`): why, steps, cues, mistakes, equipment,
/// loaded structures, the easier → harder chain, bests and the last sessions; start a training
/// with it or add it to the running one.
struct ExerciseDetailView: View {
    let core: AppCore
    let slug: String
    let onStartWorkout: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var host: ScreenHost<ExerciseDetailScreenModel, ExerciseDetailScreenState>?
    @State private var unavailable = false
    @State private var route: LibraryRoute?
    @State private var addSheet = false
    @State private var confirmDelete = false
    @State private var rejected = false
    @State private var visuals: ScreenHost<ExerciseVisualsModel, ExerciseVisualsState>?

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else if unavailable {
                ContentUnavailableView(LI("library_unavailable"), systemImage: "lock.trianglebadge.exclamationmark")
            } else {
                ProgressView()
            }
        }
        .navigationTitle(host.map { $0.state.found ? $0.state.name : L("tr_nav_exercises") } ?? L("tr_nav_exercises"))
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $route) { r in
            LibraryRouteView(core: core, route: r, onStartWorkout: onStartWorkout)
        }
        .task {
            guard host == nil else { return }
            guard let model = core.makeExerciseDetailScreen(slug: slug, language: LibraryLanguage.current) else {
                unavailable = true
                return
            }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            if visuals == nil { visuals = ExerciseBodyMapSection.makeHost(core: core, slug: slug) }
        }
    }

    @ViewBuilder
    private func screen(model: ExerciseDetailScreenModel, ui: ExerciseDetailScreenState) -> some View {
        if ui.isLoading {
            ProgressView()
        } else if !ui.found {
            ContentUnavailableView(L("trx_not_found"), systemImage: "questionmark.circle")
        } else {
            content(model: model, ui: ui)
        }
    }

    @ViewBuilder
    /// Android's performance-value card (value per side, enter, short test) and the progress card
    /// with its chart and "Open statistics", each on its own page here.
    @ViewBuilder
    private var measuresSection: some View {
        Section {
            if core.exerciseHasBenchmark(slug: slug) {
                NavigationLink {
                    ProgressBenchmarksView(core: core, focusSlug: slug)
                } label: {
                    Label(L("trbm_card_title"), systemImage: "gauge.with.dots.needle.67percent")
                }
            }
            NavigationLink {
                ProgressExerciseStatsView(core: core, slug: slug)
            } label: {
                Label(L("trs_open_stats"), systemImage: "chart.xyaxis.line")
            }
        }
    }

    private func content(model: ExerciseDetailScreenModel, ui: ExerciseDetailScreenState) -> some View {
        List {
            Section {
                HStack(spacing: 12) {
                    if let pictogram = ui.pictogram { ExercisePictogramTile(pictogram: pictogram, size: 64) }
                    VStack(alignment: .leading, spacing: 4) {
                        if !ui.aliases.isEmpty {
                            Text(ui.aliases).font(.footnote).foregroundStyle(.secondary)
                        }
                        HStack(spacing: 0) {
                            Text(L(ui.categoryKey) + " · ")
                            LibraryDifficultyDots(level: Int(ui.difficulty))
                        }
                    }
                }
                if ui.excluded {
                    Button { model.toggleExcluded() } label: {
                        Label(L("tre_excluded_info"), systemImage: "nosign")
                    }
                }
                if ui.hasInjuryMessage {
                    Text(LibraryRender.text(ui.injuryMessage))
                        .font(.subheadline)
                        .padding(8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 8)
                            .fill(ui.verdictCode == "avoid" ? Color.red.opacity(0.15) : Color.orange.opacity(0.15)))
                }
                if rejected {
                    Text(L("trx_pick_rejected")).font(.footnote).foregroundStyle(.red)
                }
            }
            ExerciseBodyMapSection(host: visuals)
            if !ui.why.isEmpty {
                Section(L("trx_why")) { Text(ui.why) }
            }
            if !ui.steps.isEmpty {
                Section(L("trx_steps")) {
                    ForEach(Array(ui.steps.enumerated()), id: \.offset) { i, step in
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text("\(i + 1).").fontWeight(.semibold).frame(width: 24, alignment: .leading)
                            Text(step)
                        }
                    }
                }
            }
            if !ui.cues.isEmpty {
                Section(L("trx_cues")) {
                    ForEach(Array(ui.cues.enumerated()), id: \.offset) { _, cue in Text("• " + cue) }
                }
            }
            if !ui.mistakes.isEmpty {
                Section(L("trx_mistakes")) {
                    ForEach(Array(ui.mistakes.enumerated()), id: \.offset) { _, m in Text("• " + m) }
                }
            }
            Section(L("trx_equipment")) {
                LibraryFlowLayout(spacing: 8) {
                    ForEach(ui.equipmentKeys, id: \.self) { key in LibraryChipLabel(title: L(key), selected: false) }
                    if ui.unilateral {
                        LibraryChipLabel(title: L("trx_unilateral"), selected: false, systemImage: "arrow.left.arrow.right")
                    }
                }
                if !ui.domainKeys.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(L("trx_loads")).font(.subheadline.weight(.semibold))
                        LibraryFlowLayout(spacing: 8) {
                            ForEach(ui.domainKeys, id: \.self) { key in LibraryChipLabel(title: L(key), selected: false) }
                        }
                    }
                }
                if ui.hasContraindications {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(LibraryRender.text(ui.contraindications)).font(.footnote).foregroundStyle(.orange)
                        Text(L("tr_not_medical_advice")).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            if !ui.chain.isEmpty {
                Section {
                    Text(L("trx_progression_hint")).font(.footnote).foregroundStyle(.secondary)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 4) {
                            ForEach(Array(ui.chain.enumerated()), id: \.offset) { i, step in
                                if i > 0 { Image(systemName: "arrow.right").font(.caption).foregroundStyle(.secondary) }
                                LibraryChip(title: step.isCurrent ? step.name + " (" + L("trx_current") + ")" : step.name,
                                            selected: step.isCurrent) {
                                    if !step.isCurrent { route = .exercise(step.slug) }
                                }
                            }
                        }
                    }
                } header: {
                    Text(L("trx_progression"))
                }
            }
            if !ui.bests.isEmpty {
                Section(L("trx_bests")) {
                    ForEach(Array(ui.bests.enumerated()), id: \.offset) { _, best in
                        HStack {
                            Text(LibraryRender.text(best.label))
                            Spacer()
                            Text(LibraryRender.text(best.value)).fontWeight(.semibold)
                        }
                    }
                }
            }
            if !ui.history.isEmpty {
                Section(L("trx_history")) {
                    ForEach(ui.history, id: \.workoutId) { session in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(LibraryRender.mediumDate(epochMs: session.completedAt)).font(.subheadline.weight(.semibold))
                            ForEach(Array(session.sets.enumerated()), id: \.offset) { _, set in
                                Text(LibraryRender.text(set.text)).font(.footnote)
                                    .foregroundStyle(set.warmup ? Color.secondary : Color.primary)
                            }
                        }
                    }
                }
            }
            measuresSection
        }
        .safeAreaInset(edge: .bottom) {
            HStack(spacing: 8) {
                Button { addSheet = true } label: {
                    Label(L("trx_to_workout"), systemImage: "text.badge.plus")
                }
                .buttonStyle(.bordered)
                Button {
                    rejected = false
                    model.addToTraining()
                } label: {
                    Label(L(ui.hasOpenWorkout ? "trx_add_to_running" : "trx_start_with"),
                          systemImage: ui.hasOpenWorkout ? "plus" : "play.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!ui.canAdd)
            }
            .padding(16)
            .background(.bar)
        }
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button { model.toggleFavorite() } label: {
                    Image(systemName: ui.favorite ? "star.fill" : "star")
                        .accessibilityLabel(L(ui.favorite ? "trx_favorite_remove" : "trx_favorite_add"))
                }
                Menu {
                    Button { model.toggleExcluded() } label: {
                        Label(L(ui.excluded ? "tre_include" : "tre_exclude"), systemImage: ui.excluded ? "eye" : "nosign")
                    }
                    if ui.custom {
                        Button(role: .destructive) { confirmDelete = true } label: {
                            Label(L("trx_custom_delete"), systemImage: "trash")
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle").accessibilityLabel(L("tre_more_actions"))
                }
            }
        }
        .onChange(of: ui.navigateToWorkoutId) { _, id in
            guard !id.isEmpty else { return }
            model.consumeEvents()
            onStartWorkout(id)
        }
        .onChange(of: ui.addRejected) { _, value in
            guard value else { return }
            model.consumeEvents()
            rejected = true
        }
        .onChange(of: ui.deleted) { _, deleted in
            if deleted { dismiss() }
        }
        .alert(L("trx_custom_delete_title", ui.name), isPresented: $confirmDelete) {
            Button(L("tr_action_delete"), role: .destructive) { model.deleteCustom() }
            Button(L("tr_action_cancel"), role: .cancel) {}
        } message: {
            Text(L("trx_custom_delete_text"))
        }
        .sheet(isPresented: $addSheet) {
            AddToWorkoutSheet(core: core, slug: ui.slug,
                              onTrainingStarted: { id in
                                  addSheet = false
                                  onStartWorkout(id)
                              },
                              onCreateRoutine: { from in
                                  addSheet = false
                                  route = .editor(routineId: "", from: from, defaultName: "")
                              })
                .presentationDetents([.medium, .large])
        }
    }
}

/// "Plan this exercise" (Android `AddToWorkoutSheet`): into the running training, a new training,
/// one of the athlete's own workouts or a new workout — plus the favourite star.
struct AddToWorkoutSheet: View {
    let core: AppCore
    let slug: String
    let onTrainingStarted: (String) -> Void
    let onCreateRoutine: (String) -> Void

    @State private var host: ScreenHost<AddToWorkoutScreenModel, AddToWorkoutScreenState>?

    var body: some View {
        NavigationStack {
            Group {
                if let host {
                    sheet(model: host.model, ui: host.state)
                } else {
                    ProgressView()
                }
            }
            .navigationTitle(host?.state.name ?? "")
            .navigationBarTitleDisplayMode(.inline)
        }
        .task {
            guard host == nil, let model = core.makeAddToWorkoutScreen(slug: slug, language: LibraryLanguage.current) else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }

    @ViewBuilder
    private func sheet(model: AddToWorkoutScreenModel, ui: AddToWorkoutScreenState) -> some View {
        List {
            if ui.hasMessage {
                Text(LibraryRender.text(ui.message))
                    .foregroundStyle(ui.messageIsWarning ? Color.red : Color.green)
            }
            Button { model.toggleFavorite() } label: {
                Label(L(ui.favorite ? "trwo_fav_remove" : "trwo_fav_add"), systemImage: ui.favorite ? "star.fill" : "star")
            }
            if ui.hasOpenWorkout {
                Button { model.addToOpen() } label: { Label(L("trwo_add_to_open"), systemImage: "text.badge.plus") }
            } else {
                Button { model.startNew() } label: { Label(L("trwo_add_start_new"), systemImage: "play.fill") }
            }
            if !ui.routines.isEmpty {
                Section(L("trwo_add_to_routine")) {
                    ForEach(ui.routines, id: \.id) { routine in
                        Button { model.addToRoutine(routineId: routine.id) } label: {
                            Label(routine.name, systemImage: "dumbbell")
                        }
                    }
                }
            }
            Button { onCreateRoutine(ui.routineFrom) } label: {
                Label(L("trwo_add_new_routine"), systemImage: "plus")
            }
            .disabled(ui.routineFrom.isEmpty)
        }
        .onChange(of: ui.startedWorkoutId) { _, id in
            guard !id.isEmpty else { return }
            model.consumeStarted()
            onTrainingStarted(id)
        }
    }
}
