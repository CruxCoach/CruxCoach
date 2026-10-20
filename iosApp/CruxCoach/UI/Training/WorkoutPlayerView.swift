import AudioToolbox
import AVFoundation
import CruxCoachCore
import SwiftUI
import UIKit

/// The training (Android `WorkoutPlayerScreen` + `ExercisePage` + `RestBar` + `PainSheet`): one page
/// per exercise or circuit with all its sets, a strip to move between them, the main action at the
/// bottom or the rest bar while resting, and the hang timer as the only full screen.
struct WorkoutPlayerView: View {
    let core: AppCore
    let onClose: () -> Void
    let onFinished: (String) -> Void
    let onOpenNutrition: () -> Void

    @State private var host: ScreenHost<WorkoutPlayerScreenModel, WorkoutPlayerState>?

    var body: some View {
        NavigationStack {
            Group {
                if let host {
                    WorkoutPlayerContent(core: core, model: host.model, ui: host.state, onClose: onClose, onFinished: onFinished)
                } else {
                    ProgressView()
                }
            }
        }
        .task {
            guard host == nil, let model = core.makeWorkoutPlayerScreen(language: TrainingStyle.catalogLanguage) else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
        // The phone lies on the floor or the mat between sets: keep the screen on.
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = core.settings.keepScreenOn }
    }
}

private struct WorkoutPlayerContent: View {
    /// For the exercise pictogram and the pain sheet's body map.
    let core: AppCore
    let model: WorkoutPlayerScreenModel
    let ui: WorkoutPlayerState
    let onClose: () -> Void
    let onFinished: (String) -> Void

    @State private var overview = false
    @State private var finishing = false
    @State private var confirmDiscard = false
    @State private var swapping = false
    @State private var pain = false
    @State private var adding = false
    @State private var typing = false
    @State private var afterOverview = ""
    @State private var confirmRemove = false
    @State private var entry: TrainingNumberEntry?
    @State private var snack: TrainingSnack?
    @State private var snackAction: (() -> Void)?
    @State private var rpe = 0
    @State private var learningInfo = false
    @State private var notes = ""
    /// A comeback session's highest pain at the injured spot, 0–10; nil = not answered (owner 2026-10-20).
    @State private var comebackPain: Int?

    var body: some View {
        withSheets(withEvents(page))
    }

