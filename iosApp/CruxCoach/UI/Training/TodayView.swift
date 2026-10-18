import CruxCoachCore
import SwiftUI

/// Today (Android `TodayScreen`): the week, the setup checklist, today's suggested training with
/// its check-in, swaps and learned choices, and the day's tiles. All decisions are the Kotlin
/// presenter's (`TodayScreenModel`); this view only shows them.
struct TodayView: View {
    let core: AppCore
    let onClose: () -> Void
    let onOpenNutrition: () -> Void
    let onOpenProgress: () -> Void

    @State private var host: ScreenHost<TodayScreenModel, TodayScreenState>?

    var body: some View {
        NavigationStack {
            Group {
                if let host {
                    TodayContent(core: core, model: host.model, ui: host.state, onClose: onClose,
                                 onOpenNutrition: onOpenNutrition, onOpenProgress: onOpenProgress)
                } else {
                    ProgressView()
                }
            }
            .navigationTitle(L("tr_nav_today"))
            .navigationBarTitleDisplayMode(.inline)
        }
        .task {
            // Android syncs climbing sessions from Health Connect when Today opens; here Apple Health.
            // Does nothing while reading is switched off.
            core.makeHealthActions()?.syncClimbing()
            guard host == nil, let model = core.makeTodayScreen(language: TrainingStyle.catalogLanguage) else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }
}

private struct TodayContent: View {
    let core: AppCore
    let model: TodayScreenModel
    let ui: TodayScreenState
    let onClose: () -> Void
    let onOpenNutrition: () -> Void
    let onOpenProgress: () -> Void

    @State private var moreToday = false
    @State private var showCheckin = false
    @State private var showWhy = false
    @State private var showNext = false
    @State private var showCircuit = false
    @State private var showWeight = false
    @State private var showEquipment = false
    @State private var swapSlug: String?
    @State private var itemsExpanded = false
    @State private var playerOpen = false
    @State private var summaryId: String?
    @State private var finishedId: String?
    @State private var afterNext = ""
    @State private var notYet: String?
    /// The training pages Today links to (injuries, training settings, the quick estimate).
    @State private var progressSheet: TodayProgressLink?
    /// The climbing-day sheet: "" logs a new day, an id edits today's entry (device test D30); nil = closed.
    @State private var climbingSheet: String?
    @State private var climbingList = false
    /// Board day: the playlist generator (Android opens it preset to the plan's type and minutes).
    @State private var boardGenerator = false
    @State private var snack: TrainingSnack?
    @State private var snackAction: (() -> Void)?

    var body: some View {
        withAlerts(withSheets(withEvents(page)))
    }

