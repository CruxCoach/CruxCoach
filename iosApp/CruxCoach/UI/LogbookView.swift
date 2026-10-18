import CruxCoachCore
import SwiftUI

/// Board logbook: entries grouped by day plus the statistics for the selected period.
/// The denominators differ per statistic exactly as on Android (see docs/en/CORE_CONCEPTS.md).
struct LogbookView: View {
    let core: AppCore
    @State private var host: ScreenHost<LogbookScreenModel, LogbookScreenState>?
    @State private var cleaning: ScreenHost<BoardCleaningScreenModel, BoardCleaningScreenState>?
    @State private var showCleaning = false
    @State private var showFilters = false
    /// The board link the climb detail needs (Android's logbook opens the detail on a tap).
    @State private var ble: ScreenHost<BleScreenModel, BleScreenState>?
    /// The guided first browse ends here: the entry just logged.
    @State private var tour: BrowserTourModel?
    @State private var tourStep = ""
    @State private var anchors = TourAnchors()

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .modifier(BleStatusInset(ble: ble))
        .modifier(RestTimerBannerSlot(core: core))
        .navigationTitle(L("board_logbook_title"))
        .toolbar { toolbarContent() }
        .sheet(isPresented: $showFilters) {
            if let host {
                LogbookFilterSheet(model: host.model, ui: host.state) { showFilters = false }
            }
        }
        .sheet(isPresented: $showCleaning) {
            NavigationStack { BoardCleaningSheet(core: core) }
        }
        .task {
            guard host == nil else { return }
            let model = core.makeLogbookScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.refresh()
            // Android: opening the logbook is the logbook step's action.
            let guided = core.makeBrowserTour()
            guided.advance(from: "logbook")
            tour = guided
            tourStep = guided.step
            let bleModel = core.bleScreen
            ble = ScreenHost(model: bleModel, initial: bleModel.currentState) { model, onState in
                model.watch(onState: onState)
            }
            let cleaningModel = core.makeBoardCleaningScreen()
            cleaning = ScreenHost(model: cleaningModel, initial: cleaningModel.currentState,
                                  subscribe: { model, onState in model.watch(onState: onState) },
                                  onClose: { model in model.close() })
        }
    }

    @ViewBuilder
    private func screen(model: LogbookScreenModel, ui: LogbookScreenState) -> some View {
        List {
            if let cleaning, cleaning.state.active {
                Section {
                    BoardCleaningBanner(ui: cleaning.state) { showCleaning = true }
                }
            }
            if ui.isLoading && !ui.hasData {
                Section { ProgressView().frame(maxWidth: .infinity) }
            } else if !ui.hasData {
                Section {
                    ContentUnavailableView(LI("logbook_empty"), systemImage: "list.bullet.rectangle",
                                           description: Text(LI("logbook_empty_hint")))
                }
            } else {
                Section {
                    Picker(LI("logbook_period"), selection: Binding(get: { ui.intervalCode }, set: { model.setInterval(code: $0) })) {
                        Text(LI("period_all")).tag("all")
                        Text(LI("period_30")).tag("days30")
                        Text(LI("period_90")).tag("days90")
                        Text(LI("period_year")).tag("year1")
                    }
                    .pickerStyle(.segmented)
                    StatsSummary(stats: ui.stats)
                }
                if ui.selectedCount > 0 {
                    selectionSection(model: model, ui: ui)
                }
                let pages: [String] = climbPages(ui)
                let tourEntry: String = tourEntryUuid(ui)
                ForEach(ui.days, id: \.date) { day in
                    Section {
                        ForEach(day.entries, id: \.uuid) { entry in
                            entryRow(model: model, entry: entry, pages: pages)
                                .tourAnchor("entry", in: entry.uuid == tourEntry ? anchors : nil)
                        }
                    } header: {
                        Text("\(day.date) · \(LI("logbook_day_summary", Int(day.sendCount), Int(day.attemptCount)))")
                    }
                }
                if ui.canLoadMore {
                    ProgressView().frame(maxWidth: .infinity).onAppear { model.loadMore() }
                }
            }
        }
        .alert(LP("board_logbook_delete_title", Int(ui.selectedCount)), isPresented: Binding(
            get: { ui.showBatchDeleteConfirm },
            set: { if !$0 { model.dismissBatchDeleteConfirm() } })) {
            Button(L("action_cancel"), role: .cancel) { model.dismissBatchDeleteConfirm() }
            Button(L("action_delete"), role: .destructive) { model.confirmBatchDelete() }
        } message: {
            Text(L("board_logbook_delete_message"))
        }
        .alert(LI("logbook_delete_confirm"), isPresented: Binding(
            get: { !ui.deleteConfirmUuid.isEmpty },
            set: { if !$0 { model.dismissDeleteConfirm() } })) {
            Button(L("action_cancel"), role: .cancel) { model.dismissDeleteConfirm() }
            Button(LI("logbook_delete"), role: .destructive) { model.confirmDelete() }
        }
        .sheet(isPresented: Binding(get: { ui.isEditing }, set: { if !$0 { model.dismissEdit() } })) {
            NavigationStack { LogbookEditSheet(model: model, ui: ui) }.presentationDetents([.medium])
        }
        .tourSpotlight(anchors: anchors, tour: tour, step: tourStep,
                       target: tourTarget(ui),
                       message: LI("tour_spotlight_entry_swipe"),
                       visible: !ui.isEditing && !showFilters && !showCleaning,
                       onMove: { refreshTour() })
        .onChange(of: tourEntryMissing(ui)) { _, missing in
            // Android ends the tour when the logged entry is not there to show.
            if missing { tour?.skip(); refreshTour() }
        }
        .overlay(alignment: .bottom) {
            if ui.errorCode != "none" {
                Text(LI("logbook_error", ui.errorCode))
                    .font(.footnote).padding(8).background(.red.opacity(0.85)).foregroundStyle(.white)
                    .clipShape(Capsule())
                    .onTapGesture { model.consumeError() }
            }
        }
    }
}