    private var page: some View {
        ZStack(alignment: .bottom) {
            VStack(spacing: 0) {
                switch ui.phaseCode {
                case "loading":
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                case "noWorkout":
                    VStack(spacing: 16) {
                        Text(L("trw_no_open")).foregroundStyle(.secondary)
                        Button(L("action_back"), action: onClose).buttonStyle(.bordered)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                case "empty":
                    emptyTraining
                case "complete" where !(ui.browsing && ui.hasPage):
                    completePage
                default:
                    pageWithStrip
                    if ui.phaseCode == "complete" {
                        bottomBar {
                            Button { model.backToActive() } label: {
                                Text(L("trpg_all_done")).frame(maxWidth: .infinity, minHeight: 48)
                            }
                            .buttonStyle(.borderedProminent)
                        }
                    } else if ui.phaseCode == "rest" {
                        WorkoutRestBar(ui: ui, model: model)
                    } else {
                        mainAction
                    }
                }
            }
            TrainingSnackBar(snack: $snack) { snackAction?() }
        }
        .animation(.default, value: snack)
        .navigationTitle(TrRender.text(ui.title))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(action: onClose) { Image(systemName: "chevron.down") }
                    .accessibilityLabel(L("action_back"))
            }
            if ui.hasWorkout {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { overview = true } label: { Image(systemName: "list.bullet") }
                        .accessibilityLabel(L("trp_overview"))
                    Button { finishing = true } label: { Image(systemName: "flag.checkered") }
                        .accessibilityLabel(L("trw_finish"))
                }
            }
        }
    }

    private func withEvents<V: View>(_ view: V) -> some View {
        view
        .fullScreenCover(isPresented: Binding(get: { ui.phaseCode == "timer" }, set: { if !$0 { model.cancelTimer() } })) {
            WorkoutHangTimerView(ui: ui, onDone: { model.completeCurrent() }, onCancel: { model.cancelTimer() },
                                 onReleased: { held in model.completeHeld(heldSeconds: held) })
        }
        .onChange(of: ui.finishedWorkoutId) { _, id in
            if !id.isEmpty {
                model.consumeFinished()
                onFinished(id)
            }
        }
        .onChange(of: ui.discarded) { _, gone in
            if gone {
                model.consumeFinished()
                onClose()
            }
        }
        .onChange(of: TrRender.text(ui.painText)) { _, text in
            guard !text.isEmpty else { return }
            let injury = ui.painInjuryId
            model.consumePainResult()
            if injury.isEmpty {
                show(text)
            } else {
                // A saved injury shapes every later suggestion: one tap takes it back.
                show(text, action: L("trpg_undo")) { model.forgetInjury(id: injury) }
            }
        }
        .onChange(of: TrRender.text(ui.endedText)) { _, text in
            guard !text.isEmpty else { return }
            show(text, action: L("trpg_undo")) { model.undoEnd() }
            Task {
                try? await Task.sleep(nanoseconds: 6_500_000_000)
                model.consumeEnded()
            }
        }
        .onChange(of: ui.asPlannedUndo) { _, undo in
            guard undo else { return }
            show(L("trpg_dayform_back"), action: L("trpg_undo")) { model.undoAsPlanned() }
            Task {
                try? await Task.sleep(nanoseconds: 6_500_000_000)
                model.consumeAsPlannedUndo()
            }
        }
    }

    private func withSheets<V: View>(_ view: V) -> some View {
        view
        .sheet(isPresented: $overview, onDismiss: {
            // The next sheet opens once the overview is gone.
            switch afterOverview {
            case "add": adding = true
            case "type": typing = true
            case "finish": finishing = true
            default: break
            }
            afterOverview = ""
        }) { overviewSheet }
        .sheet(isPresented: $swapping) {
            WorkoutSwapSheet(model: model, name: ui.exerciseName, pictogram: pictogramOf) { slug in
                swapping = false
                model.swapExercise(to: slug)
            } onClose: { swapping = false }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $pain) {
            WorkoutPainSheet(model: model, kit: core.bodyMapKit(), exerciseName: ui.exerciseName) { pain = false }
        }
        .sheet(isPresented: $adding) {
            WorkoutAddExerciseSheet(model: model) { adding = false }
        }
        .sheet(isPresented: $finishing) { finishSheet }
        .sheet(isPresented: $typing) {
            WorkoutTypedSheet(model: model) { typing = false }
        }
        .onChange(of: TrRender.text(ui.skippedText)) { _, text in
            guard !text.isEmpty else { return }
            model.consumeSkipped()
            show(text)
        }
        .alert(L("trw_remove_exercise"), isPresented: $confirmRemove) {
            Button(L("tr_action_delete"), role: .destructive) { model.removeExercise() }
            Button(L("tr_action_cancel"), role: .cancel) {}
        } message: {
            Text([TrRender.text(ui.removeConfirm), TrRender.text(ui.removeWeekly)].filter { !$0.isEmpty }.joined(separator: "\n\n"))
        }
        .trainingNumberEntry($entry)
    }

    private func show(_ text: String, action: String? = nil, perform: (() -> Void)? = nil) {
        snackAction = perform
        snack = TrainingSnack(text: text, action: action)
    }

    // ── The page and the strip ───────────────────────────────────────

    private var pageWithStrip: some View {
        VStack(spacing: 0) {
            if ui.pages.count > 1 {
                HStack(spacing: 4) {
                    ForEach(ui.pages, id: \.index) { page in
                        Button { model.showPage(index: page.index) } label: {
                            GeometryReader { geo in
                                ZStack(alignment: .leading) {
                                    Capsule().fill(Color(.secondarySystemBackground))
                                    Capsule().fill(page.open ? (page.shown ? Color.accentColor : Color.primary.opacity(0.6)) : TrainingStyle.positive)
                                        .frame(width: geo.size.width * fraction(page))
                                }
                            }
                            .frame(height: page.shown ? 8 : 5)
                            .frame(maxWidth: .infinity, minHeight: 32)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(playerPageLabel(page, count: ui.pages.count))
                    }
                }
                .padding(.horizontal, 12)
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 4, pinnedViews: [.sectionHeaders]) {
                        Section {
                            pageIntro
                            ForEach(ui.rows, id: \.id) { row in
                                WorkoutRowView(row: row, ui: ui, model: model, entry: $entry).id(row.id)
                            }
                            pageFooter
                        } header: {
                            pageHeader
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 16)
                }
                .onChange(of: activeRowId) { _, id in
                    guard let id else { return }
                    withAnimation { proxy.scrollTo(id, anchor: .center) }
                }
            }
        }
    }

    private var activeRowId: String? { ui.rows.first { $0.stateCode == "active" }?.id }

    private func fraction(_ page: WorkoutPageUi) -> CGFloat {
        guard page.open else { return 1 }
        return page.total > 0 ? CGFloat(page.done) / CGFloat(page.total) : 0
    }

    /// The exercise's movement pictogram before its name (Android `ExerciseHeader`'s `ExerciseThumb`).
    @ViewBuilder
    private var headerThumb: some View {
        if let pictogram = pictogramOf(ui.exerciseSlug) {
            ExercisePictogramTile(pictogram: pictogram, size: 40)
        }
    }

    /// Android `ExerciseThumb` for a catalogue slug; nil while the catalogue does not know it.
    private func pictogramOf(_ slug: String) -> PictogramUi? {
        slug.isEmpty ? nil : core.exercisePictogram(slug: slug)
    }

    private var pageHeader: some View {
        VStack(alignment: .leading, spacing: 2) {
            if ui.pageIsCircuit {
                Text(tr: ui.roundHeader).font(.title2.weight(.bold))
                HStack {
                    Text(tr: ui.circuitRests).font(.subheadline).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
                    if ui.pageOpen { painButton }
                }
            } else {
                HStack(alignment: .top) {
                    headerThumb
                    Text(ui.exerciseName).font(.title2.weight(.bold)).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                    exerciseMenu
                }
                HStack(spacing: 8) {
                    Text(tr: ui.positionLine).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let side = TrRender.optional(ui.sideBadge) {
                        Text(side.uppercased()).font(.subheadline.weight(.bold)).foregroundStyle(.white)
                            .padding(.horizontal, 10).padding(.vertical, 4)
                            .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 6))
                    }
                    if let badge = TrRender.optional(ui.typeBadge) {
                        Text(badge).font(.caption.weight(.semibold))
                            .padding(.horizontal, 8).padding(.vertical, 4)
                            .background(Color.accentColor.opacity(0.15), in: RoundedRectangle(cornerRadius: 6))
                    }
                    if ui.pageOpen { painButton }
                }
            }
        }
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.systemBackground))
    }

    private var painButton: some View {
        Button { pain = true } label: {
            Label(L("trp2_pain_button"), systemImage: "bandage").font(.subheadline)
        }
        .buttonStyle(.bordered)
    }

    private var exerciseMenu: some View {
        Menu {
            if ui.canSwap {
                Button { swapping = true } label: { Label(L("trp_swap_exercise"), systemImage: "arrow.left.arrow.right") }
            }
            if ui.canSkip {
                Button { model.skipExercise() } label: { Label(L("trp_skip_exercise"), systemImage: "forward.end") }
            }
            if ui.warmupAvailable {
                Button { model.addWarmups() } label: { Label(L("trw_add_warmup"), systemImage: "flame") }
            }
            Button(role: .destructive) { confirmRemove = true } label: { Label(L("trw_remove_exercise"), systemImage: "trash") }
        } label: {
            Image(systemName: "ellipsis.circle").font(.title3).frame(minWidth: 44, minHeight: 44)
        }
        .accessibilityLabel(L("trp_exercise_menu"))
    }

    @ViewBuilder
    private var pageIntro: some View {
        if let members = TrRender.optional(ui.circuitMembers) {
            Text(members).font(.subheadline)
        }
        if let dayForm = TrRender.optional(ui.dayFormLine) {
            HStack {
                Text(dayForm).font(.subheadline).frame(maxWidth: .infinity, alignment: .leading)
                Button(L("trpg_dayform_as_planned")) { model.asPlanned() }
            }
            .padding(10)
            .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
        }
        if let learning = TrRender.optional(ui.learningShort) {
            HStack {
                Text(learning).font(.subheadline).frame(maxWidth: .infinity, alignment: .leading)
                Button { learningInfo = true } label: { Image(systemName: "info.circle") }
                    .accessibilityLabel(learning)
            }
            .padding(10)
            .background(Color.purple.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
            .alert(learning, isPresented: $learningInfo) {
                Button(L("action_close"), role: .cancel) {}
            } message: { Text(tr: ui.learningText) }
        }
        if let details = TrRender.optional(ui.details) {
            Text(details).font(.subheadline).foregroundStyle(.secondary)
        }
    }

    private var pageFooter: some View {
        HStack(spacing: 8) {
            Button { model.addSet() } label: { Text(tr: ui.addSetLabel).frame(maxWidth: .infinity, minHeight: 44) }
                .buttonStyle(.bordered)
            if ui.footerEndCode == "end" {
                Button { model.endExercise() } label: { Text(tr: ui.footerEndLabel).frame(maxWidth: .infinity, minHeight: 44) }
                    .buttonStyle(.bordered)
            } else if ui.footerEndCode == "skip" {
                Button { model.skipExercise() } label: { Text(tr: ui.footerEndLabel).frame(maxWidth: .infinity, minHeight: 44) }
                    .buttonStyle(.bordered)
            }
        }
        .padding(.top, 8)
    }

    // ── Bottom ───────────────────────────────────────────────────────

    private func bottomBar<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(spacing: 4) { content() }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.bar)
    }

    private var mainAction: some View {
        bottomBar {
            switch ui.mainCode {
            case "timer":
                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    model.startTimer()
                } label: {
                    Label(TrRender.text(ui.mainLabel), systemImage: "play.fill").font(.headline).frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.borderedProminent)
                Button(L("trp_done_without_timer")) { complete() }.frame(minHeight: 44)
            case "done", "test":
                Button { complete() } label: {
                    Label(TrRender.text(ui.mainLabel), systemImage: "checkmark.circle.fill").font(.headline).frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("player_done")
                .tint(TrainingStyle.positive)
            case "toActive":
                Button { model.backToActive() } label: { Text(tr: ui.mainLabel).frame(maxWidth: .infinity, minHeight: 48) }
                    .buttonStyle(.borderedProminent)
            default:
                Button { model.backToActive() } label: { Text(tr: ui.mainLabel).frame(maxWidth: .infinity, minHeight: 48) }
                    .buttonStyle(.borderedProminent)
            }
        }
    }

    /// A clear tick when a set is done.
    private func complete() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        model.completeCurrent()
    }

    private var emptyTraining: some View {
        VStack(spacing: 12) {
            Text(L("trpg_empty_hint")).multilineTextAlignment(.center)
            Button { adding = true } label: { Label(L("trw_add_exercise"), systemImage: "plus").frame(maxWidth: .infinity, minHeight: 44) }
                .buttonStyle(.borderedProminent)
            Button { typing = true } label: { Label(L("trwo_text_open"), systemImage: "square.and.pencil").frame(maxWidth: .infinity, minHeight: 44) }
                .buttonStyle(.bordered)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    /// All sets done: how much it was, how hard it felt, finish (Android `CompletePage`).
    private var completePage: some View {
        ScrollView {
            VStack(spacing: 12) {
                Image(systemName: "checkmark.circle.fill").font(.system(size: 64)).foregroundStyle(TrainingStyle.positive)
                Text(L("trp_complete_title")).font(.title2.weight(.semibold))
                Text(tr: ui.completeSummary).font(.body)
                rpeChooser
                if ui.comeback { comebackPainQuestion }
                TextField(L("trw_notes_label"), text: $notes, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .onChange(of: notes) { _, v in if v.count > 2000 { notes = String(v.prefix(2000)) } }
                Button {
                    finishTraining()
                } label: { Text(L("trp_finish_training")).frame(maxWidth: .infinity, minHeight: 48) }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("player_complete_finish")
                Button(L("trp_overview")) { overview = true }
            }
            .padding(24)
        }
    }

    /// The end of a training; a comeback session also hands over the pain question's answer (-1 = not answered).
    private func finishTraining() {
        let pain: Int32 = Int32(comebackPain ?? -1)
        model.finish(rpe: Int32(rpe), notes: notes, comebackPain: pain)
    }

    /// A comeback session: the highest pain at the injured spot goes into the plan's diary (Android `cb_finish_pain`,
    /// owner 2026-10-20). iOS also shows the notice here, as on every comeback surface.
    private var comebackPainQuestion: some View {
        VStack(alignment: .leading, spacing: 6) {
            ComebackPainScale(label: L("cb_finish_pain"), value: comebackPain, tag: "player_comeback_pain",
                              onChange: { (value: Int?) in comebackPain = value })
            ComebackNoticeLine()
        }
    }

    private var rpeChooser: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L("trw_rpe_label")).font(.subheadline.weight(.semibold))
            ForEach([Array(1...5), Array(6...10)], id: \.self) { row in
                HStack(spacing: 6) {
                    ForEach(row, id: \.self) { v in
                        TrainingNumberChip(text: "\(v)", selected: rpe == v) { rpe = rpe == v ? 0 : v }
                    }
                }
            }
            Text(L("trw_rpe_info_text")).font(.caption2).foregroundStyle(.secondary)
        }
    }

    // ── Sheets ───────────────────────────────────────────────────────

    private var overviewSheet: some View {
        NavigationStack {
            List {
                ForEach(ui.pages, id: \.index) { page in
                    Button {
                        overview = false
                        model.showPage(index: page.index)
                    } label: {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(page.names).fontWeight(page.shown ? .semibold : .regular).lineLimit(2)
                                Text(playerPageProgress(page)).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if !page.open { Image(systemName: "checkmark.circle.fill").foregroundStyle(TrainingStyle.positive) }
                        }
                    }
                    .buttonStyle(.plain)
                }
                Section {
                    Button { afterOverview = "add"; overview = false } label: { Label(L("trw_add_exercise"), systemImage: "plus") }
                    Button { afterOverview = "type"; overview = false } label: { Label(L("trwo_text_open"), systemImage: "square.and.pencil") }
                    Button { afterOverview = "finish"; overview = false } label: { Label(L("trw_finish"), systemImage: "flag.checkered") }
                }
            }
            .navigationTitle(L("trp_overview"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L("tr_action_done")) { overview = false } }
            }
        }
    }

    @ViewBuilder
    private var finishSheet: some View {
        NavigationStack {
            Form {
                if !ui.anyDone {
                    // Nothing done: nothing would be saved, so offer to discard it (Android `NothingDoneDialog`).
                    Section {
                        Text(L("trw_nothing_done_text"))
                        Button(L("trw_discard"), role: .destructive) { finishing = false; model.discard() }
                        Button(L("trw_keep_training")) { finishing = false }
                    } header: { Text(L("trw_nothing_done_title")) }
                } else {
                    Section { rpeChooser }
                    if ui.comeback {
                        Section { comebackPainQuestion }
                    }
                    Section {
                        TextField(L("trw_notes_label"), text: $notes, axis: .vertical)
                        if ui.hasOpenSets { Text(L("trw_unfinished_hint")).font(.caption).foregroundStyle(.secondary) }
                    }
                    Section {
                        Button(L("trw_finish_confirm")) {
                            finishing = false
                            finishTraining()
                        }
                        Button(L("trw_discard"), role: .destructive) { confirmDiscard = true }
                    }
                }
            }
            .navigationTitle(L("trw_finish_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel")) { finishing = false } }
            }
            .alert(L("trw_discard_title"), isPresented: $confirmDiscard) {
                Button(L("trw_discard"), role: .destructive) { finishing = false; model.discard() }
                Button(L("tr_action_cancel"), role: .cancel) {}
            } message: { Text(L("trw_discard_text")) }
        }
    }
}