    private var page: some View {
        ZStack(alignment: .bottom) {
            if ui.loading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        TodayWeekHeader(ui: ui)
                        if ui.openPause { pauseBanner }
                        if ui.hasInjuries { injuryBanner }
                        if ui.showSetup { setupChecklist }
                        hero
                        if !ui.gradeOffer.isEmpty {
                            TodayQuestionCard(text: TrRender.text(ui.gradeOffer), no: L("trc_no"), yes: L("trc_yes")) { take in
                                model.answerGradeOffer(take: take)
                            }
                        }
                        EnergyCareCardView(core: core)
                        if ui.hasLoadSpike { loadSpikeCard }
                        dayGrid
                        nextStepCard
                        Spacer(minLength: 72)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                }
            }
            TrainingSnackBar(snack: $snack) { snackAction?() }
        }
        .animation(.default, value: snack)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(L("action_close"), action: onClose)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button { notYet = L("trt_weekly_review") } label: { Label(L("trt_weekly_review"), systemImage: "chart.bar") }
                    Button { notYet = L("trt_done_hero_history") } label: { Label(L("trt_done_hero_history"), systemImage: "clock.arrow.circlepath") }
                    if !ui.hasOpenWorkout {
                        Button { model.startEmptyWorkout() } label: { Label(L("trt_quick_free"), systemImage: "plus") }
                    }
                    Button { progressSheet = .injuries } label: { Label(L("trt_report_injury"), systemImage: "bandage") }
                    Button { progressSheet = .settings } label: { Label(L("tr_action_settings"), systemImage: "slider.horizontal.3") }
                } label: {
                    Label(L("trt_more_actions"), systemImage: "ellipsis.circle")
                }
            }
        }
    }

    private func withEvents<V: View>(_ view: V) -> some View {
        view
        .onChange(of: ui.startedWorkout) { _, started in
            if started {
                model.consumeStartedWorkout()
                playerOpen = true
            }
        }
        .onChange(of: ui.suggestionSaved) { _, saved in
            if saved {
                model.consumeSuggestionSaved()
                show(L("trsg_saved"))
            }
        }
        .onChange(of: TrRender.text(ui.gradeTakenText)) { _, text in
            guard !text.isEmpty else { return }
            model.consumeGradeTaken()
            show(text, action: L("trf_undo")) { model.undoGradeTaken() }
        }
        .onChange(of: TrRender.text(ui.levelKeptText)) { _, text in
            guard !text.isEmpty else { return }
            model.consumeLevelKept()
            show(text, action: L("trf_undo")) { model.undoLevelKept() }
        }
        .onChange(of: ui.neverOfferSlug) { _, slug in
            guard !slug.isEmpty else { return }
            let text = TrRender.text(ui.neverOfferText)
            snackAction = { model.answerNeverOffer(never: true) }
            snack = TrainingSnack(text: text, action: L("trt_never_offer_yes"))
            // Dismissed without "never": it is kept and rests a while (Android's snackbar dismissal).
            Task {
                try? await Task.sleep(nanoseconds: 6_500_000_000)
                if !model.currentState.neverOfferSlug.isEmpty { model.answerNeverOffer(never: false) }
            }
        }
    }

    private func withSheets<V: View>(_ view: V) -> some View {
        view
        .sheet(isPresented: $showCheckin) {
            TodayCheckinSheet(ui: ui, model: model, onDone: { showCheckin = false }, healthActions: core.makeHealthActions())
        }
        .sheet(isPresented: $showWhy) {
            TodayWhySheet(ui: ui) { showWhy = false }
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showNext, onDismiss: {
            // The next sheet opens once this one is gone.
            switch afterNext {
            case "checkin": showCheckin = true
            case "equipment": showEquipment = true
            default: break
            }
            afterNext = ""
        }) {
            TodayNextSheet(ui: ui, model: model,
                           onHurts: { afterNext = "checkin"; showNext = false },
                           onEquipment: { afterNext = "equipment"; showNext = false },
                           onDone: { showNext = false })
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showCircuit) {
            TodayCircuitSheet(ui: ui, model: model) { showCircuit = false }
                .presentationDetents([.medium])
        }
        .sheet(isPresented: $showWeight) {
            TodayWeightSheet(ui: ui, model: model) { saved in
                showWeight = false
                if saved { show(L("tru_weight_saved")) }
            }
            .presentationDetents([.medium])
        }
        .sheet(isPresented: $showEquipment) {
            TodayEquipmentSheet(ui: ui, model: model) { showEquipment = false }
        }
        .sheet(item: Binding(get: { swapSlug.map { TodaySwapTarget(slug: $0) } }, set: { swapSlug = $0?.slug })) { target in
            TodaySwapSheet(model: model, slug: target.slug, onSwap: { to in
                let from = model.nameOf(slug: target.slug)
                let toName = model.nameOf(slug: to)
                swapSlug = nil
                model.swapExercise(from: target.slug, to: to)
                show(L("trt_swapped_undo", toName, from), action: L("trf_undo")) { model.undoCardChange() }
            }, onDrop: {
                let name = model.nameOf(slug: target.slug)
                swapSlug = nil
                model.dropExercise(slug: target.slug)
                show(L("trt_dropped_undo", name), action: L("trf_undo")) { model.undoCardChange() }
            }, onExclude: {
                let name = model.nameOf(slug: target.slug)
                let slug = target.slug
                swapSlug = nil
                model.excludeExercise(slug: slug)
                // Never final by accident: one tap takes it back.
                show(L("trt_never_done", name), action: L("trf_undo")) { model.includeExercise(slug: slug) }
            }, onClose: { swapSlug = nil })
            .presentationDetents([.medium, .large])
        }
        .sheet(item: Binding(get: { climbingSheet.map { ClimbingDayEditTarget(id: $0) } }, set: { climbingSheet = $0?.id })) { target in
            ClimbingDaySheetView(core: core, editId: target.id, onDone: { climbingSheet = nil },
                                 onAddAnother: addAnother(editing: target.id))
                .id(target.id)
        }
        .sheet(isPresented: $boardGenerator) {
            NavigationStack {
                GeneratorView(core: core, presetType: ui.boardPlanCode, presetMinutes: Int(ui.boardPlanMinutes)) { _ in boardGenerator = false }
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel")) { boardGenerator = false } }
                    }
            }
        }
        .sheet(isPresented: $climbingList) {
            ClimbingDaysListView(core: core) { climbingList = false }
        }
    }

    private func withAlerts<V: View>(_ view: V) -> some View {
        view
        .fullScreenCover(isPresented: $playerOpen, onDismiss: {
            // The summary comes up once the player is gone: two presentations at once drop the second.
            if let id = finishedId {
                finishedId = nil
                summaryId = id
            }
        }) {
            WorkoutPlayerView(core: core, onClose: { playerOpen = false }, onFinished: { id in
                finishedId = id
                playerOpen = false
            }, onOpenNutrition: { playerOpen = false; onOpenNutrition() })
        }
        .sheet(item: Binding(get: { summaryId.map { TodaySwapTarget(slug: $0) } }, set: { summaryId = $0?.slug })) { target in
            WorkoutSummaryView(core: core, workoutId: target.slug, onClose: { summaryId = nil },
                               onOpenNutrition: { summaryId = nil; onOpenNutrition() })
        }
        .alert(TrRender.text(ui.pendingTitle), isPresented: Binding(get: { ui.hasPendingStart }, set: { if !$0 { model.resolveStart(choice: -1) } })) {
            Button(TrRender.text(ui.pendingReplace)) { model.resolveStart(choice: 1) }
            Button(TrRender.text(ui.pendingContinue)) {
                model.resolveStart(choice: 0)
                playerOpen = true
            }
            Button(L("action_cancel"), role: .cancel) { model.resolveStart(choice: -1) }
        } message: {
            Text(tr: ui.pendingText)
        }
        .sheet(item: $progressSheet) { link in
            switch link {
            case .estimate:
                ProgressQuickEstimateSheet(core: core) { progressSheet = nil }
            case .injuries, .settings:
                NavigationStack {
                    Group {
                        if link == .injuries { ProgressInjuriesView(core: core) } else { ProgressSettingsView(core: core) }
                    }
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) { Button(L("action_done")) { progressSheet = nil } }
                    }
                }
            }
        }
        .alert(notYet ?? "", isPresented: Binding(get: { notYet != nil }, set: { if !$0 { notYet = nil } })) {
            Button(L("action_close"), role: .cancel) { notYet = nil }
        } message: {
            Text(LI("training_not_on_iphone"))
        }
    }

    private func show(_ text: String, action: String? = nil, perform: (() -> Void)? = nil) {
        snackAction = perform
        snack = TrainingSnack(text: text, action: action)
    }

    /// A second session today stays one tap away from today's entry.
    private func addAnother(editing id: String) -> (() -> Void)? {
        if id.isEmpty { return nil }
        return { climbingSheet = "" }
    }

    // ── Banners ──────────────────────────────────────────────────────

    private var pauseBanner: some View {
        HStack {
            Image(systemName: "moon.zzz")
            Text(L("trt_pause_short")).frame(maxWidth: .infinity, alignment: .leading)
            Button(L("trt_pause_end")) { model.endPause() }
        }
        .padding(12)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private var injuryBanner: some View {
        Button { progressSheet = .injuries } label: {
            HStack {
                Image(systemName: "bandage")
                VStack(alignment: .leading) {
                    Text(L(ui.injuryClimbingPaused ? "trt_injury_mode_paused" : "trt_injury_mode")).font(.subheadline.weight(.semibold))
                    Text(tr: ui.injurySummary).font(.caption)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right")
            }
            .padding(12)
            .background(TrainingStyle.caution.opacity(0.18), in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }

    // ── "Set up in 3 steps" ──────────────────────────────────────────

    @ViewBuilder
    private var setupChecklist: some View {
        let steps: [(done: Bool, title: String, text: String, open: () -> Void)] = [
            (ui.setupCoachDone, L("tru_setup_coach"), L("tru_setup_coach_text"), { notYet = L("tru_setup_coach") }),
            (ui.setupEquipmentDone, L("tru_setup_equipment"), L("tru_setup_equipment_text"), { showEquipment = true }),
            (ui.setupWeightDone, L("tru_setup_weight"), L("tru_setup_weight_text"), { showWeight = true }),
        ]
        let done = steps.filter { $0.done }.count
        if ui.setupJustDone && done == steps.count {
            HStack {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(TrainingStyle.positive)
                VStack(alignment: .leading) {
                    Text(L("tru_setup_ready_title")).font(.headline)
                    Text(L("tru_setup_ready_text")).font(.caption)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Button { model.dismissSetupDone() } label: { Image(systemName: "xmark") }
                    .accessibilityLabel(L("tru_done"))
            }
            .padding(14)
            .background(TrainingStyle.positive.opacity(0.15), in: RoundedRectangle(cornerRadius: 14))
        } else {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Image(systemName: "flag.checkered").foregroundStyle(Color.accentColor)
                    Text(L("tru_setup_title")).font(.headline).frame(maxWidth: .infinity, alignment: .leading)
                    Text(L("tru_setup_progress", done, steps.count)).font(.subheadline).foregroundStyle(.secondary)
                }
                ProgressView(value: Double(done), total: Double(steps.count)).tint(Color.accentColor)
                ForEach(0..<steps.count, id: \.self) { i in
                    let step = steps[i]
                    Button { if !step.done { step.open() } } label: {
                        HStack(spacing: 12) {
                            ZStack {
                                Circle().fill(step.done ? TrainingStyle.positive : Color(.systemBackground)).frame(width: 32, height: 32)
                                if step.done {
                                    Image(systemName: "checkmark").foregroundStyle(.white)
                                } else {
                                    Text("\(i + 1)").font(.subheadline.weight(.bold))
                                }
                            }
                            VStack(alignment: .leading) {
                                Text(step.title).font(.body.weight(step.done ? .regular : .semibold))
                                    .foregroundStyle(step.done ? Color.secondary : Color.primary)
                                Text(step.done ? L("tru_setup_done") : step.text).font(.caption).foregroundStyle(.secondary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            if !step.done { Image(systemName: "chevron.right").foregroundStyle(.secondary) }
                        }
                        .frame(minHeight: 48)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(step.done)
                }
            }
            .padding(14)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))
        }
    }

    // ── The hero: open training, done today, or today's suggestion ───

    @ViewBuilder
    private var hero: some View {
        if ui.hasOpenWorkout {
            Button { playerOpen = true } label: {
                HStack {
                    Image(systemName: "play.fill").font(.title)
                    Text(L("trt_continue_workout")).font(.title3.weight(.semibold)).frame(maxWidth: .infinity, alignment: .leading)
                }
                .accessibilityIdentifier("today_open_workout")
                .foregroundStyle(.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 20)
                .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 14))
            }
            .buttonStyle(.plain)
        } else if !ui.doneTitles.isEmpty && !moreToday {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Image(systemName: "checkmark.circle.fill")
                    Text(L("trt_done_hero_title")).font(.title2.weight(.bold))
                }
                let titles = ui.doneTitles.map { TrRender.text($0) }.joined(separator: " · ")
                Text(titles + (ui.doneMinutes > 0 ? " · " + LP("trt_minutes", Int(ui.doneMinutes)) : ""))
                Text(L("trt_done_hero_text")).font(.caption)
                HStack {
                    Button(L("trt_done_hero_more")) { moreToday = true }.buttonStyle(.bordered)
                    Button(L("trt_done_hero_history")) { notYet = L("trt_done_hero_history") }
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(TrainingStyle.positive.opacity(0.15), in: RoundedRectangle(cornerRadius: 14))
        } else if ui.hasSuggestion {
            suggestionCard
            if ui.durationHint > 0 {
                HStack {
                    Image(systemName: "timer")
                    Text(L("tre_duration_hint", Int(ui.durationHint))).font(.caption).frame(maxWidth: .infinity, alignment: .leading)
                    Button(L("tre_duration_apply")) { model.applyDurationHint() }
                }
                .padding(12)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.4)))
            }
            if !ui.levelOffer.isEmpty {
                TodayQuestionCard(text: TrRender.text(ui.levelOffer), no: L("trc_no"), yes: L("trc_yes")) { keep in
                    model.answerLevelOffer(keep: keep)
                }
            }
        }
    }

    private var suggestionCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: "sparkles")
                Text(L("trt_today_hero")).font(.subheadline).frame(maxWidth: .infinity, alignment: .leading)
                if !ui.blockLabel.isEmpty {
                    Button { showWhy = true } label: { Text(tr: ui.blockLabel).font(.caption) }
                        .buttonStyle(.plain)
                        .accessibilityHint(L("tru_why"))
                }
            }
            if ui.asksBeforeSuggestion {
                TodayQuickCheckin(model: model, onMore: { showCheckin = true })
            } else {
                suggestionBody
            }
        }
        .padding(16)
        .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 16))
    }

    @ViewBuilder
    private var suggestionBody: some View {
        let title = TrRender.text(ui.suggestionTitle)
        Text(title).font(.title2.weight(.bold))
        TrainingFlow(spacing: 8) {
            Text(LP("trsg_meta_count", Int(ui.mainCount))).font(.subheadline)
            if ui.timeAdjustable {
                Menu {
                    Section(L("trt_duration_title")) {
                        ForEach(ui.durationOptions.map { $0.intValue }, id: \.self) { m in
                            Button {
                                model.chooseMinutes(minutes: Int32(m) == ui.minutesToday ? 0 : Int32(m))
                            } label: {
                                if Int32(m) == ui.minutesToday {
                                    Label(L("trt_duration_value", m), systemImage: "checkmark")
                                } else {
                                    Text(L("trt_duration_value", m))
                                }
                            }
                        }
                    }
                    if ui.minutesToday > 0 {
                        Button(L("trt_duration_usual", Int(ui.preferredMinutes))) { model.chooseMinutes(minutes: 0) }
                    }
                } label: {
                    Label(L("trt_duration_value", Int(ui.totalMinutes)), systemImage: "timer").font(.subheadline)
                }
            } else {
                Text(L("trt_duration_value", Int(ui.totalMinutes))).font(.subheadline)
            }
            if !ui.places.isEmpty {
                Menu {
                    ForEach(ui.places, id: \.id) { place in
                        Button {
                            if !place.isCurrent { model.pickPlace(placeId: place.id) }
                        } label: {
                            let name = TrRender.text(place.label)
                            let text = place.isDefault ? L("tro_summary_default", name) : name
                            if place.isCurrent { Label(text, systemImage: "checkmark") } else { Text(text) }
                        }
                    }
                } label: {
                    let current = ui.places.first { $0.isCurrent }
                    Label(current.map { TrRender.text($0.label) } ?? "", systemImage: TodayIcons.place(current?.kindCode ?? ""))
                        .font(.subheadline)
                }
                .accessibilityLabel(L("tro_pick_title"))
            }
            if ui.circuitPossible {
                Button { model.chooseCircuit(on: !ui.circuitToday) } label: {
                    Label(L("trcirc_today_chip"), systemImage: ui.circuitToday ? "checkmark" : "repeat")
                        .font(.subheadline)
                        .padding(.horizontal, 10).padding(.vertical, 6)
                        .background(ui.circuitToday ? Color.accentColor : Color.clear, in: Capsule())
                        .foregroundStyle(ui.circuitToday ? Color.white : Color.primary)
                        .overlay(Capsule().stroke(Color.accentColor, lineWidth: 1))
                }
                .buttonStyle(.plain)
            }
        }
        let shown = itemsExpanded ? ui.items : Array(ui.items.prefix(4))
        VStack(alignment: .leading, spacing: 0) {
            ForEach(shown, id: \.slug) { item in
                if !item.circuitHeader.isEmpty {
                    Button { showCircuit = true } label: {
                        HStack {
                            Image(systemName: "repeat").foregroundStyle(Color.accentColor)
                            Text(tr: item.circuitHeader).font(.subheadline.weight(.semibold)).foregroundStyle(Color.accentColor)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            Image(systemName: "pencil")
                        }
                        .frame(minHeight: 40)
                    }
                    .buttonStyle(.plain)
                }
                Button { swapSlug = item.slug } label: {
                    HStack(spacing: 6) {
                        if item.favorite {
                            Image(systemName: "star.fill").foregroundStyle(Color.accentColor).font(.caption)
                                .accessibilityLabel(L("trt_favorite"))
                        } else {
                            Text("•").foregroundStyle(item.circuit >= 0 ? Color.accentColor : Color.primary).frame(width: 14)
                        }
                        Text(item.name).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                        Text(tr: item.summary).font(.caption).lineLimit(1)
                        Image(systemName: "arrow.left.arrow.right").font(.caption)
                    }
                    .frame(minHeight: 40)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint(L("trt_swap_action"))
            }
            if ui.items.count > 4 {
                Button(itemsExpanded ? L("trt_show_less") : L("trt_more_exercises", ui.items.count - 4)) { itemsExpanded.toggle() }
                    .font(.caption.weight(.semibold))
                    .padding(.vertical, 8)
            }
        }
        if !ui.firstReason.isEmpty {
            Button { showWhy = true } label: {
                (Text(tr: ui.firstReason) + Text("  ") + Text(L("tru_why")).fontWeight(.semibold).foregroundColor(.accentColor))
                    .font(.caption)
                    .lineLimit(3)
                    .multilineTextAlignment(.leading)
            }
            .buttonStyle(.plain)
        }
        if !ui.energyHintCode.isEmpty {
            HStack(alignment: .top) {
                Image(systemName: "fork.knife")
                Text(tr: ui.energyHint)
                    .font(.caption).frame(maxWidth: .infinity, alignment: .leading)
                if ui.levelShift >= 0 {
                    Button(L("trt_level_easier")) { model.chooseLevel(shift: -1) }.font(.caption)
                }
            }
        }
        TodayCheckinRow(ui: ui, onOpen: { showCheckin = true })
        if !ui.boardPlanCode.isEmpty {
            Button { boardGenerator = true } label: {
                Label(TrRender.text(ui.boardPlanTitle), systemImage: "mountain.2").frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            Button { model.startSuggestion(title: title) } label: {
                Text(L("trt_warmup_start")).frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(!ui.canStart)
        } else {
            Button { model.startSuggestion(title: title) } label: {
                Label(L("trsg_start"), systemImage: "play.fill").frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("today_suggestion_start")
            .disabled(!ui.canStart)
        }
        HStack {
            Button { showNext = true } label: { Label(L("trsg_next"), systemImage: "arrow.clockwise") }
            Spacer()
            Menu {
                Button { showWhy = true } label: { Label(L("trsg_why_title"), systemImage: "info.circle") }
                Button { notYet = L("trsg_edit") } label: { Label(L("trsg_edit"), systemImage: "pencil") }
                    .disabled(!ui.canStart)
                Button { model.saveSuggestion(title: title) } label: { Label(L("trsg_save"), systemImage: "bookmark") }
                    .disabled(!ui.canStart)
            } label: {
                Image(systemName: "ellipsis.circle").accessibilityLabel(L("trt_more_actions"))
            }
        }
    }

    private var loadSpikeCard: some View {
        HStack {
            Image(systemName: "speedometer").foregroundStyle(TrainingStyle.caution)
            VStack(alignment: .leading) {
                Text(tr: ui.loadSpikeTitle).font(.subheadline.weight(.semibold))
                Text(L("trt_spike_text")).font(.caption)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.4)))
    }

    // ── "Your day" ───────────────────────────────────────────────────

    private var dayGrid: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L("tru_day_title")).font(.headline).padding(.top, 4)
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                ForEach(ui.tiles, id: \.code) { tile in
                    TodayTile(tile: tile, onTap: { tap(tile) }, onTrailing: onOpenProgress)
                }
            }
        }
    }

    private func tap(_ tile: TodayTileUi) {
        switch tile.code {
        case "climbing":
            // Today's entry rather than a new one that would log the day twice (device test D30).
            switch ui.climbingEntryIds.count {
            case 0: climbingSheet = ""
            case 1: climbingSheet = ui.climbingEntryIds[0]
            default: climbingList = true
            }
        case "weight": showWeight = true
        case "food": onOpenNutrition()
        case "water":
            model.addWater(ml: ui.glassMl)
            show(TrRender.text(ui.waterAddedText), action: L("trf_undo")) { model.removeLastWater() }
        default: break
        }
    }

    @ViewBuilder
    private var nextStepCard: some View {
        if ui.nextStepCode == "setBenchmarks" || ui.nextStepCode == "baselineTest" {
            let benchmarks = ui.nextStepCode == "setBenchmarks"
            VStack(alignment: .leading, spacing: 4) {
                Text(L(benchmarks ? "trt_sugg_benchmarks_title" : "trt_sugg_test_title")).font(.subheadline.weight(.semibold))
                Text(L(benchmarks ? "trt_sugg_benchmarks_text" : "trt_sugg_test_text")).font(.caption).foregroundStyle(.secondary)
                HStack {
                    Spacer()
                    Button(L(benchmarks ? "tru_estimate" : "tr_action_start")) {
                        if benchmarks { progressSheet = .estimate } else { model.startBaselineTests() }
                    }
                    .buttonStyle(.bordered)
                }
            }
            .padding(14)
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.4)))
        }
    }
}