private extension LogbookView {
    func refreshTour() {
        guard let tour else { return }
        tour.refresh()
        tourStep = tour.step
    }

    /// The entry the tour logged, else the newest one (iOS logs through the detail,
    /// which does not hand the new entry's id back). Empty while there is none.
    func tourEntryUuid(_ ui: LogbookScreenState) -> String {
        guard tourStep == "entry" else { return "" }
        let logged: String = tour?.loggedEntryUuid ?? ""
        var newest = ""
        for day in ui.days {
            for entry in day.entries {
                if !logged.isEmpty && entry.uuid == logged { return logged }
                if newest.isEmpty { newest = entry.uuid }
            }
        }
        return logged.isEmpty ? newest : ""
    }

    func tourTarget(_ ui: LogbookScreenState) -> String? {
        let uuid: String = tourEntryUuid(ui)
        return uuid.isEmpty ? nil : "entry"
    }

    /// Loaded completely and still nothing for the tour to point at.
    func tourEntryMissing(_ ui: LogbookScreenState) -> Bool {
        tourStep == "entry" && !ui.isLoading && !ui.canLoadMore && tourEntryUuid(ui).isEmpty
    }

    /// Android pages the detail through the logbook's distinct climbs.
    func climbPages(_ ui: LogbookScreenState) -> [String] {
        var seen = Set<String>()
        var pages: [String] = []
        for day in ui.days {
            for entry in day.entries where seen.insert(entry.climbUuid).inserted {
                pages.append(entry.climbUuid)
            }
        }
        return pages
    }

    /// One entry: selection, tap to the climb detail, swipe to delete or edit.
    func entryRow(model: LogbookScreenModel, entry: LogbookEntryUi, pages: [String]) -> some View {
        LogbookRow(entry: entry, onToggleSelect: { model.toggleSelection(uuid: entry.uuid) }) {
            climbDetail(model: model, entry: entry, pages: pages)
        }
        .swipeActions {
            Button(LI("logbook_delete"), role: .destructive) { model.requestDelete(uuid: entry.uuid) }
            Button(LI("logbook_edit")) {
                model.edit(uuid: entry.uuid)
                // Android: editing the tour's entry is its last step.
                if tourStep == "entry", let tour {
                    tour.advance(from: "entry")
                    refreshTour()
                }
            }
        }
    }

    /// Android's logbook tap: the climb detail at the logged angle. A climb the
    /// catalogue lacks opens on what the logbook rows carry (`LogbookOnlyClimbView`).
    @ViewBuilder
    func climbDetail(model: LogbookScreenModel, entry: LogbookEntryUi, pages: [String]) -> some View {
        if let ble {
            ClimbDetailView(core: core, uuid: entry.climbUuid, angle: entry.angle, ble: ble, pageUuids: pages) {
                model.refresh()
            }
        } else {
            ProgressView()
        }
    }

