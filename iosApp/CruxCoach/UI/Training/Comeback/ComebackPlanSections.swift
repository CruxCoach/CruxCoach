import CruxCoachCore
import SwiftUI

/// The running plan (Android `planItems`): prompts, hints, the stage card and today's sessions (or the own plan),
/// the diary, the 14-day chart, the information behind the plan and the plan's actions. A stage changes only on a tap.
struct ComebackPlanSections: View {
    let model: ComebackScreenModel
    let ui: ComebackScreenState
    let onShare: () -> Void
    let onQuestions: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            hints
            if ui.plan.sourceCode == "cruxcoach" {
                ComebackStageCard(model: model, ui: ui)
                sessions
            } else {
                ComebackOwnPlanCard(model: model, plan: ui.plan)
            }
            ComebackDiaryCard(model: model, plan: ui.plan, onQuestions: onQuestions)
            Text(L("cb_chart_title")).font(.title3.weight(.semibold))
            ComebackChart(points: ui.plan.chart, empty: ui.plan.chartEmpty)
            ComebackInfoCards(plan: ui.plan)
            ComebackActions(model: model, onShare: onShare, onQuestions: onQuestions)
        }
    }

    @ViewBuilder
    private var hints: some View {
        ForEach(ui.plan.prompts, id: \.key) { prompt in
            ComebackPromptCard(prompt: prompt, model: model, onShare: onShare)
        }
        if ui.plan.notChecked {
            ComebackHintCard(title: "", text: L("cb_banner_not_checked"), tag: "comeback_not_checked",
                             secondary: ComebackHintAction(label: L("cb_questions"), action: onQuestions))
        }
        if ui.plan.notAgreed {
            ComebackHintCard(title: "", text: L("cb_banner_not_agreed"), tag: "comeback_not_agreed",
                             secondary: ComebackHintAction(label: L("cb_mark_agreed"), action: { model.markAgreed() }))
        }
    }

    @ViewBuilder
    private var sessions: some View {
        Text(L("cb_today_sessions")).font(.title3.weight(.semibold))
        ForEach(ui.plan.sessions, id: \.index) { session in
            ComebackSessionCard(session: session, model: model)
        }
    }
}

/// A look back or hint with its buttons (Android `PromptCard`); the buttons come from Kotlin.
private struct ComebackPromptCard: View {
    let prompt: ComebackPromptUi
    let model: ComebackScreenModel
    let onShare: () -> Void

    var body: some View {
        ComebackHintCard(title: TrRender.text(prompt.title), text: TrRender.text(prompt.text),
                         tag: "comeback_prompt_" + prompt.kind, caution: prompt.caution,
                         primary: action("primary"), secondary: action("secondary"), tertiary: action("tertiary"))
    }

    private func action(_ slot: String) -> ComebackHintAction? {
        guard let found = prompt.actions.first(where: { $0.slot == slot }) else { return nil }
        let key: String = prompt.key
        let code: String = found.code
        return ComebackHintAction(label: TrRender.text(found.label)) {
            if code == "share" {
                onShare()
            } else {
                model.promptAction(key: key, code: code)
            }
        }
    }
}

/// The stage the athlete agreed on: rules, pain rule, what to avoid, climbing, and "Change stage" (Android `StageCard`).
private struct ComebackStageCard: View {
    let model: ComebackScreenModel
    let ui: ComebackScreenState

    @State private var picking = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            heading
            ComebackLabel(text: L("cb_do_this"))
            ComebackBullets(lines: ui.plan.doThis)
            ComebackLabel(text: L("cb_climbing"))
            Text(ui.plan.climbing).font(.subheadline).fixedSize(horizontal: false, vertical: true)
            rules
            Button(L("cb_change_stage")) { picking = true }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("comeback_change_stage")
        }
        .modifier(ComebackCard())
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("comeback_stage_card")
        .sheet(isPresented: $picking) {
            ComebackStagePickerSheet(stages: ui.stages, current: ui.plan.stage, onCancel: { picking = false }) { index in
                picking = false
                model.moveToStage(index: index)
            }
        }
    }

    private var heading: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(tr: ui.plan.stageHeader).font(.headline)
            Text(tr: ui.plan.stageDay).font(.caption)
            Text(ui.plan.stageGoal).font(.subheadline).fixedSize(horizontal: false, vertical: true)
        }
    }

    @ViewBuilder
    private var rules: some View {
        if !ui.plan.avoid.isEmpty {
            ComebackLabel(text: L("cb_avoid"))
            ComebackBullets(lines: ui.plan.avoid)
        }
        ComebackLabel(text: L("cb_pain_rule"))
        Text(tr: ui.plan.painRule)
            .font(.subheadline)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("comeback_pain_rule")
        ComebackLabel(text: L("cb_next_when"))
        ComebackBullets(lines: ui.plan.nextWhen)
    }
}

