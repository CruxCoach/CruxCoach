import CruxCoachCore
import SwiftUI

/// The optional coach setup (Android `CoachSetupScreen`): goal, week, climbing and experience,
/// what suits you, then start values and the proposed week. Every card can be skipped; "Next"
/// stores it. Brings its own `NavigationStack`; present it as a sheet or full-screen cover.
struct CoachSetupView: View {
    let core: AppCore
    let onDone: () -> Void

    @State private var host: ScreenHost<CoachSetupScreenModel, CoachSetupScreenState>?
    @State private var unavailable = false

    init(core: AppCore, onDone: @escaping () -> Void) {
        self.core = core
        self.onDone = onDone
    }

    var body: some View {
        NavigationStack {
            Group {
                if let host {
                    CoachSetupContent(core: core, model: host.model, ui: host.state, onDone: onDone)
                } else if unavailable {
                    Text(LI("library_unavailable")).foregroundStyle(.secondary).padding(16)
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) { Button(L("action_close"), action: onDone) }
                        }
                } else {
                    ProgressView()
                }
            }
            .navigationTitle(L("trc_setup_title"))
            .navigationBarTitleDisplayMode(.inline)
        }
        .task {
            guard host == nil else { return }
            guard let model = core.makeCoachSetupScreen(language: TrainingStyle.catalogLanguage) else {
                unavailable = true
                return
            }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }
}

/// Which grade the picker sets: target | current | flash.
private struct CoachGradeField: Identifiable {
    let id: String
}

private struct CoachSetupContent: View {
    let core: AppCore
    let model: CoachSetupScreenModel
    let ui: CoachSetupScreenState
    let onDone: () -> Void

    @State private var estimating = false
    @State private var equipmentOpen = false
    @State private var favoritesOpen = false
    @State private var weightOpen = false
    @State private var datePicking = false
    @State private var confirmDismiss = false
    @State private var benchmarksOpen = false
    @State private var playerOpen = false
    @State private var gradeField: CoachGradeField?

    var body: some View {
        VStack(spacing: 0) {
            if ui.loading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if ui.failed {
                Text(LI("library_unavailable")).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 8) {
                        CoachStepDots(step: Int(ui.step), count: Int(ui.stepCount), text: ui.stepText)
                        card
                    }
                    .padding(16)
                }
                // Every card starts at its top, not where the previous one was scrolled to.
                .id(ui.step)
                CoachBottomBar(model: model, ui: ui)
            }
        }
        .toolbar { toolbar }
        .modifier(CoachSetupSheets(core: core, model: model, ui: ui, estimating: $estimating, equipmentOpen: $equipmentOpen,
                                   favoritesOpen: $favoritesOpen, weightOpen: $weightOpen, datePicking: $datePicking,
                                   gradeField: $gradeField))
        .modifier(CoachSetupFlow(core: core, model: model, ui: ui, onDone: onDone, confirmDismiss: $confirmDismiss,
                                 benchmarksOpen: $benchmarksOpen, playerOpen: $playerOpen))
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            Button(L("action_close"), action: onDone)
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button(L("trc_dont_ask")) { confirmDismiss = true }
        }
    }

    @ViewBuilder
    private var card: some View {
        switch ui.step {
        case 0:
            CoachGoalCard(model: model, ui: ui, onPickGrade: { gradeField = CoachGradeField(id: "target") },
                          onPickDate: { datePicking = true })
        case 1:
            CoachWeekCard(model: model, ui: ui)
        case 2:
            CoachExperienceCard(model: model, ui: ui, onPickGrade: { field in gradeField = CoachGradeField(id: field) },
                                onLogWeight: { weightOpen = true })
        case 3:
            CoachPreferencesCard(model: model, ui: ui, onOpenEquipment: { equipmentOpen = true },
                                 onPickFavorites: { favoritesOpen = true })
        default:
            CoachFinishCard(model: model, ui: ui, onEstimate: { estimating = true },
                            onOpenBenchmarks: { benchmarksOpen = true })
        }
    }
}

/// Skip / Next, on the last step Back / Finish (Android's bottom bar).
private struct CoachBottomBar: View {
    let model: CoachSetupScreenModel
    let ui: CoachSetupScreenState

