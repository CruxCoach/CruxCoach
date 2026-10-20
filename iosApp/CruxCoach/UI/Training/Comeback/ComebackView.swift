import CruxCoachCore
import SwiftUI
import UIKit

/// One injury's comeback plan (Android `ComebackScreen`, owner 2026-10-20): a training aid used together with a
/// doctor or physio. Setup (questions → whose plan → the stage agreed with the doctor or physio → reference values →
/// "Before you start"), then the running plan. The notice "Kein Ersatz für den Arztbesuch …" heads the screen in
/// every state. Push it inside a `NavigationStack`; it has none of its own.
struct ComebackView: View {
    let injuryId: String

    @Environment(AppEnvironment.self) private var environment
    @State private var host: ScreenHost<ComebackScreenModel, ComebackScreenState>?

    private var core: AppCore? {
        if case .ready(let core) = environment.phase { return core }
        return nil
    }

    var body: some View {
        Group {
            if let host = host, let core = core {
                ComebackContent(core: core, model: host.model, ui: host.state)
            } else {
                VStack(alignment: .leading, spacing: 16) {
                    ComebackNotice()
                    ProgressView().frame(maxWidth: .infinity)
                }
                .padding(16)
                .frame(maxHeight: .infinity, alignment: .top)
            }
        }
        .navigationTitle(L("cb_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard host == nil, let core = core else { return }
            guard let model = core.makeComebackScreen(injuryId: injuryId, language: TrainingStyle.catalogLanguage) else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }
}

private struct ComebackContent: View {
    let core: AppCore
    let model: ComebackScreenModel
    let ui: ComebackScreenState

    /// The warning-sign alert (Android `alertOpen`); it shows while a sign is ticked.
    @State private var alertOpen = false

    var body: some View {
        page
            .modifier(ComebackCheckSheetModifier(model: model, check: ui.check, alertOpen: $alertOpen))
            .modifier(ComebackSignsAlertModifier(model: model, alert: ui.alert, isOpen: $alertOpen))
            .modifier(ComebackPlayerModifier(core: core, model: model, started: ui.startedWorkout))
            .modifier(ComebackShareModifier(model: model, text: ui.shareText))
    }

    private var page: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                // The notice heads every state of the screen (owner rule).
                ComebackNotice(onSigns: { model.openCheck() })
                if ui.found {
                    ComebackHeader(ui: ui)
                    if ui.hasSigns {
                        ComebackSignsBanner(text: TrRender.text(ui.signsBanner)) { alertOpen = true }
                    }
                    if !ui.under18.isEmpty {
                        ComebackHintCard(title: "", text: ui.under18, tag: "comeback_under18", caution: true)
                    }
                    if ui.hasPlan {
                        ComebackPlanSections(model: model, ui: ui, onShare: { share() }, onQuestions: { model.openCheck() })
                    } else {
                        ComebackSetupSection(model: model, ui: ui)
                    }
                } else if ui.loading {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .padding(16)
        }
        .accessibilityIdentifier("comeback_list")
    }

    /// "Summary for my appointment": Kotlin writes it with these words (it holds no user-visible strings).
    private func share() {
        model.share(title: L("cb_export_title"), injury: L("cb_export_injury"), region: TrRender.text(ui.regionLabel),
                    side: TrRender.text(ui.sideLabel), since: L("cb_export_since"), finding: L("cb_export_finding"),
                    signs: L("cb_export_signs"), plan: L("cb_export_plan"), stage: L("cb_export_stage"),
                    reference: L("cb_export_reference"), diaryHeader: L("cb_export_diary"), yes: L("cb_export_yes"))
    }
}

/// Region · side · detail, since when, and what a professional said (Android `InjuryHeader`).
private struct ComebackHeader: View {
    let ui: ComebackScreenState

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(tr: ui.title).font(.title2.weight(.semibold))
            Text(tr: ui.since).font(.caption).foregroundStyle(.secondary)
            if !ui.finding.isEmpty {
                Text(ui.finding).font(.subheadline)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The injury questions as a sheet; after saving with new signs the alert follows once the sheet is gone
/// (two presentations at once drop the second).
private struct ComebackCheckSheetModifier: ViewModifier {
    let model: ComebackScreenModel
    let check: ComebackCheckUi
    @Binding var alertOpen: Bool
    @State private var alertPending = false

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: Binding(get: { check.shown }, set: { if !$0 { model.closeCheck() } }), onDismiss: {
                if alertPending {
                    alertPending = false
                    alertOpen = true
                }
            }) {
                InjuryCheckSheet(model: model, check: check) { due in alertPending = due }
            }
    }
}

/// A started session opens the training as every other start does; its summary follows the player.
private struct ComebackPlayerModifier: ViewModifier {
    let core: AppCore
    let model: ComebackScreenModel
    let started: Bool

    @State private var playerOpen = false
    @State private var finishedId: String?
    @State private var summary: ComebackSummaryTarget?

    func body(content: Content) -> some View {
        content
            .onChange(of: started) { _, now in
                guard now else { return }
                model.consumeStartedWorkout()
                playerOpen = true
            }
            .fullScreenCover(isPresented: $playerOpen, onDismiss: {
                if let id = finishedId {
                    finishedId = nil
                    summary = ComebackSummaryTarget(id: id)
                }
            }) {
                WorkoutPlayerView(core: core, onClose: { playerOpen = false }, onFinished: { id in
                    finishedId = id
                    playerOpen = false
                }, onOpenNutrition: { playerOpen = false })
            }
            .sheet(item: $summary) { target in
                WorkoutSummaryView(core: core, workoutId: target.id, onClose: { summary = nil },
                                   onOpenNutrition: { summary = nil })
            }
    }
}

private struct ComebackSummaryTarget: Identifiable {
    let id: String
}

/// The appointment summary goes to the share sheet as soon as Kotlin has written it (Android's chooser).
private struct ComebackShareModifier: ViewModifier {
    let model: ComebackScreenModel
    let text: String

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: Binding(get: { !text.isEmpty }, set: { if !$0 { model.consumeShare() } })) {
                ComebackActivitySheet(text: text) { model.consumeShare() }
                    .presentationDetents([.medium, .large])
            }
    }
}

/// The system share sheet for a plain text.
private struct ComebackActivitySheet: UIViewControllerRepresentable {
    let text: String
    let onDone: () -> Void

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        controller.completionWithItemsHandler = { _, _, _, _ in onDone() }
        return controller
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// "Before you start" (Android `BeforeStartDialog`): the plan starts only with "Got it – in agreement with my doctor
/// or physio". A sheet rather than an alert: the text is long and the buttons keep their identifiers.
struct ComebackBeforeStartSheet: View {
    let onCancel: () -> Void
    let onConfirm: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    // The text says it too; the notice in its usual form keeps every comeback surface alike.
                    ComebackNotice()
                    Text(L("cb_before_text"))
                        .fixedSize(horizontal: false, vertical: true)
                    Button(action: onConfirm) {
                        Text(L("cb_understood")).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .accessibilityIdentifier("comeback_understood")
                }
                .padding(16)
            }
            .accessibilityIdentifier("comeback_before_dialog")
            .navigationTitle(L("cb_before_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("tr_action_cancel"), action: onCancel)
                }
            }
        }
    }
}
