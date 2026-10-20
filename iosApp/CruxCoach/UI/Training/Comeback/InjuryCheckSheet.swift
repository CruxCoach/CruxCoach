import CruxCoachCore
import SwiftUI

/// "Questions about the injury" (Android `InjuryCheckSheet`): what happened, the warning signs that apply and what a
/// professional said. The answers point to a check where one is due; they never pick a stage. The notice heads it.
/// The answers live in Kotlin while the sheet is open; Save writes them, Cancel drops them.
struct InjuryCheckSheet: View {
    let model: ComebackScreenModel
    let check: ComebackCheckUi
    /// True when the warning-sign alert is due after saving.
    let onSaved: (Bool) -> Void

    @State private var pickingDay = false
    @State private var day = Date()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    intro
                    whatHappened
                    signs
                    professional
                    saveButton
                }
                .padding(16)
            }
            .accessibilityIdentifier("comeback_check_list")
            .navigationTitle(L("cb_questions"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("tr_action_cancel")) { model.closeCheck() }
                }
            }
        }
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("cb_q_intro")).font(.caption).foregroundStyle(.secondary)
            // The notice on every comeback surface (owner rule).
            ComebackNotice()
        }
    }

    @ViewBuilder
    private var whatHappened: some View {
        question(L("cb_q_onset"))
        chips(check.onsets, prefix: "comeback_onset_") { code in model.toggleOnset(code: code) }
        question(L("cb_q_pop"))
        chips(check.pops, prefix: "comeback_pop_") { code in model.togglePop(code: code) }
        question(L("cb_q_swelling"))
        chips(check.swellings, prefix: "comeback_swelling_") { code in model.toggleSwelling(code: code) }
        if check.showFingers {
            question(L("cb_q_fingers"))
            chips(check.fingers, prefix: "comeback_finger_") { code in model.toggleFinger(code: code) }
        }
    }

    @ViewBuilder
    private var signs: some View {
        question(L("cb_q_signs"))
        ForEach(check.signs, id: \.code) { sign in
            ComebackCheckRow(label: TrRender.text(sign.label), checked: sign.selected, tag: "comeback_sign_" + sign.code) { _ in
                model.toggleSign(code: sign.code)
            }
        }
        if !check.nothingTicked.isEmpty {
            Text(check.nothingTicked).font(.caption).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var professional: some View {
        question(L("cb_q_checked"))
        chips(check.checks, prefix: "comeback_checked_") { code in model.chooseChecked(code: code) }
        if check.showFinding {
            question(L("cb_q_finding"))
            chips(check.findings, prefix: "comeback_finding_") { code in model.chooseFinding(code: code) }
            checkedOnRow
        }
    }

    private var saveButton: some View {
        Button {
            onSaved(model.saveCheck())
        } label: {
            Text(L("cb_save_answers")).frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .padding(.top, 8)
        .accessibilityIdentifier("comeback_check_save")
    }

    private var checkedOnRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(L("cb_q_checked_on")).frame(maxWidth: .infinity, alignment: .leading)
                Button(L("cb_checked_today")) {
                    model.setCheckedOnToday()
                    pickingDay = false
                }
                Button(check.checkedOn.isEmpty ? L("cb_checked_pick") : check.checkedOn) { pickingDay.toggle() }
                    .accessibilityIdentifier("comeback_checked_on")
            }
            if pickingDay {
                DatePicker(L("cb_q_checked_on"), selection: Binding(get: { day }, set: { pick($0) }), in: ...Date(),
                           displayedComponents: .date)
                    .datePickerStyle(.graphical)
            }
        }
        .padding(.top, 4)
    }

    /// A past day only (Android's past-date picker); Kotlin checks it again.
    private func pick(_ date: Date) {
        day = date
        let parts = Calendar(identifier: .gregorian).dateComponents([.year, .month, .day], from: date)
        guard let y = parts.year, let m = parts.month, let d = parts.day else { return }
        if model.setCheckedOn(year: Int32(y), month: Int32(m), day: Int32(d)) {
            pickingDay = false
        }
    }

    private func question(_ text: String) -> some View {
        Text(text).font(.subheadline.weight(.semibold)).padding(.top, 8)
    }

    private func chips(_ options: [ComebackOptionUi], prefix: String, onTap: @escaping (String) -> Void) -> some View {
        TrainingFlow(spacing: 6) {
            ForEach(options, id: \.code) { option in
                TrainingChoiceChip(title: TrRender.text(option.label), selected: option.selected) { onTap(option.code) }
                    .accessibilityIdentifier(prefix + option.code)
            }
        }
    }
}
