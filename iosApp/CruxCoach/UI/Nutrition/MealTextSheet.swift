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
        MealReviewForm(model: model, ui: ui, hint: L("fvp_review_text_hint"), preview: nil)
    }
}
