import CruxCoachCore
import MapKit
import SwiftUI

/// Board-location map. MapKit is a system framework, so nothing is added to the
/// dependency set; the whole dataset is offline (the bundled cruxcoach-pages
/// snapshot merged with the synced `locations` chunk).
///
/// A pin is a VENUE, not a board: boards within ~11 m collapse into one pin and
/// the pin shows how many boards stand there. The statistics describe every
/// recorded board regardless of the filters — the Android rule, stated in
/// `map_stats_scope`.
///
/// The model is created by the caller's closure inside `.task`, never in `init`:
/// SwiftUI re-runs `init` on every render pass and would leak a Kotlin presenter.
struct MapView: View {
    let makeModel: () -> MapScreenModel

    @State private var host: ScreenHost<MapScreenModel, MapScreenState>?
    @State private var camera: MapCameraPosition = .automatic
    @State private var visibleRegion: MKCoordinateRegion?
    @State private var showFilters = false
    @State private var showStats = false

    /// SwiftUI's `Map` draws every annotation it is given. The dataset holds
    /// ~2 900 venues, so only what is on screen is handed over, bounded.
    private static let maxAnnotations = 400

    var body: some View {
        Group {
            if let host {
                content(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("map_screen_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard host == nil else { return }
            let model = makeModel()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.refresh()
        }
    }

    @ViewBuilder
    private func content(model: MapScreenModel, ui: MapScreenState) -> some View {
        let shown = visiblePins(ui.pins)
        ZStack(alignment: .bottom) {
            Map(position: $camera) {
                ForEach(shown, id: \.id) { pin in
                    Annotation(pin.title, coordinate: coordinate(pin)) {
                        PinMarker(pin: pin)
                            .onTapGesture { model.selectVenue(id: pin.id) }
                            .accessibilityLabel(accessibilityLabel(pin))
                    }
                }
            }
            .mapStyle(.standard(pointsOfInterest: .excludingAll))
            .onMapCameraChange(frequency: .onEnd) { context in
                visibleRegion = context.region
            }
            .onAppear {
                if case .automatic = camera {
                    camera = .region(MKCoordinateRegion(
                        center: CLLocationCoordinate2D(latitude: ui.initialLatitude,
                                                       longitude: ui.initialLongitude),
                        span: MKCoordinateSpan(latitudeDelta: ui.initialSpanDegrees,
                                               longitudeDelta: ui.initialSpanDegrees)))
                }
            }

            footer(ui: ui, shown: shown.count)
        }
        .overlay {
            if ui.isLoading {
                ProgressView().controlSize(.large)
            } else if !ui.hasData {
                ContentUnavailableView(L("map_no_data"), systemImage: "mappin.slash")
                    .background(.background)
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(L("map_open_stats"), systemImage: "chart.bar") { showStats = true }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button(L("map_open_filters"),
                       systemImage: ui.filters.isAtDefault ? "line.3.horizontal.decrease.circle"
                                                           : "line.3.horizontal.decrease.circle.fill") {
                    showFilters = true
                }
            }
        }
        .sheet(isPresented: $showFilters) {
            NavigationStack { MapFilterSheet(model: model, ui: ui) }
        }
        .sheet(isPresented: $showStats) {
            NavigationStack { MapStatsSheet(stats: ui.stats) }
        }
        .sheet(isPresented: Binding(get: { ui.selectedVenue != nil },
                                    set: { if !$0 { model.selectVenue(id: "") } })) {
            if let venue = ui.selectedVenue {
                NavigationStack { MapVenueSheet(venue: venue) }
                    .presentationDetents([.medium, .large])
            }
        }
        .overlay(alignment: .top) {
            if ui.errorCode != "none" {
                Text(L("map_init_error_generic"))
                    .font(.footnote).padding(8)
                    .background(.red.opacity(0.85)).foregroundStyle(.white)
                    .clipShape(Capsule())
            }
        }
    }

    private func footer(ui: MapScreenState, shown: Int) -> some View {
        VStack(spacing: 2) {
            Text(L("map_filter_count_template", Int(ui.venueCount), Int(ui.totalVenueCount)))
            if shown < Int(ui.venueCount) {
                Text(LI("map_pins_capped", shown)).foregroundStyle(.secondary)
            }
        }
        .font(.footnote)
        .padding(.horizontal, 12).padding(.vertical, 6)
        .background(.thinMaterial, in: Capsule())
        .padding(.bottom, 12)
    }

    private func coordinate(_ pin: MapPinUi) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: pin.latitude, longitude: pin.longitude)
    }

    private func accessibilityLabel(_ pin: MapPinUi) -> String {
        pin.boardCount > 1
            ? "\(pin.title), \(LI("map_pin_boards", Int(pin.boardCount)))"
            : pin.title
    }

    /// Only the pins inside the visible region, bounded by [maxAnnotations].
    private func visiblePins(_ pins: [MapPinUi]) -> [MapPinUi] {
        guard let region = visibleRegion else { return Array(pins.prefix(Self.maxAnnotations)) }
        let minLat = region.center.latitude - region.span.latitudeDelta / 2
        let maxLat = region.center.latitude + region.span.latitudeDelta / 2
        let minLng = region.center.longitude - region.span.longitudeDelta / 2
        let maxLng = region.center.longitude + region.span.longitudeDelta / 2
        var result: [MapPinUi] = []
        result.reserveCapacity(Self.maxAnnotations)
        for pin in pins where pin.latitude >= minLat && pin.latitude <= maxLat
            && pin.longitude >= minLng && pin.longitude <= maxLng {
            result.append(pin)
            if result.count == Self.maxAnnotations { break }
        }
        return result
    }
}

/// Colour is the board family; the count badge, not the colour, says
/// "several boards here".
private struct PinMarker: View {
    let pin: MapPinUi

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Circle()
                .fill(MapPalette.color(forBrandWire: pin.brandWire))
                .frame(width: 16, height: 16)
                .overlay(Circle().strokeBorder(.white, lineWidth: 1.5))
                .shadow(radius: 1)
            if pin.boardCount > 1 {
                Text("\(pin.boardCount)")
                    .font(.system(size: 9, weight: .bold)).monospacedDigit()
                    .foregroundStyle(.white)
                    .padding(.horizontal, 3).padding(.vertical, 1)
                    .background(Capsule().fill(.black.opacity(0.75)))
                    .offset(x: 8, y: -6)
            }
        }
        .frame(width: 32, height: 32, alignment: .center)
        .contentShape(Rectangle())
    }
}

