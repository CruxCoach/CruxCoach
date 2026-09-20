import CruxCoachCore
import SwiftUI

struct FilterView: View {
    let model: BrowserScreenModel
    let ui: BrowserScreenState
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Form {
            Section(L("board_filter_status")) {
                statusToggle("new", L("board_filter_status_new"), ui.statusNew)
                statusToggle("attempted", L("board_filter_status_attempted"), ui.statusAttempted)
                statusToggle("sent", L("board_filter_status_sent"), ui.statusSent)
                Text(L("board_filter_status_hint")).font(.footnote).foregroundStyle(.secondary)
            }
            Section(LI("filter_grade")) {
                Stepper(LI("filter_min_grade", Int(ui.minGradeIndex)),
                        value: binding(Int(ui.minGradeIndex)) { edit, value in edit.minGrade = value },
                        in: 0...Int(model.maxGradeIndex))
                Stepper(LI("filter_max_grade", Int(ui.maxGradeIndex)),
                        value: binding(Int(ui.maxGradeIndex)) { edit, value in edit.maxGrade = value },
                        in: 0...Int(model.maxGradeIndex))
                Toggle(LI("filter_ungraded_only"), isOn: flag(ui.ungradedOnly) { edit, value in edit.ungraded = value })
            }
            Section {
                Toggle(LI("filter_benchmark_only"), isOn: flag(ui.benchmarkOnly) { edit, value in edit.benchmark = value })
                Toggle(LI("filter_my_climbs"), isOn: flag(ui.myClimbsOnly) { edit, value in edit.mine = value })
                Stepper(LI("filter_min_ascents", Int(ui.minAscensionists)),
                        value: binding(Int(ui.minAscensionists)) { edit, value in edit.ascents = value },
                        in: 0...1000, step: 5)
            }
            Section {
                Button(LI("filter_clear"), role: .destructive) { model.clearFilters() }
            }
        }
        .navigationTitle(L("board_filter_title"))
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("action_done")) { dismiss() } } }
    }

    private func statusToggle(_ code: String, _ title: String, _ isOn: Bool) -> some View {
        Toggle(title, isOn: Binding(get: { isOn }, set: { _ in model.toggleStatus(code: code) }))
    }

    private struct Edit {
        var minGrade: Int, maxGrade: Int, ascents: Int, benchmark: Bool, mine: Bool, ungraded: Bool
    }

    private func apply(_ change: (inout Edit) -> Void) {
        var edit = Edit(minGrade: Int(ui.minGradeIndex), maxGrade: Int(ui.maxGradeIndex),
                        ascents: Int(ui.minAscensionists), benchmark: ui.benchmarkOnly,
                        mine: ui.myClimbsOnly, ungraded: ui.ungradedOnly)
        change(&edit)
        model.applyFilters(minGradeIndex: Int32(edit.minGrade), maxGradeIndex: Int32(edit.maxGrade),
                           minAscensionists: Int32(edit.ascents), benchmarkOnly: edit.benchmark,
                           myClimbsOnly: edit.mine, ungradedOnly: edit.ungraded)
    }

    private func binding(_ current: Int, _ set: @escaping (inout Edit, Int) -> Void) -> Binding<Int> {
        Binding(get: { current }, set: { value in apply { set(&$0, value) } })
    }

    private func flag(_ current: Bool, _ set: @escaping (inout Edit, Bool) -> Void) -> Binding<Bool> {
        Binding(get: { current }, set: { value in apply { set(&$0, value) } })
    }
}
