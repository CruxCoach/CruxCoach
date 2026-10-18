import CruxCoachCore
import SwiftUI

/// Board browser: the app's start destination, as on Android.
struct BrowserView: View {
    let core: AppCore
    let sync: ScreenHost<SyncScreenModel, SyncScreenState>
    @Environment(AppEnvironment.self) private var environment
    @State private var host: ScreenHost<BrowserScreenModel, BrowserScreenState>?
    @State private var ble: ScreenHost<BleScreenModel, BleScreenState>?
    @State private var tour: BrowserTourModel?
    /// Mirrors the Kotlin model's step so SwiftUI re-renders when it moves.
    @State private var tourStep = ""
    @State private var search = ""
    @State private var sheet: Sheet?
    @State private var path = NavigationPath()
    /// The training area (Android's "Training & nutrition" menu entry) covers the browser while open.
    @State private var trainingOpen = false
    /// The body page over the training area, opened by a body reminder's notification.
    @State private var bodyOpen = false

    enum Sheet: String, Identifiable { case board, ble, catalogue, filters, holds; var id: String { rawValue } }

    var body: some View {
        Group {
            if let host, let ble {
                content(host: host, ble: ble)
                    .modifier(NotificationRouting(router: environment.router, athleteAvailable: core.athleteAvailable,
                                                  trainingOpen: $trainingOpen, bodyOpen: $bodyOpen,
                                                  closeSheet: { closeSheet() }))
            } else {
                ProgressView()
            }
        }
        .task {
            guard host == nil else { return }
            let model = core.makeBrowserScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            let bleModel = core.bleScreen
            ble = ScreenHost(model: bleModel, initial: bleModel.currentState) { model, onState in
                model.watch(onState: onState)
            }
            let guided = core.makeBrowserTour()
            tour = guided
            tourStep = guided.step
            model.start()
        }
    }