private struct TodaySwapTarget: Identifiable {
    let slug: String
    var id: String { slug }
}

enum TodayIcons {
    static func place(_ kind: String) -> String {
        switch kind {
        case "home": return "house"
        case "gym": return "dumbbell"
        case "travel": return "suitcase"
        case "none": return "figure.walk"
        default: return "mappin.and.ellipse"
        }
    }
}

/// The week strip and consistency line.
private struct TodayWeekHeader: View {
    let ui: TodayScreenState

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if ui.hasStreak {
                HStack {
                    VStack(alignment: .leading) {
                        Text(tr: ui.weekProgress).font(.headline)
                        Text(tr: ui.streakLine).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if ui.jokers > 0 {
                        Label(LP("trt_jokers", Int(ui.jokers)), systemImage: "shield").font(.caption)
                    }
                }
            }
            HStack {
                ForEach(ui.week, id: \.isoDate) { day in
                    VStack(spacing: 4) {
                        Text(TrRender.weekdayNarrow(Int(day.isoWeekday))).font(.caption2.weight(day.isToday ? .bold : .regular))
                        ZStack {
                            Circle().fill(day.climbed || day.trained ? Color.accentColor : Color(.secondarySystemBackground))
                                .frame(width: 28, height: 28)
                            if day.isToday { Circle().stroke(Color.primary, lineWidth: 2).frame(width: 28, height: 28) }
                            if day.climbed && day.trained {
                                Image(systemName: "checkmark.circle").font(.caption).foregroundStyle(.white)
                            } else if day.climbed {
                                Image(systemName: "mountain.2").font(.caption2).foregroundStyle(.white)
                            } else if day.trained {
                                Image(systemName: "dumbbell").font(.caption2).foregroundStyle(.white)
                            } else if day.paused {
                                Image(systemName: "moon").font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(TrRender.weekday(Int(day.isoWeekday)) + ": " + L(dayKey(day)))
                }
            }
        }
    }

    private func dayKey(_ day: TodayWeekDayUi) -> String {
        if day.climbed && day.trained { return "trt_day_both" }
        if day.climbed { return "trt_day_climbed" }
        if day.trained { return "trt_day_trained" }
        if day.paused { return "trt_day_paused" }
        return "trt_day_rest"
    }
}

/// A value tile of "Your day" (Android `ValueTile`).
private struct TodayTile: View {
    let tile: TodayTileUi
    let onTap: () -> Void
    let onTrailing: () -> Void

    private var icon: (String, Color) {
        switch tile.code {
        case "climbing": return ("mountain.2", Color.accentColor)
        case "weight": return ("scalemass", .purple)
        case "food": return ("fork.knife", TrainingStyle.positive)
        default: return ("drop.fill", TrainingStyle.water)
        }
    }

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Image(systemName: icon.0).foregroundStyle(icon.1)
                    Text(tr: tile.label).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    Spacer()
                    if tile.code == "water" {
                        Image(systemName: "plus.circle.fill").foregroundStyle(TrainingStyle.water)
                    }
                }
                Text(tr: tile.value).font(.title3.weight(.bold)).lineLimit(1)
                    .foregroundStyle(tile.valueIsAction ? Color.accentColor : Color.primary)
                if tile.progress >= 0 {
                    ProgressView(value: min(max(tile.progress, 0), 1)).tint(icon.1)
                }
                Text(tr: tile.supporting).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
            }
            .padding(12)
            .frame(maxWidth: .infinity, minHeight: 112, alignment: .topLeading)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .overlay(alignment: .topTrailing) {
            if tile.hasTrailingAction {
                Button(action: onTrailing) {
                    Image(systemName: "chart.xyaxis.line").padding(10)
                }
                .accessibilityLabel(L("tru_open_body"))
            }
        }
    }
}

