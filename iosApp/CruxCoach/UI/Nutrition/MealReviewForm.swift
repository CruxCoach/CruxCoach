import CruxCoachCore
import SwiftUI
import UIKit

/// The review list both the typed meal and the meal photo end in (Android's
/// `ReviewPane`): check or uncheck lines, change amounts (typed, or in one tap:
/// shares of the amount, whole pieces for counted foods), pick another food, a
/// packaged product or none, add a food that was missed, choose the meal, log.
/// Under a typed meal's rules list it may offer the model's second pass.
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
        VStack(alignment: .leading, spacing: 6) {
            mainLine
            if row.quick != "none" {
                // Leaving the field first lets the corrected amount show in it.
                MealQuickAmounts(row: row, model: model, onTap: { focused = false })
            }
        }
        .onAppear { amount = row.amountText }
        .onChange(of: row.amountText) { _, value in
            if !focused && value != amount { amount = value }
        }
    }

    private var mainLine: some View {
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
    }
}

/// One of Android's share chips: its label, the factor and what VoiceOver says.
private struct MealScaleStep {
    let label: String
    let factor: Double
    let key: String
}

/// One tap to correct an estimate (Android `QuickAmounts`; the photo model is about a
/// quarter off per food): whole pieces for a food people count, otherwise ½, ¾, 1¼ and
/// 1½ of the amount. The field stays for any other number.
private struct MealQuickAmounts: View {
    let row: MealReviewRowUi
    let model: MealTextScreenModel
    let onTap: () -> Void

    private static let steps: [MealScaleStep] = [
        MealScaleStep(label: "×½", factor: 0.5, key: "fvp_amount_scale_half"),
        MealScaleStep(label: "×¾", factor: 0.75, key: "fvp_amount_scale_three_quarters"),
        MealScaleStep(label: "×1¼", factor: 1.25, key: "fvp_amount_scale_more_quarter"),
        MealScaleStep(label: "×1½", factor: 1.5, key: "fvp_amount_scale_more_half"),
    ]

    var body: some View {
        if row.quick == "pieces" {
            piecesLine
        } else {
            scaleLine
        }
    }

    private var piecesLine: some View {
        HStack(spacing: 8) {
            Button {
                onTap()
                model.stepPieces(key: row.key, delta: -1)
            } label: {
                Text(verbatim: "−1")
            }
            .disabled(!row.canRemovePiece)
            .accessibilityLabel(L("fvp_piece_less"))
            Text(NutritionText.render(row.piecesText)).font(.caption)
            Button {
                onTap()
                model.stepPieces(key: row.key, delta: 1)
            } label: {
                Text(verbatim: "+1")
            }
            .accessibilityLabel(L("fvp_piece_more"))
        }
        .buttonStyle(.bordered)
        .controlSize(.small)
    }

    private var scaleLine: some View {
        HStack(spacing: 6) {
            ForEach(Self.steps, id: \.label) { step in
                scaleButton(step)
            }
        }
        .buttonStyle(.bordered)
        .controlSize(.small)
    }

    private func scaleButton(_ step: MealScaleStep) -> some View {
        Button {
            onTap()
            model.scaleAmount(key: row.key, factor: step.factor)
        } label: {
            Text(verbatim: step.label)
        }
        .accessibilityLabel(L(step.key))
    }
}

/// Another food for a line, a packaged product, "no food", or a food the parser missed.
/// A line the food table found nothing for opens with its own name as the search.
struct MealChooseFoodSheet: View {
    let model: MealTextScreenModel
    let ui: MealTextUi
    @State private var query = ""

    var body: some View {
        NavigationStack {
            choiceList
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

    private var choiceList: some View {
        List {
            ForEach(ui.choices, id: \.code) { choice in
                choiceButton(choice)
            }
        }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: L("fvp_choose_search"))
        // The core may start the search with the line's own name ("Whey"), so the products show at once.
        .onAppear { if query.isEmpty { query = ui.choiceQuery } }
        .onChange(of: query) { _, value in model.setChoiceQuery(text: value) }
    }

    /// A food, or "Product · name" for a packaged product (Android `fvp_product_label`).
    private func choiceButton(_ choice: FoodChoiceUi) -> some View {
        Button { model.pick(code: choice.code) } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(NutritionText.render(choice.label)).foregroundStyle(.primary)
                Text(NutritionText.render(choice.subtitle)).font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}
