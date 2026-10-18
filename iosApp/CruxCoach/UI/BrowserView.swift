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
    /// Where the tour's controls are, for the spotlight's cut-out.
    @State private var anchors = TourAnchors()
    /// The browser itself is on screen (nothing pushed over it): only then its spotlight shows.
    @State private var rootVisible = true
    @State private var search = ""
    @State private var sheet: Sheet?
    @State private var path = NavigationPath()
    /// The training area (Android's "Training & nutrition" menu entry) covers the browser while open.
    @State private var trainingOpen = false
    /// The body page over the training area, opened by a body reminder's notification.
    @State private var bodyOpen = false
    /// The developer contact page, opened by an announcement or reply notification: over the
    /// browser, or over the training area while that is open.
    @State private var contactOpen = false
    @State private var trainingContactOpen = false
    /// The app's running playlist: a row's "+" queues the climb while it plays (Android).
    @State private var session: ScreenHost<BoardSessionHub, SessionBannerState>?
    /// A joined Android-hosted shared playlist (the participant side).
    @State private var shared: ScreenHost<SharedSessionsModel, SharedSessionsState>?

    enum Sheet: String, Identifiable { case board, ble, catalogue, filters, holds; var id: String { rawValue } }

    var body: some View {
        Group {
            if let host, let ble {
                content(host: host, ble: ble)
                    .modifier(NotificationRouting(core: core, router: environment.router, athleteAvailable: core.athleteAvailable,
                                                  trainingOpen: $trainingOpen, bodyOpen: $bodyOpen,
                                                  contactOpen: $contactOpen, trainingContactOpen: $trainingContactOpen,
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
            let hub = core.boardSessionHub
            session = ScreenHost(model: hub, initial: hub.bannerState,
                                 subscribe: { model, onState in model.watchBanner(onState: onState) })
            let joined = core.sharedSessions
            shared = ScreenHost(model: joined, initial: joined.currentState,
                                subscribe: { model, onState in model.watch(onState: onState) })
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
                if !ui.hasCatalogue {
                    ContentUnavailableView(LI("browser_no_catalogue"), systemImage: "square.and.arrow.down",
                                           description: Text(LI("browser_no_catalogue_hint")))
                } else if ui.loadState == "failed" {
                    ContentUnavailableView(LI("browser_failed"), systemImage: "exclamationmark.triangle")
                    Button(L("action_retry")) { model.refresh() }
                } else {
                    let firstUuid: String = ui.climbs.first?.uuid ?? ""
                    ForEach(ui.climbs, id: \.uuid) { climb in
                        NavigationLink(value: climb.uuid) {
                            HStack(spacing: 8) {
                                ClimbRow(climb: climb)
                                queueAdd(climb: climb, angle: ui.angle)
                            }
                        }
                        .tourAnchor("climb", in: climb.uuid == firstUuid ? anchors : nil)
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
            .onAppear { rootVisible = true; refreshTour() }
            .onDisappear { rootVisible = false }
            // Android's BleStatusArea: the board link's chip under the playlist strip, nearby phones below it.
            .modifier(NearbyStatusInset(core: core, ble: ble))
            .modifier(BleStatusInset(ble: ble) { sheet = .ble })
            .modifier(BrowserSessionStrip(core: core, model: model, ui: ui, ble: ble, path: $path))
            .modifier(RestTimerBannerSlot(core: core))
            .searchable(text: $search, prompt: L("board_search_placeholder"))
            .onChange(of: search) { _, query in model.setSearch(query: query) }
            .navigationTitle(L("board_browser_title"))
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: String.self) { uuid in
                ClimbDetailView(core: core, uuid: uuid, angle: ui.angle, ble: ble, pageUuids: ui.climbs.map { $0.uuid }) { model.refresh() }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { advanceTour(from: "board"); sheet = .board } label: {
                        Label(LI("browser_board"), systemImage: "square.grid.3x3")
                    }
                    .tourAnchor("board", in: anchors)
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { sheet = .ble } label: {
                        Label(L("board_ble_title"), systemImage: ble.state.connected ? "lightbulb.fill" : "lightbulb")
                    }
                    .tourAnchor("ble", in: anchors)
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
                        NavigationLink(LI("shared_sessions_title")) { SharedSessionsView(core: core) }
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
                    .tourAnchor("more", in: anchors)
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
                        BleView(ble: ble, fix: BoardMismatchRoute(core: core, activeBrandWire: ui.brandWire, brandWires: sync.state.rows.filter { $0.installed }.map { $0.brandWire }, onPick: { option in model.switchBoard(option: option); sheet = nil }))
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
            .onChange(of: sheet) { old, new in sheetChanged(from: old, to: new) }
            .fullScreenCover(isPresented: $trainingOpen) { trainingArea() }
            .onOpenURL { url in
                // Universal links need a paid team, so `cruxcoach://c/<uuid>`
                // and a pasted CruxCoach URL go through the same parser.
                let uuid = core.climbUuidFromLink(url: url.absoluteString)
                if !uuid.isEmpty { path.append(uuid) }
            }
        }
        .task { model.start() }
        .modifier(BrowserTourLayer(anchors: anchors, tour: tour, step: tourStep, ui: ui,
                                   connected: ble.state.connected,
                                   visible: rootVisible && sheet == nil && !trainingOpen && !contactOpen,
                                   onMove: { refreshTour() }))
    }

    /// The Kotlin model is not observable: SwiftUI learns a new step through [tourStep].
    private func refreshTour() {
        guard let tour else { return }
        tour.refresh()
        tourStep = tour.step
    }

    private func advanceTour(from step: String) {
        guard let tour, tour.step == step else { return }
        tour.advance(from: step)
        tourStep = tour.step
    }

    /// Android moves on when the step's own action happened: the Bluetooth sheet was closed,
    /// the filters were opened.
    private func sheetChanged(from old: Sheet?, to new: Sheet?) {
        if old == .ble && new == nil { advanceTour(from: "connect") }
        if new == .filters { advanceTour(from: "filter") }
    }

    /// Android ClimbCard's "+" while a playlist runs, with how often the climb is queued.
    @ViewBuilder private func queueAdd(climb: ClimbRowUi, angle: Int32) -> some View {
        if let session, session.state.isActive {
            SessionQueueAddButton(hub: session.model, ui: session.state, climbUuid: climb.uuid, angle: angle)
        } else if let shared {
            SharedSessionAddButton(model: shared.model, ui: shared.state, climbUuid: climb.uuid, angle: angle)
        }
    }

    private func trainingArea() -> some View {
        TrainingHomeView(core: core) { trainingOpen = false }
            .sheet(isPresented: $bodyOpen) {
                NotificationBodySheet(core: core) { bodyOpen = false }
            }
            .sheet(isPresented: $trainingContactOpen) {
                NotificationContactSheet(core: core, focus: environment.router.contactFocus) { trainingContactOpen = false }
            }
    }

    /// For a notification tap: a cover cannot come up over the browser's own sheet.
    private func closeSheet() -> Bool {
        let wasOpen: Bool = sheet != nil || contactOpen
        sheet = nil
        contactOpen = false
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
            AnglePicker(state: ui) { angle in
                model.setAngle(angle: Int32(angle))
                advanceTour(from: "angle")
            }
            .tourAnchor("angle", in: anchors)
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

/// The guided first browse over the browser (Android `TourHost` around the
/// browser): which control each step points at and what it says. The steps,
/// their order and their persistence are the Kotlin model's (`BrowserTourModel`).
private struct BrowserTourLayer: ViewModifier {
    let anchors: TourAnchors
    let tour: BrowserTourModel?
    let step: String
    let ui: BrowserScreenState
    let connected: Bool
    let visible: Bool
    let onMove: () -> Void

    func body(content: Content) -> some View {
        content.tourSpotlight(anchors: anchors, tour: tour, step: step, target: target,
                              message: message, visible: visible && filled, onMove: onMove)
    }

    /// Android waits, step kept, for a filled browser: before that the steps
    /// point at controls whose actions cannot happen yet.
    private var filled: Bool {
        ui.hasCatalogue && !ui.climbs.isEmpty
    }

    /// The filters and the logbook sit under More on iPhone. The later steps
    /// (project, log, entry) belong to the climb detail and the logbook.
    private var target: String? {
        switch step {
        case "board": return "board"
        case "connect": return "ble"
        case "angle": return "angle"
        case "filter", "logbook": return "more"
        case "open": return "climb"
        default: return nil
        }
    }

    private var message: String {
        switch step {
        case "board": return L("tour_spotlight_board_picker")
        case "connect": return connected ? L("tour_spotlight_connect_done") : L("tour_spotlight_connect")
        case "angle": return L("tour_spotlight_angle")
        case "filter": return L("tour_spotlight_filter")
        case "open": return L("tour_spotlight_open")
        case "logbook": return L("tour_spotlight_logbook_overflow")
        default: return ""
        }
    }
}