/// A yes/no question on Today ("one step easier from now on?", "take the logbook grade over?").
private struct TodayQuestionCard: View {
    let text: String
    let no: String
    let yes: String
    let onAnswer: (Bool) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(text).font(.subheadline)
            HStack {
                Spacer()
                Button(no) { onAnswer(false) }
                Button(yes) { onAnswer(true) }
            }
        }
        .padding(14)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.4)))
    }
}

/// Before the suggestion: how strong and what hurts — both optional (Android `QuickCheckin`).
private struct TodayQuickCheckin: View {
    let model: TodayScreenModel
    let onMore: () -> Void
    @State private var strength = 0
    @State private var hurts: Bool?
    @State private var sore: Set<String> = []

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("trt_quick_title")).font(.title2.weight(.bold))
            Text(L("trt_quick_text")).font(.subheadline)
            Text(L("trt_quick_strength")).font(.subheadline.weight(.semibold)).padding(.top, 4)
            HStack(spacing: 6) {
                ForEach(1...5, id: \.self) { v in
                    TrainingNumberChip(text: "\(v)", selected: strength == v) { strength = strength == v ? 0 : v }
                }
            }
            HStack {
                Text(L("trt_quick_weak")).font(.caption)
                Spacer()
                Text(L("trt_quick_strong")).font(.caption)
            }
            Text(L("trt_quick_hurts")).font(.subheadline.weight(.semibold)).padding(.top, 4)
            HStack {
                TrainingChoiceChip(title: L("trt_quick_no"), selected: hurts == false) { hurts = false; sore = [] }
                TrainingChoiceChip(title: L("trt_quick_yes"), selected: hurts == true) { hurts = true }
            }
            if hurts == true {
                Text(L("trt_quick_where")).font(.caption)
                TodaySoreChips(selected: $sore)
            }
            Button {
                model.saveQuickCheckin(strength: Int32(strength), sore: hurts == true ? Array(sore) : [])
            } label: {
                Text(L("trt_quick_show")).frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("today_quick_show")
            Button(L("trt_quick_more"), action: onMore).frame(maxWidth: .infinity)
        }
    }
}

