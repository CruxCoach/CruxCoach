import CruxCoachCore
import SwiftUI

/// "Describe a meal" (Android `FoodTextSheet`): the athlete types or dictates
/// what they ate; a checkable review list turns it into food log entries. The
/// rule parser and the BLS matcher run on the device on every phone; where
/// photos would be recognised, the installed model may read the text again
/// ("Analyse with the AI model"), also on the device.
struct MealTextSheet: View {
    let core: AppCore
    let dayIso: String
    let mealCode: String
    let unitsCode: String
    let onDone: () -> Void
    @State private var host: ScreenHost<MealTextScreenModel, MealTextUi>?
    /// The engine for the model's pass; the core frees its memory when the sheet closes.
    @State private var runner: FoodVisionRunner?
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
        .task { start() }
    }

    private func start() {
        guard host == nil, !unavailable else { return }
        let engine = runner ?? FoodVisionRunner()
        runner = engine
        guard let model = core.makeMealTextScreenWithModel(
            dayIso: dayIso, mealCode: mealCode, unitsCode: unitsCode,
            physicalMemoryBytes: VisionDeviceFacts.physicalMemoryBytes,
            dotProduct: VisionDeviceFacts.dotProduct,
            halfPrecision: VisionDeviceFacts.halfPrecision,
            activeProcessors: VisionDeviceFacts.activeProcessors,
            engineBuilt: FoodVisionEngine.isAvailable,
            host: engine
        ) else {
            unavailable = true
            return
        }
        host = ScreenHost(model: model, initial: model.currentState,
                          subscribe: { model, onState in model.watch(onState: onState) },
                          onClose: { model in model.close() })
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
        case "analyzing":
            analyzingPane(model: model, ui: ui)
        case "saved":
            ProgressView().onAppear { onDone() }
        case "failed":
            failedPane(model: model, ui: ui)
        default:
            inputForm(model: model)
        }
    }

    private func inputForm(model: MealTextScreenModel) -> some View {
        Form {
            Section {
                TextField(L("trf_describe_placeholder"), text: $text, axis: .vertical)
                    .accessibilityIdentifier("meal_text_field")
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

    /// Android `AnalyzingPane` for a typed meal: no photo and no estimate, only the elapsed seconds.
    private func analyzingPane(model: MealTextScreenModel, ui: MealTextUi) -> some View {
        Form {
            MealTextWaitSection(startedMs: ui.phaseStartedMs, onCancel: { model.edit() })
            Section {
                Text(L("fvp_on_device")).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private func failedPane(model: MealTextScreenModel, ui: MealTextUi) -> some View {
        if ui.error.isEmpty {
            ContentUnavailableView {
                Label(LI("nutrition_describe_failed"), systemImage: "exclamationmark.triangle")
            } actions: {
                Button(LI("nutrition_try_again")) { model.edit() }
            }
        } else {
            // Android `ErrorPane`: what went wrong with the model, then back to the description.
            Form {
                Section {
                    Text(Self.modelErrorText(ui)).foregroundStyle(.red)
                    Button(L("fvp_retry")) { model.edit() }
                }
            }
        }
    }

    private static func modelErrorText(_ ui: MealTextUi) -> String {
        switch ui.error {
        case "memory": return L("fvp_error_memory")
        case "app_memory": return LI("nutrition_photo_app_memory")
        default: return L("fvp_error_engine", ui.errorCode)
        }
    }

    private func review(model: MealTextScreenModel, ui: MealTextUi) -> some View {
        let hint: String = ui.source == "model"
            ? L("fvp_review_text_model_hint", Int(ui.reviewSeconds))
            : L("fvp_review_text_hint")
        return MealReviewForm(model: model, ui: ui, hint: hint, preview: nil)
    }
}

/// Spinner, "Recognising foods…", the elapsed seconds and cancel (Android `WaitPane`).
private struct MealTextWaitSection: View {
    let startedMs: Int64
    let onCancel: () -> Void

    var body: some View {
        Section {
            HStack(spacing: 12) {
                ProgressView()
                Text(L("fvp_analyzing"))
            }
            if startedMs > 0 {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    elapsed(now: context.date)
                }
            }
            Button(L("tr_action_cancel"), action: onCancel)
        }
    }

    private func elapsed(now: Date) -> some View {
        let started = Date(timeIntervalSince1970: Double(startedMs) / 1000)
        let seconds: Int = max(0, Int(now.timeIntervalSince(started)))
        return Text(L("fvp_elapsed", seconds)).font(.footnote).foregroundStyle(.secondary)
    }
}