    @ViewBuilder
    private func content(host: ScreenHost<BrowserScreenModel, BrowserScreenState>,
                         ble: ScreenHost<BleScreenModel, BleScreenState>) -> some View {
        let model = host.model
        let ui = host.state
        NavigationStack(path: $path) {
            List {
                Section { header(model: model, ui: ui) }
                if let tour, !tourStep.isEmpty, tourStep != "inactive", tourStep != "done" {
                    Section { TourBanner(tour: tour, step: tourStep) { tourStep = tour.step } }
                }
                if !ui.hasCatalogue {
                    ContentUnavailableView(LI("browser_no_catalogue"), systemImage: "square.and.arrow.down",
                                           description: Text(LI("browser_no_catalogue_hint")))
                } else if ui.loadState == "failed" {
                    ContentUnavailableView(LI("browser_failed"), systemImage: "exclamationmark.triangle")
                    Button(L("action_retry")) { model.refresh() }
                } else {
                    ForEach(ui.climbs, id: \.uuid) { climb in
                        NavigationLink(value: climb.uuid) { ClimbRow(climb: climb) }
                    }
                    if ui.canLoadMore {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                            .onAppear { model.loadMore() }
                    }
                }
            }
            .listStyle(.plain)
            .overlay {
                if ui.loadState == "loading" && ui.climbs.isEmpty { ProgressView() }
            }
            .modifier(BrowserSessionStrip(core: core, model: model, ui: ui, ble: ble, path: $path))
            .searchable(text: $search, prompt: L("board_search_placeholder"))
            .onChange(of: search) { _, query in model.setSearch(query: query) }
            .navigationTitle(L("board_browser_title"))
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: String.self) { uuid in
                ClimbDetailView(core: core, uuid: uuid, angle: ui.angle, ble: ble, pageUuids: ui.climbs.map { $0.uuid }) { model.refresh() }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { sheet = .board } label: {
                        Label(LI("browser_board"), systemImage: "square.grid.3x3")
                    }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { sheet = .ble } label: {
                        Label(L("board_ble_title"), systemImage: ble.state.connected ? "lightbulb.fill" : "lightbulb")
                    }
                    Menu {
                        Button(L("board_filter_title"), systemImage: "line.3.horizontal.decrease") { sheet = .filters }
                        Button(LI("hold_search_title"), systemImage: "hand.point.up.left") { sheet = .holds }
                        Menu(LI("browser_sort")) {
                            ForEach(model.sortCodes, id: \.self) { code in
                                Button(sortTitle(code)) { model.setSort(code: code) }
                            }
                        }
                        Button(L("board_sync_title"), systemImage: "arrow.down.circle") { sheet = .catalogue }
                        NavigationLink(LI("creator_title")) { CreatorView(core: core) }
                        NavigationLink(L("board_lists_title")) { ListsView(core: core) }
                        NavigationLink(LI("map_title")) {
                            // Only Swift can resolve the bundled map folder.
                            MapView(makeModel: {
                                core.makeMapScreen(boardMapDirectory:
                                    Bundle.main.resourceURL?.appendingPathComponent("board_map").path ?? "")
                            }, core: core,
                            onPickBoard: { model.switchBoard(option: $0) },
                            boardBrandWires: sync.state.rows.filter { $0.installed }.map { $0.brandWire })
                        }
                        Button(L("tr_nav_training_nutrition"), systemImage: "figure.climbing") { trainingOpen = true }
                            .accessibilityIdentifier("browser_training")
                        NavigationLink(L("board_logbook_title")) { LogbookView(core: core) }
                        NavigationLink(LI("stats_title")) { StatsView(core: core) }
                        NavigationLink(LI("history_title")) { HistoryView(core: core) }
                        NavigationLink(L("settings_title")) { SettingsView(core: core) }
                    } label: {
                        Label(LI("browser_more"), systemImage: "ellipsis.circle")
                    }
                }
            }
            .sheet(item: $sheet) { which in
                NavigationStack {
                    switch which {
                    case .board:
                        BoardPickerView(core: core, brandWires: sync.state.rows.filter { $0.installed }.map { $0.brandWire }) { option in
                            model.switchBoard(option: option)
                            sheet = nil
                        }
                    case .ble:
                        BleView(ble: ble)
                    case .catalogue:
                        CatalogueView(core: core, sync: sync, isOnboarding: false)
                    case .filters:
                        FilterView(model: model, ui: ui)
                    case .holds:
                        HoldSearchView(model: model, ui: ui)
                    }
                }
            }
            .onChange(of: sync.state.catalogueRevision) { _, _ in model.refresh() }
            .fullScreenCover(isPresented: $trainingOpen) { trainingArea() }
            .onOpenURL { url in
                // Universal links need a paid team, so `cruxcoach://c/<uuid>`
                // and a pasted CruxCoach URL go through the same parser.
                let uuid = core.climbUuidFromLink(url: url.absoluteString)
                if !uuid.isEmpty { path.append(uuid) }
            }
        }
        .task { model.start() }
    }

    private func trainingArea() -> some View {
        TrainingHomeView(core: core) { trainingOpen = false }
            .sheet(isPresented: $bodyOpen) {
                NotificationBodySheet(core: core) { bodyOpen = false }
            }
    }

    /// For a notification tap: a cover cannot come up over the browser's own sheet.
    private func closeSheet() -> Bool {
        let wasOpen: Bool = sheet != nil
        sheet = nil
        return wasOpen
    }

    private func sortTitle(_ code: String) -> String {
        switch code {
        case "quality": return LI("sort_quality")
        case "qualitySends": return L("board_sort_quality_sends")
        case "hardest": return LI("sort_hardest")
        case "easiest": return LI("sort_easiest")
        case "name": return LI("sort_name")
        case "newest": return L("board_sort_newest")
        case "random": return L("board_sort_random")
        default: return LI("sort_popular")
        }
    }

    @ViewBuilder private func header(model: BrowserScreenModel, ui: BrowserScreenState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(ui.boardTitle).font(.headline)
                Spacer()
                if ui.totalCount >= 0 {
                    Text("\(ui.totalCount)")
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                        .accessibilityLabel(LI("browser_count_a11y", Int(ui.totalCount)))
                }
            }
            AnglePicker(state: ui) { model.setAngle(angle: Int32($0)) }
            Toggle(L("board_filter_exclude_sent"),
                   isOn: Binding(get: { ui.excludesSent }, set: { model.setExcludeSent(exclude: $0) }))
        }
    }
}