/// Body areas as chips, several at once; the same ones as for an injury.
struct TodaySoreChips: View {
    @Binding var selected: Set<String>
    static let regions = ["FINGER", "WRIST", "ELBOW", "SHOULDER", "BACK", "HIP", "KNEE", "ANKLE", "SKIN", "OTHER"]

    var body: some View {
        TrainingFlow(spacing: 6) {
            ForEach(Self.regions, id: \.self) { r in
                TrainingChoiceChip(title: L("tr_region_" + r.lowercased()), selected: selected.contains(r)) {
                    if selected.contains(r) { selected.remove(r) } else { selected.insert(r) }
                }
            }
        }
    }
}

/// The check-in as one line inside today's training (Android `CheckinRow`).
private struct TodayCheckinRow: View {
    let ui: TodayScreenState
    let onOpen: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if !ui.hasCheckin && ui.checkinEnabled {
                HStack {
                    Image(systemName: "face.smiling")
                    Text(L("trt_checkin_title")).font(.subheadline).frame(maxWidth: .infinity, alignment: .leading)
                    Button(L("tru_checkin_action"), action: onOpen)
                }
            } else {
                HStack {
                    Image(systemName: icon).foregroundStyle(color)
                    VStack(alignment: .leading) {
                        Text(tr: ui.readinessTitle).font(.subheadline.weight(.semibold))
                        if let answers = TrRender.optional(ui.checkinAnswers) {
                            Text(answers).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if ui.checkinEnabled { Button(L("trt_checkin_change"), action: onOpen) }
                }
                if let reason = TrRender.optional(ui.readinessReason) {
                    Text(reason).font(.caption)
                }
            }
        }
        .padding(10)
        .background(Color(.systemBackground).opacity(0.35), in: RoundedRectangle(cornerRadius: 10))
    }

