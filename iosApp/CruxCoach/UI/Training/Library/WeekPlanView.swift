import CruxCoachCore
import SwiftUI

/// The standard week (Android `WeekPlanScreen`): a workout, a board session or rest per weekday,
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
            NavigationStack {
                List {
                    ForEach(choice.day.options, id: \.entry) { option in
                        Button {
                            model.setDay(weekday: choice.day.weekday, entry: option.entry)
                            choosing = nil
                        } label: {
                            HStack {
                                Text(LibraryRender.text(option.label))
                                Spacer()
                                if option.selected { Image(systemName: "checkmark") }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .navigationTitle(LibraryRender.weekday(Int(choice.day.weekday)))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(L("tr_action_cancel")) { choosing = nil }
                    }
                }
            }
            .presentationDetents([.medium, .large])
        }
    }
}

/// Identifiable wrapper for the chooser of one weekday.
struct LibraryWeekdayChoice: Identifiable {
    let day: PlanDayUi
    var id: Int32 { day.weekday }
}