/// "Choose the stage": the athlete's own pick, saved only with Save (Android's stage dialog).
private struct ComebackStagePickerSheet: View {
    let stages: [ComebackStageChoiceUi]
    let current: Int32
    let onCancel: () -> Void
    let onSave: (Int32) -> Void

    @State private var chosen: Int32 = -1

    private var selected: Int32 { chosen < 0 ? current : chosen }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    Text(L("cb_stage_pick_hint")).font(.caption)
                    ComebackStageChoices(stages: stages, selected: selected) { index in chosen = index }
                }
                .padding(16)
            }
            .accessibilityIdentifier("comeback_stage_dialog")
            .navigationTitle(L("cb_choose_stage"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("tr_action_cancel"), action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("tr_action_save")) { onSave(selected) }
                        .accessibilityIdentifier("comeback_stage_confirm")
                }
            }
        }
    }
}

/// One of today's sessions (Android `SessionCard`). Never locked: the status says what the stage suggests, the
/// athlete decides.
private struct ComebackSessionCard: View {
    let session: ComebackSessionUi
    let model: ComebackScreenModel

    private var index: Int32 { session.index }
    private var lines: [String] { session.items.map { TrRender.text($0) } }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(tr: session.title).font(.headline).frame(maxWidth: .infinity, alignment: .leading)
                if session.isOptional {
                    Text(L("cb_session_optional"))
                        .font(.caption)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Color(.tertiarySystemFill), in: Capsule())
                }
            }
            Text(tr: session.frequency).font(.caption).foregroundStyle(.secondary)
            ForEach(lines.indices, id: \.self) { i in
                Text(lines[i]).font(.subheadline).fixedSize(horizontal: false, vertical: true)
            }
            footer
        }
        .modifier(ComebackCard())
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("comeback_session_\(index)")
    }

    private var footer: some View {
        HStack {
            Text(tr: session.status)
                .font(.subheadline.weight(.semibold))
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("comeback_session_status_\(index)")
            Button(L("cb_session_start")) {
                model.startSession(index: index, name: TrRender.text(session.name))
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("comeback_session_start_\(index)")
        }
        .padding(.top, 4)
    }
}

/// "My physio's plan": the chosen routine starts as a comeback training (Android `OwnPlanCard`).
private struct ComebackOwnPlanCard: View {
    let model: ComebackScreenModel
    let plan: ComebackPlanUi

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L("cb_source_own")).font(.headline)
            Text(L("cb_source_own_hint")).font(.caption)
            ComebackRoutineChips(routines: plan.ownRoutines) { id in model.chooseOwnRoutine(id: id) }
            if plan.ownRoutineChosen {
                Button(L("cb_session_start")) { model.startOwn(name: TrRender.text(plan.ownSessionName)) }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("comeback_own_start")
            } else {
                Text(L("cb_own_routine_none")).font(.caption)
            }
        }
        .modifier(ComebackCard())
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("comeback_own_card")
    }
}

/// Today's diary (Android `DiaryCard`): morning pain, pressure pain, swelling, a new warning sign (opens the
/// questions) and a note, saved as they are entered.
private struct ComebackDiaryCard: View {
    let model: ComebackScreenModel
    let plan: ComebackPlanUi
    let onQuestions: () -> Void

    @State private var note = ""