    var body: some View {
        HStack(spacing: 8) {
            if ui.isLastStep {
                Button(L("trc_back")) { model.back() }
                Spacer()
                Button(L("trc_finish")) { model.finish() }.buttonStyle(.borderedProminent)
            } else {
                if ui.step > 0 {
                    Button { model.back() } label: { Image(systemName: "chevron.left") }
                        .accessibilityLabel(L("trc_back"))
                }
                Button(L("trc_skip")) { model.skip() }
                Spacer()
                Button(L("trc_next")) { model.next() }.buttonStyle(.borderedProminent)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.bar)
    }
}

/// Sheets of the cards: quick estimate, equipment, favourites, weight, date and grade pickers.
private struct CoachSetupSheets: ViewModifier {
    let core: AppCore
    let model: CoachSetupScreenModel
    let ui: CoachSetupScreenState
    @Binding var estimating: Bool
    @Binding var equipmentOpen: Bool
    @Binding var favoritesOpen: Bool
    @Binding var weightOpen: Bool
    @Binding var datePicking: Bool
    @Binding var gradeField: CoachGradeField?

    func body(content: Content) -> some View {
        pickers(cardSheets(content))
    }

    private func cardSheets(_ content: Content) -> some View {
        content
            .sheet(isPresented: $estimating) {
                ProgressQuickEstimateSheet(core: core) { estimating = false }
            }
            .sheet(isPresented: $equipmentOpen) {
                CoachEquipmentSheet(model: model, ui: ui) { equipmentOpen = false }
            }
            .sheet(isPresented: $favoritesOpen) {
                CoachFavoritesSheet(model: model) { favoritesOpen = false }
            }
    }

    private func pickers<V: View>(_ view: V) -> some View {
        view
            .sheet(isPresented: $weightOpen) {
                CoachWeightSheet(model: model, ui: ui) { weightOpen = false }
            }
            .sheet(isPresented: $datePicking) {
                CoachDateSheet(model: model, ui: ui) { datePicking = false }
            }
            .sheet(item: $gradeField) { field in
                CoachGradeSheet(model: model, field: field.id, current: currentGrade(field.id)) { gradeField = nil }
            }
    }

    private func currentGrade(_ field: String) -> String {
        switch field {
        case "target": return ui.targetGrade
        case "flash": return ui.flashGrade
        default: return ui.currentGrade
        }
    }
}

/// Finish, the test session in the player, the open-training question, "don't ask again".
private struct CoachSetupFlow: ViewModifier {
    let core: AppCore
    let model: CoachSetupScreenModel
    let ui: CoachSetupScreenState
    let onDone: () -> Void
    @Binding var confirmDismiss: Bool
    @Binding var benchmarksOpen: Bool
    @Binding var playerOpen: Bool

    func body(content: Content) -> some View {
        alerts(flow(content))
    }

    private func flow(_ content: Content) -> some View {
        content
            .navigationDestination(isPresented: $benchmarksOpen) {
                ProgressBenchmarksView(core: core)
            }
            .onChange(of: ui.finished) { _, now in
                if now { onDone() }
            }
            .onChange(of: ui.testStarted) { _, now in
                if now {
                    model.consumeTestStarted()
                    playerOpen = true
                }
            }
            // The setup is complete once the test started; closing the player closes the setup too.
            .fullScreenCover(isPresented: $playerOpen, onDismiss: onDone) {
                WorkoutPlayerView(core: core, onClose: { playerOpen = false }, onFinished: { _ in playerOpen = false },
                                  onOpenNutrition: { playerOpen = false })
            }
    }

    private var pendingShown: Binding<Bool> {
        Binding(get: { ui.hasPendingStart }, set: { shown in if !shown { model.resolveStart(choice: -1) } })
    }

    private func alerts<V: View>(_ view: V) -> some View {
        view
            .alert(TrRender.text(ui.pendingTitle), isPresented: pendingShown) {
                Button(TrRender.text(ui.pendingReplace)) { model.resolveStart(choice: 1) }
                Button(TrRender.text(ui.pendingContinue)) { model.resolveStart(choice: 0) }
                Button(L("action_cancel"), role: .cancel) { model.resolveStart(choice: -1) }
            } message: {
                Text(tr: ui.pendingText)
            }
            .alert(L("trc_dont_ask"), isPresented: $confirmDismiss) {
                Button(L("trc_dont_ask_confirm")) {
                    model.dismiss()
                    onDone()
                }
                Button(L("tr_action_cancel"), role: .cancel) {}
            } message: {
                Text(L("trc_dont_ask_text"))
            }
    }
}

// ── Shared pieces ────────────────────────────────────────────────────

private struct CoachStepDots: View {
    let step: Int
    let count: Int
    let text: TrText

