import CruxCoachCore
import SwiftUI

/// Setting the plan up (Android `Setup`): 1 · the injury questions, 2 · whose plan, 3 · the stage agreed with the
/// doctor or physio, 4 · reference values (fingers), "agreed", then "Before you start". The app never picks a stage.
struct ComebackSetupSection: View {
    let model: ComebackScreenModel
    let ui: ComebackScreenState

    @State private var confirming = false
    @State private var half = ""
    @State private var openHand = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !ui.setup.endedOn.isEmpty {
                Text(L("cb_end") + ": " + ui.setup.endedOn).font(.caption)
            }
            questionsStep
            sourceStep
            if ui.setup.showStage {
                stageStep
            }
            if ui.setup.showReference {
                referenceStep
            }
            ComebackCheckRow(label: L("cb_agreed"), checked: ui.setup.agreed, tag: "comeback_agreed") { on in
                model.setAgreed(on: on)
            }
            startButton
            Text(L("cb_privacy")).font(.caption).foregroundStyle(.secondary)
        }
        .onAppear {
            half = ui.setup.refHalf
            openHand = ui.setup.refOpen
        }
        .sheet(isPresented: $confirming) {
            ComebackBeforeStartSheet(onCancel: { confirming = false }, onConfirm: {
                confirming = false
                model.startPlan()
            })
        }
    }

    private var startButton: some View {
        Button {
            confirming = true
        } label: {
            Text(L(ui.setup.restart ? "cb_restart" : "cb_start")).frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .accessibilityIdentifier("comeback_start")
    }

    private func stepCard<C: View>(_ title: String, @ViewBuilder content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.headline)
            content()
        }
        .modifier(ComebackCard())
    }

    private var questionsStep: some View {
        stepCard(L("cb_step_questions")) {
            Text(tr: ui.setup.questionsStatus).font(.caption)
            Button(L("cb_questions")) { model.openCheck() }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("comeback_open_questions")
            ComebackFindingNotes(notes: ui.setup.findingNotes)
        }
    }

    private var sourceStep: some View {
        stepCard(L("cb_step_source")) {
            ComebackRadioRow(title: L("cb_source_cruxcoach"), hint: L("cb_source_cruxcoach_hint"),
                             selected: ui.setup.sourceCode == "cruxcoach", tag: "comeback_source_cruxcoach") {
                model.chooseSource(code: "cruxcoach")
            }
            ComebackRadioRow(title: L("cb_source_own"), hint: L("cb_source_own_hint"),
                             selected: ui.setup.sourceCode == "own", tag: "comeback_source_own") {
                model.chooseSource(code: "own")
            }
            if !ui.setup.ownPlanNote.isEmpty {
                Text(ui.setup.ownPlanNote)
                    .font(.caption)
                    .foregroundStyle(Color.red)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("comeback_own_plan_note")
            }
            if ui.setup.sourceCode == "own" {
                ownRoutines
            }
        }
    }

    @ViewBuilder
    private var ownRoutines: some View {
        Text(L("cb_own_routine")).font(.subheadline.weight(.semibold)).padding(.top, 6)
        if ui.setup.routines.isEmpty {
            Text(L("cb_own_routine_none")).font(.caption)
        }
        ComebackRoutineChips(routines: ui.setup.routines) { id in model.chooseSetupRoutine(id: id) }
    }

    private var stageStep: some View {
        stepCard(L("cb_step_stage")) {
            Text(L("cb_stage_pick_hint")).font(.caption)
            ComebackStageChoices(stages: ui.stages, selected: ui.setup.stage) { index in
                model.chooseStage(index: index)
            }
        }
    }

    private var referenceStep: some View {
        stepCard(L("cb_step_reference")) {
            Text(L("cb_reference_hint")).font(.caption).fixedSize(horizontal: false, vertical: true)
            ComebackReferenceField(label: L("cb_reference_half"), text: $half, tag: "comeback_ref_half",
                                   hint: TrRender.text(ui.setup.refHalfHint), value: ui.setup.refHalfValue) { text in
                model.setReferenceHalf(text: text)
            }
            ComebackReferenceField(label: L("cb_reference_open"), text: $openHand, tag: "comeback_ref_open",
                                   hint: TrRender.text(ui.setup.refOpenHint), value: ui.setup.refOpenValue) { text in
                model.setReferenceOpen(text: text)
            }
            ComebackCheckRow(label: L("cb_dominant"), checked: ui.setup.dominant, tag: "comeback_dominant") { on in
                model.setDominant(on: on)
            }
        }
    }
}

/// A reference value in kg with the suggestion from the logged history ("Before the injury: 30.0 kg · Use"); in the
/// setup and in "Enter reference value" of a running plan.
struct ComebackReferenceField: View {
    let label: String
    @Binding var text: String
    let tag: String
    let hint: String
    let value: String
    let onChange: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            HStack(alignment: .center, spacing: 8) {
                TextField(label, text: $text)
                    .keyboardType(.decimalPad)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier(tag)
                    .onChange(of: text) { _, now in changed(now) }
                if !hint.isEmpty {
                    Button {
                        text = value
                    } label: {
                        Text(hint + "\n" + L("cb_reference_use"))
                            .font(.caption2)
                            .multilineTextAlignment(.leading)
                    }
                    .accessibilityIdentifier(tag + "_use")
                }
            }
        }
        .padding(.top, 4)
    }

    /// At most 6 characters, as Android's field.
    private func changed(_ now: String) {
        let cut = String(now.prefix(6))
        if cut != now {
            text = cut
            return
        }
        onChange(cut)
    }
}

/// Notes for what a professional said (Android `FindingNotes`).
struct ComebackFindingNotes: View {
    let notes: [String]

    var body: some View {
        if !notes.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text(L("cb_notes_finding")).font(.subheadline.weight(.semibold)).padding(.top, 8)
                ComebackBullets(lines: notes)
            }
        }
    }
}