    private var sessionLines: [String] { plan.diarySessions.map { TrRender.text($0) } }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L("cb_diary")).font(.headline)
            ComebackPainScale(label: L("cb_morning_pain"), value: painValue(plan.morningPain), tag: "comeback_morning") { v in
                model.setMorningPain(value: Int32(v ?? -1))
            }
            ComebackPainScale(label: L("cb_pressure_pain"), value: painValue(plan.pressurePain), tag: "comeback_pressure",
                              hint: L("cb_pressure_hint")) { v in
                model.setPressurePain(value: Int32(v ?? -1))
            }
            checks
            noteField
            ForEach(sessionLines.indices, id: \.self) { i in
                Text(sessionLines[i]).font(.caption)
            }
        }
        .modifier(ComebackCard())
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("comeback_diary")
        .onAppear { note = plan.note }
        // A new day starts with that day's note (Android `remember(entry?.day)`).
        .onChange(of: plan.diaryDay) { _, _ in note = plan.note }
    }

    @ViewBuilder
    private var checks: some View {
        ComebackCheckRow(label: L("cb_swelling_today"), checked: plan.swelling, tag: "comeback_swelling_today") { on in
            model.setSwelling(on: on)
        }
        ComebackCheckRow(label: L("cb_new_sign"), checked: plan.diaryNewSign, tag: "comeback_new_sign") { on in
            model.setDiaryNewSign(on: on)
            if on { onQuestions() }
        }
    }

    private var noteField: some View {
        TextField(L("cb_note"), text: $note, axis: .vertical)
            .textFieldStyle(.roundedBorder)
            .accessibilityIdentifier("comeback_note")
            .onChange(of: note) { _, now in noteChanged(now) }
    }

    private func noteChanged(_ now: String) {
        let cut = String(now.prefix(500))
        if cut != now {
            note = cut
            return
        }
        if cut != plan.note { model.setNote(text: cut) }
    }

    private func painValue(_ v: Int32) -> Int? {
        v < 0 ? nil : Int(v)
    }
}

/// Typical course, nutrition and the evidence with its sources (Android `InfoCards`).
private struct ComebackInfoCards: View {
    let plan: ComebackPlanUi

    @State private var allSources = false

    private var sources: [String] { plan.sources.map { TrRender.text($0) } }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !plan.typicalCourse.isEmpty {
                typical
            }
            if !plan.nutrition.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("cb_nutrition")).font(.headline)
                    Text(plan.nutrition).font(.caption).fixedSize(horizontal: false, vertical: true)
                }
                .modifier(ComebackCard())
            }
            evidence
        }
    }

    private var typical: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L("cb_typical")).font(.headline)
            Text(plan.typicalCourse).font(.subheadline).fixedSize(horizontal: false, vertical: true)
            Text(L("cb_typical_hint")).font(.caption).foregroundStyle(.secondary)
            ComebackFindingNotes(notes: plan.typicalNotes)
        }
        .modifier(ComebackCard())
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("comeback_typical")
    }

    private var evidence: some View {
        let all: [String] = sources
        let shown: [String] = allSources ? all : Array(all.prefix(4))
        return VStack(alignment: .leading, spacing: 4) {
            Text(L("cb_evidence")).font(.headline)
            Text(plan.evidence).font(.caption).fixedSize(horizontal: false, vertical: true)
            Text(L("cb_sources")).font(.subheadline.weight(.semibold)).padding(.top, 4)
            ForEach(shown.indices, id: \.self) { i in
                Text(shown[i]).font(.caption).fixedSize(horizontal: false, vertical: true)
            }
            if all.count > 4 {
                Button(allSources ? L("cb_sources_less") : L("cb_sources_more", all.count - 4)) { allSources.toggle() }
                    .font(.caption)
                    .accessibilityIdentifier("comeback_sources_more")
            }
        }
        .modifier(ComebackCard())
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("comeback_sources")
    }
}

/// Summary for the appointment, the questions, end plan, delete all comeback data (Android `Actions`).
private struct ComebackActions: View {
    let model: ComebackScreenModel
    let onShare: () -> Void
    let onQuestions: () -> Void

    @State private var ending = false
    @State private var deleting = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            wide(L("cb_export"), tag: "comeback_export", action: onShare)
            wide(L("cb_questions"), tag: "comeback_actions_questions", action: onQuestions)
            wide(L("cb_end"), tag: "comeback_end") { ending = true }
            Button(role: .destructive) {
                deleting = true
            } label: {
                Text(L("cb_delete_all"))
            }
            .accessibilityIdentifier("comeback_delete")
            Text(L("cb_privacy")).font(.caption).foregroundStyle(.secondary)
            Spacer(minLength: 32)
        }
        .alert(L("cb_end_title"), isPresented: $ending) {
            Button(L("cb_end_resolved")) { model.end(resolved: true) }
            Button(L("cb_end_keep")) { model.end(resolved: false) }
            Button(L("tr_action_cancel"), role: .cancel) {}
        } message: {
            Text(L("cb_end_text"))
        }
        .alert(L("cb_delete_title"), isPresented: $deleting) {
            Button(L("tr_action_delete"), role: .destructive) { model.deleteAll() }
            Button(L("tr_action_cancel"), role: .cancel) {}
        } message: {
            Text(L("cb_delete_text"))
        }
    }

    private func wide(_ title: String, tag: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .accessibilityIdentifier(tag)
    }
}
