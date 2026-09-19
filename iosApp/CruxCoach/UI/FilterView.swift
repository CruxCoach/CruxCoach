import CruxCoachCore
import SwiftUI

struct FilterView: View {
    let presenter: BoardBrowserPresenter
    let state: Observed<BoardBrowserUiState>
    @Environment(\.dismiss) private var dismiss

    private var filter: BrowserFilterState { state.value.filter }

    var body: some View {
        Form {
            Section(L("board_filter_status")) {
                statusToggle(.theNew, L("board_filter_status_new"))
                statusToggle(.attempted, L("board_filter_status_attempted"))
                statusToggle(.sent, L("board_filter_status_sent"))
                Text(L("board_filter_status_hint")).font(.footnote).foregroundStyle(.secondary)
            }
            Section(LI("filter_grade")) {
                Stepper(LI("filter_min_grade", Int(filter.minGradeIndex)), value: gradeBinding(\.minGradeIndex) { edit, v in edit.min = v }, in: 0...Int(GradeConverter.shared.MAX_INDEX))
                Stepper(LI("filter_max_grade", Int(filter.maxGradeIndex)), value: gradeBinding(\.maxGradeIndex) { edit, v in edit.max = v }, in: 0...Int(GradeConverter.shared.MAX_INDEX))
                Toggle(LI("filter_ungraded_only"), isOn: flag(\.ungradedOnly) { $0.ungraded = $1 })
            }
            Section {
                Toggle(LI("filter_benchmark_only"), isOn: flag(\.benchmarkOnly) { $0.benchmark = $1 })
                Toggle(LI("filter_my_climbs"), isOn: flag(\.myClimbsOnly) { $0.mine = $1 })
                Stepper(LI("filter_min_ascents", Int(filter.minAscensionists)),
                        value: gradeBinding(\.minAscensionists) { edit, v in edit.ascents = v }, in: 0...1000, step: 5)
            }
            Section { Button(LI("filter_clear"), role: .destructive) { presenter.clearAllFilters() } }
        }
        .navigationTitle(L("board_filter_title"))
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("action_done")) { dismiss() } } }
    }

    private func statusToggle(_ status: ClimbStatusFilter, _ title: String) -> some View {
        Toggle(title, isOn: Binding(get: { filter.statusFilter.contains(status) }, set: { _ in presenter.toggleStatus(status: status) }))
    }

    private struct Edit { var min: Int, max: Int, ascents: Int, benchmark: Bool, mine: Bool, ungraded: Bool }

    private func apply(_ change: (inout Edit) -> Void) {
        var edit = Edit(min: Int(filter.minGradeIndex), max: Int(filter.maxGradeIndex), ascents: Int(filter.minAscensionists),
                        benchmark: filter.benchmarkOnly, mine: filter.myClimbsOnly, ungraded: filter.ungradedOnly)
        change(&edit)
        presenter.setFilters(e: BrowseFilterEdit(
            minGradeIndex: Int32(edit.min), maxGradeIndex: Int32(edit.max), minAscensionists: Int32(edit.ascents),
            climbTypeFilter: filter.climbTypeFilter, benchmarkOnly: edit.benchmark, originFilter: filter.originFilter,
            myClimbsOnly: edit.mine, ungradedOnly: edit.ungraded, quantumRuleMask: filter.quantumRuleMask,
            quantumOverlapFilter: filter.quantumOverlapFilter))
    }

    private func gradeBinding(_ key: KeyPath<BrowserFilterState, Int32>, _ set: @escaping (inout Edit, Int) -> Void) -> Binding<Int> {
        Binding(get: { Int(filter[keyPath: key]) }, set: { value in apply { set(&$0, value) } })
    }

    private func flag(_ key: KeyPath<BrowserFilterState, Bool>, _ set: @escaping (inout Edit, Bool) -> Void) -> Binding<Bool> {
        Binding(get: { filter[keyPath: key] }, set: { value in apply { set(&$0, value) } })
    }
}
