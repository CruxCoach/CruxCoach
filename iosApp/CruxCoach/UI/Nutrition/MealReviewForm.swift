import CruxCoachCore
import SwiftUI
import UIKit

/// The review list both the typed meal and the meal photo end in (Android's
/// `ReviewPane`): check or uncheck lines, change amounts, pick another food or
/// none, add a food that was missed, choose the meal, log. Under a typed
/// meal's rules list it may offer the model's second pass.
struct MealReviewForm: View {
    let model: MealTextScreenModel
    let ui: MealTextUi
    let hint: String
    let preview: UIImage?

    var body: some View {
        Form {
            itemsSection
            saveSection
        }
        .sheet(isPresented: choosing) {
            MealChooseFoodSheet(model: model, ui: ui)
        }
    }

    private var choosing: Binding<Bool> {
        Binding(get: { ui.choosingKey != -1 }, set: { if !$0 { model.endChoice() } })
    }

    private var itemsSection: some View {
        Section {
            if let preview {
                Image(uiImage: preview)
                    .resizable()
                    .scaledToFill()
                    .frame(height: 160)
                    .clipped()
                    .accessibilityLabel(L("fvp_photo_description"))
            }
            Text(hint).font(.footnote).foregroundStyle(.secondary)
            if ui.canAskModel {
                // Android's second opinion under a typed meal's rules list; the core offers it
                // only with the installed, timed model on a phone that passed the device check.
                Button { model.readWithModel() } label: {
                    Label(L("fvp_text_model"), systemImage: "sparkles")
                }
            }
            if ui.rows.isEmpty {
                Text(L("fvp_review_empty"))
            }
            ForEach(ui.rows, id: \.key) { row in
                MealReviewRow(row: row, model: model)
            }
            Button { model.beginChoice(key: -2) } label: {
                Label(L("fvp_add_item"), systemImage: "plus")
            }
        }
    }

    private var saveSection: some View {
        Section {
            Picker(L("trf_qa_meal"), selection: Binding(get: { ui.mealCode }, set: { model.setMeal(code: $0) })) {
                ForEach(NutritionText.mealCodes, id: \.self) { code in
                    Text(NutritionText.mealTitle(code)).tag(code)
                }
            }
            Button(NutritionText.render(ui.saveLabel)) { model.save() }
                .disabled(ui.saveCount == 0)
        } footer: {
            Text(L("fvp_attribution"))
        }
    }
}

struct MealReviewRow: View {
    let row: MealReviewRowUi
    let model: MealTextScreenModel
    /// The typed amount, kept here for the same reason as the description.
    @State private var amount = ""
    /// While typing, the core's echoes lag behind and are not written back; changes made
    /// elsewhere (another food switches g to ml) arrive while the field is not focused.
    @FocusState private var focused: Bool

    var body: some View {
        HStack(alignment: .top) {
            Button {
                model.setIncluded(key: row.key, included: !row.included)
            } label: {
                Image(systemName: row.included ? "checkmark.square.fill" : "square")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L("fvp_include_food", row.isWater ? L("fvp_water_item") : row.title))
            Button {
                if !row.isWater { model.beginChoice(key: row.key) }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        if row.isWater { Image(systemName: "waterbottle") }
                        Text(row.isWater ? L("fvp_water_item") : row.title).foregroundStyle(.primary)
                        if row.uncertain {
                            Text(L("fvp_uncertain")).font(.caption2).foregroundStyle(.orange)
                        }
                    }
                    if !row.isWater {
                        if row.hasChoice {
                            Text(NutritionText.render(row.macros)).font(.caption).foregroundStyle(.secondary)
                        } else {
                            Text(L("fvp_no_match")).font(.caption).foregroundStyle(.red)
                        }
                    }
                    if row.hasDetectedLine {
                        Text(NutritionText.render(row.detectedLine)).font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }
            .buttonStyle(.borderless)
            Spacer()
            HStack(spacing: 2) {
                TextField("", text: $amount)
                    .focused($focused)
                    .keyboardType(.decimalPad)
                    .onChange(of: amount) { _, value in
                        if value != row.amountText { model.setAmount(key: row.key, text: value) }
                    }
                    .multilineTextAlignment(.trailing)
                    .frame(width: 56)
                    .foregroundStyle(row.amountInvalid ? Color.red : Color.primary)
                Text(NutritionText.render(row.unitLabel)).font(.caption).foregroundStyle(.secondary)
            }
        }
        .onAppear { amount = row.amountText }
        .onChange(of: row.amountText) { _, value in
            if !focused && value != amount { amount = value }
        }
    }
}

/// Another food for a line, "no food", or a food the parser missed.
struct MealChooseFoodSheet: View {
    let model: MealTextScreenModel
    let ui: MealTextUi
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                ForEach(ui.choices, id: \.code) { choice in
                    Button { model.pick(code: choice.code) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(choice.title).foregroundStyle(.primary)
                            Text(NutritionText.render(choice.subtitle)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: L("fvp_choose_search"))
            .onChange(of: query) { _, value in model.setChoiceQuery(text: value) }
            .navigationTitle(L("fvp_choose_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel")) { model.endChoice() } }
                if ui.allowNone {
                    ToolbarItem(placement: .confirmationAction) { Button(L("fvp_choose_none")) { model.pick(code: "") } }
                }
            }
        }
    }
}
