import CruxCoachCore
import SwiftUI

/// The standard week (Android `WeekPlanScreen`): a workout, a board session, a climbing day or rest per weekday,
/// the coach's proposal with "apply" and "undo", and the usual session length.
struct WeekPlanView: View {
    let core: AppCore

    @State private var host: ScreenHost<WeekPlanScreenModel, WeekPlanScreenState>?
    @State private var unavailable = false
    @State private var choosing: PlanDayUi?
    @State private var showProposal = false
    @State private var showInfo = false

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else if unavailable {
                ContentUnavailableView(LI("library_unavailable"), systemImage: "lock.trianglebadge.exclamationmark")
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("trwo_week_plan"))
        .modifier(RestTimerBannerSlot(core: core))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { showInfo = true } label: { Image(systemName: "info.circle") }
            }
        }
        .alert(L("trwo_week_plan"), isPresented: $showInfo) {
            Button(L("action_close"), role: .cancel) {}
        } message: {
            Text(L("trwo_week_plan_intro"))
        }
        .task {
            guard host == nil else { return }
            guard let model = core.makeWeekPlanScreen() else {
                unavailable = true
                return
            }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }

    @ViewBuilder
    private func screen(model: WeekPlanScreenModel, ui: WeekPlanScreenState) -> some View {
        List {
            if ui.isLoading { ProgressView().frame(maxWidth: .infinity) }
            if ui.failed {
                Label(LI("library_failed"), systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
            }
            if ui.hasProposal {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(L("trc_week_proposal_title")).font(.headline)
                        Text(L(ui.proposalSame ? "trc_week_proposal_same" : "trc_week_proposal_text"))
                            .font(.footnote).foregroundStyle(.secondary)
                        if showProposal {
                            ForEach(ui.days, id: \.weekday) { day in
                                HStack {
                                    Text(LibraryRender.weekday(Int(day.weekday))).fontWeight(.semibold).frame(width: 112, alignment: .leading)
                                    Text(LibraryRender.text(day.proposalLabel))
                                }
                                .font(.subheadline)
                            }
                        }
                        if !ui.proposalSame {
                            HStack {
                                Button(L(showProposal ? "trc_week_proposal_hide" : "trc_week_proposal_show")) { showProposal.toggle() }
                                    .buttonStyle(.borderless)
                                Button(L("trc_plan_apply")) { model.applyProposal() }
                                    .buttonStyle(.bordered)
                            }
                        }
                        if ui.canUndo {
                            HStack {
                                Text(L("trwo_week_applied")).font(.footnote)
                                Spacer()
                                Button(L("trpg_undo")) { model.undoProposal() }.buttonStyle(.borderless)
                            }
                        }
                    }
                }
            }
            Section {
                ForEach(ui.days, id: \.weekday) { day in
                    Button { choosing = day } label: {
                        HStack {
                            Text(LibraryRender.weekday(Int(day.weekday))).fontWeight(.semibold).frame(width: 120, alignment: .leading)
                            Text(LibraryRender.text(day.label))
                                .foregroundStyle(day.hasEntry ? Color.primary : Color.secondary)
                            Spacer()
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            Section {
                LibraryFlowLayout(spacing: 6) {
                    ForEach(ui.minuteOptions.map { Int(truncating: $0) }, id: \.self) { m in
                        LibraryChip(title: L("trwo_minutes", m), selected: Int(ui.sessionMinutes) == m) {
                            model.setMinutes(minutes: Int32(m))
                        }
                    }
                }
            } header: {
                Text(L("trwo_session_minutes"))
            } footer: {
                Text(L("trwo_session_minutes_hint"))
            }
        }
        .sheet(item: Binding(get: { choosing.map { LibraryWeekdayChoice(day: $0) } }, set: { choosing = $0?.day })) { choice in
            weekdayChooser(choice.day, ui: ui, model: model)
        }
    }

    /// Android `PlanChooserDialog` for a weekday: free, rest, a climbing day (where, how hard), board, a training.
    private func weekdayChooser(_ day: PlanDayUi, ui: WeekPlanScreenState, model: WeekPlanScreenModel) -> some View {
        let weekday: Int32 = day.weekday
        let options: [DayPlanChoice] = planChoices(day.options)
        let head: [DayPlanChoice] = options.filter { $0.entry.isEmpty || $0.entry == "rest" }
        let trainings: [DayPlanChoice] = options.filter { !$0.entry.isEmpty && $0.entry != "rest" && $0.entry != "board" }
        let board: DayPlanChoice = options.first(where: { $0.entry == "board" })
            ?? DayPlanChoice(entry: "board", label: L("trwo_plan_board"), selected: false)
        return DayPlanChooser(
            title: LibraryRender.weekday(Int(weekday)),
            standardLabel: nil,
            head: head,
            climbSelected: day.climbSelected,
            climbKind: day.climbKind,
            climbIntensity: day.climbIntensity,
            kinds: climbChoices(ui.climbKinds),
            intensities: climbChoices(ui.climbIntensities),
            board: board,
            swaps: [],
            trainings: trainings,
            onChoose: { entry in
                choosing = nil
                model.setDay(weekday: weekday, entry: entry)
            },
            onClimb: { kind, intensity in
                choosing = nil
                model.setDayClimb(weekday: weekday, kind: kind, intensity: intensity)
            },
            onStandard: {},
            onSwap: { _ in },
            onCancel: { choosing = nil }
        )
    }

    private func planChoices(_ options: [PlanOptionUi]) -> [DayPlanChoice] {
        var out: [DayPlanChoice] = []
        for option in options {
            out.append(DayPlanChoice(entry: option.entry, label: LibraryRender.text(option.label), selected: option.selected))
        }
        return out
    }

    private func climbChoices(_ choices: [PlanClimbChoiceUi]) -> [DayPlanChoice] {
        var out: [DayPlanChoice] = []
        for choice in choices {
            out.append(DayPlanChoice(entry: choice.code, label: LibraryRender.text(choice.label), selected: false))
        }
        return out
    }
}

/// Identifiable wrapper for the chooser of one weekday.
struct LibraryWeekdayChoice: Identifiable {
    let day: PlanDayUi
    var id: Int32 { day.weekday }
}