/// One row of the exercise page: done, being corrected, the active set, or planned.
private struct WorkoutRowView: View {
    let row: WorkoutRowUi
    let ui: WorkoutPlayerState
    let model: WorkoutPlayerScreenModel
    @Binding var entry: TrainingNumberEntry?

    var body: some View {
        switch row.kind {
        case "round":
            Text(tr: row.label).font(.subheadline.weight(.semibold)).foregroundStyle(.secondary).padding(.top, 12)
        case "warmupsDone":
            Button { model.unfoldWarmups() } label: {
                HStack {
                    Text(L("trw_warmup_short")).foregroundStyle(.secondary).frame(width: 44, alignment: .leading)
                    Text(tr: row.label).frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "checkmark").foregroundStyle(TrainingStyle.positive)
                    Image(systemName: "chevron.down")
                }
                .frame(minHeight: 48)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        default:
            switch row.stateCode {
            case "done": doneRow
            case "editing": editingRow
            case "active": activeRow
            default: plannedRow
            }
        }
    }

    private var label: some View {
        Text(tr: row.label).font(.headline).foregroundStyle(.secondary).frame(width: 44, alignment: .leading)
    }

    private var doneRow: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button { model.edit(setId: row.id) } label: {
                HStack {
                    label
                    VStack(alignment: .leading, spacing: 2) {
                        if !row.name.isEmpty { Text(row.name).font(.subheadline).lineLimit(1) }
                        HStack(spacing: 6) {
                            Text(tr: row.result).font(.headline)
                            outcomeIcon
                            if let load = TrRender.optional(row.loadLine) {
                                Text(load).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if let reserve = TrRender.optional(row.reserveLine) {
                        Text(reserve).font(.caption).foregroundStyle(.secondary)
                    }
                }
                .frame(minHeight: 48)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(L("tr_action_edit"))
            if row.isLatest {
                VStack(alignment: .leading, spacing: 4) {
                    if row.asksReserve { reserveChoice }
                    if let record = TrRender.optional(row.recordText) {
                        Label(record, systemImage: "trophy.fill").font(.subheadline).foregroundStyle(TrainingStyle.positive)
                    }
                    if let capacity = TrRender.optional(row.capacityText) {
                        Label(capacity, systemImage: "chart.line.uptrend.xyaxis").font(.subheadline)
                    }
                    if let adjustment = TrRender.optional(row.adjustmentText) {
                        Text(adjustment).font(.subheadline.weight(.semibold)).foregroundStyle(Color.accentColor)
                    }
                }
                .padding(.leading, 48)
                .padding(.bottom, 4)
            }
        }
    }

    @ViewBuilder
    private var outcomeIcon: some View {
        switch row.outcomeCode {
        case "under":
            Image(systemName: "arrow.down").foregroundStyle(TrainingStyle.caution).accessibilityLabel(L("trpg_outcome_under"))
        case "over":
            Image(systemName: "arrow.up").foregroundStyle(TrainingStyle.positive).accessibilityLabel(L("trpg_outcome_over"))
        case "met":
            Image(systemName: "checkmark").foregroundStyle(TrainingStyle.positive).accessibilityLabel(L("trpg_outcome_met"))
        default:
            Image(systemName: "checkmark").foregroundStyle(TrainingStyle.positive).accessibilityLabel(L("trp_set_done_label"))
        }
    }

    private var reserveChoice: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L("trpg_reserve_question")).font(.subheadline)
            HStack(spacing: 6) {
                ForEach(0...3, id: \.self) { v in
                    TrainingNumberChip(text: v == 3 ? L("trw_rir_3plus") : "\(v)", selected: row.rir == Int32(v)) {
                        model.setReserve(setId: row.id, rir: row.rir == Int32(v) ? -1 : Int32(v))
                    }
                }
            }
        }
    }

    private var editingRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                label
                Text(row.name.isEmpty ? ui.exerciseName : row.name).font(.subheadline.weight(.semibold)).lineLimit(1)
            }
            steppers
            if row.asksReserve { reserveChoice }
            HStack {
                Button(L("trp_reopen")) { model.reopen(setId: row.id) }
                Spacer()
                Button(L("tr_action_done")) { model.closeEdit() }.buttonStyle(.borderedProminent)
            }
        }
        .padding(12)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.5)))
        .padding(.vertical, 4)
    }

    private var activeRow: some View {
        HStack(spacing: 0) {
            Rectangle().fill(Color.accentColor).frame(width: 4)
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    label
                    VStack(alignment: .leading) {
                        if !row.name.isEmpty { Text(row.name).font(.subheadline).lineLimit(1) }
                        if let target = TrRender.optional(row.target) { Text(target).font(.headline) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if let percent = TrRender.optional(row.percent) {
                        Text(percent).font(.caption).foregroundStyle(.secondary)
                    }
                    movedMarker
                }
                steppers
                if let carried = TrRender.optional(row.carriedText) {
                    Text(carried).font(.caption).foregroundStyle(.secondary)
                }
                if row.showsGrip {
                    Menu {
                        ForEach(model.gripCodes, id: \.self) { code in
                            Button(TrRender.text(model.gripLabel(code: code))) { model.setGrip(setId: row.id, code: code) }
                        }
                    } label: {
                        Label(TrRender.text(row.gripLabel), systemImage: "chevron.down").font(.subheadline)
                    }
                }
                if !row.resultChips.isEmpty {
                    Text(L("trpg_other_result")).font(.caption.weight(.semibold)).foregroundStyle(.secondary).padding(.top, 4)
                    HStack(spacing: 6) {
                        ForEach(row.resultChips.map { $0.intValue }, id: \.self) { value in
                            TrainingNumberChip(text: "\(value)") {
                                UINotificationFeedbackGenerator().notificationOccurred(.success)
                                model.completeAs(value: Int32(value))
                            }
                        }
                        TrainingNumberChip(text: "…") {
                            entry = TrainingNumberEntry(title: L("trpg_other_number"), initial: row.typedResultInitial,
                                                        decimal: false, allowNegative: false) { v in
                                model.completeAs(value: Int32(min(max(v.rounded(), 0), 999)))
                            }
                        }
                        .accessibilityLabel(L("trpg_other_number"))
                    }
                }
            }
            .padding(12)
        }
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .padding(.vertical, 4)
    }

    private var plannedRow: some View {
        Button { model.focus(setId: row.id) } label: {
            HStack {
                label
                VStack(alignment: .leading) {
                    if !row.name.isEmpty { Text(row.name).font(.subheadline).foregroundStyle(.secondary).lineLimit(1) }
                    Text(tr: row.summary).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                movedMarker
            }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint(L("trp_next_set"))
    }

    @ViewBuilder
    private var movedMarker: some View {
        if row.movedBy < 0 {
            Image(systemName: "arrow.down").foregroundStyle(TrainingStyle.caution)
        } else if row.movedBy > 0 {
            Image(systemName: "arrow.up").foregroundStyle(TrainingStyle.positive)
        }
    }

    private var steppers: some View {
        VStack(spacing: 8) {
            ForEach(row.steppers, id: \.code) { stepper in
                HStack {
                    Text(tr: stepper.label).font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity, alignment: .leading)
                    Button { step(stepper.code, -1) } label: { Image(systemName: "minus").frame(width: 48, height: 48) }
                        .buttonStyle(.bordered)
                        .accessibilityLabel(L("trw_minus", TrRender.text(stepper.label)))
                    Button {
                        guard !stepper.editTitle.isEmpty else { return }
                        let code = stepper.code
                        entry = TrainingNumberEntry(title: TrRender.text(stepper.editTitle), initial: stepper.editInitial,
                                                    decimal: stepper.decimal, allowNegative: stepper.allowNegative) { v in
                            if code == "load" { model.setLoad(setId: row.id, display: v) } else { model.setMain(setId: row.id, value: v) }
                        }
                    } label: {
                        Text(tr: stepper.value).font(.title3.weight(.bold)).frame(minWidth: 110)
                    }
                    .buttonStyle(.plain)
                    Button { step(stepper.code, 1) } label: { Image(systemName: "plus").frame(width: 48, height: 48) }
                        .buttonStyle(.bordered)
                        .accessibilityLabel(L("trw_plus", TrRender.text(stepper.label)))
                }
            }
        }
    }

    private func step(_ code: String, _ direction: Int32) {
        switch code {
        case "load": model.stepLoad(setId: row.id, direction: direction)
        case "edge": model.stepEdge(setId: row.id, direction: direction)
        default: model.stepMain(setId: row.id, direction: direction)
        }
    }
}

