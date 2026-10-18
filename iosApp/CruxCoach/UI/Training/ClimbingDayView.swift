import CruxCoachCore
import SwiftUI

/// "Log a climbing day" (Android `ClimbingDaySheet`): gym, rock or another board in a few taps, so
/// recovery rules, streak, stats and nutrition know about it. `editId` "" logs a new day.
struct ClimbingDaySheetView: View {
    let core: AppCore
    let editId: String
    let onDone: () -> Void
    /// While editing: log another session instead (today's entry opened from Today's tile).
    var onAddAnother: (() -> Void)?
    /// The entry was deleted: the host offers "Undo" (device test D45).
    var onDeleted: ((ClimbingDayUndo) -> Void)?

    @State private var host: ScreenHost<ClimbingDayScreenModel, ClimbingDayScreenState>?
    @State private var picking = false
    @State private var pickedDate = Date()

    var body: some View {
        NavigationStack {
            Group {
                if let host, !host.state.loading {
                    form(host.model, host.state)
                } else {
                    ProgressView()
                }
            }
            .navigationTitle(L(host?.state.isEdit == true ? "trl_day_edit_title" : "trl_day_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel"), action: onDone) } }
        }
        .task {
            guard host == nil, let model = core.makeClimbingDayScreen() else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.load(editId: editId)
        }
        .onChange(of: host?.state.done ?? false) { _, done in
            if done {
                // Read before consumeDone, which forgets it.
                let undo: ClimbingDayUndo? = host?.model.lastDeleted()
                host?.model.consumeDone()
                if let undo, let onDeleted { onDeleted(undo) }
                onDone()
            }
        }
    }

    // One function per section: as a single Form the expression was too large for the type checker.
    @ViewBuilder
    private func form(_ model: ClimbingDayScreenModel, _ ui: ClimbingDayScreenState) -> some View {
        Form {
            daySection(model, ui)
            kindSection(model, ui)
            minutesSection(model, ui)
            intensitySection(model, ui)
            noteSection(model, ui)
            actionsSection(model, ui)
        }
    }

    @ViewBuilder
    private func daySection(_ model: ClimbingDayScreenModel, _ ui: ClimbingDayScreenState) -> some View {
        Section {
            Text(L("trl_day_intro")).font(.caption).foregroundStyle(.secondary)
            TrainingFlow {
                TrainingChoiceChip(title: L("trl_day_today"), selected: ui.dayCode == "today") { model.setDay(code: "today") }
                TrainingChoiceChip(title: L("trl_day_yesterday"), selected: ui.dayCode == "yesterday") { model.setDay(code: "yesterday") }
                TrainingChoiceChip(title: ui.dayCode == "other" ? TrRender.date(ui.day) : L("trl_day_pick"), selected: ui.dayCode == "other") {
                    picking = true
                }
            }
            if picking {
                DatePicker(L("trl_day_pick"), selection: $pickedDate, in: ...Date(), displayedComponents: .date)
                    .datePickerStyle(.graphical)
                    .onChange(of: pickedDate) { _, date in
                        model.setDay(code: Self.iso(date))
                        picking = false
                    }
            }
        }
    }

    @ViewBuilder
    private func kindSection(_ model: ClimbingDayScreenModel, _ ui: ClimbingDayScreenState) -> some View {
        Section(L("trl_day_kind")) {
            TrainingFlow {
                ForEach(model.kindCodes, id: \.self) { code in
                    TrainingChoiceChip(title: TrRender.text(model.kindLabel(code: code)), selected: ui.kindCode == code) {
                        model.setKind(code: code)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func minutesSection(_ model: ClimbingDayScreenModel, _ ui: ClimbingDayScreenState) -> some View {
        let options: [Int32] = ui.minuteOptions.map { $0.int32Value }
        let chosen: Int32 = ui.minutes
        Section(L("trl_day_minutes")) {
            TrainingFlow {
                ForEach(options, id: \.self) { m in
                    minuteChip(m, selected: m == chosen, model: model)
                }
            }
        }
    }

    private func minuteChip(_ m: Int32, selected: Bool, model: ClimbingDayScreenModel) -> some View {
        let title: String = L("trl_day_minutes_value", Int(m))
        return TrainingChoiceChip(title: title, selected: selected) { model.setMinutes(minutes: m) }
    }

    @ViewBuilder
    private func intensitySection(_ model: ClimbingDayScreenModel, _ ui: ClimbingDayScreenState) -> some View {
        Section(L("trl_day_intensity")) {
            ForEach(model.intensityCodes, id: \.self) { code in
                Button { model.setIntensity(code: code) } label: {
                    HStack {
                        Image(systemName: ui.intensityCode == code ? "largecircle.fill.circle" : "circle")
                            .foregroundStyle(Color.accentColor)
                        VStack(alignment: .leading) {
                            Text(tr: model.intensityLabel(code: code))
                            Text(tr: model.intensityHint(code: code)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder
    private func noteSection(_ model: ClimbingDayScreenModel, _ ui: ClimbingDayScreenState) -> some View {
        Section {
            TextField(L("trl_day_note"), text: Binding(get: { ui.note }, set: { model.setNote(note: $0) }))
            if ui.fromHealth {
                Text(L("trl_day_from_health")).font(.caption).foregroundStyle(.secondary)
                if ui.hasOverlap {
                    Text(tr: ui.overlapText)
                    Toggle(isOn: Binding(get: { ui.countAnyway }, set: { model.setCountAnyway(on: $0) })) {
                        VStack(alignment: .leading) {
                            Text(L("trl_day_count_anyway"))
                            Text(L("trl_day_count_anyway_hint")).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func actionsSection(_ model: ClimbingDayScreenModel, _ ui: ClimbingDayScreenState) -> some View {
        Section {
            Button(L("action_save")) { model.save() }
            if ui.isEdit {
                Button(L("action_delete"), role: .destructive) { model.delete() }
                if let onAddAnother {
                    Button { onAddAnother() } label: { Label(L("trl_day_add_another"), systemImage: "plus") }
                }
            }
        }
    }

    private static func iso(_ date: Date) -> String {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: date)
    }
}

/// Climbing logged outside the board app, with edit (Android `ClimbingDaysScreen`).
struct ClimbingDaysListView: View {
    let core: AppCore
    let onDone: () -> Void

    @State private var host: ScreenHost<ClimbingDayScreenModel, ClimbingDayScreenState>?
    @State private var editing: String?
    @State private var adding = false
    /// "Climbing day deleted · Undo" after a delete from the list (device test D45).
    @State private var snack: TrainingSnack?
    @State private var snackAction: (() -> Void)?

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                content
                TrainingSnackBar(snack: $snack) { snackAction?() }
            }
            .animation(.default, value: snack)
            .navigationTitle(L("trl_days_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("action_close"), action: onDone) }
                ToolbarItem(placement: .primaryAction) {
                    Button { adding = true } label: { Label(L("trl_action_log_climbing"), systemImage: "plus") }
                }
            }
        }
        .task {
            guard host == nil, let model = core.makeClimbingDayScreen() else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.loadList()
        }
        .sheet(item: Binding(get: { editing.map { ClimbingDayEditTarget(id: $0) } }, set: { editing = $0?.id })) { target in
            ClimbingDaySheetView(core: core, editId: target.id, onDone: { editing = nil },
                                 onDeleted: { undo in offerUndo(undo) })
        }
        .sheet(isPresented: $adding) {
            ClimbingDaySheetView(core: core, editId: "") { adding = false }
        }
    }

    @ViewBuilder
    private var content: some View {
        if let host, host.state.listLoaded {
            if host.state.rows.isEmpty {
                ContentUnavailableView(L("trl_days_empty"), systemImage: "mountain.2")
            } else {
                List(host.state.rows, id: \.id) { row in
                    Button { editing = row.id } label: { rowView(row) }
                        .buttonStyle(.plain)
                }
            }
        } else {
            ProgressView()
        }
    }

    private func rowView(_ row: ClimbingDayRowUi) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(tr: row.kind).font(.subheadline.weight(.semibold))
                if row.fromHealth { Text(L("trl_days_source_health")).font(.caption).foregroundStyle(.secondary) }
            }
            Text(tr: row.line).font(.caption).foregroundStyle(.secondary)
            if !row.note.isEmpty { Text(row.note).font(.caption) }
            if let overlap = TrRender.optional(row.overlap) { Text(overlap).font(.caption).foregroundStyle(.secondary) }
        }
    }

    /// The entry and its board-overlap choice come back with one tap (Android `ClimbingDaysScreen`).
    private func offerUndo(_ undo: ClimbingDayUndo) {
        snackAction = { undo.restore() }
        snack = TrainingSnack(text: L("trl_day_deleted"), action: L("trf_undo"))
    }
}

struct ClimbingDayEditTarget: Identifiable {
    let id: String
}