enum MapPalette {
    static func color(forBrandWire wire: String) -> Color {
        switch wire {
        case "kilter": return ChartPalette.orange
        case "moonboard": return ChartPalette.blue
        case "multi": return ChartPalette.aqua
        default: return Color.secondary
        }
    }
}

// MARK: - Filters

private struct MapFilterSheet: View {
    let model: MapScreenModel
    let ui: MapScreenState
    @Environment(\.dismiss) private var dismiss

    private let accessCodes = ["public", "private", "members", "unknown"]
    private let adjustabilityCodes = ["adjustable", "fixed", "unknown"]
    private let ledCodes = ["led", "noLed", "unknown"]

    var body: some View {
        List {
            Section(L("board_selection_brand_label")) {
                Toggle(L("map_filter_show_all"),
                       isOn: Binding(get: { ui.filters.brands.isEmpty },
                                     set: { _ in model.selectAllBrands() }))
                ForEach(ui.brandOptions, id: \.code) { option in
                    Toggle(isOn: Binding(get: { option.isSelected },
                                         set: { _ in model.toggleBrand(brandWire: option.code) })) {
                        LabeledContent(option.label, value: "\(option.count)")
                    }
                }
            }

            Section(L("map_filter_section_membership")) {
                Toggle(L("map_filter_wellpass"),
                       isOn: Binding(get: { ui.filters.wellpassOnly },
                                     set: { _ in model.toggleWellpassOnly() }))
            }

            Section(L("map_filter_section_layout")) {
                Toggle(L("map_filter_show_original"),
                       isOn: Binding(get: { ui.filters.showOriginal },
                                     set: { _ in model.toggleShowOriginal() }))
                Toggle(L("map_filter_show_homewalls"),
                       isOn: Binding(get: { ui.filters.showHomewalls },
                                     set: { _ in model.toggleShowHomewalls() }))
                Toggle(isOn: Binding(get: { ui.filters.matchesMyBoard },
                                     set: { _ in model.toggleMatchesMyBoard() })) {
                    VStack(alignment: .leading) {
                        Text(L("map_filter_matches_my_board"))
                        if !ui.canFilterByMyBoard {
                            Text(L("map_filter_match_disabled"))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                .disabled(!ui.canFilterByMyBoard)
            }

            if !ui.moonVariantOptions.isEmpty {
                Section(L("map_filter_section_moon_variant")) {
                    ForEach(ui.moonVariantOptions, id: \.code) { option in
                        Toggle(isOn: Binding(get: { option.isSelected },
                                             set: { _ in model.toggleMoonLayoutId(layoutId: Int32(option.code) ?? 0) })) {
                            LabeledContent(option.label, value: "\(option.count)")
                        }
                    }
                }
                Section(L("map_filter_section_moon_type")) {
                    ForEach(accessCodes, id: \.self) { code in
                        Toggle(accessLabel(code),
                               isOn: Binding(get: { ui.filters.accessTypes.contains(code) },
                                             set: { _ in model.toggleAccessType(code: code) }))
                    }
                }
                Section(L("map_filter_section_moon_led")) {
                    ForEach(ledCodes, id: \.self) { code in
                        Toggle(ledLabel(code),
                               isOn: Binding(get: { ui.filters.moonLedStates.contains(code) },
                                             set: { _ in model.toggleMoonLedState(code: code) }))
                    }
                }
            }

            Section(L("map_filter_section_adjustability")) {
                ForEach(adjustabilityCodes, id: \.self) { code in
                    Toggle(adjustabilityLabel(code),
                           isOn: Binding(get: { ui.filters.adjustabilities.contains(code) },
                                         set: { _ in model.toggleAdjustability(code: code) }))
                }
            }

            if !ui.sizeOptions.isEmpty {
                Section(L("map_filter_section_size")) {
                    ForEach(ui.sizeOptions, id: \.code) { option in
                        Toggle(isOn: Binding(get: { option.isSelected },
                                             set: { _ in model.toggleSizeId(sizeId: Int32(option.code) ?? 0) })) {
                            LabeledContent(option.label, value: "\(option.count)")
                        }
                    }
                }
            }

            if !ui.countryOptions.isEmpty {
                Section(L("map_filter_section_country")) {
                    ForEach(ui.countryOptions, id: \.code) { option in
                        Toggle(isOn: Binding(get: { option.isSelected },
                                             set: { _ in model.toggleCountry(code: option.code) })) {
                            LabeledContent(countryName(option.code), value: "\(option.count)")
                        }
                    }
                }
            }
        }
        .navigationTitle(L("map_filter_sheet_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button(L("map_filter_reset")) { model.resetFilters() }
                    .disabled(ui.filters.isAtDefault)
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(L("map_offline_dialog_close")) { dismiss() }
            }
        }
        .safeAreaInset(edge: .bottom) {
            Text(L("map_filter_count_template", Int(ui.venueCount), Int(ui.totalVenueCount)))
                .font(.footnote).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity).padding(8).background(.bar)
        }
    }
}

private func accessLabel(_ code: String) -> String {
    switch code {
    case "public": return L("map_access_public")
    case "private": return L("map_access_private")
    case "members": return L("map_access_members")
    default: return L("map_marker_field_unknown")
    }
}

private func adjustabilityLabel(_ code: String) -> String {
    switch code {
    case "adjustable": return L("map_adjustability_adjustable")
    case "fixed": return L("map_adjustability_fixed")
    default: return L("map_marker_field_unknown")
    }
}

private func ledLabel(_ code: String) -> String {
    switch code {
    // A hardware feature name, not translatable text — Android prints it literally too.
    case "led": return "LED"
    case "noLed": return L("map_filter_moon_no_led")
    default: return L("map_marker_field_unknown")
    }
}

private func countryName(_ code: String) -> String {
    Locale.current.localizedString(forRegionCode: code) ?? code
}

// MARK: - Statistics

/// Every number here counts BOARDS over the whole dataset, not the filtered
/// pins, and one location can hold several boards — `map_stats_scope` says so.
private struct MapStatsSheet: View {
    let stats: MapStatsUi
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Text("\(stats.total)")
                        .font(.largeTitle.monospacedDigit().weight(.bold))
                        .foregroundStyle(Color.accentColor)
                    Text(L("map_stats_total_locations")).font(.headline)
                }
                HStack {
                    tile(L("map_stats_label_countries"), "\(stats.countryCount)")
                    tile(L("map_stats_label_public"), "\(stats.publicCount)")
                    tile(L("map_stats_label_adjustable"), "\(stats.adjustableCount)")
                }
            } footer: {
                Text(L("map_stats_scope")).font(.footnote)
            }

