import CruxCoachCore
import SwiftUI

/// "Describe a meal" (Android `FoodTextSheet`): the athlete types or dictates
/// what they ate; a checkable review list turns it into food log entries. The
/// rule parser and the BLS matcher run on the device; no model is involved.
struct MealTextSheet: View {
    let core: AppCore
    let dayIso: String
    let mealCode: String
    let unitsCode: String
    let onDone: () -> Void
    @State private var host: ScreenHost<MealTextScreenModel, MealTextUi>?
    @State private var unavailable = false
    /// Typed text stays in the view: the core's state arrives a run-loop later, and binding a field to it loses keystrokes.
    @State private var text = ""

    var body: some View {
        NavigationStack {
            Group {
                if let host {
                    content(model: host.model, ui: host.state)
                } else if unavailable {
                    ContentUnavailableView(LI("nutrition_unavailable"), systemImage: "fork.knife")
                } else {
                    ProgressView()
                }
            }
            .navigationTitle(L("trf_describe_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel"), action: onDone) }
            }
        }
        .task {
            guard host == nil, !unavailable else { return }
            guard let model = core.makeMealTextScreen(dayIso: dayIso, mealCode: mealCode, unitsCode: unitsCode) else {
                unavailable = true
                return
            }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }

    @ViewBuilder
    private func content(model: MealTextScreenModel, ui: MealTextUi) -> some View {
        switch ui.phase {
        case "review":
            review(model: model, ui: ui)
        case "reading":
            VStack(spacing: 12) {
                ProgressView()
                Button(L("tr_action_cancel")) { model.edit() }
            }
        case "saved":
            ProgressView().onAppear { onDone() }
        case "failed":
            ContentUnavailableView {
                Label(LI("nutrition_describe_failed"), systemImage: "exclamationmark.triangle")
            } actions: {
                Button(LI("nutrition_try_again")) { model.edit() }
            }
        default:
            Form {
                Section {
                    TextField(L("trf_describe_placeholder"), text: $text, axis: .vertical)
                        .lineLimit(3...8)
                        .textInputAutocapitalization(.sentences)
                        .onChange(of: text) { _, value in model.setText(text: value) }
                } footer: {
                    Text(L("trf_describe_hint"))
                }
                Section {
                    Button(L("trf_describe_go")) {
                        model.setText(text: text)
                        model.read()
                    }
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private func review(model: MealTextScreenModel, ui: MealTextUi) -> some View {
        Form {
            Section {
                Text(L("fvp_review_text_hint")).font(.footnote).foregroundStyle(.secondary)
                if ui.rows.isEmpty {
                    Text(L("fvp_review_empty"))
                }
                ForEach(ui.rows, id: \.key) { row in
                    ReviewRow(row: row, model: model)
                }
                Button { model.beginChoice(key: -2) } label: {
                    Label(L("fvp_add_item"), systemImage: "plus")
                }
            }
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
        .sheet(isPresented: Binding(get: { ui.choosingKey != -1 }, set: { if !$0 { model.endChoice() } })) {
            ChooseFoodSheet(model: model, ui: ui)
        }
    }
}

private struct ReviewRow: View {
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
private struct ChooseFoodSheet: View {
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