    /// Android's logbook actions: filters, then the options menu ("Clean holds",
    /// select all, delete selected).
    @ToolbarContentBuilder
    func toolbarContent() -> some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            if let host, host.state.hasData {
                Button { showFilters = true } label: {
                    Image(systemName: filtersActive(host.state)
                          ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
                        .accessibilityLabel(L("board_logbook_filters"))
                }
            }
            Menu {
                Button { showCleaning = true } label: {
                    Label(L("board_cleaning_title"), systemImage: "sparkles")
                }
                if let host, host.state.hasData {
                    selectionMenuItems(model: host.model, ui: host.state)
                }
            } label: {
                Image(systemName: "ellipsis.circle")
                    .accessibilityLabel(L("board_cleaning_logbook_options"))
            }
        }
    }

    @ViewBuilder
    func selectionMenuItems(model: LogbookScreenModel, ui: LogbookScreenState) -> some View {
        let all: Bool = allSelected(ui)
        Button {
            if all { model.clearSelection() } else { model.selectAll() }
        } label: {
            Label(all ? L("cd_deselect_all") : L("cd_select_all"), systemImage: "checklist")
        }
        Button(role: .destructive) { model.requestBatchDelete() } label: {
            Label(L("cd_delete_selected"), systemImage: "trash")
        }
        .disabled(ui.selectedCount == 0)
    }

    func selectionSection(model: LogbookScreenModel, ui: LogbookScreenState) -> some View {
        Section {
            HStack {
                Text(L("board_logbook_selected", Int(ui.selectedCount)))
                Spacer()
                Button(L("cd_deselect_all")) { model.clearSelection() }
                    .buttonStyle(.borderless)
                Button(role: .destructive) { model.requestBatchDelete() } label: {
                    Image(systemName: "trash").accessibilityLabel(L("cd_delete_selected"))
                }
                .buttonStyle(.borderless)
            }
        }
    }

    func filtersActive(_ ui: LogbookScreenState) -> Bool {
        ui.outcomeFilterCode != "all" || !ui.listBoardFilter.isEmpty || ui.angleFilter >= 0
    }

    func allSelected(_ ui: LogbookScreenState) -> Bool {
        var visible: Int = 0
        for day in ui.days { visible += day.entries.count }
        return visible > 0 && Int(ui.selectedCount) == visible
    }
}

/// Android `LogbookFilterSheet`: result, board (with more than one) and angle.
private struct LogbookFilterSheet: View {
    let model: LogbookScreenModel
    let ui: LogbookScreenState
    let onDone: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(L("ux_logbook_help")).font(.footnote).foregroundStyle(.secondary)
                }
                outcomeSection
                if ui.availableBoardWires.count > 1 {
                    boardSection
                }
                if !ui.availableAngles.isEmpty {
                    angleSection
                }
            }
            .navigationTitle(L("board_logbook_filters"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L("action_done"), action: onDone) }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var outcomeSection: some View {
        Section(L("board_logbook_filter_outcome")) {
            Picker(L("board_logbook_filter_outcome"),
                   selection: Binding(get: { ui.outcomeFilterCode }, set: { model.setOutcomeFilter(code: $0) })) {
                Text(L("map_filter_show_all")).tag("all")
                Text(L("board_sends")).tag("sends")
                Text(L("ux_logbook_unsent_attempts")).tag("attempts")
            }
            .pickerStyle(.segmented)
        }
    }

    private var boardSection: some View {
        let choices: [LogbookBoardChoice] = boardChoices()
        return Section(L("board_logbook_filter_board")) {
            Picker(L("board_logbook_filter_board"),
                   selection: Binding(get: { ui.listBoardFilter }, set: { model.setListBoardFilter(boardWire: $0) })) {
                Text(L("map_filter_show_all")).tag("")
                ForEach(choices) { choice in
                    Text(choice.title).tag(choice.id)
                }
            }
            .pickerStyle(.inline)
            .labelsHidden()
        }
    }

    private var angleSection: some View {
        let angles: [Int32] = ui.availableAngles.map { $0.int32Value }
        return Section(L("board_logbook_filter_angle")) {
            Picker(L("board_logbook_filter_angle"),
                   selection: Binding(get: { ui.angleFilter }, set: { model.setAngleFilter(angle: $0) })) {
                Text(L("map_filter_show_all")).tag(Int32(-1))
                ForEach(angles, id: \.self) { angle in
                    Text("\(angle)°").tag(angle)
                }
            }
            .pickerStyle(.menu)
        }
    }

    private func boardChoices() -> [LogbookBoardChoice] {
        let wires: [String] = ui.availableBoardWires
        let titles: [String] = ui.availableBoardTitles
        var out: [LogbookBoardChoice] = []
        for index in 0..<wires.count {
            let title: String = index < titles.count ? titles[index] : wires[index]
            out.append(LogbookBoardChoice(id: wires[index], title: title))
        }
        return out
    }
}

