import CruxCoachCore
import SwiftUI

/// "Energy today" (Android `EnergySheet`): resting energy, everyday life,
/// training, the need, and while losing weight the deficit and calorie
/// target. Missing height, birth year or sex are entered right here.
struct NutritionEnergySheet: View {
    let host: ScreenHost<NutritionScreenModel, NutritionDayUi>
    let onGoal: () -> Void
    let onDone: () -> Void
    @State private var info = false

    private var model: NutritionScreenModel { host.model }

    var body: some View {
        let sheet = host.state.energySheet
        NavigationStack {
            Form {
                if sheet.needsWeight {
                    Section {
                        NutritionAutoSaveField(label: L("tru_weight_title"), unit: sheet.massUnit,
                                               isValid: { model.isWeightValid(text: $0) },
                                               onSave: { _ = model.enterWeight(text: $0) })
                    } header: {
                        Text(L("trn_needs_weight")).textCase(nil)
                    }
                }
                if sheet.needsHeight {
                    Section {
                        NutritionAutoSaveField(label: NutritionText.render(sheet.heightLabel), unit: sheet.lengthUnit,
                                               isValid: { model.isHeightValid(text: $0) },
                                               onSave: { _ = model.enterHeight(text: $0) })
                    } header: {
                        Text(L("trn_needs_height")).textCase(nil)
                    }
                }
                if sheet.hasNeed {
                    Section {
                        row(sheet.resting)
                        row(sheet.everyday)
                        Picker(L("trn_everyday"), selection: Binding(
                            get: { sheet.everydayChoices.first(where: { $0.selected })?.code ?? "seated" },
                            set: { model.setEverydayActivity(code: $0) })) {
                            ForEach(sheet.everydayChoices, id: \.code) { choice in
                                Text(NutritionText.render(choice.label)).tag(choice.code)
                            }
                        }
                        row(sheet.training)
                        if sheet.hasPlannedTraining {
                            Text(NutritionText.render(sheet.plannedTraining)).font(.footnote).foregroundStyle(.secondary)
                        }
                        row(sheet.need)
                        if sheet.hasPlan {
                            row(sheet.deficit)
                            row(sheet.target)
                            ForEach(Array(sheet.planNotes.enumerated()), id: \.offset) { _, note in
                                Text(NutritionText.render(note)).font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                        if sheet.hasEaten {
                            row(sheet.eaten)
                        }
                    }
                    Section {
                        // Recommended (the line above) or an own calorie target; a low one is named right here, never refused.
                        NutritionTargetEditor(target: sheet.ownEnergy, model: model)
                        if sheet.hasOwnWarning {
                            Label(NutritionText.render(sheet.ownWarning), systemImage: "exclamationmark.triangle")
                                .font(.footnote)
                                .foregroundStyle(.orange)
                        }
                    }
                    if sheet.ageAssumed || sheet.sexAssumed {
                        Section {
                            if sheet.ageAssumed {
                                Text(NutritionText.render(sheet.ageText)).font(.footnote).foregroundStyle(.secondary)
                                NutritionAutoSaveField(label: L("trn_birth_year_label"), unit: "", numbersOnly: true,
                                                       isValid: { model.isBirthYearValid(text: $0) },
                                                       onSave: { _ = model.enterBirthYear(text: $0) })
                            }
                            if sheet.sexAssumed {
                                Text(L("trn_sex_assumed")).font(.footnote).foregroundStyle(.secondary)
                                Picker(L("trn_sex_assumed"), selection: Binding(
                                    get: { sheet.sexChoices.first(where: { $0.selected })?.code ?? "" },
                                    set: { model.setSex(code: $0) })) {
                                    ForEach(sheet.sexChoices, id: \.code) { choice in
                                        Text(NutritionText.render(choice.label)).tag(choice.code)
                                    }
                                }
                                .pickerStyle(.segmented)
                            }
                        }
                    }
                }
                Section {
                    Button(L(sheet.goalButtonKey), action: onGoal)
                }
            }
            .navigationTitle(L("trn_sheet_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { info = true } label: { Image(systemName: "info.circle") }
                        .accessibilityLabel(L("trn_sheet_title"))
                }
                ToolbarItem(placement: .confirmationAction) { Button(L("action_close"), action: onDone) }
            }
            .alert(L("trn_sheet_title"), isPresented: $info) {
                Button(L("action_close"), role: .cancel) {}
            } message: {
                Text(L("trn_estimate_note"))
            }
        }
    }

    private func row(_ row: EnergyRowUi) -> some View {
        HStack {
            Text(NutritionText.render(row.label))
            Spacer()
            Text(NutritionText.render(row.value)).monospacedDigit()
        }
        .fontWeight(row.bold ? .semibold : .regular)
    }
}

/// A field that saves itself (Android `AutoSaveTextField`; no save buttons in
/// training and nutrition): a valid entry is kept once typing pauses for a
/// second, when the field loses focus and when it leaves the screen. An
/// invalid entry is marked, with the hint saying why when there is one.
struct NutritionAutoSaveField: View {
    let label: String
    let unit: String
    /// The saved value as text ("" when there is none).
    var stored: String = ""
    var numbersOnly: Bool = false
    var invalidHint: String? = nil
    let isValid: (String) -> Bool
    let onSave: (String) -> Void
    @State private var text = ""
    @State private var handedOver = ""
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                TextField(label, text: $text)
                    .keyboardType(numbersOnly ? .numberPad : .decimalPad)
                    .focused($focused)
                    .onSubmit { commit() }
                    .foregroundStyle(invalid ? Color.red : Color.primary)
                if !unit.isEmpty { Text(unit).foregroundStyle(.secondary) }
            }
            if invalid, let invalidHint {
                Text(invalidHint).font(.caption).foregroundStyle(.red)
            }
        }
        .onAppear {
            text = stored
            handedOver = stored
        }
        .onChange(of: stored) { _, value in
            text = value
            handedOver = value
        }
        .onChange(of: focused) { _, isFocused in
            if !isFocused { commit() }
        }
        .task(id: text) {
            guard pending else { return }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            if !Task.isCancelled { commit() }
        }
        .onDisappear { commit() }
    }

    private var trimmed: String { text.trimmingCharacters(in: .whitespaces) }
    private var invalid: Bool { !trimmed.isEmpty && !isValid(trimmed) }
    private var pending: Bool { trimmed != handedOver && isValid(trimmed) }

    private func commit() {
        guard pending else { return }
        handedOver = trimmed
        onSave(trimmed)
    }
}