/// The rest as a bar under the page (Android `RestBar`): countdown, ±15 s and "on".
private struct WorkoutRestBar: View {
    let ui: WorkoutPlayerState
    let model: WorkoutPlayerScreenModel

    var body: some View {
        let remaining = Int(ui.restRemaining)
        let progress = ui.restFinished || !ui.restArmed ? 1.0 : min(max(Double(remaining) / Double(max(ui.restTotal, 1)), 0), 1)
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                if ui.sideSwitch && !ui.restFinished { Image(systemName: "arrow.left.arrow.right") }
                VStack(alignment: .leading) {
                    Text(tr: ui.restHeadline).font(.headline).lineLimit(2)
                    if let upcoming = TrRender.optional(ui.restUpcoming) {
                        Text(upcoming).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Text(ui.restFinished ? "✓" : (ui.restArmed ? TrRender.clock(remaining) : "…"))
                    .font(.system(size: 40, weight: .bold).monospacedDigit())
                    .foregroundStyle(ui.restFinished ? TrainingStyle.positive : (remaining > 0 && remaining <= 10 ? Color.accentColor : Color.primary))
                    .accessibilityLabel(L("trp_rest_remaining_cd", TrRender.clock(remaining <= 10 ? remaining : (remaining / 10) * 10)))
            }
            ProgressView(value: progress).tint(remaining > 0 && remaining <= 10 ? Color.accentColor : Color.primary)
            if !ui.restFinished {
                HStack(spacing: 8) {
                    Button(L("trp_rest_minus")) { model.adjustRest(deltaSeconds: -15) }
                        .buttonStyle(.bordered).disabled(!ui.restArmed)
                    Button { model.endRest() } label: {
                        Text(L(ui.restFinishedExercise ? "trpg_to_next" : "trpg_rest_on")).frame(maxWidth: .infinity, minHeight: 40)
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("player_rest_skip")
                    Button(L("trp_rest_plus")) { model.adjustRest(deltaSeconds: 15) }
                        .buttonStyle(.bordered).disabled(!ui.restArmed)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.bar)
        .onChange(of: ui.restFinished) { _, finished in
            if finished { UINotificationFeedbackGenerator().notificationOccurred(.warning) }
        }
    }
}

/// "Instead of X" during a training: the same alternatives as on today's card.
private struct WorkoutSwapSheet: View {
    let model: WorkoutPlayerScreenModel
    let name: String
    /// Android shows each alternative's `ExerciseThumb`.
    let pictogram: (String) -> PictogramUi?
    let onSwap: (String) -> Void
    let onClose: () -> Void
    @State private var alternatives: [TodayAlternativeUi]?

    var body: some View {
        NavigationStack {
            List {
                if let alternatives {
                    if alternatives.isEmpty { Text(L("trt_swap_none")).foregroundStyle(.secondary) }
                    ForEach(alternatives, id: \.slug) { alt in
                        Button { onSwap(alt.slug) } label: {
                            HStack(spacing: 10) {
                                if let tile = pictogram(alt.slug) { ExercisePictogramTile(pictogram: tile, size: 40) }
                                VStack(alignment: .leading) {
                                    Text(alt.name)
                                    Text([TrRender.text(alt.tag), TrRender.text(alt.equipment)].filter { !$0.isEmpty }.joined(separator: " · "))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                } else {
                    ProgressView()
                }
            }
            .navigationTitle(L("trt_swap_title", name))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel"), action: onClose) } }
        }
        .task {
            // The alternatives read the database; off the main thread.
            let found = await Task.detached { model.alternatives() }.value
            alternatives = found
        }
    }
}

/// "It hurts" during a set (Android `PainSheet`): where, which side, how much, then swap, lighter or end.
private struct WorkoutPainSheet: View {
    let model: WorkoutPlayerScreenModel
    let kit: BodyMapKit
    let exerciseName: String
    let onDone: () -> Void
    @State private var area = ""
    @State private var region = ""
    @State private var side = ""
    @State private var level = 4.0
    @State private var action = "swap"
    @State private var remember: Bool?

    var body: some View {
        let severity = Int(level.rounded())
        let remembers = remember ?? (severity >= Int(model.painPauseSeverity))
        NavigationStack {
            Form {
                Section {
                    Text(exerciseName).foregroundStyle(.secondary)
                }
                regionSection
                Section(L("trp2_pain_side")) {
                    TrainingFlow {
                        ForEach(model.painSideCodes, id: \.self) { code in
                            TrainingChoiceChip(title: TrRender.text(model.injurySideLabel(code: code)), selected: side == code) {
                                side = side == code ? "" : code
                            }
                        }
                    }
                }
                Section(L("trp2_pain_level", severity)) {
                    Slider(value: $level, in: 0...9, step: 1)
                }
                Section(L("trp2_pain_action")) {
                    Picker(L("trp2_pain_action"), selection: $action) {
                        Text(L("trp2_action_swap")).tag("swap")
                        Text(L("trp2_action_lighter")).tag("lighter")
                        Text(L("trp2_action_end")).tag("end")
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                    Toggle(L(severity >= Int(model.painPauseSeverity) ? "trp2_pain_remember_pause" : "trp2_pain_remember"),
                           isOn: Binding(get: { remembers }, set: { remember = $0 }))
                }
                Section {
                    Text(L("trp2_pain_medical")).font(.caption)
                }
                Section {
                    Button(L("trp2_pain_confirm")) {
                        model.reportPain(regionCode: region, sideCode: side, severity: Int32(severity), actionCode: action, remember: remembers)
                        onDone()
                    }
                    .disabled(region.isEmpty)
                }
            }
            .navigationTitle(L("trp2_pain_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel"), action: onDone) } }
        }
        .onAppear { side = model.painStartSide }
    }
}

extension WorkoutPainSheet {
    /// Android `PainSheet`: tap the body — the area picks the region, a paired area the side — or a chip.
    var regionSection: some View {
        Section(L("trp2_pain_region")) {
            BodyMapPicker(kit: kit, selected: area) { hit in
                area = hit.areaCode
                let code: String = kit.injuryRegionCode(areaCode: hit.areaCode)
                if !code.isEmpty { region = code }
                if hit.bodySideCode == "left" { side = "LEFT" }
                if hit.bodySideCode == "right" { side = "RIGHT" }
            }
            TrainingFlow {
                ForEach(model.painRegionCodes, id: \.self) { code in
                    TrainingChoiceChip(title: TrRender.text(model.regionLabel(code: code)), selected: region == code) { region = code }
                }
            }
        }
    }
}

/// "Type exercises" (Android `WorkoutTextSheet`): free text, circuits included, read off the main thread a
/// moment after typing (`WorkoutTypedReaderModel`), with a preview of what each exercise becomes — a catalogue
/// exercise, or a new one the app creates on "Add" — and of the circuits. "Add" takes exactly what is in the field.
private struct WorkoutTypedSheet: View {
    let model: WorkoutPlayerScreenModel
    let onDone: () -> Void
    @State private var text = ""
    @State private var reader: ScreenHost<WorkoutTypedReaderModel, WorkoutTypedPreviewUi>?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L("trwo_text_placeholder"), text: $text, axis: .vertical)
                        .lineLimit(4...12)
                        .textInputAutocapitalization(.sentences)
                } header: {
                    Text(L("trwo_text_label"))
                } footer: {
                    Text(L("trwo_text_hint"))
                }
                if let reader {
                    previewRows(reader.state)
                    applyButton(reader)
                }
            }
            .navigationTitle(L("trw_typed_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel"), action: onDone) } }
        }
        .task {
            guard reader == nil else { return }
            let typed: WorkoutTypedReaderModel = model.typedReader()
            reader = ScreenHost(model: typed, initial: typed.currentState,
                                subscribe: { typed, onState in typed.watch(onState: onState) },
                                onClose: { typed in typed.close() })
            // Typed before the reader was there: read it now.
            if !text.isEmpty { typed.setText(text: text) }
        }
        .onChange(of: text) { _, next in
            reader?.model.setText(text: next)
        }
    }

    @ViewBuilder
    private func previewRows(_ preview: WorkoutTypedPreviewUi) -> some View {
        if let title = TrRender.optional(preview.title) {
            Text(title).font(.subheadline.weight(.semibold))
        }
        ForEach(Array(preview.lines.enumerated()), id: \.offset) { _, line in
            typedRow(line)
        }
    }

    private func applyButton(_ host: ScreenHost<WorkoutTypedReaderModel, WorkoutTypedPreviewUi>) -> some View {
        let label: String = TrRender.text(host.state.applyLabel)
        let empty: Bool = host.state.lines.isEmpty
        return Button(label) {
            host.model.add(text: text)
            onDone()
        }
        .disabled(empty)
    }

    /// A circuit's first member carries the circuit's line above it; members are indented under it.
    private func typedRow(_ line: WorkoutTypedLineUi) -> some View {
        let indent: CGFloat = line.inCircuit ? 12 : 0
        let starts: Bool = line.startsCircuit
        return VStack(alignment: .leading, spacing: 6) {
            if starts {
                circuitHeader(line)
            }
            typedLine(line)
                .padding(.leading, indent)
        }
    }

    private func circuitHeader(_ line: WorkoutTypedLineUi) -> some View {
        let header: String = TrRender.text(line.circuitHeader)
        return HStack(spacing: 6) {
            Image(systemName: "repeat").font(.footnote.weight(.semibold))
            Text(header).font(.subheadline.weight(.semibold))
        }
        .foregroundStyle(Color.accentColor)
    }

    private func typedLine(_ line: WorkoutTypedLineUi) -> some View {
        HStack(alignment: .top) {
            Image(systemName: line.isNew ? "plus.circle.fill" : "checkmark.circle.fill")
                .foregroundStyle(line.isNew ? Color.accentColor : TrainingStyle.positive)
            VStack(alignment: .leading) {
                Text(line.name).font(.subheadline.weight(.semibold))
                Text(tr: line.summary).font(.subheadline)
                if let newHint = TrRender.optional(line.creationHint) { Text(newHint).font(.caption).foregroundStyle(.secondary) }
            }
        }
    }
}