    var body: some View {
        VStack(spacing: 4) {
            HStack(spacing: 6) {
                ForEach(0..<count, id: \.self) { i in
                    Circle()
                        .fill(i <= step ? Color.accentColor : Color.secondary.opacity(0.3))
                        .frame(width: i == step ? 10 : 8, height: i == step ? 10 : 8)
                }
            }
            Text(tr: text).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

/// Card header: question, what it is for.
private struct CoachHeader: View {
    let title: String
    let why: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.title2.weight(.semibold))
            Text(why).font(.callout).foregroundStyle(.secondary)
        }
        .padding(.top, 8)
    }
}

private struct CoachQuestion: View {
    let text: String

    var body: some View {
        Text(text).font(.subheadline.weight(.semibold)).padding(.top, 12)
    }
}

/// "From your logbook" under a prefilled answer.
private struct CoachPrefill: View {
    let text: TrText

    var body: some View {
        if let hint = TrRender.optional(text) {
            Text(hint).font(.caption).foregroundStyle(Color.accentColor)
        }
    }
}

/// The immediate effect of the answers so far.
private struct CoachPreview: View {
    let title: String
    let text: TrText

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption.weight(.semibold))
            Text(tr: text).font(.callout)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
        .padding(.top, 12)
    }
}

private struct CoachChips: View {
    let chips: [CoachChipUi]
    let onTap: (String) -> Void

    var body: some View {
        TrainingFlow(spacing: 6) {
            ForEach(chips, id: \.code) { chip in
                CoachChip(chip: chip, onTap: onTap)
            }
        }
    }
}

private struct CoachChip: View {
    let chip: CoachChipUi
    let onTap: (String) -> Void

    var body: some View {
        TrainingChoiceChip(title: TrRender.text(chip.label), selected: chip.selected) { onTap(chip.code) }
            .disabled(!chip.enabled)
            .opacity(chip.enabled ? 1 : 0.4)
    }
}

/// Font grade with − / +; tapping the value opens the whole scale (Android `GradeStepper`).
private struct CoachGradeRow: View {
    let text: String
    let onPick: () -> Void
    let onClear: () -> Void
    let onStep: (Int32) -> Void

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onPick) {
                Text(text.isEmpty ? L("trc_grade_pick") : text)
                    .font(.headline)
                    .foregroundStyle(text.isEmpty ? Color.accentColor : Color.primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            if !text.isEmpty {
                Button(action: onClear) { Image(systemName: "xmark") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(L("trc_clear"))
            }
            Button("−") { onStep(-1) }.buttonStyle(.bordered)
            Button("+") { onStep(1) }.buttonStyle(.bordered)
        }
    }
}

/// One day of the proposed week.
private struct CoachPlanRows: View {
    let days: [CoachPlanDayUi]
    let short: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(days, id: \.isoDay) { day in
                HStack(alignment: .firstTextBaseline) {
                    Text(short ? CoachDayName.short(Int(day.isoDay)) : TrRender.weekday(Int(day.isoDay)))
                        .font(.callout.weight(short ? .regular : .semibold))
                        .frame(width: short ? 48 : 112, alignment: .leading)
                    Text(tr: day.label).font(.callout)
                }
            }
        }
    }
}

private enum CoachDayName {
    /// ISO weekday 1 = Monday … 7 = Sunday, short name in the device language.
    static func short(_ iso: Int) -> String {
        let names = DateFormatter().shortWeekdaySymbols ?? []
        let index = iso % 7
        return index < names.count ? names[index] : ""
    }
}

// ── Card 1: goal ─────────────────────────────────────────────────────