    private var icon: String {
        switch ui.readinessCode {
        case "go": return "checkmark.circle.fill"
        case "adapt": return "slider.horizontal.3"
        default: return "moon.zzz"
        }
    }

    private var color: Color {
        switch ui.readinessCode {
        case "go": return TrainingStyle.positive
        case "adapt": return TrainingStyle.caution
        default: return .secondary
        }
    }
}

/// The four questions and what hurts; tapping the selected value clears it (Android `CheckinSheet`).
private struct TodayCheckinSheet: View {
    let ui: TodayScreenState
    let model: TodayScreenModel
    let onDone: () -> Void
    /// Apple Health's sleep of last night, offered as a tap while sleep is unanswered (Android: Health Connect).
    var healthActions: HealthActions? = nil
    @State private var sleepHint: HealthSleepHintUi?
    @State private var sleep = 0
    @State private var energy = 0
    @State private var skin = 0
    @State private var fingers = 0
    @State private var sick = false
    @State private var sore: Set<String> = []

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(L("trt_checkin_hint")).font(.caption).foregroundStyle(.secondary)
                    scale(L("trt_q_sleep"), $sleep)
                    if let hint = sleepHint, hint.hasHint, sleep == 0 {
                        Button(LI("health_sleep_chip", hint.hours.formatted(.number.precision(.fractionLength(0...1))), Int(hint.score))) {
                            sleep = Int(hint.score)
                        }
                        .font(.caption)
                    }
                    scale(L("trt_q_energy"), $energy)
                    scale(L("trt_q_skin"), $skin)
                    scale(L("trt_q_fingers"), $fingers)
                    Toggle(L("trt_q_sick"), isOn: $sick)
                }
                Section(L("trt_q_sore")) {
                    TodaySoreChips(selected: $sore)
                }
                Section {
                    Button(L("tr_action_save")) {
                        model.saveCheckin(sleep: Int32(sleep), energy: Int32(energy), skin: Int32(skin), fingers: Int32(fingers),
                                          sick: sick, sore: Array(sore))
                        onDone()
                    }
                    .disabled(!(sick || !sore.isEmpty || sleep > 0 || energy > 0 || skin > 0 || fingers > 0))
                    if ui.hasCheckin {
                        Button(L("trt_checkin_reset"), role: .destructive) {
                            model.clearCheckin()
                            onDone()
                        }
                    }
                }
            }
            .navigationTitle(L("trt_checkin_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel"), action: onDone) }
            }
        }
        .task { healthActions?.loadSleepHint { sleepHint = $0 } }
        .onAppear {
            sleep = Int(ui.sleep)
            energy = Int(ui.energy)
            skin = Int(ui.skin)
            fingers = Int(ui.fingers)
            sick = ui.sick
            sore = Set(ui.soreCodes)
        }
    }

    private func scale(_ label: String, _ value: Binding<Int>) -> some View {
        VStack(alignment: .leading) {
            Text(label)
            HStack(spacing: 6) {
                ForEach(1...5, id: \.self) { v in
                    TrainingNumberChip(text: "\(v)", selected: value.wrappedValue == v) {
                        value.wrappedValue = value.wrappedValue == v ? 0 : v
                    }
                }
            }
        }
    }
}

