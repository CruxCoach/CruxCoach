import CruxCoachCore
import SwiftUI

/// The add sheet (Android `FoodsSheet`): the meal on top, one search field,
/// the other ways in as tiles (scan, describe, photo, plain values), what the
/// meal usually holds, "my foods", and – while searching – the BLS, USDA and
/// packaged-product databases. In ingredient mode it picks a recipe's next
/// ingredient instead.
struct FoodAddSheet: View {
    let host: ScreenHost<NutritionScreenModel, NutritionDayUi>
    /// The next sheet to show; nil closes the add sheet.
    let onSelect: (NutritionSheet?) -> Void
    /// Set while picking a recipe ingredient: the picked row's ref goes here.
    let onIngredientPicked: ((String) -> Void)?
    /// MealPhotoSheet.support: "supported", "too_slow" or why the photo entry is unavailable.
    var photoSupport: String = "no_engine"
    @State private var query = ""
    @State private var confirmDelete: FoodRowUi?
    @State private var photoInfo = false

    private var model: NutritionScreenModel { host.model }
    private var ui: FoodSearchUi { host.state.search }

    var body: some View {
        NavigationStack {
            List {
                if ui.showMeal {
                    Section {
                        Picker(L("trf_qa_meal"), selection: Binding(get: { ui.mealCode }, set: { model.setSearchMeal(mealCode: $0) })) {
                            ForEach(NutritionText.mealCodes, id: \.self) { code in
                                Text(NutritionText.mealTitle(code)).tag(code)
                            }
                        }
                    }
                }
                if query.trimmingCharacters(in: .whitespaces).isEmpty && onIngredientPicked == nil {
                    Section { actionTiles }
                }
                if !ui.recent.isEmpty {
                    Section(L("trf_recent_title")) {
                        // One tap each: the same food with the same amount.
                        ForEach(ui.recent, id: \.ref) { row in
                            Button {
                                model.relog(entryId: row.ref)
                                onSelect(nil)
                            } label: {
                                rowLabel(row, trailing: "plus.circle.fill")
                            }
                            .accessibilityLabel(L("trf_recent_log", row.title))
                        }
                    }
                }
                Section {
                    if ui.ownEmpty {
                        Text(L("trf_foods_empty")).font(.footnote).foregroundStyle(.secondary)
                    }
                    ForEach(ui.own, id: \.ref) { row in
                        HStack {
                            // Several buttons in one row: each needs its own hit area, or a tap fires them all.
                            Button { pick(row.ref) } label: { rowLabel(row, trailing: nil) }
                                .buttonStyle(.plain)
                            if row.isRecipe && onIngredientPicked == nil {
                                Button {
                                    model.editRecipe(foodId: row.ref) { form in
                                        if let form { onSelect(.recipe(form)) }
                                    }
                                } label: { Image(systemName: "pencil") }
                                .buttonStyle(.borderless)
                                .accessibilityLabel(L("trf_recipe_edit"))
                            }
                            Button { model.toggleFavorite(foodId: row.ref) } label: {
                                Image(systemName: row.isFavorite ? "star.fill" : "star")
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(row.isFavorite ? L("trf_food_unfavorite") : L("trf_food_favorite"))
                            if row.canDelete {
                                Button { confirmDelete = row } label: { Image(systemName: "trash") }
                                    .buttonStyle(.borderless)
                                    .accessibilityLabel(L("trf_food_delete"))
                            }
                        }
                    }
                } header: {
                    HStack {
                        Text(L("trf_my_foods"))
                        Spacer()
                        if ui.showOwnHeader {
                            Button {
                                onSelect(.recipe(model.makeRecipeForm()))
                            } label: { Label(L("trf_recipe_new"), systemImage: "book") }
                            .font(.caption)
                            Button {
                                onSelect(.create(model.createFoodForm(barcode: "")))
                            } label: { Label(L("tru_new_food"), systemImage: "plus") }
                            .font(.caption)
                        }
                    }
                    .textCase(nil)
                }
                Section {
                    ForEach(ui.bls, id: \.ref) { row in
                        Button { pick(row.ref) } label: { rowLabel(row, trailing: nil) }
                    }
                } header: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("trf_foods_bls_title"))
                        Text(NutritionText.render(ui.blsHint)).font(.caption2).textCase(nil)
                    }
                }
                // US generic foods with household measures (cups, slices); English names only.
                if !ui.usda.isEmpty {
                    Section {
                        ForEach(ui.usda, id: \.ref) { row in
                            Button { pick(row.ref) } label: { rowLabel(row, trailing: nil) }
                        }
                    } header: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("trf_foods_usda_title"))
                            Text(L("trf_usda_attribution")).font(.caption2).textCase(nil)
                        }
                    }
                }
                if ui.showProducts {
                    Section {
                        ForEach(ui.products, id: \.ref) { row in
                            Button { pick(row.ref) } label: { rowLabel(row, trailing: nil) }
                        }
                    } header: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("trf_foods_products_title"))
                            Text(NutritionText.render(ui.productsHint)).font(.caption2).textCase(nil)
                        }
                    }
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: L("trf_find_food"))
            .onChange(of: query) { _, value in model.setQuery(text: value) }
            .navigationTitle(onIngredientPicked == nil ? L("tru_fuel_add") : L("trf_recipe_pick"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("tr_action_cancel")) { onSelect(nil) }
                }
            }
            .alert(confirmDelete.map { L("trf_food_delete_confirm_title", $0.title) } ?? "", isPresented: Binding(
                get: { confirmDelete != nil },
                set: { if !$0 { confirmDelete = nil } })) {
                Button(L("tr_action_cancel"), role: .cancel) { confirmDelete = nil }
                Button(L("tr_action_delete"), role: .destructive) {
                    if let row = confirmDelete { model.deleteFood(foodId: row.ref) }
                    confirmDelete = nil
                }
            } message: {
                Text(L("trf_food_delete_confirm_text"))
            }
            .alert(L("fvp_unavailable_title"), isPresented: $photoInfo) {
                Button(L("fvp_ok"), role: .cancel) {}
            } message: {
                Text(MealPhotoSheet.reason(photoSupport) + "\n\n" + L("fvp_unavailable_alternatives"))
            }
        }
    }

    /// Scan, describe, photo and plain values. On a phone (or build) without
    /// photo recognition the photo tile stays visible, greyed out, and explains
    /// why; after a too-slow test run it opens the sheet, which says so.
    private var actionTiles: some View {
        let photoDimmed: Bool = photoSupport != "supported"
        return HStack(spacing: 8) {
            tile(L("tru_add_scan"), "barcode.viewfinder", dimmed: false) { onSelect(.scan) }
            tile(L("trf_describe"), "text.bubble", dimmed: false) { onSelect(.describe(ui.mealCode)) }
            tile(L("fvp_button"), "camera", dimmed: photoDimmed) { openPhoto() }
                .accessibilityValue(photoDimmed ? L("fvp_unavailable_short") : "")
            tile(L("tru_add_quick"), "bolt", dimmed: false) {
                onSelect(.quick(model.quickForm(mealCode: ui.mealCode)))
            }
        }
        .buttonStyle(.borderless)
    }

    private func openPhoto() {
        if photoSupport == "supported" || photoSupport == "too_slow" {
            onSelect(.photo(ui.mealCode))
        } else {
            photoInfo = true
        }
    }

    private func tile(_ label: String, _ icon: String, dimmed: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.title3)
                    .foregroundStyle(dimmed ? Color.secondary.opacity(0.5) : Color.accentColor)
                Text(label).font(.caption).lineLimit(1).minimumScaleFactor(0.6)
                    .foregroundStyle(dimmed ? Color.secondary : Color.primary)
            }
            .frame(maxWidth: .infinity, minHeight: 60)
            .background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
        }
    }

    private func rowLabel(_ row: FoodRowUi, trailing: String?) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title).foregroundStyle(.primary).lineLimit(2)
                Text(NutritionText.render(row.subtitle)).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            Spacer()
            if let trailing {
                Image(systemName: trailing).foregroundStyle(Color.accentColor)
            }
        }
        .contentShape(Rectangle())
    }

    private func pick(_ ref: String) {
        if let onIngredientPicked {
            onIngredientPicked(ref)
        } else if let form = model.amountForm(ref: ref) {
            onSelect(.amount(form))
        }
    }
}
