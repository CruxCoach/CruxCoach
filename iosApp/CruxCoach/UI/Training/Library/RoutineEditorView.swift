import CruxCoachCore
import SwiftUI

/// The workout editor (Android `RoutineEditorScreen`): name, notes, exercises with sets, reps, time,
/// load and rest, circuits, the multi-select picker and typed workouts, and the week's volume hint.
struct RoutineEditorView: View {
    let core: AppCore
    /// "" = a new workout.
    let routineId: String
    /// "builtin:<key>" copies a starter routine, "ex:<slug>" starts with an exercise.
    let from: String
    let defaultName: String

    @Environment(\.dismiss) private var dismiss
    @State private var host: ScreenHost<RoutineEditorScreenModel, RoutineEditorScreenState>?
    @State private var unavailable = false
    @State private var picking = false
    @State private var typing = false
    @State private var confirmLeave = false
    @State private var loadEditing: EditorItemUi?
    @State private var loadDraft = ""

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
        .navigationTitle(L(routineId.isEmpty ? "trwo_editor_title_new" : "trwo_editor_title_edit"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard host == nil else { return }
            guard let model = core.makeRoutineEditorScreen(routineId: routineId, from: from, defaultName: defaultName,
                                                           language: LibraryLanguage.current) else {
                unavailable = true
                return
            }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }

    @ViewBuilder
    private func screen(model: RoutineEditorScreenModel, ui: RoutineEditorScreenState) -> some View {
        List {
            if ui.isLoading { ProgressView().frame(maxWidth: .infinity) }
            if ui.failed {
                Label(LI("library_failed"), systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
            }
            Section {
                TextField(L("trwo_editor_name"), text: Binding(get: { ui.name }, set: { model.setName(value: $0) }))
                TextField(L("trwo_editor_notes"), text: Binding(get: { ui.notes }, set: { model.setNotes(value: $0) }), axis: .vertical)
                    .lineLimit(1...4)
            }
            Section {
                if ui.items.isEmpty {
                    Text(L("trwo_editor_empty")).foregroundStyle(.secondary)
                }
                if !ui.weeklyLines.isEmpty {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("trv_hint_title")).font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
                        ForEach(Array(ui.weeklyLines.enumerated()), id: \.offset) { _, line in
                            Text(LibraryRender.text(line)).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
            } header: {
                Text(LP("trwo_exercise_count", ui.items.count))
            }
            ForEach(ui.items, id: \.key) { item in
                if item.startsCircuit {
                    Section { circuitCard(item, model: model) }
                }
                Section { itemCard(item, model: model) }
            }
            Section {
                Button { picking = true } label: { Label(L("trwo_editor_add"), systemImage: "plus") }
                Button { typing = true } label: { Label(L("trwo_text_open"), systemImage: "square.and.pencil") }
            }
        }
        .navigationBarBackButtonHidden(ui.dirty)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                if ui.dirty {
                    Button(L("tr_action_cancel")) { confirmLeave = true }
                }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(L("tr_action_save")) { model.save() }.disabled(!ui.canSave)
            }
        }
        .onChange(of: ui.saved) { _, saved in
            if saved { dismiss() }
        }
        .alert(L("trwo_editor_unsaved_title"), isPresented: $confirmLeave) {
            Button(L("trwo_editor_discard"), role: .destructive) { dismiss() }
            Button(L("tr_action_cancel"), role: .cancel) {}
        } message: {
            Text(L("trwo_editor_unsaved_text"))
        }
        .alert(L("trw_load_input", ui.massUnit), isPresented: Binding(get: { loadEditing != nil }, set: { if !$0 { loadEditing = nil } })) {
            TextField("", text: $loadDraft).keyboardType(.decimalPad)
            Button(L("tr_action_save")) {
                if let item = loadEditing { _ = model.setLoadText(key: item.key, text: loadDraft) }
                loadEditing = nil
            }
            Button(L("trwo_load_auto")) {
                if let item = loadEditing { model.setLoadAuto(key: item.key) }
                loadEditing = nil
            }
            Button(L("tr_action_cancel"), role: .cancel) { loadEditing = nil }
        } message: {
            Text(L(loadEditing?.loadAdded == true ? "trwo_load_hint_added" : "trwo_load_hint"))
        }
        .sheet(isPresented: $picking, onDismiss: { model.resetPicker() }) {
            ExercisePickerSheet(model: model, ui: ui, onDone: { picking = false })
        }
        .sheet(isPresented: $typing, onDismiss: { model.clearTyped() }) {
            WorkoutTextSheet(model: model, ui: ui, onDone: { typing = false })
        }
    }

    @ViewBuilder
    private func itemCard(_ item: EditorItemUi, model: RoutineEditorScreenModel) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                if item.inCircuit {
                    RoundedRectangle(cornerRadius: 2).fill(Color.accentColor).frame(width: 4, height: 32)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.name).font(.headline)
                    Text(L(item.categoryKey)).font(.footnote).foregroundStyle(.secondary)
                }
                Spacer()
                Button { model.moveUp(key: item.key) } label: { Image(systemName: "chevron.up") }
                    .buttonStyle(.borderless).disabled(item.isFirst)
                    .accessibilityLabel(L("trwo_move_up"))
                Button { model.moveDown(key: item.key) } label: { Image(systemName: "chevron.down") }
                    .buttonStyle(.borderless).disabled(item.isLast)
                    .accessibilityLabel(L("trwo_move_down"))
                Button { model.remove(key: item.key) } label: { Image(systemName: "xmark") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(L("trwo_remove"))
            }
            ForEach(item.steppers, id: \.field) { s in stepper(s, item: item, model: model) }
            if item.showLoad {
                HStack {
                    Text(L("trwo_field_load"))
                    Spacer()
                    Button { model.stepLoad(key: item.key, up: false) } label: { Image(systemName: "minus") }
                        .buttonStyle(.bordered)
                        .accessibilityLabel(L("trwo_minus", L("trwo_field_load")))
                    Button {
                        loadDraft = item.loadIsAuto ? "" : LibraryRender.number(item.loadInput, decimals: 2)
                        loadEditing = item
                    } label: {
                        Text(LibraryRender.text(item.loadText)).font(.headline.monospacedDigit())
                            .foregroundStyle(item.loadIsAuto ? Color.secondary : Color.primary)
                            .frame(minWidth: 72)
                    }
                    .buttonStyle(.plain)
                    Button { model.stepLoad(key: item.key, up: true) } label: { Image(systemName: "plus") }
                        .buttonStyle(.bordered)
                        .accessibilityLabel(L("trwo_plus", L("trwo_field_load")))
                }
            }
            ForEach(item.steppersAfterLoad, id: \.field) { s in stepper(s, item: item, model: model) }
            if item.unilateral {
                LibraryFlowLayout(spacing: 6) {
                    LibraryChip(title: L("trwo_side_both"), selected: item.sidesCode == "both") { model.setSides(key: item.key, code: "both") }
                    LibraryChip(title: L("trwo_side_left"), selected: item.sidesCode == "left_only") { model.setSides(key: item.key, code: "left_only") }
                    LibraryChip(title: L("trwo_side_right"), selected: item.sidesCode == "right_only") { model.setSides(key: item.key, code: "right_only") }
                }
            }
            Toggle(L("trwo_field_warmup"), isOn: Binding(get: { item.warmup }, set: { model.setWarmup(key: item.key, warmup: $0) }))
            if item.linkedToNext {
                Button { model.unlink(key: item.key) } label: { Label(L("trcirc_unlink"), systemImage: "link.badge.plus") }
                    .buttonStyle(.borderless)
            } else if item.canLink {
                Button { model.link(key: item.key) } label: { Label(L("trcirc_link"), systemImage: "link") }
                    .buttonStyle(.borderless)
            }
        }
    }

    private func stepper(_ s: EditorStepperUi, item: EditorItemUi, model: RoutineEditorScreenModel) -> some View {
        LibraryStepperRow(label: L(s.labelKey),
                          valueText: s.asClock ? LibraryRender.clock(Int(s.value)) : String(s.value),
                          range: L("trwo_range", Int(s.min), Int(s.max)),
                          onMinus: { model.stepField(key: item.key, field: s.field, up: false) },
                          onPlus: { model.stepField(key: item.key, field: s.field, up: true) },
                          onTyped: { model.setField(key: item.key, field: s.field, value: Int32(clamping: $0)) })
    }

    @ViewBuilder
    private func circuitCard(_ item: EditorItemUi, model: RoutineEditorScreenModel) -> some View {
        let circuit = item.circuit
        VStack(alignment: .leading, spacing: 6) {
            Text(L("trcirc_title")).font(.headline)
            Text(L("trcirc_explain")).font(.footnote).foregroundStyle(.secondary)
            LibraryStepperRow(label: L("trwo_field_rounds"), valueText: String(item.circuitRounds),
                              range: L("trwo_range", 1, 20),
                              onMinus: { model.setCircuitRounds(circuit: circuit, rounds: max(1, item.circuitRounds - 1)) },
                              onPlus: { model.setCircuitRounds(circuit: circuit, rounds: min(20, item.circuitRounds + 1)) },
                              onTyped: { if (1...20).contains($0) { model.setCircuitRounds(circuit: circuit, rounds: Int32($0)) } })
            LibraryStepperRow(label: L("trcirc_rest_between"), valueText: LibraryRender.clock(Int(item.circuitRestS)),
                              range: L("trwo_range", 0, 120),
                              onMinus: { model.setCircuitRest(circuit: circuit, seconds: max(0, item.circuitRestS - 5)) },
                              onPlus: { model.setCircuitRest(circuit: circuit, seconds: min(120, item.circuitRestS + 5)) },
                              onTyped: { if (0...120).contains($0) { model.setCircuitRest(circuit: circuit, seconds: Int32($0)) } })
            LibraryStepperRow(label: L("trcirc_rest_round"), valueText: LibraryRender.clock(Int(item.roundRestS)),
                              range: L("trwo_range", 0, 600),
                              onMinus: { model.setRoundRest(circuit: circuit, seconds: max(0, item.roundRestS - 15)) },
                              onPlus: { model.setRoundRest(circuit: circuit, seconds: min(600, item.roundRestS + 15)) },
                              onTyped: { if (0...600).contains($0) { model.setRoundRest(circuit: circuit, seconds: Int32($0)) } })
            Button(L("trcirc_dissolve")) { model.dissolve(circuit: circuit) }.buttonStyle(.borderless)
        }
    }
}

