import CruxCoachCore
import SwiftUI

/// Amount for a saved food (Android `AmountDialog`): portions always; grams
/// (or oz, ml, fl oz, cups) when the food has per-100 g values. The live
/// preview and the validation are the Kotlin form's.
struct FoodAmountSheet: View {
    let form: FoodAmountForm
    let onDone: () -> Void
    @State private var ui: FoodAmountUi

    init(form: FoodAmountForm, onDone: @escaping () -> Void) {
        self.form = form
        self.onDone = onDone
        _ui = State(initialValue: form.current)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(L("trf_amount_title")) {
                    if ui.showChoice {
                        Picker(L("trf_amount_title"), selection: Binding(
                            get: { ui.useGrams ? (ui.units.first(where: { $0.selected })?.code ?? "") : "portions" },
                            set: { code in
                                if code == "portions" { ui = form.usePortions() } else { ui = form.switchUnit(code: code) }
                            })) {
                            if ui.canPortion { Text(L("trf_amount_portions")).tag("portions") }
                            ForEach(ui.units, id: \.code) { unit in
                                Text(NutritionText.render(unit.label)).tag(unit.code)
                            }
                        }
                        .pickerStyle(.segmented)
                    }
                    if ui.useGrams {
                        HStack {
                            TextField(NutritionText.render(ui.amountLabel),
                                      text: Binding(get: { ui.amountText }, set: { ui = form.setAmount(text: $0) }))
                                .keyboardType(.decimalPad)
                            Text(NutritionText.render(ui.amountLabel)).foregroundStyle(.secondary)
                        }
                        .foregroundStyle(ui.amountInvalid ? Color.red : Color.primary)
                    } else {
                        HStack {
                            ForEach(ui.presets, id: \.self) { preset in
                                Button(preset) { ui = form.setPortions(text: preset) }
                                    .buttonStyle(.bordered)
                            }
                        }
                        HStack {
                            TextField(L("trf_amount_portions"),
                                      text: Binding(get: { ui.portionsText }, set: { ui = form.setPortions(text: $0) }))
                                .keyboardType(.decimalPad)
                            Text(L("trf_amount_portions")).foregroundStyle(.secondary)
                        }
                        .foregroundStyle(ui.portionsInvalid ? Color.red : Color.primary)
                        if ui.hasServing {
                            Text(NutritionText.render(ui.serving)).font(.footnote)
                        }
                    }
                    // What this amount brings, while typing.
                    if ui.hasPreview {
                        Text(NutritionText.render(ui.preview)).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                if ui.showMeal {
                    Section {
                        Picker(L("trf_qa_meal"), selection: Binding(get: { ui.mealCode }, set: { ui = form.setMeal(code: $0) })) {
                            ForEach(NutritionText.mealCodes, id: \.self) { code in
                                Text(NutritionText.mealTitle(code)).tag(code)
                            }
                        }
                    }
                }
            }
            .navigationTitle(ui.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel"), action: onDone) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L(ui.confirmKey)) {
                        if form.confirm() { onDone() }
                    }
                    .disabled(!ui.valid)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// Quick entry (Android `QuickAddSheet`): a name and any subset of nutrients.
struct NutritionQuickEntrySheet: View {
    let form: QuickEntryForm
    let onDone: () -> Void
    @State private var ui: QuickEntryUi

    init(form: QuickEntryForm, onDone: @escaping () -> Void) {
        self.form = form
        self.onDone = onDone
        _ui = State(initialValue: form.current)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L("trf_qa_name"), text: Binding(get: { ui.name }, set: { ui = form.setName(text: $0) }))
                    Picker(L("trf_qa_meal"), selection: Binding(get: { ui.mealCode }, set: { ui = form.setMeal(code: $0) })) {
                        ForEach(NutritionText.mealCodes, id: \.self) { code in
                            Text(NutritionText.mealTitle(code)).tag(code)
                        }
                    }
                }
                Section {
                    NutritionNumberField(label: L("trf_qa_protein"), text: ui.protein, invalid: ui.proteinInvalid) { ui = form.setProtein(text: $0) }
                    NutritionNumberField(label: L("trf_qa_carbs"), text: ui.carbs, invalid: ui.carbsInvalid) { ui = form.setCarbs(text: $0) }
                    NutritionNumberField(label: L("trf_qa_fat"), text: ui.fat, invalid: ui.fatInvalid) { ui = form.setFat(text: $0) }
                    NutritionNumberField(label: L("trf_qa_kcal"), text: ui.kcal, invalid: ui.kcalInvalid) { ui = form.setKcal(text: $0) }
                } footer: {
                    Text(L("trf_qa_optional"))
                }
                if ui.showRemember {
                    Section {
                        Toggle(L("trf_qa_remember"), isOn: Binding(get: { ui.remember }, set: { ui = form.setRemember(value: $0) }))
                    }
                }
            }
            .navigationTitle(ui.isEditing ? L("trf_edit_title") : L("trf_qa_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel"), action: onDone) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("tr_action_save")) {
                        if form.save(defaultName: L("trf_qa_default_name")) { onDone() }
                    }
                    .disabled(!ui.valid)
                }
            }
        }
    }
}

