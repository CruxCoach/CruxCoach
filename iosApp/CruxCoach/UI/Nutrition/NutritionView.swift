import CruxCoachCore
import SwiftUI

/// What the nutrition tab presents on top of the day. One sheet at a time;
/// moving from one to the next (add → amount) replaces it.
enum NutritionSheet: Identifiable {
    case add
    case amount(FoodAmountForm)
    case quick(QuickEntryForm)
    case create(CreateFoodForm)
    case recipe(RecipeForm)
    case ingredient(RecipeForm)
    case describe(String)
    case energy
    case goal(WeightGoalForm)
    case weight
    case explain(String)
    case scan
    case settings

    var id: String {
        switch self {
        case .add: return "add"
        case .amount(let form): return "amount-\(ObjectIdentifier(form).hashValue)"
        case .quick(let form): return "quick-\(ObjectIdentifier(form).hashValue)"
        case .create(let form): return "create-\(ObjectIdentifier(form).hashValue)"
        case .recipe(let form): return "recipe-\(ObjectIdentifier(form).hashValue)"
        case .ingredient(let form): return "ingredient-\(ObjectIdentifier(form).hashValue)"
        case .describe(let meal): return "describe-\(meal)"
        case .energy: return "energy"
        case .goal(let form): return "goal-\(ObjectIdentifier(form).hashValue)"
        case .weight: return "weight"
        case .explain(let code): return "explain-\(code)"
        case .scan: return "scan"
        case .settings: return "settings"
        }
    }
}

/// The nutrition tab (Android `FuelScreen`): rings for energy, carbohydrate,
/// protein and fat against targets that follow the training day, water, the
/// meals with their entries, and every way of adding food.
struct NutritionView: View {
    let core: AppCore
    @State private var host: ScreenHost<NutritionScreenModel, NutritionDayUi>?
    @State private var unavailable = false
    @State private var sheet: NutritionSheet?
    @State private var confirmRepeat = false
    @State private var guideInfo = false
    /// The recipe whose ingredient is being picked; the amount goes back to it.
    @State private var recipeInProgress: RecipeForm?
    /// Sends the shown day's meals and water to Apple Health when that is switched on (Android: Health Connect export).
    @State private var healthActions: HealthActions?

    init(core: AppCore) {
        self.core = core
    }

    /// Changes whenever the day, its water or any total changes, i.e. when there is something new to send.
    private static func exportKey(_ ui: NutritionDayUi) -> String {
        ui.dayIso + "|" + NutritionText.render(ui.water.total) + "|" + ui.rings.map { NutritionText.render($0.value) }.joined(separator: ",")
    }

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else if unavailable {
                ContentUnavailableView(LI("nutrition_unavailable"), systemImage: "fork.knife",
                                       description: Text(LI("nutrition_unavailable_hint")))
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("tr_nav_fuel"))
        .onChange(of: host.map { Self.exportKey($0.state) } ?? "") { _, _ in
            if let day = host?.state.dayIso, !day.isEmpty { healthActions?.exportDay(dayIso: day) }
        }
        .task {
            if healthActions == nil { healthActions = core.makeHealthActions() }
            guard host == nil, !unavailable else { return }
            guard let model = core.makeNutritionScreen() else {
                unavailable = true
                return
            }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }

    @ViewBuilder
    private func screen(model: NutritionScreenModel, ui: NutritionDayUi) -> some View {
        if ui.isLoading {
            ProgressView()
        } else if ui.failed {
            ContentUnavailableView(LI("nutrition_unavailable"), systemImage: "exclamationmark.triangle",
                                   description: Text(LI("nutrition_unavailable_hint")))
        } else {
            dayList(model: model, ui: ui)
                .toolbar { toolbar(model: model, ui: ui) }
                .safeAreaInset(edge: .bottom) { bottomBar(model: model, ui: ui) }
                .sheet(item: $sheet) { which in
                    sheetContent(which, model: model)
                }
                .alert(L("trf_copy_confirm_title"), isPresented: $confirmRepeat) {
                    Button(L("tr_action_cancel"), role: .cancel) {}
                    Button(L("trf_copy_confirm")) { model.repeatPreviousDay() }
                } message: {
                    Text(NutritionText.render(ui.repeatConfirmText))
                }
                .alert(L("trf_intro_title"), isPresented: $guideInfo) {
                    Button(L("action_close"), role: .cancel) {}
                } message: {
                    Text(NutritionGuide.text)
                }
                .alert(L("trf_scan_not_found_title"), isPresented: Binding(
                    get: { ui.scan.phase == "missing" },
                    set: { if !$0 { model.consumeScan() } })) {
                    Button(L("tr_action_cancel"), role: .cancel) { model.consumeScan() }
                    Button(L("trf_scan_create")) {
                        let code = ui.scan.code
                        model.consumeScan()
                        sheet = .create(model.createFoodForm(barcode: code))
                    }
                } message: {
                    Text(L("trf_scan_not_found", ui.scan.code))
                }
                .onChange(of: ui.scan.phase) { _, phase in
                    // The scanner stays up until the code is looked up: a found code replaces it with
                    // its amount (as a picked search row does), a missing one closes it for the alert.
                    if phase == "found" {
                        let ref = ui.scan.ref
                        model.consumeScan()
                        if let form = model.amountForm(ref: ref) { sheet = .amount(form) } else { sheet = nil }
                    } else if phase == "missing" {
                        sheet = nil
                    }
                }
        }
    }