private struct CoachGoalCard: View {
    let model: CoachSetupScreenModel
    let ui: CoachSetupScreenState
    let onPickGrade: () -> Void
    let onPickDate: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            CoachHeader(title: L("trc_goal_title"), why: L("trc_goal_why"))
            CoachChips(chips: ui.goals) { model.toggleGoal(code: $0) }
            CoachQuestion(text: L("trc_target_grade"))
            CoachGradeRow(text: ui.targetGradeText, onPick: onPickGrade,
                          onClear: { model.setGrade(field: "target", grade: "") },
                          onStep: { model.stepGrade(field: "target", delta: $0) })
            projects
            CoachQuestion(text: L("trc_target_date"))
            dateRow
            CoachPreview(title: L("trc_preview_title"), text: ui.goalPreview)
        }
    }

    @ViewBuilder
    private var projects: some View {
        if !ui.projects.isEmpty {
            CoachQuestion(text: L("trc_target_project"))
            Text(L("trc_target_project_hint")).font(.caption).foregroundStyle(.secondary)
            CoachChips(chips: ui.projects) { model.toggleProject(climbUuid: $0) }
        }
    }

    private var dateRow: some View {
        HStack {
            Text(ui.targetDate.isEmpty ? L("trc_none") : TrRender.date(ui.targetDate))
                .frame(maxWidth: .infinity, alignment: .leading)
            if !ui.targetDate.isEmpty {
                Button { model.clearTargetDate() } label: { Image(systemName: "xmark") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(L("trc_clear"))
            }
            Button(L("trc_pick_date"), action: onPickDate).buttonStyle(.bordered)
        }
    }
}

// ── Card 2: week ─────────────────────────────────────────────────────

private struct CoachWeekCard: View {
    let model: CoachSetupScreenModel
    let ui: CoachSetupScreenState

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            CoachHeader(title: L("trc_week_title"), why: L("trc_week_why"))
            days
            minutesAndAddOn
            weekPreview
        }
    }

    @ViewBuilder
    private var days: some View {
        CoachQuestion(text: L("trc_training_days"))
        daysStepper
        CoachPrefill(text: ui.trainingDaysPrefill)
        CoachQuestion(text: L("trc_climbing_days"))
        climbingDays
        CoachPrefill(text: ui.climbingDaysPrefill)
    }

    @ViewBuilder
    private var minutesAndAddOn: some View {
        CoachQuestion(text: L("trc_session_minutes"))
        CoachChips(chips: ui.minutes) { code in
            if let m = Int32(code) { model.setMinutes(minutes: m) }
        }
        CoachQuestion(text: L("trc_add_on"))
        addOn
    }

    private var daysStepper: some View {
        HStack(spacing: 8) {
            Text(tr: ui.trainingDaysText).font(.headline).frame(maxWidth: .infinity, alignment: .leading)
            Button("−") { model.changeTrainingDays(delta: -1) }.buttonStyle(.bordered)
            Button("+") { model.changeTrainingDays(delta: 1) }.buttonStyle(.bordered)
        }
    }

    private var climbingDays: some View {
        TrainingFlow(spacing: 6) {
            ForEach(ui.climbingDays, id: \.isoDay) { day in
                TrainingChoiceChip(title: CoachDayName.short(Int(day.isoDay)), selected: day.selected) {
                    model.toggleClimbingDay(isoDay: day.isoDay)
                }
            }
        }
    }

    private var addOn: some View {
        HStack(spacing: 6) {
            TrainingChoiceChip(title: L("trc_yes"), selected: ui.addOnCode == "yes") { model.toggleAddOn(code: "yes") }
            TrainingChoiceChip(title: L("trc_no"), selected: ui.addOnCode == "no") { model.toggleAddOn(code: "no") }
        }
    }

    private var weekPreview: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L("trc_preview_week_title")).font(.caption.weight(.semibold))
            CoachPlanRows(days: ui.weekPreview, short: true)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
        .padding(.top, 12)
    }
}

// ── Card 3: climbing and experience ──────────────────────────────────

private struct CoachExperienceCard: View {
    let model: CoachSetupScreenModel
    let ui: CoachSetupScreenState
    let onPickGrade: (String) -> Void
    let onLogWeight: () -> Void