/// A number field for one nutrient; red while what is typed is not a number ≥ 0.
struct NutritionNumberField: View {
    let label: String
    let text: String
    let invalid: Bool
    let onChange: (String) -> Void

    var body: some View {
        HStack {
            Text(label)
            Spacer()
            TextField(label, text: Binding(get: { text }, set: { onChange($0) }))
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .frame(maxWidth: 120)
                .foregroundStyle(invalid ? Color.red : Color.primary)
        }
    }
}

/// A food with per-100 g values and an optional portion size (Android `CreateFoodDialog`).
/// US labels give values per serving, European ones per 100 g.
struct CreateFoodSheet: View {
    let form: CreateFoodForm
    let onDone: () -> Void
    let onCancel: () -> Void
    @State private var ui: CreateFoodUi

    init(form: CreateFoodForm, onDone: @escaping () -> Void, onCancel: @escaping () -> Void) {
        self.form = form
        self.onDone = onDone
        self.onCancel = onCancel
        _ui = State(initialValue: form.current)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L("trf_create_name"), text: Binding(get: { ui.name }, set: { ui = form.setName(text: $0) }))
                    TextField(L("trf_create_brand"), text: Binding(get: { ui.brand }, set: { ui = form.setBrand(text: $0) }))
                    if !ui.barcode.isEmpty {
                        Text(L("trf_create_barcode", ui.barcode)).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Section {
                    Picker(L("trf_create_title"), selection: Binding(get: { ui.perServing }, set: { ui = form.setPerServing(value: $0) })) {
                        Text(L("trf_create_values_100g")).tag(false)
                        Text(L("trf_create_values_serving")).tag(true)
                    }
                    .pickerStyle(.segmented)
                    NutritionNumberField(label: L("trf_qa_protein"), text: ui.protein, invalid: ui.invalidFields.contains("protein")) { ui = form.setProtein(text: $0) }
                    NutritionNumberField(label: L("trf_qa_carbs"), text: ui.carbs, invalid: ui.invalidFields.contains("carbs")) { ui = form.setCarbs(text: $0) }
                    NutritionNumberField(label: L("trf_qa_fat"), text: ui.fat, invalid: ui.invalidFields.contains("fat")) { ui = form.setFat(text: $0) }
                    NutritionNumberField(label: L("trf_qa_kcal"), text: ui.kcal, invalid: ui.invalidFields.contains("kcal")) { ui = form.setKcal(text: $0) }
                } header: {
                    Text(ui.perServing ? L("trf_create_per_serving") : L("trf_create_per100"))
                }
                Section {
                    NutritionNumberField(label: L("trf_create_serving_g"), text: ui.servingG, invalid: ui.invalidFields.contains("serving")) {
                        ui = form.setServingGrams(text: $0)
                    }
                    TextField(L("trf_create_serving_label"), text: Binding(get: { ui.servingLabel }, set: { ui = form.setServingLabel(text: $0) }))
                }
            }
            .navigationTitle(L("trf_create_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel"), action: onCancel) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("tr_action_save")) {
                        if form.save() { onDone() }
                    }
                    .disabled(!ui.valid)
                }
            }
        }
    }
}

