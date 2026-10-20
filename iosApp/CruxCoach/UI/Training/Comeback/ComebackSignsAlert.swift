import CruxCoachCore
import SwiftUI

/// The alert for ticked warning signs (Android `SignsAlert`). Now: "Call 112" first (opens the dialer), "It has been
/// checked", and "Continue anyway" as a plain button. Within days / soon: "It has been checked" or OK, with the
/// 116117 hint within days. It never locks the screen: every button closes it, the banner stays.
/// iOS-only: the message ends with the notice "Kein Ersatz für den Arztbesuch …" (owner rule: every surface).
struct ComebackSignsAlertModifier: ViewModifier {
    let model: ComebackScreenModel
    let alert: ComebackSignsAlertUi
    @Binding var isOpen: Bool

    @Environment(\.openURL) private var openURL

    func body(content: Content) -> some View {
        content
            .alert(alert.title, isPresented: Binding(get: { isOpen && !alert.urgency.isEmpty }, set: { if !$0 { isOpen = false } })) {
                actions
            } message: {
                Text(message)
            }
    }

    @ViewBuilder
    private var actions: some View {
        if alert.urgency == "emergency" {
            Button(L("cb_call_112")) {
                if let url = URL(string: "tel:112") { openURL(url) }
            }
            .accessibilityIdentifier("comeback_call_112")
            Button(L("cb_has_been_checked")) { model.acknowledgeSigns() }
                .accessibilityIdentifier("comeback_signs_checked")
            Button(L("cb_continue_anyway")) { model.acknowledgeSigns() }
                .accessibilityIdentifier("comeback_signs_continue")
        } else {
            Button(L("cb_has_been_checked")) { model.acknowledgeSigns() }
                .accessibilityIdentifier("comeback_signs_checked")
            Button(L("cb_ok"), role: .cancel) {}
                .accessibilityIdentifier("comeback_signs_ok")
        }
    }

    private var message: String {
        var parts: [String] = [alert.text]
        parts.append(contentsOf: alert.reasons)
        if alert.outOfHours { parts.append(L("cb_out_of_hours")) }
        parts.append(L("cb_not_a_substitute"))
        return parts.joined(separator: "\n\n")
    }
}

/// The banner while ticked signs stay (Android `SignsBanner`); a tap opens the alert again.
struct ComebackSignsBanner: View {
    let text: String
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .accessibilityHidden(true)
                Text(text)
                    .font(.subheadline)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(Color.primary)
            .padding(12)
            .background(TrainingStyle.caution.opacity(0.18), in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("comeback_signs_banner")
    }
}

/// One button of a `ComebackHintCard`.
struct ComebackHintAction {
    let label: String
    let action: () -> Void
}

/// A plain card for a hint with up to three actions (Android `HintCard`): filled, outlined, text.
struct ComebackHintCard: View {
    let title: String
    let text: String
    let tag: String
    var caution: Bool = false
    var primary: ComebackHintAction? = nil
    var secondary: ComebackHintAction? = nil
    var tertiary: ComebackHintAction? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if !title.isEmpty {
                Text(title).font(.subheadline.weight(.semibold))
            }
            Text(text)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
            if primary != nil || secondary != nil || tertiary != nil {
                buttons.padding(.top, 4)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(background, in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(tag)
    }

    private var background: Color {
        caution ? TrainingStyle.caution.opacity(0.15) : Color(.secondarySystemBackground)
    }

    private var buttons: some View {
        TrainingFlow(spacing: 8) {
            if let p = primary {
                Button(p.label, action: p.action)
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier(tag + "_primary")
            }
            if let s = secondary {
                Button(s.label, action: s.action)
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier(tag + "_secondary")
            }
            if let t = tertiary {
                Button(t.label, action: t.action)
                    .buttonStyle(.borderless)
                    .accessibilityIdentifier(tag + "_tertiary")
            }
        }
    }
}

/// A card's background (Android `Card`).
struct ComebackCard: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))
    }
}

/// A check box row (Android `CheckRow` / the questions' checkboxes). The whole row is the button, so a tap on the
/// text toggles it too (Android f871a82f1 made its rows do the same after the device recheck of 2026-10-20).
struct ComebackCheckRow: View {
    let label: String
    let checked: Bool
    let tag: String
    let onChange: (Bool) -> Void

    var body: some View {
        Button {
            onChange(!checked)
        } label: {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: checked ? "checkmark.square.fill" : "square")
                    .font(.title3)
                    .foregroundStyle(checked ? Color.accentColor : Color.secondary)
                Text(label)
                    .font(.subheadline)
                    .foregroundStyle(Color.primary)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.vertical, 2)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(checked ? .isSelected : [])
        .accessibilityIdentifier(tag)
    }
}

/// A radio row with a hint (Android `SourceOption` / `StageChoices`).
struct ComebackRadioRow: View {
    let title: String
    let hint: String
    let selected: Bool
    let tag: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .font(.title3)
                    .foregroundStyle(selected ? Color.accentColor : Color.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body).foregroundStyle(Color.primary)
                    if !hint.isEmpty {
                        Text(hint).font(.caption).foregroundStyle(.secondary)
                    }
                }
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(tag)
    }
}

/// The stages of the ladder to pick from (Android `StageChoices`).
struct ComebackStageChoices: View {
    let stages: [ComebackStageChoiceUi]
    let selected: Int32
    let onSelect: (Int32) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(stages, id: \.index) { stage in
                ComebackRadioRow(title: stage.title, hint: hint(stage), selected: stage.index == selected,
                                 tag: "comeback_stage_\(stage.index)") { onSelect(stage.index) }
            }
        }
    }

    private func hint(_ stage: ComebackStageChoiceUi) -> String {
        stage.goal + "\n" + L("cb_climbing") + ": " + stage.climbing
    }
}

/// A small coloured heading inside a card (Android `Label`).
struct ComebackLabel: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Color.accentColor)
            .padding(.top, 4)
    }
}

/// Bullet lines (Android `Bullet`).
struct ComebackBullets: View {
    let lines: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(lines.indices, id: \.self) { i in
                HStack(alignment: .top, spacing: 6) {
                    Text("•")
                    Text(lines[i]).fixedSize(horizontal: false, vertical: true)
                }
                .font(.subheadline)
            }
        }
    }
}

/// The own plan's routines as chips; tapping the chosen one again clears it.
struct ComebackRoutineChips: View {
    let routines: [ComebackRoutineUi]
    let onTap: (String) -> Void

    var body: some View {
        TrainingFlow(spacing: 6) {
            ForEach(routines, id: \.id) { routine in
                TrainingChoiceChip(title: routine.name, selected: routine.selected) { onTap(routine.id) }
                    .accessibilityIdentifier("comeback_own_routine_" + routine.id)
            }
        }
    }
}
