import CruxCoachCore
import SwiftUI

/// Nutrition settings (Android: the training settings' nutrition section,
/// `UnitsSection` and `FoodDataStorageSection`): calories shown or hidden,
/// everyday activity, every target recommended or own, protein per kg, the
/// unit system, and the space the product database takes.
struct NutritionSettingsView: View {
    let host: ScreenHost<NutritionScreenModel, NutritionDayUi>

    private var model: NutritionScreenModel { host.model }

    var body: some View {
        let settings = host.state.settings
        Form {
            Section {
                Toggle(L("tra_show_calories"), isOn: Binding(get: { settings.showCalories }, set: { model.setShowCalories(show: $0) }))
                Picker(L("trn_set_everyday"), selection: Binding(
                    get: { settings.everydayChoices.first(where: { $0.selected })?.code ?? "seated" },
                    set: { model.setEverydayActivity(code: $0) })) {
                    ForEach(settings.everydayChoices, id: \.code) { choice in
                        Text(NutritionText.render(choice.label)).tag(choice.code)
                    }
                }
            }
            Section(L("trn_targets_title")) {
                // Hidden calories hide the number, not the own target: it still counts and can be taken back.
                if settings.ownKcalHidden {
                    Text(L("trn_own_kcal_hidden")).font(.footnote)
                    Button(L("trn_own_kcal_reset")) { model.followRecommendation(kindCode: "energy") }
                }
                ForEach(settings.targets, id: \.kindCode) { target in
                    NutritionTargetEditor(target: target, model: model)
                    // The protein recommendation per kg, while protein follows it.
                    if target.kindCode == "protein" && settings.showProteinPerKg {
                        ProteinPerKgRow(settings: settings, model: model)
                    }
                }
            }
            Section {
                Picker(L("settings_units_title"), selection: Binding(get: { settings.unitsCode }, set: { model.setUnits(code: $0) })) {
                    Text(L("settings_units_metric")).tag("metric")
                    Text(L("settings_units_us")).tag("us")
                }
                .pickerStyle(.inline)
            } header: {
                Text(L("settings_units_title"))
            } footer: {
                Text(L("settings_units_desc"))
            }
            Section {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("settings_food_products", ByteCountFormatter.string(fromByteCount: settings.productBytes, countStyle: .file)))
                        Text(L("settings_food_products_hint")).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button(L("settings_food_remove")) { model.removeProducts() }
                        .buttonStyle(.bordered)
                        .disabled(settings.productBytes <= 0)
                }
            } header: {
                Text(L("settings_food_data_title"))
            }
        }
        .navigationTitle(L("tra_module_fuel"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.refreshProductBytes() }
    }
}

/// Up to 2.4 g/kg in 0.1 steps: the choice stays with the athlete.
private struct ProteinPerKgRow: View {
    let settings: NutritionSettingsUi
    let model: NutritionScreenModel
    @State private var value: Double = 1.6

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(NutritionText.render(settings.proteinPerKgText)).font(.footnote)
            Slider(value: $value, in: settings.proteinMin...settings.proteinMax, step: 0.1) { editing in
                if !editing { model.setProteinPerKg(value: value) }
            }
            // While losing weight the recommendation is higher, and named with its numbers.
            if settings.hasProteinLosingHint {
                Text(NutritionText.render(settings.proteinLosingHint)).font(.caption).foregroundStyle(.secondary)
            }
        }
        .onAppear { value = settings.proteinPerKg }
        .onChange(of: settings.proteinPerKg) { _, next in value = next }
    }
}
