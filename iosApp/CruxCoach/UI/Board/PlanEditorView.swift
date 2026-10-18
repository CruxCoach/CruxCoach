import CruxCoachCore
import SwiftUI
import UIKit

/// Training-plan editor of one list (Android `PlaylistDetailScreen`): the
/// ordered steps with repeats and rests. It shares the list detail's model, so
/// both screens always show the same plan.
struct PlanEditorView: View {
    let core: AppCore
    let host: ScreenHost<ListDetailScreenModel, ListDetailScreenState>

    @State private var showAddRest = false
    /// 0 appends the rest at the end.
    @State private var addRestAfterStepId: Int64 = 0
    @State private var confirmReset = false
    @State private var confirmClear = false
    @State private var linkCopied = false
    /// Local, not the model's rename flag: the list detail below binds its own alert to that one.
    @State private var renaming = false
    @State private var renameText = ""

    var body: some View {
        let model: ListDetailScreenModel = host.model
        let ui: ListDetailScreenState = host.state
        content(model: model, ui: ui)
            .navigationTitle(ui.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbarContent(model: model, ui: ui) }
            .modifier(PlanDialogs(model: model, ui: ui, showAddRest: $showAddRest,
                                  addRestAfterStepId: $addRestAfterStepId,
                                  confirmReset: $confirmReset, confirmClear: $confirmClear,
                                  renaming: $renaming, renameText: $renameText))
            .overlay(alignment: .bottom) { banner(model: model, ui: ui) }
            .task { model.preparePlan() }
    }

    @ViewBuilder
    private func content(model: ListDetailScreenModel, ui: ListDetailScreenState) -> some View {
        List {
            infoSection(ui: ui)
            if ui.planSteps.isEmpty && !ui.isLoading {
                ContentUnavailableView(L("playlist_empty_message"), systemImage: "list.bullet.rectangle")
            }
            if ui.editMode {
                editRows(model: model, ui: ui)
            } else {
                viewRows(model: model, ui: ui)
            }
        }
        .environment(\.editMode, .constant(ui.editMode ? EditMode.active : EditMode.inactive))
    }

    @ViewBuilder
    private func infoSection(ui: ListDetailScreenState) -> some View {
        Section {
            DisclosureGroup(L("ux_plan_title")) {
                Text(L("list_plan_membership_info")).font(.footnote).foregroundStyle(.secondary)
            }
            if ui.planUnavailableCount > 0 {
                Text(L("playlist_unavailable_climbs", Int(ui.planUnavailableCount)))
                    .font(.footnote).foregroundStyle(.blue)
            }
            if ui.playHasMultipleBoards {
                Text(L("list_playback_error_multiple_boards")).font(.footnote).foregroundStyle(.red)
            }
        }
    }

    // MARK: View mode: attempts on one climb collapse into one row.

    @ViewBuilder
    private func viewRows(model: ListDetailScreenModel, ui: ListDetailScreenState) -> some View {
        Section {
            ForEach(ui.planRows, id: \.key) { row in
                if row.isRest {
                    Button { model.editRest(stepId: row.step.stepId) } label: {
                        PlanRestLabel(seconds: Int(row.step.restSeconds), editable: true)
                    }
                } else {
                    PlanClimbViewRow(row: row) { model.editAttemptRests(rowKey: row.key) }
                }
            }
        }
    }

    // MARK: Edit mode: every step with its own controls.

    @ViewBuilder
    private func editRows(model: ListDetailScreenModel, ui: ListDetailScreenState) -> some View {
        Section {
            ForEach(ui.planSteps, id: \.stepId) { step in
                PlanEditRow(step: step,
                            onEditRest: { model.editRest(stepId: step.stepId) },
                            onDuplicate: { duplicate(model: model, step: step) },
                            onAddRestAfter: {
                                addRestAfterStepId = step.stepId
                                showAddRest = true
                            },
                            onMoveUp: { model.moveStep(fromIndex: step.index, toIndex: step.index - 1) },
                            onMoveDown: { model.moveStep(fromIndex: step.index, toIndex: step.index + 1) },
                            onRemove: { model.removeStep(stepId: step.stepId) })
            }
            .onMove { source, destination in move(model: model, source: source, destination: destination) }
            .onDelete { offsets in remove(model: model, ui: ui, offsets: offsets) }
        }
    }