/// Adds an exercise to the open training from the catalogue (free training).
private struct WorkoutAddExerciseSheet: View {
    let model: WorkoutPlayerScreenModel
    let onDone: () -> Void
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                ForEach(model.searchExercises(query: query, limit: 60), id: \.slug) { row in
                    Button {
                        model.addExercise(slug: row.slug)
                        onDone()
                    } label: {
                        VStack(alignment: .leading) {
                            Text(row.name)
                            Text(tr: row.category).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always))
            .navigationTitle(L("trw_add_exercise"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel"), action: onDone) } }
        }
    }
}

/// The full-screen hang / interval timer (Android `HangTimerContent`): prepare → work → rest … → done.
/// The wall clock decides (start anchor + accumulated time), so a stall never stretches a hang.
private struct WorkoutHangTimerView: View {
    let ui: WorkoutPlayerState
    let onDone: () -> Void
    let onCancel: () -> Void
    let onReleased: (Double) -> Void

    @State private var timer: WorkoutHangTimer?
    @State private var tick: WorkoutHangTick?
    @State private var running = true
    @State private var baseMs: Int64 = 0
    @State private var anchor = Date()
    @State private var lastBeep = -1
    @State private var finished = false
    @State private var speech = AVSpeechSynthesizer()

    private var elapsedMs: Int64 {
        running ? baseMs + Int64(Date().timeIntervalSince(anchor) * 1000) : baseMs
    }

