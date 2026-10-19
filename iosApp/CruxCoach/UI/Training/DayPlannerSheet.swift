import CruxCoachCore
import SwiftUI

/// "Plan days" (Android `DayPlannerSheet`, owner 2026-10-19): today and the next two weeks, one tap per day to
/// plan or swap it; the free days show how the coach fits them around the climbing, with advice (nothing locked),
/// "Undo" for the last change and a one-tap move for a training that had to give way. All decisions are the
/// Kotlin presenter's (`DayPlannerScreenModel`); this view only shows them.
struct DayPlannerSheet: View {
    let core: AppCore
    let onClose: () -> Void

    @State private var host: ScreenHost<DayPlannerScreenModel, DayPlannerScreenState>?
    @State private var unavailable = false

    var body: some View {
        NavigationStack {
            Group {
                if let host {
                    DayPlannerContent(model: host.model, ui: host.state)
                } else if unavailable {
                    ContentUnavailableView(LI("library_unavailable"), systemImage: "lock.trianglebadge.exclamationmark")
                } else {
                    ProgressView()
                }
            }
            .navigationTitle(L("trdp_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_done"), action: onClose)
                }
            }
        }
        .task {
            guard host == nil else { return }
            guard let model = core.makeDayPlannerScreen() else {
                unavailable = true
                return
            }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }
}

/// "Today", "Tomorrow", else the short weekday and date ("Wed, Oct 22"), as Android's `plannerDayLabel`.
enum DayPlanDates {
    static func label(_ iso: String, today: String, tomorrow: String) -> String {
        if iso == today { return L("trdp_today") }
        if iso == tomorrow { return L("trdp_tomorrow") }
        return short(iso)
    }

    static func short(_ iso: String) -> String {
        let parser = DateFormatter()
        parser.calendar = Calendar(identifier: .gregorian)
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.dateFormat = "yyyy-MM-dd"
        guard let day = parser.date(from: iso) else { return iso }
        let out = DateFormatter()
        out.setLocalizedDateFormatFromTemplate("EEEdMMM")
        return out.string(from: day)
    }
}

private struct DayPlanRowChoice: Identifiable {
    let row: DayPlanRowUi
    var id: String { row.isoDate }
}

private struct DayPlannerContent: View {
    let model: DayPlannerScreenModel
    let ui: DayPlannerScreenState

    @State private var choosing: DayPlanRowUi?

    var body: some View {
        List {
            Section {
                Text(L("trdp_intro")).font(.footnote).foregroundStyle(.secondary)
                if ui.hasChange { changedRow }
                if ui.hasMoveOffer { moveCard }
                ForEach(Array(ui.warnings.enumerated()), id: \.offset) { _, warning in
                    warningRow(warning)
                }
            }
            if ui.loading {
                ProgressView().frame(maxWidth: .infinity)
            }
            Section {
                ForEach(ui.days, id: \.isoDate) { day in
                    dayRow(day)
                }
            }
        }
        .sheet(item: Binding(get: { choosing.map { DayPlanRowChoice(row: $0) } }, set: { choosing = $0?.row })) { choice in
            chooser(choice.row)
        }
    }

    private func label(_ iso: String) -> String {
        DayPlanDates.label(iso, today: ui.todayIso, tomorrow: ui.tomorrowIso)
    }

    private var changedRow: some View {
        HStack {
            Text(L("trdp_changed")).font(.subheadline)
            Spacer()
            Button(L("trpg_undo")) { model.undo() }
                .buttonStyle(.borderless)
                .accessibilityIdentifier("day_planner_undo")
        }
    }

    private var moveCard: some View {
        let entry: String = TrRender.text(ui.moveEntryLabel)
        let from: String = label(ui.moveFromIso)
        let to: String = label(ui.moveToIso)
        return VStack(alignment: .leading, spacing: 8) {
            Text(L("trdp_move_offer", entry, from, to)).font(.subheadline)
            HStack(spacing: 12) {
                Button(L("trdp_move_yes")) { model.acceptMove() }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("day_planner_move_yes")
                Button(L("trdp_move_no")) { model.dismissMove() }
                    .buttonStyle(.borderless)
                    .accessibilityIdentifier("day_planner_move_no")
            }
        }
        .padding(.vertical, 4)
    }