    private func duplicate(model: ListDetailScreenModel, step: PlanStepUi) {
        if step.isRest {
            model.duplicateRest(stepId: step.stepId)
        } else {
            model.duplicateClimb(stepId: step.stepId)
        }
    }

    private func move(model: ListDetailScreenModel, source: IndexSet, destination: Int) {
        guard let from = source.first else { return }
        // SwiftUI names the slot before removal; the presenter wants the final index.
        let to: Int = destination > from ? destination - 1 : destination
        if to != from { model.moveStep(fromIndex: Int32(from), toIndex: Int32(to)) }
    }

    private func remove(model: ListDetailScreenModel, ui: ListDetailScreenState, offsets: IndexSet) {
        let steps: [PlanStepUi] = ui.planSteps
        for index in offsets where index < steps.count {
            model.removeStep(stepId: steps[index].stepId)
        }
    }

    // MARK: Toolbar

    @ToolbarContentBuilder
    private func toolbarContent(model: ListDetailScreenModel, ui: ListDetailScreenState) -> some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            if !ui.playClimbUuids.isEmpty && !ui.playHasMultipleBoards {
                NavigationLink {
                    playlistPlayer(core: core, ui: ui)
                } label: {
                    Label(L("playlist_play"), systemImage: "play.fill")
                }
            }
            Button { model.toggleEditMode() } label: {
                Label(L("playlist_reorder"), systemImage: ui.editMode ? "checkmark.circle.fill" : "arrow.up.arrow.down")
            }
            moreMenu(model: model, ui: ui)
        }
    }

    @ViewBuilder
    private func moreMenu(model: ListDetailScreenModel, ui: ListDetailScreenState) -> some View {
        Menu {
            if !ui.isBuiltin {
                Button(L("playlist_rename"), systemImage: "pencil") {
                    renameText = ui.name
                    renaming = true
                }
            }
            Button(L("playlist_add_rest"), systemImage: "timer") {
                addRestAfterStepId = 0
                showAddRest = true
            }
            .disabled(!ui.canAddRest)
            Button(L("list_plan_reset_action"), systemImage: "arrow.clockwise") { confirmReset = true }
            Button(L("list_plan_clear_action"), systemImage: "trash", role: .destructive) { confirmClear = true }
            Button(L("board_detail_share_link"), systemImage: "square.and.arrow.up") { copyLink(model: model) }
        } label: {
            Label(L("action_more_options"), systemImage: "ellipsis.circle")
        }
    }

    private func copyLink(model: ListDetailScreenModel) {
        let link: String = model.planShareLink()
        guard !link.isEmpty else { return }
        UIPasteboard.general.string = link
        linkCopied = true
        Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            linkCopied = false
        }
    }

    @ViewBuilder
    private func banner(model: ListDetailScreenModel, ui: ListDetailScreenState) -> some View {
        if linkCopied {
            PlanBanner(text: L("board_detail_link_copied"), isError: false)
        } else if ui.errorCode != "none" && ui.errorCode != "notFound" {
            PlanBanner(text: LI("plan_error"), isError: true).onTapGesture { model.consumeError() }
        }
    }
}

/// Rename, add-rest, edit-rest and the two confirmations, kept out of the
/// view's body so the type checker sees small expressions.
private struct PlanDialogs: ViewModifier {
    let model: ListDetailScreenModel
    let ui: ListDetailScreenState
    @Binding var showAddRest: Bool
    @Binding var addRestAfterStepId: Int64
    @Binding var confirmReset: Bool
    @Binding var confirmClear: Bool
    @Binding var renaming: Bool
    @Binding var renameText: String

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: $showAddRest) { addRestSheet }
            .sheet(isPresented: editRestBinding) { editRestSheet }
            .alert(L("playlist_rename"), isPresented: $renaming) { renameActions }
            .alert(L("list_plan_reset_title"), isPresented: $confirmReset) {
                Button(L("action_cancel"), role: .cancel) {}
                Button(L("list_plan_reset_action")) { model.resetPlanFromList() }
            } message: { Text(L("list_plan_reset_message")) }
            .alert(L("list_plan_clear_title"), isPresented: $confirmClear) {
                Button(L("action_cancel"), role: .cancel) {}
                Button(L("list_plan_clear_action"), role: .destructive) { model.clearPlan() }
            } message: { Text(L("list_plan_clear_message")) }
    }

    private var editRestBinding: Binding<Bool> {
        Binding(get: { !ui.editRestStepIds.isEmpty }, set: { if !$0 { model.dismissEditRests() } })
    }

    @ViewBuilder
    private var renameActions: some View {
        TextField(LI("lists_name"), text: $renameText)
        Button(L("action_cancel"), role: .cancel) {}
        Button(L("action_save")) {
            model.setRenameValue(value: renameText)
            model.confirmRename()
        }
        .disabled(renameText.trimmingCharacters(in: .whitespaces).isEmpty)
    }

    private var addRestSheet: some View {
        let after: Int64 = addRestAfterStepId
        return RestDurationSheet(title: L("playlist_add_rest"),
                                 initialSeconds: 180,
                                 affectedCount: 1,
                                 onSave: { seconds in
                                     model.addRest(seconds: Int32(seconds), afterStepId: after)
                                     showAddRest = false
                                 },
                                 onDelete: nil,
                                 onCancel: { showAddRest = false })
    }

    private var editRestSheet: some View {
        let count: Int = ui.editRestStepIds.count
        return RestDurationSheet(title: L("playlist_rest_edit_title"),
                                 initialSeconds: Int(ui.editRestSeconds),
                                 affectedCount: count,
                                 onSave: { seconds in model.setSelectedRestSeconds(seconds: Int32(seconds)) },
                                 onDelete: { model.removeSelectedRests() },
                                 onCancel: { model.dismissEditRests() })
    }
}