    var body: some View {
        let phase = tick?.phaseCode ?? "prepare"
        ZStack {
            background(phase).ignoresSafeArea()
            VStack(spacing: 12) {
                HStack {
                    Spacer()
                    Button(action: onCancel) { Image(systemName: "xmark").font(.title2).padding() }
                        .accessibilityLabel(L("trw_timer_close"))
                }
                Spacer()
                if let side = TrRender.optional(ui.timerSide) { Text(side).font(.title2) }
                Text(L(phaseKey(phase))).font(.largeTitle.weight(.semibold))
                Text(tick.map { $0.done ? "✓" : String($0.remainingSeconds) } ?? "")
                    .font(.system(size: 120, weight: .bold).monospacedDigit())
                if let t = tick, let timer, !t.done, phase != "prepare", timer.repCount > 1 {
                    Text(L("trw_timer_rep", Int(t.rep) + 1, Int(timer.repCount))).font(.title3)
                }
                ProgressView(value: tick?.progress ?? 0).padding(.horizontal, 40)
                if ui.timerReleasable && phase == "work" {
                    Button {
                        guard let t = tick else { return }
                        running = false
                        let held = Double(max(0, elapsedMs - t.segmentStartMs) / 100) / 10.0
                        onReleased(held)
                    } label: {
                        Text(L("trw_timer_released")).font(.headline).frame(maxWidth: .infinity, minHeight: 64)
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(.horizontal, 40)
                }
                HStack(spacing: 16) {
                    Button { togglePause() } label: {
                        Label(L(running ? "trw_timer_pause" : "trw_timer_resume"), systemImage: running ? "pause.fill" : "play.fill")
                    }
                    .buttonStyle(.bordered)
                    Button { skip() } label: { Label(L("trw_timer_skip"), systemImage: "forward.end") }
                        .buttonStyle(.bordered)
                }
                Spacer()
            }
            .foregroundStyle(foreground(phase))
        }
        .onAppear {
            let t = WorkoutHangTimer(workS: ui.timerWorkS, restBetweenS: ui.timerRestBetweenS, reps: ui.timerReps, sets: 1, restBetweenSetsS: 0)
            timer = t
            anchor = Date()
            tick = t.at(elapsedMs: 0)
            UIApplication.shared.isIdleTimerDisabled = true
        }
        .task {
            while !Task.isCancelled && !finished {
                step()
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
        }
    }

    private func step() {
        guard let timer, running else { return }
        let next = timer.at(elapsedMs: elapsedMs)
        let previous = tick
        tick = next
        if next.done {
            finished = true
            signal(long: true, buzz: .success)
            say(L("trw_voice_done"))
            onDone()
            return
        }
        if previous == nil || previous!.segmentIndex != next.segmentIndex {
            lastBeep = -1
            if previous != nil {
                signal(long: true, buzz: .warning)
                switch next.phaseCode {
                case "work": say(L("trw_voice_go"))
                case "rest", "setRest": say(L("trw_voice_rest"))
                default: break
                }
            }
        }
        let remaining = Int(next.remainingSeconds)
        if (1...3).contains(remaining) && remaining != lastBeep {
            lastBeep = remaining
            signal(long: false, buzz: nil)
        }
    }

    private func togglePause() {
        guard tick?.done != true else { return }
        if running {
            baseMs = elapsedMs
            running = false
        } else {
            anchor = Date()
            running = true
        }
    }

    private func skip() {
        guard let timer, let t = tick, !t.done else { return }
        baseMs = timer.nextStartMs(segmentIndex: t.segmentIndex)
        anchor = Date()
        if !running { tick = timer.at(elapsedMs: baseMs) }
    }

    /// Beeps for the last three seconds and a longer tone at a phase change; haptics where Android vibrates.
    private func signal(long: Bool, buzz: UINotificationFeedbackGenerator.FeedbackType?) {
        if ui.timerSound { AudioServicesPlaySystemSound(long ? 1005 : 1057) }
        if ui.timerVibration, let buzz { UINotificationFeedbackGenerator().notificationOccurred(buzz) }
    }

    private func say(_ text: String) {
        guard ui.timerVoice else { return }
        speech.stopSpeaking(at: .immediate)
        speech.speak(AVSpeechUtterance(string: text))
    }

    private func phaseKey(_ phase: String) -> String {
        switch phase {
        case "work": return "trw_phase_work"
        case "rest": return "trw_phase_rest"
        case "setRest": return "trw_phase_set_rest"
        case "done": return "trw_phase_done"
        default: return "trw_phase_prepare"
        }
    }

    private func background(_ phase: String) -> Color {
        switch phase {
        case "work": return Color.accentColor
        case "rest": return Color.accentColor.opacity(0.25)
        case "setRest": return Color(.secondarySystemBackground)
        case "done": return TrainingStyle.positive.opacity(0.3)
        default: return TrainingStyle.caution.opacity(0.35)
        }
    }

    private func foreground(_ phase: String) -> Color {
        phase == "work" ? .white : .primary
    }
}

/// "Exercise 2 of 5: …, 0 of 2 sets per side": per-side exercises count sets per side (Android D47).
private func playerPageLabel(_ page: WorkoutPageUi, count: Int) -> String {
    let key: String = page.perSide ? "trpg_page_cd_sides" : "trpg_page_cd"
    let number: Int = Int(page.index) + 1
    return L(key, number, count, page.names, Int(page.setsDone), Int(page.setsTotal))
}

private func playerPageProgress(_ page: WorkoutPageUi) -> String {
    let key: String = page.perSide ? "trpg_page_progress_sides" : "trpg_page_progress"
    return L(key, Int(page.setsDone), Int(page.setsTotal))
}