    @State private var rope = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            CoachHeader(title: L("trc_experience_title"), why: L("trc_experience_why"))
            bands
            grades
            ropeField
            weightRow
            CoachPreview(title: L("trc_preview_title"), text: ui.guardrailPreview)
        }
        .onAppear { rope = ui.ropeGrade }
    }

    @ViewBuilder
    private var bands: some View {
        CoachQuestion(text: L("trc_contexts"))
        CoachChips(chips: ui.contexts) { model.toggleContext(code: $0) }
        CoachQuestion(text: L("trc_experience"))
        CoachChips(chips: ui.experiences) { model.toggleExperience(code: $0) }
        CoachPrefill(text: ui.experiencePrefill)
        CoachQuestion(text: L("trc_age"))
        CoachChips(chips: ui.ages) { model.toggleAge(code: $0) }
        CoachPrefill(text: ui.agePrefill)
    }

    @ViewBuilder
    private var grades: some View {
        CoachQuestion(text: L("trc_current_grade"))
        CoachGradeRow(text: ui.currentGradeText, onPick: { onPickGrade("current") },
                      onClear: { model.setGrade(field: "current", grade: "") },
                      onStep: { model.stepGrade(field: "current", delta: $0) })
        CoachPrefill(text: ui.gradePrefill)
        CoachQuestion(text: L("trc_flash_grade"))
        CoachGradeRow(text: ui.flashGradeText, onPick: { onPickGrade("flash") },
                      onClear: { model.setGrade(field: "flash", grade: "") },
                      onStep: { model.stepGrade(field: "flash", delta: $0) })
        CoachPrefill(text: ui.flashPrefill)
    }

    private var ropeField: some View {
        TextField(L("trc_rope_grade"), text: $rope)
            .textFieldStyle(.roundedBorder)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .padding(.top, 12)
            .onChange(of: rope) { _, now in
                let cut = String(now.prefix(8))
                if cut != now { rope = cut }
                model.setRopeGrade(text: cut)
            }
    }

    @ViewBuilder
    private var weightRow: some View {
        CoachQuestion(text: L("tru_setup_weight"))
        HStack {
            Text(tr: ui.weightText).frame(maxWidth: .infinity, alignment: .leading)
            Button(ui.hasWeight ? L("trt_checkin_change") : L("tru_enter_weight"), action: onLogWeight)
                .buttonStyle(.bordered)
        }
    }
}

// ── Card 4: what suits you ───────────────────────────────────────────

private struct CoachPreferencesCard: View {
    let model: CoachSetupScreenModel
    let ui: CoachSetupScreenState
    let onOpenEquipment: () -> Void
    let onPickFavorites: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            CoachHeader(title: L("trc_prefs_title"), why: L("trc_prefs_why"))
            focus
            CoachQuestion(text: L("trc_finger_pref"))
            CoachChips(chips: ui.fingers) { model.toggleFinger(code: $0) }
            CoachSlider(title: L("trc_style_intensity"), left: L("trc_style_intense"), right: L("trc_style_calm"),
                        value: Int(ui.intensity)) { model.setIntensity(value: $0) }
            CoachSlider(title: L("trc_style_variety"), left: L("trc_style_routine"), right: L("trc_style_varied"),
                        value: Int(ui.variety)) { model.setVariety(value: $0) }
            CoachFavoritesRow(model: model, ui: ui, onPick: onPickFavorites)
            equipment
            CoachPreview(title: L("trc_preview_title"), text: ui.preferencesPreview)
        }
    }

    @ViewBuilder
    private var focus: some View {
        CoachQuestion(text: L("trc_focus"))
        TrainingFlow(spacing: 6) {
            TrainingChoiceChip(title: L("trc_focus_unknown"), selected: ui.focusUnknown) { model.toggleFocus(code: "") }
            ForEach(ui.focus, id: \.code) { chip in
                CoachChip(chip: chip) { model.toggleFocus(code: $0) }
            }
        }
        Text(L("trc_focus_max")).font(.caption).foregroundStyle(.secondary)
    }

    @ViewBuilder
    private var equipment: some View {
        CoachQuestion(text: L("trc_equipment"))
        Text(tr: ui.equipmentText).font(.callout)
        Button(L("trc_equipment_edit"), action: onOpenEquipment).buttonStyle(.bordered)
    }
}

