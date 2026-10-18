import CruxCoachCore
import SwiftUI

/// The Training tab (Android `WorkoutsScreen` ⇄ `ExerciseCatalogScreen` with `TrainingSwitch`):
/// workouts, the week and the exercise library. [onStartWorkout] gets the id of a training this
/// tab started or continued (the Kotlin side has already started it through `AthleteService`);
/// the caller opens the training player for it.
struct TrainingLibraryView: View {
    let core: AppCore
    let onStartWorkout: (String) -> Void
    let onClose: (() -> Void)?
    let onLogClimbing: (() -> Void)?
    let onOpenHistory: (() -> Void)?

    init(core: AppCore,
         onStartWorkout: @escaping (String) -> Void,
         onClose: (() -> Void)? = nil,
         onLogClimbing: (() -> Void)? = nil,
         onOpenHistory: (() -> Void)? = nil) {
        self.core = core
        self.onStartWorkout = onStartWorkout
        self.onClose = onClose
        self.onLogClimbing = onLogClimbing
        self.onOpenHistory = onOpenHistory
    }

    @State private var showExercises = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker(L("tr_tab_training"), selection: $showExercises) {
                    Text(L("tr_tab_workouts")).tag(false)
                    Text(L("tr_tab_exercises")).tag(true)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                if showExercises {
                    ExerciseLibraryView(core: core, pickForWorkout: "", onStartWorkout: onStartWorkout, onPicked: {})
                } else {
                    WorkoutsListView(core: core, onStartWorkout: onStartWorkout,
                                     onLogClimbing: onLogClimbing, onOpenHistory: onOpenHistory)
                }
            }
            .navigationTitle(L("tr_tab_training"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if let onClose {
                        Button(L("action_close"), action: onClose)
                    }
                }
            }
        }
    }
}

/// The Workouts half: the open training, the week, own workouts, the starter routines.
struct WorkoutsListView: View {
    let core: AppCore
    let onStartWorkout: (String) -> Void
    let onLogClimbing: (() -> Void)?
    let onOpenHistory: (() -> Void)?