/// "Why?": every reason, what the suggestion was based on, and how sure it is.
private struct TodayWhySheet: View {
    let ui: TodayScreenState
    let onDone: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(Array(ui.reasons.enumerated()), id: \.offset) { _, reason in
                        Text("• " + TrRender.text(reason))
                    }
                    if !ui.evidence.isEmpty {
                        Text(L("trt_based_on_title") + ": " + ui.evidence.map { TrRender.text($0) }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                Section(L("tre_conf_info_title") + ": " + TrRender.text(ui.confidence)) {
                    Text(L("tre_conf_info_text")).font(.caption)
                    if ui.confidenceLow { Text(L("trt_low_data")).font(.caption) }
                }
                if !ui.whyBlock.isEmpty {
                    Section(TrRender.text(ui.whyBlock)) {
                        Text(L("tre_block_info_text")).font(.caption)
                    }
                }
                Section {
                    // Android opens "What the coach learned" (athlete settings) here; that page is not on iPhone yet.
                    Label(L("trlr_title"), systemImage: "brain.head.profile").foregroundStyle(.secondary)
                    Text(LI("training_not_on_iphone")).font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle(L("trsg_why_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L("tr_action_done"), action: onDone) }
            }
        }
    }
}

/// "Another suggestion": the kind of training, where, lighter or harder, or another mix.
private struct TodayNextSheet: View {
    let ui: TodayScreenState
    let model: TodayScreenModel
    let onHurts: () -> Void
    let onEquipment: () -> Void
    let onDone: () -> Void

    private let kinds: [(code: String, key: String)] = [
        ("fingerStrength", "trt_kind_finger"), ("pullPush", "trt_kind_pull_push"), ("legsCore", "trt_kind_legs_core"),
        ("boardDay", "trt_kind_board"), ("mobilityRecovery", "trt_kind_mobility"),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(L("trt_next_kind")).font(.headline)
                    TrainingFlow {
                        ForEach(kinds, id: \.code) { kind in
                            TrainingChoiceChip(title: L(kind.key), selected: ui.wishCode == kind.code) {
                                model.chooseKind(code: ui.wishCode == kind.code ? "" : kind.code)
                                onDone()
                            }
                        }
                        if !ui.wishCode.isEmpty {
                            TrainingChoiceChip(title: L("trt_kind_coach"), selected: false) {
                                model.chooseKind(code: "")
                                onDone()
                            }
                        }
                    }
                    if !ui.places.isEmpty {
                        Text(L("trt_next_place")).font(.headline).padding(.top, 8)
                        TrainingFlow {
                            ForEach(ui.places, id: \.id) { place in
                                TrainingChoiceChip(title: TrRender.text(place.label), selected: place.isCurrent) {
                                    if !place.isCurrent { model.pickPlace(placeId: place.id) }
                                    onDone()
                                }
                            }
                        }
                    }
                    Text(L("trt_next_level")).font(.headline).padding(.top, 8)
                    HStack {
                        TrainingChoiceChip(title: L("trt_level_easier"), selected: ui.levelShift < 0) {
                            model.chooseLevel(shift: ui.levelShift < 0 ? 0 : -1)
                            onDone()
                        }
                        TrainingChoiceChip(title: L("trt_level_harder"), selected: ui.levelShift > 0) {
                            model.chooseLevel(shift: ui.levelShift > 0 ? 0 : 1)
                            onDone()
                        }
                    }
                    Button {
                        model.shuffleSuggestion()
                        onDone()
                    } label: {
                        Label(L("trt_next_shuffle"), systemImage: "arrow.clockwise").frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 12)
                    HStack {
                        Button(action: onHurts) { Label(L("trt_next_hurts"), systemImage: "bandage") }
                        Spacer()
                        Button(action: onEquipment) { Label(L("trt_next_equipment"), systemImage: "dumbbell") }
                    }
                }
                .padding(16)
            }
            .navigationTitle(L("trsg_next"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel"), action: onDone) }
            }
        }
    }
}

/// A tap on an exercise of today's card: what could take its place, leave it out, never suggest it.
private struct TodaySwapSheet: View {
    let model: TodayScreenModel
    let slug: String
    let onSwap: (String) -> Void
    let onDrop: () -> Void
    let onExclude: () -> Void
    let onClose: () -> Void
    @State private var alternatives: [TodayAlternativeUi]?

