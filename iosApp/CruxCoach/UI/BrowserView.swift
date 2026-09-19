import CruxCoachCore
import SwiftUI

/// Board browser: the app's start destination, as on Android.
struct BrowserView: View {
    let core: AppCore
    let sync: Observed<CatalogueSyncState>
    private let presenter: BoardBrowserPresenter
    @State private var state: Observed<BoardBrowserUiState>
    @State private var ble: Observed<BoardConnectionUiState>
    @State private var search = ""
    @State private var sheet: Sheet?

    enum Sheet: String, Identifiable { case board, ble, catalogue, filters; var id: String { rawValue } }

    init(core: AppCore, sync: Observed<CatalogueSyncState>) {
        self.core = core; self.sync = sync
        let presenter = core.newBrowserPresenter()
        self.presenter = presenter
        _state = State(initialValue: Observed(presenter.state, initial: presenter.state.value as! BoardBrowserUiState))
        _ble = State(initialValue: Observed(core.boardConnection.state, initial: core.boardConnection.state.value as! BoardConnectionUiState))
    }

    private var ui: BoardBrowserUiState { state.value }
    private var useFrench: Bool { (core.platform.keyValues.getString(key: "grade_scale") ?? "FRENCH") != "V_SCALE" }

    var body: some View {
        NavigationStack {
            List {
                Section { header }
                if !ui.board.hasCatalogue {
                    ContentUnavailableView(LI("browser_no_catalogue"), systemImage: "square.and.arrow.down",
                                           description: Text(LI("browser_no_catalogue_hint")))
                } else if ui.loadState == .failed {
                    ContentUnavailableView(LI("browser_failed"), systemImage: "exclamationmark.triangle")
                    Button(L("action_retry")) { presenter.refresh() }
                } else {
                    ForEach(ui.climbs, id: \.uuid) { climb in
                        NavigationLink(value: climb.uuid) { ClimbRow(climb: climb, useFrench: useFrench) }
                    }
                    if ui.canLoadMore {
                        ProgressView().frame(maxWidth: .infinity).onAppear { presenter.loadMore() }
                    }
                }
            }
            .listStyle(.plain)
            .overlay { if ui.loadState == .loading && ui.climbs.isEmpty { ProgressView() } }
            .searchable(text: $search, prompt: L("board_search_placeholder"))
            .onChange(of: search) { _, query in presenter.setSearch(query: query) }
            .navigationTitle(L("board_browser_title"))
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: String.self) { uuid in
                ClimbDetailView(core: core, uuid: uuid, angle: ui.filter.angle, brandWire: ui.filter.boardBrand) { presenter.refresh() }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { sheet = .board } label: { Label(LI("browser_board"), systemImage: "square.grid.3x3") }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { sheet = .ble } label: {
                        Label(L("board_ble_title"), systemImage: ble.value.connection == .connected || ble.value.connection == .sending
                              ? "lightbulb.fill" : "lightbulb")
                    }
                    Menu {
                        Button(L("board_filter_title"), systemImage: "line.3.horizontal.decrease") { sheet = .filters }
                        Button(L("board_sort_random"), systemImage: "shuffle") { presenter.setSort(field: .random, direction: .desc) }
                        Button(L("board_sync_title"), systemImage: "arrow.down.circle") { sheet = .catalogue }
                        NavigationLink(L("settings_title")) { SettingsView(core: core) }
                    } label: { Label(LI("browser_more"), systemImage: "ellipsis.circle") }
                }
            }
            .sheet(item: $sheet) { which in
                NavigationStack {
                    switch which {
                    case .board: BoardPickerView(core: core, installed: sync.value.installedBrands) { option in
                        presenter.switchBoard(boardBrand: option.brandWire, layoutId: option.layoutId,
                                              productSizeId: option.productSizeId == 0 ? nil : KotlinInt(int: option.productSizeId), angle: nil)
                        sheet = nil
                    }
                    case .ble: BleView(core: core, ble: ble)
                    case .catalogue: CatalogueView(core: core, sync: sync, isOnboarding: false)
                    case .filters: FilterView(presenter: presenter, state: state)
                    }
                }
            }
            .onChange(of: sync.value.catalogueRevision) { _, _ in presenter.refresh() }
        }
        .task { presenter.start() }
    }

    @ViewBuilder private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(ui.board.boardSize?.name ?? BoardBrand.companion.fromWire(wire: ui.filter.boardBrand).displayNameForUi).font(.headline)
                Spacer()
                if ui.totalCount >= 0 {
                    Text(LI("browser_count", Int(ui.totalCount))).font(.subheadline).foregroundStyle(.secondary)
                        .accessibilityLabel(LI("browser_count_a11y", Int(ui.totalCount)))
                }
            }
            AnglePicker(filter: ui.filter) { presenter.setAngle(angle: Int32($0)) }
            Toggle(L("board_filter_exclude_sent"), isOn: Binding(get: { ui.excludesSent }, set: { presenter.setExcludeSent(exclude: $0) }))
        }
    }
}

private struct AnglePicker: View {
    let filter: BrowserFilterState
    let onPick: (Int) -> Void

    var body: some View {
        let chips = filter.angleChips.map { $0.intValue }
        HStack {
            Text(L("board_angle"))
            Spacer()
            if chips.isEmpty {
                Stepper("\(filter.angle)°", value: Binding(get: { Int(filter.angle) }, set: onPick), in: 0...70, step: 5)
                    .accessibilityValue("\(filter.angle)°")
            } else {
                Picker(L("board_angle"), selection: Binding(get: { Int(filter.angle) }, set: onPick)) {
                    ForEach(chips, id: \.self) { Text("\($0)°").tag($0) }
                }.pickerStyle(.menu)
            }
        }
    }
}

struct ClimbRow: View {
    let climb: ClimbWithStats
    let useFrench: Bool

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(climb.name).font(.body)
                if let setter = climb.setterUsername, !setter.isEmpty {
                    Text(setter).font(.footnote).foregroundStyle(.secondary)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(Grades.label(climb.difficultyAverage?.doubleValue, french: useFrench)).font(.body.monospacedDigit())
                HStack(spacing: 6) {
                    if let quality = climb.qualityAverage?.doubleValue {
                        Label(String(format: "%.1f", quality), systemImage: "star.fill").labelStyle(.titleAndIcon)
                    }
                    if let sends = climb.ascensionistCount?.int64Value { Text("\(sends)") }
                }.font(.footnote).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

enum Grades {
    static func label(_ difficulty: Double?, french: Bool) -> String {
        guard let difficulty else { return "–" }
        return french ? KilterGradeMapper.shared.difficultyToFont(difficulty: difficulty)
                      : KilterGradeMapper.shared.difficultyToVScale(difficulty: difficulty)
    }
}