/// Android `formatRest`: "45 s", "2 min", "2 min 5 s".
func planRestText(_ seconds: Int) -> String {
    let minutes: Int = seconds / 60
    let remaining: Int = seconds % 60
    if minutes == 0 { return "\(remaining) s" }
    if remaining == 0 { return "\(minutes) min" }
    return "\(minutes) min \(remaining) s"
}

private struct PlanBanner: View {
    let text: String
    let isError: Bool

    var body: some View {
        Text(text)
            .font(.footnote)
            .padding(8)
            .background(isError ? Color.red.opacity(0.85) : Color.black.opacity(0.75))
            .foregroundStyle(.white)
            .clipShape(Capsule())
            .padding(.bottom, 8)
    }
}

private struct PlanRestLabel: View {
    let seconds: Int
    let editable: Bool

    var body: some View {
        HStack {
            Image(systemName: "hourglass.bottomhalf.filled").foregroundStyle(.blue)
            Text(L("playlist_rest_entry", planRestText(seconds))).foregroundStyle(.blue)
            Spacer()
            if editable {
                Image(systemName: "pencil").foregroundStyle(.blue)
                    .accessibilityLabel(L("playlist_rest_edit_title"))
            }
        }
    }
}

/// Climb name and its subtitle: grade · angle · attempts (rest).
private struct PlanClimbText: View {
    let step: PlanStepUi
    let attemptCount: Int
    let attemptRestSeconds: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(step.isAvailable ? step.name : L("playlist_climb_unavailable"))
                .fontWeight(.semibold)
                .foregroundStyle(step.isAvailable ? Color.primary : Color.secondary)
            let line: String = subtitle()
            if !line.isEmpty {
                Text(line).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private func subtitle() -> String {
        var parts: [String] = []
        if !step.grade.isEmpty { parts.append(step.grade) }
        if step.angle >= 0 { parts.append(L("playlist_angle_label", Int(step.angle))) }
        if attemptCount > 1 {
            var attempts: String = L("playlist_attempts_badge", attemptCount)
            if attemptRestSeconds >= 0 {
                attempts += " (" + L("playlist_rest_entry", planRestText(attemptRestSeconds)) + ")"
            }
            parts.append(attempts)
        }
        return parts.joined(separator: " · ")
    }
}

private struct PlanClimbViewRow: View {
    let row: PlanRowUi
    let onEditAttemptRests: () -> Void

    var body: some View {
        HStack {
            if !row.step.isAvailable {
                Image(systemName: "questionmark.circle").foregroundStyle(.secondary)
            }
            PlanClimbText(step: row.step,
                          attemptCount: Int(row.attemptCount),
                          attemptRestSeconds: Int(row.attemptRestSeconds))
            Spacer()
            if !row.attemptRestStepIds.isEmpty {
                Button(action: onEditAttemptRests) {
                    Image(systemName: "pencil").foregroundStyle(.blue)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(L("playlist_rest_edit_title"))
            }
        }
    }
}

private struct PlanEditRow: View {
    let step: PlanStepUi
    let onEditRest: () -> Void
    let onDuplicate: () -> Void
    let onAddRestAfter: () -> Void
    let onMoveUp: () -> Void
    let onMoveDown: () -> Void
    let onRemove: () -> Void