private struct LogbookBoardChoice: Identifiable {
    let id: String
    let title: String
}

private struct StatsSummary: View {
    let stats: LogbookStatsUi

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                stat(LI("stat_sends"), "\(stats.totalSends)")
                stat(LI("stat_attempts"), "\(stats.totalAttempts)")
                stat(LI("stat_unique"), "\(stats.uniqueClimbs)")
            }
            HStack {
                stat(LI("stat_hardest"), stats.hardestGrade.isEmpty ? "–" : stats.hardestGrade)
                stat(LI("stat_flash_rate"), "\(Int((stats.flashRate * 100).rounded()))%")
                stat(LI("stat_sessions"), "\(stats.sessionCount)")
            }
            if !stats.gradePyramid.isEmpty {
                Text(LI("stat_pyramid")).font(.footnote).foregroundStyle(.secondary)
                ForEach(stats.gradePyramid, id: \.difficulty) { bar in
                    HStack {
                        Text(bar.grade).font(.caption.monospaced()).frame(width: 44, alignment: .leading)
                        GeometryReader { geo in
                            let maximum = stats.gradePyramid.map { Int($0.count) }.max() ?? 1
                            Capsule()
                                .frame(width: geo.size.width * CGFloat(Int(bar.count)) / CGFloat(max(maximum, 1)), height: 10)
                                .foregroundStyle(Color.accentColor)
                        }
                        .frame(height: 10)
                        Text("\(bar.count)").font(.caption.monospacedDigit()).frame(width: 34, alignment: .trailing)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
    }

    private func stat(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.title3.monospacedDigit())
            Text(title).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

private struct LogbookRow<Destination: View>: View {
    let entry: LogbookEntryUi
    let onToggleSelect: () -> Void
    @ViewBuilder let destination: () -> Destination

    var body: some View {
        HStack {
            // Android AscentCard's selection checkbox; outside the combined
            // element so VoiceOver can still reach it on its own.
            Button(action: onToggleSelect) {
                Image(systemName: entry.isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(entry.isSelected ? Color.accentColor : Color.secondary)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L("board_logbook_select_entry", entry.climbName))
            .accessibilityAddTraits(entry.isSelected ? AccessibilityTraits.isSelected : AccessibilityTraits())
            NavigationLink { destination() } label: { details }
        }
    }

    private var details: some View {
        HStack {
            Image(systemName: entry.isSend ? (entry.isFlash ? "bolt.circle.fill" : "checkmark.circle.fill") : "circle.dotted")
                .foregroundStyle(entry.isSend ? Color.accentColor : .secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.climbName)
                HStack(spacing: 6) {
                    if !entry.grade.isEmpty { Text(entry.grade) }
                    Text("\(entry.angle)°")
                    if entry.isMirror { Image(systemName: "arrow.left.and.right") }
                    if !entry.comment.isEmpty { Image(systemName: "text.bubble") }
                }
                .font(.footnote).foregroundStyle(.secondary)
            }
            Spacer()
            Text("×\(entry.tries)").font(.footnote.monospacedDigit()).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct LogbookEditSheet: View {
    let model: LogbookScreenModel
    let ui: LogbookScreenState

    var body: some View {
        Form {
            Stepper(LI("log_tries", Int(ui.editTries)),
                    value: Binding(get: { Int(ui.editTries) }, set: { model.setEditTries(count: Int32($0)) }), in: 1...999)
            if ui.editIsSend {
                Picker(LI("log_quality"), selection: Binding(get: { Int(ui.editQuality) }, set: { model.setEditQuality(quality: Int32($0)) })) {
                    ForEach(0...5, id: \.self) { value in
                        Text(value == 0 ? "–" : String(repeating: "★", count: value)).tag(value)
                    }
                }
            }
            TextField(LI("log_comment"), text: Binding(get: { ui.editComment }, set: { model.setEditComment(comment: $0) }), axis: .vertical)
        }
        .navigationTitle(LI("logbook_edit"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel")) { model.dismissEdit() } }
            ToolbarItem(placement: .confirmationAction) { Button(L("action_save")) { model.saveEdit() } }
        }
    }
}

/// Projection history ("Verlauf"): what was sent to a board, not what was climbed.
struct HistoryView: View {
    let core: AppCore
    @State private var host: ScreenHost<HistoryScreenModel, HistoryScreenState>?

    var body: some View {
        Group {
            if let host {
                let model = host.model
                let ui = host.state
                List {
                    Section {
                        Picker(LI("history_retention"), selection: Binding(get: { ui.retentionCode }, set: { model.setRetention(code: $0) })) {
                            Text(LI("retention_off")).tag("off")
                            Text(LI("retention_30")).tag("days30")
                            Text(LI("retention_90")).tag("days90")
                            Text(LI("retention_365")).tag("days365")
                        }
                    } footer: {
                        Text(LI("history_hint"))
                    }
                    ForEach(ui.entries, id: \.id) { entry in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(entry.climbName)
                            Text("\(entry.recordedAt) · \(entry.angle)°")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    if !ui.entries.isEmpty {
                        Button(LI("history_clear"), role: .destructive) { model.clearHistory() }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(LI("history_title"))
        .task {
            guard host == nil else { return }
            let model = core.makeHistoryScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }
}

/// Android `LogbookOnlyClimbContent`: a climb the user logged but the catalogue
/// lacks (e.g. a Kilter climb CruxCoach never mirrored) shows a friendly
/// explanation and exactly what the logbook rows carry — no uuid diagnostic.
struct LogbookOnlyClimbView: View {
    let core: AppCore
    let climbUuid: String
    @State private var host: ScreenHost<LogbookOnlyClimbModel, LogbookOnlyClimbState>?

    var body: some View {
        Group {
            if let host {
                content(ui: host.state)
            } else {
                ProgressView()
            }
        }
        .task {
            guard host == nil else { return }
            let model = core.makeLogbookOnlyClimb()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.open(climbUuid: climbUuid)
        }
    }

    @ViewBuilder
    private func content(ui: LogbookOnlyClimbState) -> some View {
        if ui.status == "loading" {
            ProgressView()
        } else {
            List {
                Section { explanation(ui: ui) }
                Section {
                    ForEach(ui.entries, id: \.uuid) { entry in
                        LogbookOnlyEntryRow(entry: entry)
                    }
                } header: {
                    Text(L("error_climb_not_in_db_your_logs"))
                }
            }
        }
    }

    private func explanation(ui: LogbookOnlyClimbState) -> some View {
        let name: String = ui.climbName.isEmpty ? L("error_climb_not_in_db_unnamed") : ui.climbName
        return VStack(alignment: .leading, spacing: 10) {
            Text(name).font(.title2.weight(.bold))
            Label {
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("error_climb_not_in_db_title")).font(.headline)
                    Text(L("error_climb_not_in_db_body")).font(.subheadline).foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: "info.circle").foregroundStyle(.secondary)
            }
        }
        .accessibilityIdentifier("detail_logbook_only")
    }
}

private struct LogbookOnlyEntryRow: View {
    let entry: LogbookOnlyEntryUi

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Text(entry.isSend ? L("error_climb_not_in_db_log_send") : L("error_climb_not_in_db_log_attempt"))
                    .font(.body.weight(.bold))
                Text("\(entry.angle)°").font(.footnote).foregroundStyle(.secondary)
                Text(Self.dateText(entry.date)).font(.footnote).foregroundStyle(.secondary)
                if entry.isMirror {
                    Image(systemName: "arrow.left.and.right")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .accessibilityLabel(L("cd_mirrored"))
                }
            }
            Text(L("error_climb_not_in_db_log_attempts", Int(entry.attempts)))
                .font(.footnote).foregroundStyle(.secondary)
            if !entry.comment.isEmpty {
                Text("\"\(entry.comment)\"")
                    .font(.footnote).foregroundStyle(.secondary)
                    .lineLimit(3)
            }
        }
        .accessibilityElement(children: .combine)
    }

    /// Android `formatDate`: the locale's medium date, the raw day when it does not parse.
    static func dateText(_ isoDay: String) -> String {
        let parser = DateFormatter()
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.dateFormat = "yyyy-MM-dd"
        guard let date = parser.date(from: isoDay) else { return isoDay }
        return date.formatted(date: .abbreviated, time: .omitted)
    }
}