            barSection(L("map_stats_brand_distribution"), stats.byBrand.map { ($0.label, Int($0.count)) })
            barSection(L("map_stats_layout_distribution"),
                       stats.byLayout.map { ($0.label, Int($0.count)) })

            Section(L("map_stats_access_distribution")) {
                row(L("map_access_public"), Int(stats.publicCount), of: accessTotal)
                row(L("map_access_private"), Int(stats.privateCount), of: accessTotal)
                row(L("map_access_members"), Int(stats.membersCount), of: accessTotal)
                row(L("map_marker_field_unknown"), Int(stats.accessUnknownCount), of: accessTotal)
            }

            Section(L("map_stats_adjustability_distribution")) {
                row(L("map_adjustability_adjustable"), Int(stats.adjustableCount), of: adjustabilityTotal)
                row(L("map_adjustability_fixed"), Int(stats.fixedCount), of: adjustabilityTotal)
                row(L("map_marker_field_unknown"), Int(stats.adjUnknownCount), of: adjustabilityTotal)
            }

            barSection(L("map_stats_top_countries"),
                       stats.byCountry.prefix(15).map { (countryName($0.code), Int($0.count)) })
            barSection(L("map_stats_size_distribution"), stats.bySize.map { ($0.label, Int($0.count)) })
        }
        .navigationTitle(LI("map_stats_sheet_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(L("map_offline_dialog_close")) { dismiss() }
            }
        }
    }

    private var accessTotal: Int {
        Int(stats.publicCount) + Int(stats.privateCount)
            + Int(stats.membersCount) + Int(stats.accessUnknownCount)
    }

    private var adjustabilityTotal: Int {
        Int(stats.adjustableCount) + Int(stats.fixedCount) + Int(stats.adjUnknownCount)
    }

    @ViewBuilder
    private func barSection(_ title: String, _ items: some Collection<(String, Int)>) -> some View {
        if items.isEmpty || (items.map(\.1).max() ?? 0) == 0 {
            Section(title) {
                Text(L("map_stats_empty")).font(.footnote).foregroundStyle(.secondary)
            }
        } else {
            let maximum = items.map(\.1).max() ?? 1
            Section(title) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    bar(item.0, item.1, of: maximum)
                }
            }
        }
    }

    private func bar(_ label: String, _ count: Int, of maximum: Int) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(label).lineLimit(1)
                Spacer()
                Text("\(count)").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
            GeometryReader { geometry in
                Capsule()
                    .fill(Color.accentColor)
                    .frame(width: geometry.size.width * CGFloat(count) / CGFloat(max(maximum, 1)),
                           height: 8)
            }
            .frame(height: 8)
        }
        .accessibilityElement(children: .combine)
    }

    private func row(_ label: String, _ count: Int, of total: Int) -> some View {
        LabeledContent(label) {
            Text(total > 0 ? "\(count) · \(count * 100 / total)%" : "\(count)")
                .monospacedDigit()
        }
    }

    private func tile(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.title3.monospacedDigit())
            Text(title).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Venue detail