    // MARK: Day

    private func dayList(model: NutritionScreenModel, ui: NutritionDayUi) -> some View {
        List {
            if ui.showIntro {
                Section { NutritionIntroCard(onInfo: { guideInfo = true }, onGotIt: { model.acceptIntro() }) }
            }
            Section {
                NutritionDayHeader(ui: ui, model: model)
            }
            if ui.energyCard.visible {
                Section {
                    NutritionEnergyCard(card: ui.energyCard, onAdjust: { sheet = .goal(model.weightGoalForm()) })
                }
            }
            if ui.needsWeight {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Label(L("tru_fuel_targets_title"), systemImage: "scalemass").font(.headline)
                        Text(L("trf_needs_weight")).font(.subheadline).foregroundStyle(.secondary)
                        Button(L("tru_enter_weight")) { sheet = .weight }.buttonStyle(.borderedProminent)
                    }
                }
            }
            Section {
                NutritionRingsRow(rings: ui.rings) { code in
                    if code == "energy" { sheet = .energy } else { sheet = .explain(code) }
                }
                ForEach(Array(ui.ownNotes.enumerated()), id: \.offset) { _, note in
                    Text(NutritionText.render(note)).font(.footnote).foregroundStyle(.secondary)
                }
            }
            Section {
                NutritionWaterCard(water: ui.water,
                                   onAdd: { model.addGlass() },
                                   onRemoveLast: { model.removeLastGlass() })
            }
            if ui.showRepeatCard {
                Section {
                    HStack {
                        Image(systemName: "doc.on.doc").foregroundStyle(Color.accentColor)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("tru_copy_title")).font(.subheadline.weight(.semibold))
                            Text(NutritionText.render(ui.repeatCardText)).font(.footnote).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button(L("trf_copy_confirm")) { confirmRepeat = true }
                    }
                }
            }
            ForEach(ui.meals, id: \.code) { meal in
                NutritionMealSection(meal: meal,
                                     onAdd: { openAdd(model: model, meal: meal.code) },
                                     onEdit: { entry in edit(model: model, entry: entry) },
                                     onDelete: { entry in model.deleteEntry(id: entry.id) })
            }
            if ui.showRepeatLink {
                Section {
                    Button { confirmRepeat = true } label: {
                        Label(L("trf_copy_previous"), systemImage: "doc.on.doc")
                    }
                }
            }
            if ui.micros.visible {
                Section { NutritionMicroCard(micros: ui.micros) }
            }
        }
    }

    @ToolbarContentBuilder
    private func toolbar(model: NutritionScreenModel, ui: NutritionDayUi) -> some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            // Once the intro card is gone, its full explanation stays one tap away in the app bar.
            if ui.showGuideButton {
                Button { guideInfo = true } label: {
                    Image(systemName: "info.circle")
                }
                .accessibilityLabel(L("trf_intro_title"))
            }
            Button { sheet = .settings } label: {
                Image(systemName: "slider.horizontal.3")
            }
            .accessibilityLabel(L("tr_action_settings"))
        }
    }

    /// One way in for every kind of food, and the undo line.
    private func bottomBar(model: NutritionScreenModel, ui: NutritionDayUi) -> some View {
        VStack(spacing: 8) {
            if ui.undo.visible {
                HStack {
                    Text(NutritionText.render(ui.undo.message)).font(.subheadline).lineLimit(2)
                    Spacer()
                    Button(L("trf_undo")) { model.undo() }.fontWeight(.semibold)
                    Button { model.dismissUndo() } label: { Image(systemName: "xmark") }
                        .accessibilityLabel(L("action_close"))
                }
                .padding(12)
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
                .task(id: NutritionText.render(ui.undo.message)) {
                    try? await Task.sleep(nanoseconds: 5_000_000_000)
                    model.dismissUndo()
                }
            }
            HStack {
                Spacer()
                Button {
                    openAdd(model: model, meal: model.mealNowCode())
                } label: {
                    Label(L("tru_fuel_add"), systemImage: "plus")
                        .fontWeight(.semibold)
                        .padding(.horizontal, 8)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            }
        }
        .padding(.horizontal)
        .padding(.bottom, 8)
    }

    private func openAdd(model: NutritionScreenModel, meal: String) {
        model.beginSearch(purpose: "log", mealCode: meal, recipe: nil)
        sheet = .add
    }

    private func edit(model: NutritionScreenModel, entry: FoodEntryUi) {
        if let form = model.editForm(entryId: entry.id) {
            sheet = .amount(form)
        } else if let quick = model.quickEditForm(entryId: entry.id) {
            sheet = .quick(quick)
        }
    }

    // MARK: Sheets

    @ViewBuilder
    private func sheetContent(_ which: NutritionSheet, model: NutritionScreenModel) -> some View {
        switch which {
        case .add:
            if let host {
                FoodAddSheet(host: host, onSelect: { next in sheet = next }, onIngredientPicked: nil)
            }
        case .ingredient(let recipe):
            if let host {
                // Closing the ingredient picker goes back to the recipe, not out of it.
                FoodAddSheet(host: host, onSelect: { next in sheet = next ?? .recipe(recipe) },
                             onIngredientPicked: { ref in
                             if let form = model.ingredientForm(ref: ref, recipe: recipe) {
                                 recipeInProgress = recipe
                                 sheet = .amount(form)
                             } else {
                                 sheet = .recipe(recipe)
                             }
                         })
            }
        case .amount(let form):
            FoodAmountSheet(form: form, onDone: {
                if let recipe = recipeInProgress {
                    recipeInProgress = nil
                    sheet = .recipe(recipe)
                } else {
                    sheet = nil
                }
            })
        case .quick(let form):
            NutritionQuickEntrySheet(form: form, onDone: { sheet = nil })
        case .create(let form):
            CreateFoodSheet(form: form, onDone: {
                // Back to the add sheet, where the new food now is.
                model.beginSearch(purpose: "log", mealCode: model.mealNowCode(), recipe: nil)
                sheet = .add
            }, onCancel: { sheet = nil })
        case .recipe(let form):
            NutritionRecipeSheet(form: form, onAddIngredient: {
                model.beginSearch(purpose: "ingredient", mealCode: "", recipe: form)
                sheet = .ingredient(form)
            }, onDone: { saved in
                if saved {
                    model.beginSearch(purpose: "log", mealCode: model.mealNowCode(), recipe: nil)
                    sheet = .add
                } else {
                    sheet = nil
                }
            })
        case .describe(let meal):
            MealTextSheet(core: core, dayIso: host?.state.dayIso ?? "", mealCode: meal,
                          unitsCode: host?.state.unitsCode ?? "metric", onDone: { sheet = nil })
        case .energy:
            if let host {
                NutritionEnergySheet(host: host, onGoal: { sheet = .goal(model.weightGoalForm()) }, onDone: { sheet = nil })
            }
        case .goal(let form):
            if let host {
                NutritionGoalSheet(host: host, form: form, onDone: { sheet = nil })
            }
        case .weight:
            if let host {
                NutritionWeightSheet(model: model, unit: host.state.massUnit, reason: L("tru_weight_reason_fuel"),
                                 onDone: { sheet = nil })
            }
        case .explain(let code):
            if let host {
                NutritionRingSheet(host: host, code: code, onDone: { sheet = nil })
            }
        case .scan:
            BarcodeScannerSheet(onCode: { code in model.lookupBarcode(code: code) }, onCancel: { sheet = nil })
        case .settings:
            if let host {
                NavigationStack {
                    NutritionSettingsView(host: host)
                        .toolbar {
                            ToolbarItem(placement: .confirmationAction) {
                                Button(L("action_close")) { sheet = nil }
                            }
                        }
                }
            }
        }
    }
}