/// Multi-select exercise picker: favourites first, search over German and English names and aliases
/// (Android `ExercisePickerSheet`).
struct ExercisePickerSheet: View {
    let model: RoutineEditorScreenModel
    let ui: RoutineEditorScreenState
    let onDone: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            LibraryChip(title: L("trwo_picker_all"), selected: ui.pickerCategory.isEmpty) { model.setPickerCategory(code: "") }
                            ForEach(model.categoryCodes, id: \.self) { code in
                                LibraryChip(title: L("tr_cat_" + code), selected: ui.pickerCategory == code) {
                                    model.setPickerCategory(code: ui.pickerCategory == code ? "" : code)
                                }
                            }
                        }
                    }
                }
                if !ui.pickerFavorites.isEmpty {
                    Section(L("trwo_picker_favorites")) {
                        ForEach(ui.pickerFavorites, id: \.slug) { row in pickerRow(row) }
                    }
                }
                Section {
                    ForEach(ui.pickerResults, id: \.slug) { row in pickerRow(row) }
                }
            }
            .searchable(text: Binding(get: { ui.pickerQuery }, set: { model.setPickerQuery(query: $0) }), prompt: L("trwo_picker_search"))
            .navigationTitle(L("trwo_editor_add"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("tr_action_cancel"), action: onDone)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(LP("trwo_picker_add_n", Int(ui.pickerSelectedCount))) {
                        model.confirmPicker()
                        onDone()
                    }
                    .disabled(ui.pickerSelectedCount == 0)
                }
            }
        }
    }

    private func pickerRow(_ row: PickerRowUi) -> some View {
        HStack {
            Image(systemName: row.selected ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(row.selected ? Color.accentColor : Color.secondary)
            VStack(alignment: .leading) {
                Text(row.name)
                Text(L(row.categoryKey)).font(.footnote).foregroundStyle(.secondary)
            }
            Spacer()
            Button { model.toggleFavorite(slug: row.slug) } label: {
                Image(systemName: "star.fill").foregroundStyle(row.favorite ? Color.accentColor : Color(uiColor: .tertiaryLabel))
                    .accessibilityLabel(L(row.favorite ? "trwo_fav_remove" : "trwo_fav_add"))
            }
            .buttonStyle(.borderless)
        }
        .contentShape(Rectangle())
        .onTapGesture { model.togglePicked(slug: row.slug) }
    }
}