private struct MapVenueSheet: View {
    let venue: MapVenueUi
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            Section {
                if !venue.address.isEmpty { Text(venue.address) }
                if !venue.city.isEmpty || !venue.countryCode.isEmpty {
                    Text([venue.city, countryName(venue.countryCode)]
                        .filter { !$0.isEmpty }.joined(separator: " · "))
                }
                if let url = URL(string: venue.url), !venue.url.isEmpty {
                    Link(venue.url, destination: url)
                }
                if venue.boards.contains(where: { $0.acceptsWellpass }) {
                    Text(L("map_venue_wellpass_badge")).foregroundStyle(.secondary)
                }
                Link(L("map_marker_open_in_maps"), destination: mapsURL)
            }

            Section(L("map_venue_section_boards")) {
                ForEach(Array(venue.boards.enumerated()), id: \.offset) { _, board in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(boardTitle(board)).font(.headline)
                        LabeledContent(L("map_marker_access"), value: accessLabel(board.accessCode))
                        LabeledContent(L("map_marker_adjustability"), value: adjustability(board))
                        if !board.sizeLabel.isEmpty {
                            LabeledContent(L("map_filter_section_size"), value: board.sizeLabel)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
        .navigationTitle(venue.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(L("map_offline_dialog_close")) { dismiss() }
            }
        }
    }

    private var mapsURL: URL {
        // Apple Maps query link; no location permission and no network call here.
        URL(string: "http://maps.apple.com/?ll=\(venue.latitude),\(venue.longitude)")!
    }

    private func boardTitle(_ board: MapBoardUi) -> String {
        board.layoutLabel.isEmpty ? board.brandTitle : "\(board.brandTitle) · \(board.layoutLabel)"
    }

    private func adjustability(_ board: MapBoardUi) -> String {
        if board.adjustabilityCode == "fixed" && board.fixedAngle > 0 {
            return L("map_adjustability_fixed_angle", Int(board.fixedAngle))
        }
        return adjustabilityLabel(board.adjustabilityCode)
    }
}