/// A random pick from the browser (Android `pickRandomClimb`): a page of its own.
struct RandomClimbTarget: Hashable {
    let uuid: String
}

/// Android's strip under the browser header (`SessionTimerBar` / the playlist
/// banner) and the random pick it opens. A modifier of its own keeps the
/// browser's modifier chain short enough for the type checker.
private struct BrowserSessionStrip: ViewModifier {
    let core: AppCore
    let model: BrowserScreenModel
    let ui: BrowserScreenState
    let ble: ScreenHost<BleScreenModel, BleScreenState>
    @Binding var path: NavigationPath

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .top, spacing: 0) { strip }
            .onChange(of: ui.randomClimbUuid) { _, uuid in openRandomClimb(uuid) }
            .navigationDestination(for: RandomClimbTarget.self) { target in
                // Android opens a random pick on its own, without the list's pager.
                ClimbDetailView(core: core, uuid: target.uuid, angle: ui.angle, ble: ble) { model.refresh() }
            }
    }

    /// The running playlist's banner, or "Random climb" while nothing runs and the catalogue is there.
    private var strip: some View {
        var pick: (() -> Void)? = nil
        if ui.hasCatalogue {
            let browser: BrowserScreenModel = model
            pick = { browser.pickRandom() }
        }
        return SessionPlaylistBanner(core: core, onRandomClimb: pick)
    }

    private func openRandomClimb(_ uuid: String) {
        guard !uuid.isEmpty else { return }
        model.consumeRandom()
        path.append(RandomClimbTarget(uuid: uuid))
    }
}

private struct AnglePicker: View {
    let state: BrowserScreenState
    let onPick: (Int) -> Void

    var body: some View {
        let chips = state.angleChips.map { Int(truncating: $0) }
        HStack {
            Text(L("board_angle"))
            Spacer()
            if chips.isEmpty {
                // Kilter's continuous range, as on Android.
                Stepper("\(state.angle)°", value: Binding(get: { Int(state.angle) }, set: onPick), in: 0...70, step: 5)
                    .accessibilityValue("\(state.angle)°")
            } else {
                Picker(L("board_angle"), selection: Binding(get: { Int(state.angle) }, set: onPick)) {
                    ForEach(chips, id: \.self) { Text("\($0)°").tag($0) }
                }
                .pickerStyle(.menu)
            }
        }
    }
}

struct ClimbRow: View {
    let climb: ClimbRowUi

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(climb.name).font(.body)
                if !climb.setter.isEmpty {
                    Text(climb.setter).font(.footnote).foregroundStyle(.secondary)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(climb.grade.isEmpty ? "–" : climb.grade).font(.body.monospacedDigit())
                HStack(spacing: 6) {
                    if !climb.quality.isEmpty {
                        Label(climb.quality, systemImage: "star.fill").labelStyle(.titleAndIcon)
                    }
                    Text("\(climb.sends)")
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// One step of the guided first browse.
///
/// Android draws a spotlight cut-out around the control it describes; this is
/// a banner instead, so the text says which control it means. The steps, their
/// order and their persistence are the Kotlin model's, shared with Android's.
private struct TourBanner: View {
    let tour: BrowserTourModel
    /// Passed in rather than read off the model: a Kotlin object is not
    /// observable, so the step has to reach SwiftUI as a value.
    let step: String
    let onMove: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L(Self.text[step] ?? "")).font(.footnote)
            HStack {
                Button(L("tour_skip")) { tour.skip(); onMove() }
                    .buttonStyle(.bordered)
                Spacer()
                if step == "connect" {
                    Button(LI("tour_no_board")) { tour.deferBluetooth(); onMove() }
                }
                Button(L("action_next")) { tour.advance(from: step); onMove() }
                    .buttonStyle(.borderedProminent)
            }
        }
    }

    /// The Kotlin model's own step codes (lowercase strings, like every
    /// other code the facade exposes).
    private static let text = [
        "connect": "tour_spotlight_connect",
        "angle": "tour_spotlight_angle",
        "filter": "tour_spotlight_filter",
        "open": "tour_spotlight_open",
        "project": "tour_spotlight_project",
        "log": "tour_spotlight_quicklog",
        "logbook": "tour_spotlight_logbook",
        "entry": "tour_spotlight_entry",
    ]
}
