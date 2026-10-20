import CruxCoachCore
import SwiftUI

/// A running comeback plan on Today (Android `ComebackTodayCard`, owner 2026-10-20): the plan's title and stage
/// (tapping them opens the plan), a hint when the plan has a note, the morning value in one tap, the sessions due
/// now, and the notice every comeback surface carries. A session starts through Today's usual start, so an open
/// training asks first.
struct TodayComebackCard: View {
    let card: TodayComebackUi
    let onOpen: () -> Void
    /// 0–10, or nil when the chosen value was tapped again.
    let onMorning: (Int?) -> Void
    /// The session's index and its training name (`cb_session_name`).
    let onStart: (Int32, String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
            if card.hasPrompt {
                Text(L("cb_today_prompt"))
                    .font(.subheadline)
                    .foregroundStyle(Color.accentColor)
                    .accessibilityIdentifier("today_comeback_prompt")
            }
            ComebackPainScale(label: L("cb_today_question"), value: morning, tag: "today_comeback_morning", onChange: onMorning)
            ForEach(card.sessions, id: \.index) { session in
                sessionRow(session)
            }
            ComebackNoticeLine()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
    }

    private var morning: Int? {
        let value: Int = Int(card.morningPain)
        return value >= 0 ? value : nil
    }

    /// Android's whole card opens the plan; here the title row does, so the chips and buttons stay their own taps.
    private var header: some View {
        Button(action: onOpen) {
            HStack(alignment: .center, spacing: 8) {
                Image(systemName: "bandage").foregroundStyle(TrainingStyle.caution)
                VStack(alignment: .leading, spacing: 2) {
                    Text(tr: card.title).font(.headline)
                    Text(tr: card.stageLine).font(.subheadline)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right").foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("today_comeback_\(card.injuryId)")
    }

    private func sessionRow(_ session: TodayComebackSessionUi) -> some View {
        let title: String = TrRender.text(session.title)
        let index: Int32 = session.index
        let name: String = L("cb_session_name", title)
        return HStack(spacing: 8) {
            Text(title).font(.body).frame(maxWidth: .infinity, alignment: .leading)
            Button(L("cb_session_start")) { onStart(index, name) }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("today_comeback_start_\(index)")
        }
    }
}

/// The plan a Today card opens.
struct TodayComebackTarget: Identifiable, Hashable {
    let id: String
}

/// Pushes the comeback plan (`ComebackView`) on Today's navigation stack when a card is tapped.
struct TodayComebackDestination: ViewModifier {
    @Binding var injuryId: String?

    func body(content: Content) -> some View {
        content.navigationDestination(item: Binding(get: { injuryId.map { TodayComebackTarget(id: $0) } },
                                                    set: { injuryId = $0?.id })) { target in
            ComebackView(injuryId: target.id)
        }
    }
}