/// A 0–100 preference with "no preference" (Android `PreferenceSlider`).
private struct CoachSlider: View {
    let title: String
    let left: String
    let right: String
    /// -1 = no preference.
    let value: Int
    let onChange: (Int32) -> Void

    @State private var position: Double = 50

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            CoachQuestion(text: title)
            HStack {
                Text(left).font(.caption)
                Spacer()
                Text(right).font(.caption)
            }
            Slider(value: $position, in: 0...100, onEditingChanged: { editing in
                if !editing { onChange(Int32(position.rounded())) }
            })
            .tint(value < 0 ? Color.secondary : Color.accentColor)
            TrainingChoiceChip(title: L("trc_no_preference"), selected: value < 0) { onChange(-1) }
        }
        .onAppear { position = Double(value < 0 ? 50 : value) }
        .onChange(of: value) { _, now in position = Double(now < 0 ? 50 : now) }
    }
}

/// Favourite exercises right below "routine or variety": how often they come depends on it.
private struct CoachFavoritesRow: View {
    let model: CoachSetupScreenModel
    let ui: CoachSetupScreenState
    let onPick: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            CoachQuestion(text: L("trc_favorites"))
            Text(tr: ui.favoritesHint).font(.caption).foregroundStyle(.secondary)
            TrainingFlow(spacing: 6) {
                ForEach(ui.favorites, id: \.slug) { fav in
                    Button { model.removeFavorite(slug: fav.slug) } label: {
                        Label(fav.name, systemImage: "star.fill").font(.subheadline)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityHint(L("trc_favorites_remove", fav.name))
                }
                Button(action: onPick) { Label(L("trc_favorites_add"), systemImage: "plus") }
                    .buttonStyle(.bordered)
            }
        }
    }
}

// ── Step 5: start values and week plan ───────────────────────────────

private struct CoachFinishCard: View {
    let model: CoachSetupScreenModel
    let ui: CoachSetupScreenState
    let onEstimate: () -> Void
    let onOpenBenchmarks: () -> Void

    @State private var path = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            CoachHeader(title: L("trc_values_title"), why: L("trc_values_why"))
            CoachPathCard(selected: path == 0, title: L("trc_path_learn"), text: L("trc_path_learn_text")) { path = 0 }
            CoachPathCard(selected: path == 1, title: L("trc_path_estimate"), text: L("trc_path_estimate_text")) {
                path = 1
                onEstimate()
            }
            CoachPathCard(selected: path == 2, title: L("trc_path_test"), text: L("trc_path_test_text")) {
                path = 2
                model.startTestSession(title: TrRender.text(ui.testTitle))
            }
            Button(L("trc_open_benchmarks"), action: onOpenBenchmarks)
            plan
        }
    }

    @ViewBuilder
    private var plan: some View {
        Text(L("trc_plan_title")).font(.headline).padding(.top, 16)
        Text(L("trc_plan_why")).font(.caption).foregroundStyle(.secondary)
        CoachPlanRows(days: ui.plan, short: false)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        planActions
    }

    @ViewBuilder
    private var planActions: some View {
        if ui.planApplied {
            HStack(alignment: .top) {
                Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                Text(L("trc_plan_applied")).font(.callout)
            }
            Button(L("trb_undo")) { model.undoPlan() }
        } else {
            HStack {
                Button(L("trc_plan_apply")) { model.applyPlan() }.buttonStyle(.bordered)
                Text(L("trc_plan_later")).font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

private struct CoachPathCard: View {
    let selected: Bool
    let title: String
    let text: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(Color.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.subheadline.weight(.semibold))
                    Text(text).font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
            .padding(12)
            .overlay(RoundedRectangle(cornerRadius: 12)
                .stroke(selected ? Color.accentColor : Color.secondary.opacity(0.4), lineWidth: selected ? 2 : 1))
        }
        .buttonStyle(.plain)
    }
}

// ── Sheets ───────────────────────────────────────────────────────────

/// The whole Font scale: one tap instead of a dozen "+".
private struct CoachGradeSheet: View {
    let model: CoachSetupScreenModel
    let field: String
    let current: String
    let onDone: () -> Void

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List(model.grades, id: \.code) { grade in
                    Button {
                        model.setGrade(field: field, grade: grade.code)
                        onDone()
                    } label: {
                        Text(grade.label)
                            .font(.headline)
                            .fontWeight(grade.code == current ? .bold : .regular)
                            .foregroundStyle(grade.code == current ? Color.accentColor : Color.primary)
                    }
                    .id(grade.code)
                }
                .onAppear { if !current.isEmpty { proxy.scrollTo(current, anchor: .center) } }
            }
            .navigationTitle(L("trc_grade_pick"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel"), action: onDone) }
            }
        }
    }
}

