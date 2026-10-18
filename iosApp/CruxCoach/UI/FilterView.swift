import CruxCoachCore
import SwiftUI

struct FilterView: View {
    let model: BrowserScreenModel
    let ui: BrowserScreenState
    @Environment(\.dismiss) private var dismiss

    @State private var termsOpen = false

    var body: some View {
        Form {
            Section(L("board_filter_status")) {
                statusToggle("new", L("board_filter_status_new"), ui.statusNew)
                statusToggle("attempted", L("board_filter_status_attempted"), ui.statusAttempted)
                statusToggle("sent", L("board_filter_status_sent"), ui.statusSent)
                Text(L("board_filter_status_hint")).font(.footnote).foregroundStyle(.secondary)
            }
            gradeSection
            sortSection
            moreSection
            if ui.isQuantum {
                quantumRulesSection
                quantumOverlapSection
            }
            Section {
                Button(LI("filter_clear"), role: .destructive) { model.clearFilters() }
            }
        }
        .navigationTitle(L("board_filter_title"))
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { termsOpen = true } label: {
                    Label(L("board_filter_info_action"), systemImage: "info.circle")
                }
            }
            ToolbarItem(placement: .confirmationAction) { Button(L("action_done")) { dismiss() } }
        }
        .sheet(isPresented: $termsOpen) { FilterTermsSheet() }
        // Android refreshes the "without a grade" count whenever board, angle or type change.
        .task(id: ungradedKey) { model.refreshUngradedAvailable() }
    }

    private var ungradedKey: String { ui.brandWire + "|" + String(ui.angle) + "|" + ui.climbType + "|" + ui.boardTitle }

    // MARK: Grade (Android: inert while ungraded-only or own climbs)

    private var gradeSection: some View {
        let inert: Bool = ui.ungradedOnly || ui.myClimbsOnly
        return Section(LI("filter_grade")) {
            Stepper(LI("filter_min_grade", Int(ui.minGradeIndex)),
                    value: binding(Int(ui.minGradeIndex)) { edit, value in edit.minGrade = value },
                    in: 0...Int(model.maxGradeIndex))
                .disabled(inert)
            Stepper(LI("filter_max_grade", Int(ui.maxGradeIndex)),
                    value: binding(Int(ui.maxGradeIndex)) { edit, value in edit.maxGrade = value },
                    in: 0...Int(model.maxGradeIndex))
                .disabled(inert)
            if ui.myClimbsOnly {
                Text(L("board_filter_own_scope")).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    // MARK: Sort (Android: field and, except for random, its direction)

    private var sortSection: some View {
        let fields: [String] = model.sortFieldCodes
        return Section(L("board_filter_sort")) {
            Picker(L("board_filter_sort"), selection: Binding(
                get: { ui.sortField },
                set: { code in model.setSortField(code: code) })) {
                ForEach(fields, id: \.self) { code in
                    Text(FilterView.sortFieldTitle(code)).tag(code)
                }
            }
            .accessibilityIdentifier("board_sort_row")
            if ui.sortField != "random" {
                Picker(L("board_filter_order"), selection: Binding(
                    get: { ui.sortDescending },
                    set: { descending in model.setSortDescending(descending: descending) })) {
                    Text(L("board_filter_sort_desc")).tag(true)
                    Text(L("board_filter_sort_asc")).tag(false)
                }
                .accessibilityIdentifier("board_sort_direction")
            }
        }
    }

    /// Android's sort labels; difficulty/name are older iOS picks Android does not offer.
    static func sortFieldTitle(_ code: String) -> String {
        switch code {
        case "quality": return L("board_quality")
        case "qualitySends": return L("board_sort_quality_sends")
        case "moves": return L("board_moves")
        case "newest": return L("board_sort_newest")
        case "random": return L("board_sort_random")
        case "difficulty": return LI("sort_hardest")
        case "name": return LI("sort_name")
        default: return L("board_sends")
        }
    }

    // MARK: More filters (Android's collapsible "More filters")

    private var moreSection: some View {
        Section {
            ungradedRow
            Toggle(L("board_filter_my_climbs"), isOn: flag(ui.myClimbsOnly) { edit, value in edit.mine = value })
            if ui.supportsBenchmark {
                Toggle(L("board_filter_benchmarks_only"), isOn: flag(ui.benchmarkOnly) { edit, value in edit.benchmark = value })
                    .disabled(ui.ungradedOnly)
            }
            Stepper(L("board_filter_min_ascents", Int(ui.minAscensionists)),
                    value: binding(Int(ui.minAscensionists)) { edit, value in edit.ascents = value },
                    in: 0...50)
                .disabled(ui.myClimbsOnly || ui.ungradedOnly)
            if ui.supportsClimbType { typePicker }
            originPicker
        } header: {
            Text(L("board_filter_more"))
        }
    }

    private var ungradedRow: some View {
        let count: Int64 = ui.ungradedAvailable
        let title: String = count > 0 ? L("board_filter_ungraded_only_count", Int(count)) : L("board_filter_ungraded_only")
        // Still switchable while on, so a stale "on" can always be turned off.
        let enabled: Bool = ui.ungradedOnly || count != 0
        return VStack(alignment: .leading, spacing: 2) {
            Toggle(title, isOn: Binding(get: { ui.ungradedOnly }, set: { on in model.setUngradedOnly(enabled: on) }))
                .disabled(!enabled)
                .accessibilityIdentifier("board_filter_ungraded_only")
            if ui.ungradedOnly {
                Text(L("board_filter_ungraded_only_active")).font(.footnote).foregroundStyle(.secondary)
            } else if count == 0 {
                Text(L("board_filter_ungraded_only_none")).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private var typePicker: some View {
        let codes: [String] = model.climbTypeCodes
        return Picker(L("board_filter_type"), selection: Binding(
            get: { ui.climbType },
            set: { code in model.setClimbType(code: code) })) {
            ForEach(codes, id: \.self) { code in
                Text(FilterView.typeTitle(code)).tag(code)
            }
        }
        .accessibilityIdentifier("board_filter_type")
    }

    private static func typeTitle(_ code: String) -> String {
        switch code {
        case "route": return L("board_filter_type_routes")
        case "all": return L("board_filter_all")
        default: return L("board_filter_type_boulder")
        }
    }

    private var originPicker: some View {
        var codes: [String] = ["all", "cruxcoach", "kilter"]
        if ui.supportsBoardSeshOrigin { codes.append("boardsesh") }
        let quantum: Bool = ui.isQuantum
        return Picker(L("board_filter_origin"), selection: Binding(
            get: { ui.origin },
            set: { code in model.setOrigin(code: code) })) {
            ForEach(codes, id: \.self) { code in
                Text(FilterView.originTitle(code, quantum: quantum)).tag(code)
            }
        }
        .accessibilityIdentifier("board_filter_origin")
    }

    private static func originTitle(_ code: String, quantum: Bool) -> String {
        switch code {
        case "cruxcoach": return L("board_filter_origin_cruxcoach")
        case "kilter": return quantum ? L("board_filter_origin_quantum") : L("board_filter_origin_kilter")
        case "boardsesh": return L("board_filter_origin_boardsesh")
        default: return L("board_filter_all")
        }
    }

    // MARK: Quantum (Android BoardFilterScreen: rules chips and "Fits the wall")

    private var quantumRulesSection: some View {
        let codes: [String] = model.quantumRuleCodes
        let active: [String] = ui.quantumRules
        return Section(L("board_filter_quantum_rules")) {
            ForEach(codes, id: \.self) { code in
                Toggle(L("board_filter_quantum_" + code), isOn: Binding(
                    get: { active.contains(code) },
                    set: { _ in model.toggleQuantumRule(code: code) }))
            }
        }
    }

    /// Android's "Fits the wall": measured against the layers the controller confirms right now.
    @ViewBuilder
    private var quantumOverlapSection: some View {
        if ui.quantumWallKnown {
            Section(L("board_filter_quantum_overlap_title")) {
                ForEach(["off", "none", "atMostOne"], id: \.self) { code in
                    quantumOverlapRow(code)
                }
                Text(quantumOverlapHint)
                    .font(.footnote)
                    .foregroundStyle(quantumOverlapWarns ? Color.red : Color.secondary)
            }
        } else {
            Section(L("board_filter_quantum_overlap_title")) {
                Label(LI("quantum_wall_fit_unavailable"), systemImage: "iphone.slash")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func quantumOverlapRow(_ code: String) -> some View {
        let selected: Bool = ui.quantumOverlap == code
        let enabled: Bool = code == "off" || ui.quantumLayersAvailable
        let title: String = code == "off" ? L("board_filter_quantum_overlap_off")
            : (code == "none" ? L("board_filter_quantum_overlap_none") : L("board_filter_quantum_overlap_one"))
        return Button { model.setQuantumOverlap(code: code) } label: {
            HStack {
                Text(title).foregroundStyle(.primary)
                Spacer()
                if selected { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
            }
        }
        .disabled(!enabled)
        .accessibilityIdentifier("board_filter_quantum_overlap_" + code)
    }

    private var quantumOverlapWarns: Bool { ui.quantumLayersOccupied && !ui.quantumLayersComplete }

    private var quantumOverlapHint: String {
        if quantumOverlapWarns { return L("board_filter_quantum_overlap_incomplete") }
        if !ui.quantumLayersOccupied { return L("board_filter_quantum_overlap_inert") }
        let count: Int64 = ui.quantumFitCount
        if ui.quantumOverlap != "off" && count >= 0 { return L("board_filter_quantum_overlap_count", Int(count)) }
        return L("board_filter_quantum_overlap_hint")
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

/// Android `FilterTermInfoDialog`: the plain-language glossary behind the filter's info action.
private struct FilterTermsSheet: View {
    @Environment(\.dismiss) private var dismiss

    private let termKeys: [String] = [
        "board_filter_status_new", "board_filter_status_attempted", "board_filter_status_sent",
        "board_filter_benchmarks_only", "board_filter_ungraded_only", "board_sends", "board_quality",
        "board_sort_quality_sends", "board_moves", "board_sort_newest", "board_sort_random",
    ]
    private let explanationKeys: [String] = [
        "board_filter_info_status_new", "board_filter_info_status_attempted", "board_filter_info_status_sent",
        "board_filter_info_benchmarks", "board_filter_info_ungraded", "board_filter_info_sort_sends",
        "board_filter_info_sort_quality", "board_filter_info_sort_quality_sends", "board_filter_info_sort_moves",
        "board_filter_info_sort_newest", "board_filter_info_sort_random",
    ]

    var body: some View {
        NavigationStack {
            List {
                ForEach(0..<termKeys.count, id: \.self) { index in
                    termRow(index)
                }
            }
            .navigationTitle(L("board_filter_info_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("action_close")) { dismiss() } } }
        }
    }

    private func termRow(_ index: Int) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L(termKeys[index])).font(.headline)
            Text(L(explanationKeys[index])).font(.subheadline).foregroundStyle(.secondary)
        }
    }
}