/// "Lose weight" (Android `WeightGoalSheet`): optional target weight and the
/// pace in 0.1 kg steps, with the resulting calorie target and every warning
/// that applies – live while choosing, never blocking the choice. While the
/// goal runs every change is kept at once.
struct NutritionGoalSheet: View {
    let host: ScreenHost<NutritionScreenModel, NutritionDayUi>
    let form: WeightGoalForm
    let onDone: () -> Void
    @State private var ui: WeightGoalUi
    @State private var info = false

    init(host: ScreenHost<NutritionScreenModel, NutritionDayUi>, form: WeightGoalForm, onDone: @escaping () -> Void) {
        self.host = host
        self.form = form
        self.onDone = onDone
        _ui = State(initialValue: form.current)
    }

    var body: some View {
        NavigationStack {
            Form {
                if ui.youthWarning {
                    Label(L("trn_w_youth"), systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
                }
                if ui.needsWeight {
                    Section {
                        NutritionAutoSaveField(label: L("tru_weight_title"), unit: ui.massUnit,
                                               isValid: { host.model.isWeightValid(text: $0) },
                                               onSave: { _ = host.model.enterWeight(text: $0) })
                    } header: {
                        Text(L("trn_needs_weight")).textCase(nil)
                    }
                }
                Section {
                    HStack {
                        TextField(L("trn_goal_target"), text: Binding(get: { ui.targetText }, set: { ui = form.setTarget(text: $0) }))
                            .keyboardType(.decimalPad)
                            .foregroundStyle(ui.targetInvalid ? Color.red : Color.primary)
                        Text(ui.massUnit).foregroundStyle(.secondary)
                    }
                }
                Section(L("trn_goal_pace")) {
                    HStack {
                        Button { ui = form.decreasePace() } label: { Image(systemName: "minus.circle.fill").font(.title2) }
                            .buttonStyle(.borderless)
                            .disabled(!ui.canDecrease)
                            .accessibilityLabel(L("trn_goal_pace_minus"))
                        Spacer()
                        Text(NutritionText.render(ui.pace)).font(.headline)
                        Spacer()
                        Button { ui = form.increasePace() } label: { Image(systemName: "plus.circle.fill").font(.title2) }
                            .buttonStyle(.borderless)
                            .disabled(!ui.canIncrease)
                            .accessibilityLabel(L("trn_goal_pace_plus"))
                    }
                    if ui.hasPlan {
                        Text(NutritionText.render(ui.paceShare)).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                if ui.hasPlan {
                    Section {
                        if ui.targetReached {
                            Text(L("trn_goal_reached"))
                        } else if ui.hasTargetKcal {
                            Text(NutritionText.render(ui.targetToday)).font(.headline)
                            Text(NutritionText.render(ui.targetMath)).font(.footnote).foregroundStyle(.secondary)
                        }
                        ForEach(Array(ui.planNotes.enumerated()), id: \.offset) { _, note in
                            Text(NutritionText.render(note)).font(.footnote).foregroundStyle(.secondary)
                        }
                        if ui.hasWeeks {
                            Text(NutritionText.render(ui.weeks))
                        }
                        if ui.needsHeight {
                            Text(L("trn_needs_height")).font(.footnote)
                            NutritionAutoSaveField(label: NutritionText.render(ui.heightLabel), unit: ui.lengthUnit,
                                                   isValid: { host.model.isHeightValid(text: $0) },
                                                   onSave: { _ = host.model.enterHeight(text: $0) })
                        }
                    }
                    // The same reasons as on the energy card, live while choosing.
                    if !ui.warnings.isEmpty {
                        Section {
                            ForEach(ui.warnings, id: \.code) { warning in
                                Label(NutritionText.render(warning.text), systemImage: "exclamationmark.triangle")
                                    .font(.footnote)
                                    .foregroundStyle(.orange)
                            }
                        }
                    }
                }
                Section {
                    if ui.losing {
                        Button(L("tru_done")) {
                            form.keep()
                            onDone()
                        }
                        Button(L("trn_goal_stop"), role: .destructive) {
                            form.stop()
                            onDone()
                        }
                    } else {
                        Button(L("trn_goal_start")) {
                            if form.start() { onDone() }
                        }
                        .disabled(!ui.canStart)
                    }
                }
            }
            .navigationTitle(L("trn_goal_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { info = true } label: { Image(systemName: "info.circle") }
                        .accessibilityLabel(L("trn_goal_title"))
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("tr_action_cancel")) {
                        form.keep()
                        onDone()
                    }
                }
            }
            .alert(L("trn_goal_title"), isPresented: $info) {
                Button(L("action_close"), role: .cancel) {}
            } message: {
                Text(L("trn_goal_info"))
            }
            // Weight or height entered here, or the goal switched on, changes what the plan can say.
            .onChange(of: host.state.energySheet.goalButtonKey) { _, _ in ui = form.current }
            .onChange(of: host.state.needsWeight) { _, _ in ui = form.current }
            .onChange(of: host.state.energySheet.needsHeight) { _, _ in ui = form.current }
        }
    }
}