    private func warningRow(_ warning: DayPlanWarningUi) -> some View {
        let other: String = label(warning.otherIso)
        let date: String = label(warning.dateIso)
        let text: String = warning.kindCode == "backToBack"
            ? L("trdp_warn_back_to_back", other, date)
            : L("trdp_warn_fingers_before", other, date)
        return HStack(alignment: .top, spacing: 8) {
            Image(systemName: "exclamationmark.triangle").foregroundStyle(Color.red)
            Text(text).font(.caption)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("day_planner_warning")
    }

    private func dayRow(_ day: DayPlanRowUi) -> some View {
        let dayLabel: String = label(day.isoDate)
        let detail: String = TrRender.text(day.detail)
        let weight: Font.Weight = day.isToday ? .bold : .regular
        let entryWeight: Font.Weight = day.emphasized ? .semibold : .regular
        let entryColor: Color = day.emphasized ? Color.primary : Color.secondary
        let detailColor: Color = day.detailAccent ? Color.accentColor : Color.secondary
        return Button { choosing = day } label: {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(dayLabel).font(.body.weight(weight)).frame(width: 112, alignment: .leading)
                VStack(alignment: .leading, spacing: 2) {
                    Text(tr: day.label).font(.body.weight(entryWeight)).foregroundStyle(entryColor)
                    if !detail.isEmpty {
                        Text(detail).font(.caption).foregroundStyle(detailColor)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("day_planner_day_" + day.isoDate)
    }

    private func chooser(_ day: DayPlanRowUi) -> some View {
        let iso: String = day.isoDate
        return DayPlanChooser(
            title: label(iso),
            standardLabel: TrRender.optional(day.standardLabel),
            head: headChoices(day),
            climbSelected: day.climbSelected,
            climbKind: day.climbKind,
            climbIntensity: day.climbIntensity,
            kinds: kindChoices(),
            intensities: intensityChoices(),
            board: DayPlanChoice(entry: "board", label: L("trwo_plan_board"), selected: day.entry == "board"),
            swaps: swapChoices(day),
            trainings: trainingChoices(day),
            onChoose: { entry in
                choosing = nil
                model.setDay(isoDate: iso, entry: entry)
            },
            onClimb: { kind, intensity in
                choosing = nil
                model.setClimb(isoDate: iso, kind: kind, intensity: intensity)
            },
            onStandard: {
                choosing = nil
                model.setStandard(isoDate: iso)
            },
            onSwap: { other in
                choosing = nil
                model.swap(isoDate: iso, otherIso: other)
            },
            onCancel: { choosing = nil }
        )
    }

    private func headChoices(_ day: DayPlanRowUi) -> [DayPlanChoice] {
        let free = DayPlanChoice(entry: "", label: L("trdp_free"), selected: day.entry.isEmpty)
        let rest = DayPlanChoice(entry: "rest", label: L("trwo_plan_rest"), selected: day.entry == "rest")
        return [free, rest]
    }

    private func kindChoices() -> [DayPlanChoice] {
        var out: [DayPlanChoice] = []
        for choice in ui.climbKinds {
            out.append(DayPlanChoice(entry: choice.code, label: TrRender.text(choice.label), selected: false))
        }
        return out
    }

    private func intensityChoices() -> [DayPlanChoice] {
        var out: [DayPlanChoice] = []
        for choice in ui.climbIntensities {
            out.append(DayPlanChoice(entry: choice.code, label: TrRender.text(choice.label), selected: false))
        }
        return out
    }

    private func swapChoices(_ day: DayPlanRowUi) -> [DayPlanChoice] {
        var out: [DayPlanChoice] = []
        for iso in day.swapTargets {
            out.append(DayPlanChoice(entry: iso, label: label(iso), selected: false))
        }
        return out
    }

    private func trainingChoices(_ day: DayPlanRowUi) -> [DayPlanChoice] {
        var out: [DayPlanChoice] = []
        for option in ui.trainings {
            out.append(DayPlanChoice(entry: option.entry, label: TrRender.text(option.label), selected: option.entry == day.entry))
        }
        return out
    }
}

/// One option of the day chooser, already in the device language.
struct DayPlanChoice: Identifiable {
    let entry: String
    let label: String
    let selected: Bool
    var id: String { entry }
}

/// What a day can be (Android `PlanChooserDialog`): free, rest, a climbing day (where, how hard), a board session,
/// a swap with another day, or a training. Used for the standard week's weekdays and for single dates (those also
/// go back to the standard week [standardLabel] or swap [swaps]).
struct DayPlanChooser: View {
    let title: String
    let standardLabel: String?
    let head: [DayPlanChoice]
    let climbSelected: Bool
    let kinds: [DayPlanChoice]
    let intensities: [DayPlanChoice]
    let board: DayPlanChoice
    let swaps: [DayPlanChoice]
    let trainings: [DayPlanChoice]
    let onChoose: (String) -> Void
    let onClimb: (String, String) -> Void
    let onStandard: () -> Void
    let onSwap: (String) -> Void
    let onCancel: () -> Void

    @State private var kind: String
    @State private var intensity: String

    init(title: String, standardLabel: String?, head: [DayPlanChoice], climbSelected: Bool,
         climbKind: String, climbIntensity: String, kinds: [DayPlanChoice], intensities: [DayPlanChoice],
         board: DayPlanChoice, swaps: [DayPlanChoice], trainings: [DayPlanChoice],
         onChoose: @escaping (String) -> Void, onClimb: @escaping (String, String) -> Void,
         onStandard: @escaping () -> Void, onSwap: @escaping (String) -> Void, onCancel: @escaping () -> Void) {
        self.title = title
        self.standardLabel = standardLabel
        self.head = head
        self.climbSelected = climbSelected
        self.kinds = kinds
        self.intensities = intensities
        self.board = board
        self.swaps = swaps
        self.trainings = trainings
        self.onChoose = onChoose
        self.onClimb = onClimb
        self.onStandard = onStandard
        self.onSwap = onSwap
        self.onCancel = onCancel
        _kind = State(initialValue: climbKind)
        _intensity = State(initialValue: climbIntensity)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let standardLabel {
                        row(DayPlanChoice(entry: "standard", label: standardLabel, selected: false),
                            tag: "week_plan_option_standard", action: onStandard)
                    }
                    ForEach(head) { option in
                        row(option, tag: tag(option)) { onChoose(option.entry) }
                    }
                    climbSection
                    row(board, tag: "week_plan_option_board") { onChoose(board.entry) }
                    if !swaps.isEmpty { swapSection }
                    ForEach(trainings) { option in
                        row(option, tag: "week_plan_option_" + option.entry) { onChoose(option.entry) }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("tr_action_cancel"), action: onCancel)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    /// Android's test tags: "week_plan_option_free", "week_plan_option_rest", "week_plan_option_<entry>".
    private func tag(_ option: DayPlanChoice) -> String {
        let code: String = option.entry.isEmpty ? "free" : option.entry
        return "week_plan_option_" + code
    }

    private func row(_ option: DayPlanChoice, tag: String, action: @escaping () -> Void) -> some View {
        VStack(spacing: 0) {
            Button(action: action) {
                HStack {
                    Text(option.label).foregroundStyle(Color.primary).frame(maxWidth: .infinity, alignment: .leading)
                    if option.selected { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier(tag)
            Divider()
        }
    }

    private var climbSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(L("trdp_climb")).frame(maxWidth: .infinity, alignment: .leading)
                if climbSelected { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
            }
            .padding(.top, 10)
            Text(L("trdp_climb_where")).font(.caption).foregroundStyle(.secondary)
            TrainingFlow(spacing: 6) {
                ForEach(kinds) { choice in
                    TrainingChoiceChip(title: choice.label, selected: kind == choice.entry) { kind = choice.entry }
                        .accessibilityIdentifier("week_plan_climb_kind_" + choice.entry)
                }
            }
            Text(L("trdp_climb_how")).font(.caption).foregroundStyle(.secondary)
            TrainingFlow(spacing: 6) {
                ForEach(intensities) { choice in
                    TrainingChoiceChip(title: choice.label, selected: intensity == choice.entry) { intensity = choice.entry }
                        .accessibilityIdentifier("week_plan_climb_intensity_" + choice.entry)
                }
            }
            Button(L("trdp_climb_apply")) { onClimb(kind, intensity) }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("week_plan_climb_apply")
                .padding(.bottom, 10)
            Divider()
        }
    }

    private var swapSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(L("trdp_swap"), systemImage: "arrow.left.arrow.right").padding(.top, 10)
            TrainingFlow(spacing: 6) {
                ForEach(swaps) { choice in
                    TrainingChoiceChip(title: choice.label, selected: false) { onSwap(choice.entry) }
                        .accessibilityIdentifier("week_plan_swap_" + choice.entry)
                }
            }
            .padding(.bottom, 10)
            Divider()
        }
    }
}