/// Writes a recipe: name, ingredients with their raw weight, portions and,
/// if weighed, the finished dish. It is saved as one of "my foods" and
/// logged by the portion.
struct NutritionRecipeSheet: View {
    let form: RecipeForm
    let onAddIngredient: () -> Void
    /// true when the recipe was saved.
    let onDone: (Bool) -> Void
    @State private var ui: RecipeUi

    init(form: RecipeForm, onAddIngredient: @escaping () -> Void, onDone: @escaping (Bool) -> Void) {
        self.form = form
        self.onAddIngredient = onAddIngredient
        self.onDone = onDone
        _ui = State(initialValue: form.current)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L("trf_recipe_name"), text: Binding(get: { ui.name }, set: { ui = form.setName(text: $0) }))
                }
                Section(L("trf_recipe_ingredients")) {
                    ForEach(ui.ingredients, id: \.index) { ingredient in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(ingredient.name)
                                Text(NutritionText.render(ingredient.amount)).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button { ui = form.removeIngredient(index: ingredient.index) } label: {
                                Image(systemName: "xmark.circle")
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(L("tr_action_delete"))
                        }
                    }
                    Button(action: onAddIngredient) { Label(L("trf_recipe_add"), systemImage: "plus") }
                }
                Section {
                    NutritionNumberField(label: L("trf_recipe_portions"), text: ui.portions, invalid: false) { ui = form.setPortions(text: $0) }
                    NutritionNumberField(label: NutritionText.render(ui.cookedLabel), text: ui.cooked, invalid: false) { ui = form.setCooked(text: $0) }
                } footer: {
                    Text(L("trf_recipe_cooked_hint"))
                }
                if ui.hasSummary {
                    Section {
                        Text(NutritionText.render(ui.summary)).font(.subheadline)
                    }
                }
            }
            .navigationTitle(L("trf_recipe_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel")) { onDone(false) } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("tr_action_save")) {
                        if form.save() { onDone(true) }
                    }
                    .disabled(!ui.valid)
                }
            }
            // An ingredient added through the picker changes the form behind this view.
            .onAppear { ui = form.current }
        }
    }
}

/// "Weight today" (Android `WeightSheet`): one big number, −/+ in 0.1 kg
/// (0.2 lb) steps, and why it is asked. Saving is a deliberate tap.
struct NutritionWeightSheet: View {
    let model: NutritionScreenModel
    /// "kg" or "lb".
    let unit: String
    let reason: String
    let onDone: () -> Void
    @State private var text = ""

    private var step: Double { unit == "lb" ? 0.2 : 0.1 }

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                Text(reason).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                HStack(spacing: 12) {
                    Button { nudge(-step) } label: { Image(systemName: "minus.circle.fill").font(.largeTitle) }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(L("tru_weight_less"))
                    TextField(unit == "lb" ? "150" : "70", text: $text)
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.center)
                        .font(.largeTitle.weight(.bold))
                        .frame(maxWidth: 160)
                    Text(unit).font(.title3).foregroundStyle(.secondary)
                    Button { nudge(step) } label: { Image(systemName: "plus.circle.fill").font(.largeTitle) }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(L("tru_weight_more"))
                }
                Button {
                    if model.enterWeight(text: text) { onDone() }
                } label: {
                    Text(L("tr_action_save")).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(!model.isWeightValid(text: text))
                Spacer()
            }
            .padding()
            .navigationTitle(L("tru_weight_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel"), action: onDone) }
            }
        }
        .presentationDetents([.medium])
    }

    private func nudge(_ by: Double) {
        let current = Double(text.replacingOccurrences(of: ",", with: ".")) ?? (unit == "lb" ? 150 : 70)
        text = NutritionText.number(((current + by) * 10).rounded() / 10, decimals: 1)
    }
}
