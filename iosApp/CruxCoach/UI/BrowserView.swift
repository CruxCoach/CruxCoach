import CruxCoachCore
import SwiftUI

/// Board browser: the app's start destination, as on Android.
struct BrowserView: View {
    let core: AppCore
    let sync: ScreenHost<SyncScreenModel, SyncScreenState>
    @State private var host: ScreenHost<BrowserScreenModel, BrowserScreenState>?
    @State private var ble: ScreenHost<BleScreenModel, BleScreenState>?
    @State private var search = ""
    @State private var sheet: Sheet?

    enum Sheet: String, Identifiable { case board, ble, catalogue, filters; var id: String { rawValue } }

    var body: some View {
        Group {
            if let host, let ble {
                content(host: host, ble: ble)
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
            model.start()
        }
    }

    @ViewBuilder
    private func content(host: ScreenHost<BrowserScreenModel, BrowserScreenState>,
                         ble: ScreenHost<BleScreenModel, BleScreenState>) -> some View {
        let model = host.model
        let ui = host.state
        NavigationStack {
            List {
                Section { header(model: model, ui: ui) }
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
            .searchable(text: $search, prompt: L("board_search_placeholder"))
            .onChange(of: search) { _, query in model.setSearch(query: query) }
            .navigationTitle(L("board_browser_title"))
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: String.self) { uuid in
                ClimbDetailView(core: core, uuid: uuid, angle: ui.angle, ble: ble) { model.refresh() }
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
                        Menu(LI("browser_sort")) {
                            ForEach(model.sortCodes, id: \.self) { code in
                                Button(sortTitle(code)) { model.setSort(code: code) }
                            }
                        }
                        Button(L("board_sync_title"), systemImage: "arrow.down.circle") { sheet = .catalogue }
                        NavigationLink(L("board_logbook_title")) { LogbookView(core: core) }
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
                    }
                }
            }
            .onChange(of: sync.state.catalogueRevision) { _, _ in model.refresh() }
        }
        .task { model.start() }
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