/// How nutrition works and its guard rails: in the intro card's info, later in the app bar's.
enum NutritionGuide {
    static var text: String {
        ["trf_intro_p1", "trf_intro_protein", "trf_intro_carbs", "trf_intro_no_budget", "trf_intro_hidden", "trf_intro_private"]
            .map { L($0) }
            .joined(separator: "\n\n")
    }
}

/// Short on the screen, the full guard rails behind the info icon.
private struct NutritionIntroCard: View {
    let onInfo: () -> Void
    let onGotIt: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: "fork.knife").foregroundStyle(Color.accentColor)
                Text(L("trf_intro_title")).font(.headline)
                Spacer()
                Button(action: onInfo) { Image(systemName: "info.circle") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(L("trf_intro_title"))
            }
            Text(L("tru_fuel_intro_short")).font(.subheadline)
            HStack {
                Spacer()
                Button(L("trf_intro_got_it"), action: onGotIt).buttonStyle(.borderless)
            }
        }
    }
}

private struct NutritionDayHeader: View {
    let ui: NutritionDayUi
    let model: NutritionScreenModel

    var body: some View {
        HStack {
            Button { model.previousDay() } label: { Image(systemName: "chevron.left") }
                .buttonStyle(.borderless)
                .accessibilityLabel(L("trf_prev_day"))
            Spacer()
            VStack(spacing: 2) {
                Text(NutritionText.dayLabel(iso: ui.dayIso, isToday: ui.isToday, isYesterday: ui.isYesterday))
                    .font(.headline)
                Text(NutritionText.render(ui.dayLoad)).font(.caption).foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            Spacer()
            if !ui.isToday {
                Button(L("trf_today")) { model.goToday() }.buttonStyle(.borderless).font(.footnote)
            }
            Button { model.nextDay() } label: { Image(systemName: "chevron.right") }
                .buttonStyle(.borderless)
                .disabled(ui.isToday)
                .accessibilityLabel(L("trf_next_day"))
        }
    }
}

/// The energy card (RED-S guard): the same reasons wherever it appears; nothing is locked.
struct NutritionEnergyCard: View {
    let card: NutritionEnergyCardUi
    let onAdjust: () -> Void
    @State private var info = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: "heart.fill")
                Text(L("trt_reds_title")).font(.headline)
                Spacer()
                Button { info = true } label: { Image(systemName: "info.circle") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(L("trt_reds_title"))
            }
            ForEach(card.warnings, id: \.code) { warning in
                Text("• " + NutritionText.render(warning.text)).font(.subheadline)
            }
            if card.canAdjustGoal {
                HStack {
                    Spacer()
                    Button(L("trn_adjust_goal"), action: onAdjust).buttonStyle(.borderless)
                }
            }
        }
        .listRowBackground(Color.orange.opacity(0.15))
        .alert(L("trt_reds_title"), isPresented: $info) {
            Button(L("action_close"), role: .cancel) {}
        } message: {
            Text(L("trt_reds_info"))
        }
    }
}