    var body: some View {
        HStack {
            if step.isRest {
                Button(action: onEditRest) { PlanRestLabel(seconds: Int(step.restSeconds), editable: false) }
                    .buttonStyle(.borderless)
            } else {
                PlanClimbText(step: step, attemptCount: 1, attemptRestSeconds: -1)
                Spacer()
            }
            controls
        }
        .accessibilityAction(named: Text(L("playlist_move_up"))) { if step.canMoveUp { onMoveUp() } }
        .accessibilityAction(named: Text(L("playlist_move_down"))) { if step.canMoveDown { onMoveDown() } }
    }

    private var controls: some View {
        Menu {
            Button(step.isRest ? L("playlist_duplicate_rest") : L("list_plan_duplicate_step"),
                   systemImage: "plus.square.on.square", action: onDuplicate)
            if step.canAddRestAfter {
                Button(L("playlist_add_rest_after"), systemImage: "hourglass", action: onAddRestAfter)
            }
            if step.canMoveUp {
                Button(L("playlist_move_up"), systemImage: "arrow.up", action: onMoveUp)
            }
            if step.canMoveDown {
                Button(L("playlist_move_down"), systemImage: "arrow.down", action: onMoveDown)
            }
            Button(L("action_delete"), systemImage: "xmark", role: .destructive, action: onRemove)
        } label: {
            Image(systemName: "ellipsis.circle").accessibilityLabel(L("action_more_options"))
        }
        .buttonStyle(.borderless)
    }
}

/// Android `RestDurationDialog`: quick presets plus minute/second steppers, 10 s to 60 min.
struct RestDurationSheet: View {
    let title: String
    let initialSeconds: Int
    let affectedCount: Int
    let onSave: (Int) -> Void
    let onDelete: (() -> Void)?
    let onCancel: () -> Void

    @State private var seconds: Int = 180
    @State private var started = false

    private static let presets: [Int] = [30, 60, 120, 180, 300, 600]

    var body: some View {
        NavigationStack {
            Form {
                if affectedCount > 1 {
                    Text(L("playlist_rest_edit_multiple", affectedCount)).font(.footnote).foregroundStyle(.secondary)
                }
                presetSection
                stepperSection
                if let onDelete {
                    Button(role: .destructive, action: onDelete) {
                        Label(affectedCount > 1 ? L("playlist_delete_rests") : L("playlist_delete_rest"),
                              systemImage: "trash")
                    }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel"), action: onCancel) }
                ToolbarItem(placement: .confirmationAction) { Button(L("action_save")) { onSave(seconds) } }
            }
        }
        .presentationDetents([.medium, .large])
        .onAppear {
            guard !started else { return }
            started = true
            seconds = min(max(initialSeconds, 10), 3600)
        }
    }

    private var presetSection: some View {
        Section {
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                ForEach(Self.presets, id: \.self) { preset in
                    presetChip(preset)
                }
            }
            Text(planRestText(seconds))
                .font(.title3.weight(.bold).monospacedDigit())
                .frame(maxWidth: .infinity)
        }
    }

    private func presetChip(_ preset: Int) -> some View {
        let selected: Bool = preset == seconds
        return Button { seconds = preset } label: {
            Text(planRestText(preset)).frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .tint(selected ? Color.accentColor : Color.secondary)
    }

    private var stepperSection: some View {
        Section {
            Stepper(value: minuteBinding, in: 0...60) {
                Text("\(L("settings_duration_minutes_label")): \(seconds / 60)")
            }
            .accessibilityHint(L("playlist_rest_increase_minute"))
            Stepper(value: secondBinding, in: 0...59) {
                Text("\(L("settings_duration_seconds_label")): \(seconds % 60)")
            }
            .accessibilityHint(L("playlist_rest_increase_second"))
        } footer: {
            Text(L("playlist_rest_range"))
        }
    }

    private var minuteBinding: Binding<Int> {
        Binding(get: { seconds / 60 }, set: { seconds = min(max($0 * 60 + seconds % 60, 10), 3600) })
    }

    private var secondBinding: Binding<Int> {
        Binding(get: { seconds % 60 }, set: { seconds = min(max((seconds / 60) * 60 + $0, 10), 3600) })
    }
}