    @State private var host: ScreenHost<WorkoutsScreenModel, WorkoutsScreenState>?
    @State private var unavailable = false
    @State private var route: LibraryRoute?
    @State private var deleting: RoutineCardUi?
    @State private var dayCell: WeekDayUi?

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else if unavailable {
                ContentUnavailableView(LI("library_unavailable"), systemImage: "lock.trianglebadge.exclamationmark")
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationDestination(item: $route) { r in
            LibraryRouteView(core: core, route: r, onStartWorkout: onStartWorkout)
        }
        .task {
            guard host == nil else {
                host?.model.refreshWeek()
                return
            }
            guard let model = core.makeWorkoutsScreen(language: LibraryLanguage.current) else {
                unavailable = true
                return
            }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }

    @ViewBuilder
    private func screen(model: WorkoutsScreenModel, ui: WorkoutsScreenState) -> some View {
        List {
            if ui.isLoading {
                ProgressView().frame(maxWidth: .infinity)
            }
            if ui.failed {
                Label(LI("library_failed"), systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
            }
            if ui.hasReturnBanner {
                Section { returnBanner(ui.returnBanner) }
            }
            if !ui.week.isEmpty {
                Section { weekCard(ui) }
            }
            if !ui.openWorkoutId.isEmpty {
                Section {
                    Button {
                        onStartWorkout(ui.openWorkoutId)
                    } label: {
                        Label(L("trwo_continue"), systemImage: "play.fill").font(.headline)
                    }
                    .listRowBackground(Color.accentColor.opacity(0.2))
                }
            }
            Section {
                if ui.own.isEmpty {
                    Text(L("trwo_mine_empty")).font(.footnote).foregroundStyle(.secondary)
                }
                ForEach(ui.own, id: \.id) { card in
                    routineRow(card, model: model)
                }
            } header: {
                HStack {
                    Text(L("trwo_mine"))
                    Spacer()
                    Button {
                        model.startFree(title: L("trt_quick_free"))
                    } label: {
                        Label(L("trt_quick_free"), systemImage: "play.fill").font(.footnote)
                    }
                    .textCase(nil)
                }
            }
            Section(L("trwo_templates")) {
                ForEach(ui.builtins, id: \.id) { card in
                    routineRow(card, model: model)
                }
            }
            if let onOpenHistory {
                Section {
                    Button(action: onOpenHistory) {
                        Label(L("trwo_history"), systemImage: "clock.arrow.circlepath")
                    }
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    route = .editor(routineId: "", from: "", defaultName: "")
                } label: {
                    Label(L("trwo_new"), systemImage: "plus")
                }
            }
        }
        .onChange(of: ui.startedWorkoutId) { _, id in
            guard !id.isEmpty else { return }
            model.consumeStarted()
            onStartWorkout(id)
        }
        .alert(L("trw_open_title", LibraryRender.text(ui.pendingOpenTitle)),
               // Every button answers the question itself; the dismissal that follows must not answer it again.
               isPresented: Binding(get: { ui.hasPendingStart }, set: { _ in })) {
            Button(ui.pendingHasNewTitle ? L("trw_open_replace", LibraryRender.text(ui.pendingNewTitle)) : L("trw_open_replace_empty")) {
                model.replaceOpenAndStart()
            }
            Button(L("trw_open_continue", LibraryRender.text(ui.pendingOpenTitle))) { model.continueOpen() }
            Button(L("tr_action_cancel"), role: .cancel) { model.dismissPendingStart() }
        } message: {
            let total = Int(ui.pendingTotalSets)
            let done = Int(ui.pendingDoneSets)
            Text(L("trw_open_done" + (total == 1 ? ".one" : ".other"), done, total) + " "
                 + L(done == 0 ? "trw_open_dropped" : "trw_open_kept"))
        }
        .alert(L("trwo_delete_title"), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button(L("tr_action_delete"), role: .destructive) {
                if let routine = deleting { model.delete(routineId: routine.id) }
                deleting = nil
            }
            Button(L("tr_action_cancel"), role: .cancel) { deleting = nil }
        } message: {
            Text(L("trwo_delete_text", deleting.map { LibraryRender.text($0.title) } ?? ""))
        }
        .sheet(item: Binding(get: { dayCell.map { LibraryDaySelection(cell: $0) } }, set: { dayCell = $0?.cell })) { selection in
            WeekDaySheet(cell: selection.cell,
                         onStart: {
                             dayCell = nil
                             model.startPlanned(dateIso: selection.cell.dateIso, title: LibraryRender.text(selection.cell.plannedTitle))
                         },
                         onAdapt: {
                             dayCell = nil
                             let id = selection.cell.plannedRoutineId
                             if id.hasPrefix("builtin:") {
                                 route = .editor(routineId: "", from: id, defaultName: LibraryRender.text(selection.cell.plannedTitle))
                             } else {
                                 route = .editor(routineId: id, from: "", defaultName: "")
                             }
                         },
                         onLogClimbing: closingDaySheet(onLogClimbing),
                         onOpenHistory: closingDaySheet(onOpenHistory))
                .presentationDetents([.medium, .large])
        }
    }

    /// [action] after the day sheet has closed; nil stays nil (the sheet then hides the button).
    private func closingDaySheet(_ action: (() -> Void)?) -> (() -> Void)? {
        guard let action else { return nil }
        return {
            dayCell = nil
            action()
        }
    }

    @ViewBuilder
    private func routineRow(_ card: RoutineCardUi, model: WorkoutsScreenModel) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(LibraryRender.text(card.title)).font(.headline)
                    Text(LP("trwo_exercise_count", Int(card.exerciseCount)) + " · " + L("trwo_minutes", Int(card.minutes)))
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Spacer()
                if card.isOwn {
                    Menu {
                        Button { route = .editor(routineId: card.id, from: "", defaultName: "") } label: {
                            Label(L("tr_action_edit"), systemImage: "pencil")
                        }
                        Button { model.duplicate(routineId: card.id, copySuffix: L("trwo_copy_suffix")) } label: {
                            Label(L("trwo_duplicate"), systemImage: "doc.on.doc")
                        }
                        Button(role: .destructive) { deleting = card } label: {
                            Label(L("tr_action_delete"), systemImage: "trash")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle").accessibilityLabel(L("trwo_more"))
                    }
                }
            }
            if card.hasSubtitle {
                Text(LibraryRender.text(card.subtitle)).font(.footnote)
            }
            if !card.preview.isEmpty {
                Text(card.preview).font(.footnote).foregroundStyle(.secondary).lineLimit(2)
            }
            HStack(spacing: 12) {
                Button {
                    model.startRoutine(id: card.id, title: card.isOwn ? "" : LibraryRender.text(card.title))
                } label: {
                    Label(L("tr_action_start"), systemImage: "play.fill")
                }
                .buttonStyle(.bordered)
                if card.isOwn {
                    Button(L("tr_action_edit")) { route = .editor(routineId: card.id, from: "", defaultName: "") }
                        .buttonStyle(.borderless)
                } else {
                    Button(L("trwo_copy_adjust")) {
                        route = .editor(routineId: "", from: card.id, defaultName: LibraryRender.text(card.title))
                    }
                    .buttonStyle(.borderless)
                }
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func weekCard(_ ui: WorkoutsScreenState) -> some View {
        // Without a standard week the seven empty days would only repeat Today's strip.
        let compact = ui.weekPlanEmpty
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(L(compact ? "trwo_week_plan" : "trw_week_title")).font(.headline)
                Spacer()
                if ui.weekPlanned > 0 {
                    Text(L("trw_week_progress", Int(ui.weekDone), Int(ui.weekPlanned)))
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            if !compact {
                HStack(spacing: 0) {
                    ForEach(ui.week, id: \.dateIso) { cell in
                        Button { dayCell = cell } label: { dayView(cell) }
                            .buttonStyle(.plain)
                            .frame(maxWidth: .infinity)
                    }
                }
            } else {
                Text(L("trwo_week_plan_empty")).font(.footnote).foregroundStyle(.secondary)
            }
            Button(L("trwo_week_plan_edit")) { route = .weekPlan }
                .buttonStyle(.borderless)
        }
    }

    private func dayView(_ cell: WeekDayUi) -> some View {
        let (bg, fg): (Color, Color) = {
            switch cell.statusCode {
            case "done": return (Color.accentColor, Color.white)
            case "missed": return (Color.red.opacity(0.2), Color.red)
            default: return (Color(uiColor: .secondarySystemBackground), Color.secondary)
            }
        }()
        let icon: String? = {
            switch cell.statusCode {
            case "done": return "checkmark"
            case "paused": return "moon.zzz"
            case "rest": return "sofa"
            case "planned", "missed": return cell.plannedBoard ? "mountain.2" : "dumbbell"
            default: return nil
            }
        }()
        return VStack(spacing: 4) {
            Text(LibraryRender.weekday(Int(cell.weekday), short: true))
                .font(.caption2.weight(cell.isToday ? .bold : .regular))
            ZStack {
                Circle().fill(bg)
                if cell.isToday { Circle().stroke(Color.primary, lineWidth: 2) }
                if let icon { Image(systemName: icon).font(.caption2).foregroundStyle(fg) }
            }
            .frame(width: 32, height: 32)
            Text(cell.hasPlan ? LibraryRender.text(cell.shortPlanLabel) : "")
                .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([LibraryRender.weekday(Int(cell.weekday)),
                             cell.hasPlan ? LibraryRender.text(cell.shortPlanLabel) : nil,
                             L(cell.statusKey)].compactMap { $0 }.joined(separator: ", "))
    }

    @ViewBuilder
    private func returnBanner(_ r: ReturnBannerUi) -> some View {
        let title: String = {
            switch r.reasonCode {
            case "illness": return L("trw_return_illness", Int(r.daysOff), Int(r.week), Int(r.weeksTotal))
            case "finger": return L("trw_return_finger", Int(r.week), Int(r.weeksTotal))
            default: return L("trw_return_break", Int(r.week), Int(r.weeksTotal), Int(r.daysOff))
            }
        }()
        let detail: String = r.fingerOnly ? L("trw_return_detail_finger")
            : (r.noMaxFinger ? L("trw_return_detail_nomax", Int(r.percent)) : L("trw_return_detail", Int(r.percent)))
        VStack(alignment: .leading, spacing: 4) {
            Label(title, systemImage: "arrow.uturn.backward.circle").font(.subheadline.weight(.semibold))
            Text(detail).font(.footnote)
            DisclosureGroup(L("trw_return_info_title")) {
                Text(L("trw_return_info")).font(.footnote).foregroundStyle(.secondary)
            }
            .font(.footnote)
        }
    }
}

/// Identifiable wrapper for the day sheet.
struct LibraryDaySelection: Identifiable {
    let cell: WeekDayUi
    var id: String { cell.dateIso }
}

/// One day of the week: planned and done, and what makes sense now (Android `WeekDaySheet`).
struct WeekDaySheet: View {
    let cell: WeekDayUi
    let onStart: () -> Void
    let onAdapt: () -> Void
    let onLogClimbing: (() -> Void)?
    let onOpenHistory: (() -> Void)?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(L(cell.statusKey)).foregroundStyle(.secondary)
                    if cell.hasPlan {
                        Text(L("trw_day_planned", LibraryRender.text(cell.planLabel)))
                    }
                    if cell.climbingMinutes > 0 {
                        Text(LP("trt_board_minutes", Int(cell.climbingMinutes)))
                    } else if cell.climbingEfforts > 0 {
                        Text(LP("trt_board_efforts", Int(cell.climbingEfforts)))
                    }
                    ForEach(Array(cell.workouts.enumerated()), id: \.offset) { _, w in
                        Text("• " + LibraryRender.text(w.title) + (w.minutes >= 0 ? " · " + LP("trt_minutes", Int(w.minutes)) : ""))
                    }
                    switch cell.statusCode {
                    case "missed": Text(L("trw_day_missed")).font(.footnote)
                    case "paused": Text(L("trw_day_paused")).font(.footnote)
                    case "free":
                        if !cell.hasPlan && cell.workouts.isEmpty {
                            Text(L("trw_day_nothing")).font(.footnote).foregroundStyle(.secondary)
                        }
                    default: EmptyView()
                    }
                }
                Section {
                    if cell.canStart {
                        Button(action: onStart) { Label(L("tr_action_start"), systemImage: "play.fill") }
                    }
                    if cell.canAdapt {
                        Button(L("trw_action_adapt"), action: onAdapt)
                    }
                    if cell.isToday || cell.isPast, let onLogClimbing {
                        Button(action: onLogClimbing) { Label(L("trw_action_log"), systemImage: "mountain.2") }
                    }
                    if cell.isPast, let onOpenHistory {
                        Button(action: onOpenHistory) { Label(L("trw_action_history"), systemImage: "clock.arrow.circlepath") }
                    }
                }
            }
            .navigationTitle(LibraryRender.fullDate(cell.dateIso))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