/// "Type a workout" (Android `WorkoutTextSheet`): free text, circuits included, read off the main thread a
/// moment after typing, with a preview of what each exercise becomes — a catalogue exercise, or a new one
/// created on "Add" — and of the circuits. "Add" takes exactly what is in the field.
struct WorkoutTextSheet: View {
    let model: RoutineEditorScreenModel
    let ui: RoutineEditorScreenState
    let onDone: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField(L("trwo_text_placeholder"), text: Binding(get: { ui.typedText }, set: { model.setTypedText(text: $0) }),
                              axis: .vertical)
                        .lineLimit(4...12)
                        .accessibilityIdentifier("workout_text_field")
                } header: {
                    Text(L("trwo_text_label"))
                } footer: {
                    Text(L("trwo_text_hint"))
                }
                if ui.typedHasTitle {
                    Text(L("trwo_text_name", ui.typedTitle)).font(.subheadline.weight(.semibold))
                }
                ForEach(Array(ui.typedLines.enumerated()), id: \.offset) { _, line in
                    typedRow(line)
                }
            }
            .navigationTitle(L("trwo_text_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("tr_action_cancel"), action: onDone)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(LibraryRender.text(ui.typedApplyLabel)) {
                        model.applyTyped(joiner: L("trwo_text_name_join"))
                        onDone()
                    }
                    .disabled(ui.typedLines.isEmpty)
                }
            }
        }
    }

    /// A circuit's first member carries the circuit's line above it; members are indented under it.
    private func typedRow(_ line: TypedLineUi) -> some View {
        let indent: CGFloat = line.inCircuit ? 12 : 0
        let starts: Bool = line.startsCircuit
        return VStack(alignment: .leading, spacing: 6) {
            if starts {
                typedCircuitHeader(line)
            }
            typedLine(line)
                .padding(.leading, indent)
        }
    }

    private func typedCircuitHeader(_ line: TypedLineUi) -> some View {
        let header: String = LibraryRender.text(line.circuitHeader)
        return HStack(spacing: 6) {
            Image(systemName: "repeat").font(.footnote.weight(.semibold))
            Text(header).font(.subheadline.weight(.semibold))
        }
        .foregroundStyle(Color.accentColor)
    }

    private func typedLine(_ line: TypedLineUi) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: line.isNew ? "plus.circle.fill" : "checkmark.circle.fill")
                .foregroundStyle(line.isNew ? Color.accentColor : Color.green)
            VStack(alignment: .leading, spacing: 2) {
                Text(line.name).font(.subheadline.weight(.semibold))
                Text(LibraryRender.text(line.summary)).font(.subheadline)
                if line.isNew {
                    Text(LibraryRender.text(line.creationHint)).font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
    }
}