    var body: some View {
        NavigationStack {
            List {
                if let alternatives {
                    if alternatives.isEmpty {
                        Text(L("trt_swap_none")).foregroundStyle(.secondary)
                    }
                    ForEach(alternatives, id: \.slug) { alt in
                        Button { onSwap(alt.slug) } label: {
                            VStack(alignment: .leading) {
                                Text(alt.name)
                                Text([TrRender.text(alt.tag), TrRender.text(alt.equipment)].filter { !$0.isEmpty }.joined(separator: " · "))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                } else {
                    ProgressView()
                }
                Section {
                    if let weekly = TrRender.optional(model.weeklyHintFor(slug: slug)) {
                        Text(weekly).font(.caption).foregroundStyle(.secondary)
                    }
                    Button(action: onDrop) { Label(L("trt_swap_drop"), systemImage: "minus.circle") }
                    Button(role: .destructive, action: onExclude) { Label(L("trt_swap_exclude"), systemImage: "nosign") }
                }
            }
            .navigationTitle(L("trt_swap_title", model.nameOf(slug: slug)))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel"), action: onClose) }
            }
        }
        .task { alternatives = model.alternatives(slug: slug) }
    }
}

/// Today's circuit: rounds, rest between the exercises and after each round — or no circuit after all.
private struct TodayCircuitSheet: View {
    let ui: TodayScreenState
    let model: TodayScreenModel
    let onDone: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                Text(L("trcirc_explain")).font(.caption).foregroundStyle(.secondary)
                Stepper(value: Binding(get: { Int(ui.circuitRounds) }, set: { set(rounds: $0) }), in: 1...10) {
                    Text(L("trwo_field_rounds") + ": \(ui.circuitRounds)")
                }
                Stepper(value: Binding(get: { Int(ui.circuitRestS) }, set: { set(between: $0) }), in: 0...120, step: 5) {
                    Text(L("trcirc_rest_between") + ": " + TrRender.clock(Int(ui.circuitRestS)))
                }
                Stepper(value: Binding(get: { Int(ui.circuitRoundRestS) }, set: { set(after: $0) }), in: 0...600, step: 15) {
                    Text(L("trcirc_rest_round") + ": " + TrRender.clock(Int(ui.circuitRoundRestS)))
                }
                Button(L("trcirc_dissolve"), role: .destructive) {
                    model.chooseCircuit(on: false)
                    onDone()
                }
            }
            .navigationTitle(L("trcirc_sheet_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L("tr_action_done"), action: onDone) }
            }
        }
    }

    private func set(rounds: Int? = nil, between: Int? = nil, after: Int? = nil) {
        model.setCircuit(rounds: Int32(rounds ?? Int(ui.circuitRounds)),
                         restS: Int32(between ?? Int(ui.circuitRestS)),
                         roundRestS: Int32(after ?? Int(ui.circuitRoundRestS)))
    }
}

/// Today's body weight (Android `WeightSheet`).
private struct TodayWeightSheet: View {
    let ui: TodayScreenState
    let model: TodayScreenModel
    let onDone: (Bool) -> Void
    @State private var text = ""
    @State private var invalid = false

    var body: some View {
        NavigationStack {
            Form {
                Text(L("tru_weight_reason_setup")).font(.caption).foregroundStyle(.secondary)
                HStack {
                    TextField(L("trq_weight"), text: $text).keyboardType(.decimalPad)
                    Text(ui.unitsCode == "imperial" ? "lb" : "kg").foregroundStyle(.secondary)
                }
                if invalid { Text(LI("training_weight_invalid")).font(.caption).foregroundStyle(.red) }
            }
            .navigationTitle(L("trq_weight"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel")) { onDone(false) } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("tr_action_save")) {
                        if let v = TrRender.parse(text), model.logWeight(display: v) { onDone(true) } else { invalid = true }
                    }
                }
            }
        }
    }
}

/// The equipment of the place trained at today (Android `EquipmentSheet`): every tap is kept, "Done" closes.
private struct TodayEquipmentSheet: View {
    let ui: TodayScreenState
    let model: TodayScreenModel
    let onDone: () -> Void
    @State private var selected: [String] = []
    private let equipment = BasicsEquipment()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let place = TrRender.optional(ui.todaysPlaceName) {
                        Text(place).font(.headline)
                    }
                    TrainingEquipmentEditor(selected: $selected)
                }
                .padding(16)
            }
            .navigationTitle(L("tru_setup_equipment"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L("tr_action_done"), action: onDone) }
            }
        }
        .onAppear { selected = ui.equipmentCodes }
        .onChange(of: selected) { _, now in model.saveEquipment(codes: now) }
    }
}

/// Where you train (presets), then what you have (Android `EquipmentEditor`).
struct TrainingEquipmentEditor: View {
    @Binding var selected: [String]
    private let equipment = BasicsEquipment()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("tru_equipment_where")).font(.subheadline.weight(.semibold))
            TrainingFlow {
                ForEach([("home", "tra_preset_home"), ("gym", "tra_preset_gym"), ("travel", "tra_preset_travel")], id: \.0) { preset in
                    TrainingChoiceChip(title: L(preset.1), selected: equipment.presetOf(selected: selected) == preset.0) {
                        selected = equipment.preset(code: preset.0)
                    }
                }
            }
            Text(L("tru_equipment_what")).font(.subheadline.weight(.semibold)).padding(.top, 8)
            TrainingFlow(spacing: 6) {
                ForEach(equipment.codes, id: \.self) { code in
                    TrainingChoiceChip(title: TrRender.text(equipment.label(code: code)), selected: selected.contains(code)) {
                        selected = equipment.toggle(selected: selected, code: code)
                    }
                }
            }
        }
    }
}

private enum TodayProgressLink: String, Identifiable {
    case injuries, settings, estimate
    var id: String { rawValue }
}