/// Trip or competition day: today or later.
private struct CoachDateSheet: View {
    let model: CoachSetupScreenModel
    let ui: CoachSetupScreenState
    let onDone: () -> Void

    @State private var day = Date()

    var body: some View {
        NavigationStack {
            DatePicker(L("trc_target_date"), selection: $day, in: Date()..., displayedComponents: .date)
                .datePickerStyle(.graphical)
                .padding(16)
                .navigationTitle(L("trc_pick_date"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel"), action: onDone) }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L("action_ok")) {
                            _ = model.setTargetDate(iso: CoachDateSheet.iso(day))
                            onDone()
                        }
                    }
                }
        }
        .onAppear { day = CoachDateSheet.initial(ui.targetDate) }
    }

    private static func iso(_ date: Date) -> String {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: date)
    }

    /// The chosen day, else eight weeks from today (Android's picker start).
    private static func initial(_ iso: String) -> Date {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        if let d = f.date(from: iso) { return d }
        return Calendar.current.date(byAdding: .weekOfYear, value: 8, to: Date()) ?? Date()
    }
}

private struct CoachWeightSheet: View {
    let model: CoachSetupScreenModel
    let ui: CoachSetupScreenState
    let onDone: () -> Void

    @State private var text = ""
    @State private var invalid = false

    var body: some View {
        NavigationStack {
            Form {
                Text(L("tru_weight_reason_setup")).font(.caption).foregroundStyle(.secondary)
                HStack {
                    TextField(L("trq_weight"), text: $text).keyboardType(.decimalPad)
                    Text(ui.unitsCode == "imperial" ? "lb" : "kg").foregroundStyle(.secondary)
                }
                if invalid { Text(LI("training_weight_invalid")).font(.caption).foregroundStyle(.red) }
            }
            .navigationTitle(L("trq_weight"))
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

/// The equipment of the place trained at today (Android `EquipmentSheet`): every tap is kept.
private struct CoachEquipmentSheet: View {
    let model: CoachSetupScreenModel
    let ui: CoachSetupScreenState
    let onDone: () -> Void

    @State private var selected: [String] = []

    var body: some View {
        NavigationStack {
            ScrollView {
                TrainingEquipmentEditor(selected: $selected).padding(16)
            }
            .navigationTitle(L("trc_equipment"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L("tr_action_done"), action: onDone) }
            }
        }
        .onAppear { selected = ui.equipmentCodes }
        .onChange(of: selected) { _, now in model.saveEquipment(codes: now) }
    }
}

/// Pick favourites from the catalogue; a star marks and unmarks one at once.
private struct CoachFavoritesSheet: View {
    let model: CoachSetupScreenModel
    let onDone: () -> Void

    @State private var query = ""
    @State private var hits: [CoachExerciseUi] = []

    var body: some View {
        NavigationStack {
            List(hits, id: \.slug) { hit in
                Button {
                    model.toggleFavorite(slug: hit.slug)
                    refreshSoon()
                } label: {
                    HStack {
                        Text(hit.name).foregroundStyle(Color.primary)
                        Spacer()
                        Image(systemName: hit.isFavorite ? "star.fill" : "star")
                            .foregroundStyle(hit.isFavorite ? Color.accentColor : Color.secondary)
                    }
                }
            }
            .searchable(text: $query)
            .navigationTitle(L("trc_favorites_add"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L("tr_action_done"), action: onDone) }
            }
        }
        .onAppear { hits = model.searchExercises(query: query) }
        .onChange(of: query) { _, now in hits = model.searchExercises(query: now) }
    }

    /// The favourite is written off the main thread; the star follows once it is stored.
    private func refreshSoon() {
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 300_000_000)
            hits = model.searchExercises(query: query)
        }
    }
}