private struct NutritionMealSection: View {
    let meal: NutritionMealUi
    let onAdd: () -> Void
    let onEdit: (FoodEntryUi) -> Void
    let onDelete: (FoodEntryUi) -> Void

    var body: some View {
        Section {
            ForEach(meal.entries, id: \.id) { entry in
                Button { onEdit(entry) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.name).foregroundStyle(.primary)
                        Text(NutritionText.render(entry.subtitle)).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .accessibilityHint(L("trf_entry_edit"))
                .swipeActions {
                    Button(L("tr_action_delete"), role: .destructive) { onDelete(entry) }
                    Button(L("trf_entry_edit")) { onEdit(entry) }
                }
                .contextMenu {
                    Button { onEdit(entry) } label: { Label(L("trf_entry_edit"), systemImage: "pencil") }
                    Button(role: .destructive) { onDelete(entry) } label: { Label(L("tr_action_delete"), systemImage: "trash") }
                }
            }
        } header: {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(NutritionText.render(meal.title)).font(.subheadline.weight(.semibold)).textCase(nil)
                    if meal.hasTotals {
                        Text(NutritionText.render(meal.totals)).font(.caption).textCase(nil)
                    }
                }
                Spacer()
                Button(action: onAdd) { Image(systemName: "plus.circle.fill").font(.title3) }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(NutritionText.render(meal.addLabel))
            }
        }
    }
}

private struct NutritionMicroCard: View {
    let micros: MicroWeekUi
    @State private var info = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(L("trf_micro_title")).font(.headline)
                Spacer()
                Button { info = true } label: { Image(systemName: "info.circle") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(L("trf_micro_title"))
            }
            ForEach(micros.items, id: \.code) { item in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(NutritionText.render(item.label)).font(.subheadline)
                        Spacer()
                        Text(NutritionText.render(item.value)).font(.caption)
                    }
                    ProgressView(value: item.progress).tint(.green)
                }
            }
            Text(NutritionText.render(micros.coverage)).font(.caption).foregroundStyle(.secondary)
        }
        .alert(L("trf_micro_title"), isPresented: $info) {
            Button(L("action_close"), role: .cancel) {}
        } message: {
            Text(L("trf_micro_info"))
        }
    }
}
