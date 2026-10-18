import CruxCoachCore
import MapKit
import SwiftUI

/// Board map (Android 0.2.4 `MapScreen`): MapKit over the offline dataset (the
/// bundled cruxcoach-pages snapshot merged with the synced `locations` chunk).
///
/// One marker per venue in its family colour and letter, a pie for several
/// families; gym names from city zoom; a floating search bar; the family list
/// with counts ("All boards") and the filter count; a panel listing the places
/// in view, nearest to the centre (or to you, once located) first; the venue
/// detail. Rotation and tilt are off. The map reopens where it was left; a
/// first visit opens on the country of the phone's timezone.
///
/// "Browse climbs" and "Choose this board" need the board browser: they show
/// only when the host passes [core] and [onPickBoard].
///
/// The model is created by the caller's closure inside `.task`, never in `init`:
/// SwiftUI re-runs `init` on every render pass and would leak a Kotlin presenter.
struct MapView: View {
    let makeModel: () -> MapScreenModel
    var core: AppCore? = nil
    var onPickBoard: ((BoardOption) -> Void)? = nil
    /// Brands whose catalogue is installed: the boards "Choose this board" can switch to.
    var boardBrandWires: [String] = []

    @State private var host: ScreenHost<MapScreenModel, MapScreenState>?
    @State private var camera: MapCameraPosition = .automatic
    @State private var visibleRegion: MKCoordinateRegion?
    @State private var startApplied = false
    @State private var showFilters = false
    @State private var showStats = false
    @State private var showLegend = false
    @State private var query = ""
    @State private var panelExpanded = false
    @State private var locator: LocationProvider?
    @State private var locating = false
    @State private var locateFailure: LocateOutcome?
    @State private var chooseVenueId: String?

    /// SwiftUI's `Map` draws every annotation it is given. The dataset holds
    /// ~2 900 venues, so only what is on screen is handed over, bounded.
    private static let maxAnnotations = 400
    /// Gym names appear from about city zoom (Android: zoom 9).
    private static let namesBelowSpan = 0.8

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
        let styles = MapMarkerStyleBook(ui.markerStyles)
        let showNames = (visibleRegion?.span.latitudeDelta ?? 180) < Self.namesBelowSpan
        Map(position: $camera, interactionModes: [.pan, .zoom]) {
            ForEach(shown, id: \.id) { pin in
                Annotation(pin.title, coordinate: CLLocationCoordinate2D(latitude: pin.latitude, longitude: pin.longitude),
                           anchor: .center) {
                    MapVenueMarker(brandWires: pin.markerBrandWires, styles: styles,
                                   selected: pin.id == ui.selectedVenue?.id)
                        .onTapGesture {
                            model.selectVenue(id: pin.id)
                            panelExpanded = true
                        }
                        .accessibilityLabel(accessibilityLabel(pin))
                        .accessibilityAddTraits(.isButton)
                }
            }
            if ui.hasUserPosition {
                Annotation(L("locate_me"), coordinate: CLLocationCoordinate2D(latitude: ui.userLatitude,
                                                                             longitude: ui.userLongitude),
                           anchor: .center) {
                    Circle().fill(Color.blue)
                        .frame(width: 14, height: 14)
                        .overlay(Circle().strokeBorder(.white, lineWidth: 2.5))
                        .shadow(radius: 2)
                }
            }
        }
        .annotationTitles(showNames ? .automatic : .hidden)
        .mapStyle(.standard(pointsOfInterest: .excludingAll))
        .mapControls { MapScaleView() }
        .onMapCameraChange(frequency: .onEnd) { context in
            visibleRegion = context.region
            report(context.region, model: model)
        }
        .onChange(of: ui.hasStartCamera, initial: true) { _, ready in
            guard ready, !startApplied else { return }
            startApplied = true
            camera = .region(region(ui.startCamera))
        }
        .onChange(of: ui.cameraRequestId) { _, _ in
            withAnimation { camera = .region(region(ui.cameraRequest)) }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            topBar(model: model, ui: ui, styles: styles)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(alignment: .trailing, spacing: 8) {
                locateButton(model: model)
                MapPlacesPanel(model: model, ui: ui, styles: styles, expanded: $panelExpanded,
                               canPickBoards: core != nil && onPickBoard != nil,
                               onBrowse: { option in onPickBoard?(option) },
                               onChoose: { venueId in chooseVenueId = venueId })
            }
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
        }
        .sheet(isPresented: $showFilters) {
            NavigationStack { MapFilterSheet(model: model, ui: ui) }
        }
        .sheet(isPresented: $showStats) {
            NavigationStack { MapStatsSheet(stats: ui.stats) }
        }
        .sheet(isPresented: $showLegend) {
            NavigationStack { MapLegendSheet(model: model, ui: ui, styles: styles) }
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: Binding(get: { chooseVenueId != nil }, set: { if !$0 { chooseVenueId = nil } })) {
            if let core, let onPickBoard, let venueId = chooseVenueId {
                NavigationStack {
                    GymSearchView(core: core, availableBrandWires: boardBrandWires,
                                  startNearby: false,
                                  onPick: { option in
                                      chooseVenueId = nil
                                      onPickBoard(option)
                                  },
                                  onPickDirectly: { chooseVenueId = nil },
                                  mapModel: model, startVenueId: venueId)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(L("action_close")) { chooseVenueId = nil }
                        }
                    }
                }
            }
        }
    }

    // MARK: Top bar: search, family list, filters

    @ViewBuilder
    private func topBar(model: MapScreenModel, ui: MapScreenState, styles: MapMarkerStyleBook) -> some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField(L("map_search_hint"), text: $query)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                    .onChange(of: query) { _, text in model.search(query: text) }
                if !query.isEmpty {
                    Button {
                        query = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                    }
                    .accessibilityLabel(L("map_search_clear"))
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(.regularMaterial, in: Capsule())

            if query.trimmingCharacters(in: .whitespaces).count >= 2 {
                searchResults(model: model, ui: ui, styles: styles)
            } else {
                HStack(spacing: 8) {
                    Button { showLegend = true } label: {
                        Label(brandChipTitle(ui), systemImage: "circle.grid.2x2")
                    }
                    .buttonStyle(.bordered)
                    Button { showFilters = true } label: {
                        Label(ui.filterCount > 0 ? L("map_filters_chip_active", Int(ui.filterCount)) : L("map_filters_chip"),
                              systemImage: ui.filterCount > 0 ? "line.3.horizontal.decrease.circle.fill"
                                                              : "line.3.horizontal.decrease.circle")
                    }
                    .buttonStyle(.bordered)
                    Spacer()
                }
                .font(.footnote)
                .tint(Color.primary)
            }
            if let failure = locateFailure, let text = failure.failureText {
                HStack {
                    Text(text).font(.footnote)
                    Spacer()
                    if failure.opensSettings {
                        Button(L("locate_me_open_settings")) { LocationSettings.open() }.font(.footnote)
                    }
                    Button { locateFailure = nil } label: { Image(systemName: "xmark") }
                        .accessibilityLabel(L("action_close"))
                }
                .padding(10)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 6)
    }

    private func brandChipTitle(_ ui: MapScreenState) -> String {
        let chosen = ui.brandOptions.filter { $0.isSelected }.map { $0.label }
        return chosen.isEmpty ? L("map_brand_all") : chosen.joined(separator: ", ")
    }

    @ViewBuilder
    private func searchResults(model: MapScreenModel, ui: MapScreenState, styles: MapMarkerStyleBook) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if ui.searchResults.isEmpty {
                Text(L("map_search_no_results")).font(.footnote).foregroundStyle(.secondary).padding(12)
            } else {
                ForEach(ui.searchResults, id: \.index) { result in
                    Button {
                        model.pickSearchResult(index: result.index)
                        if result.kind == "venue" { panelExpanded = true }
                        query = ""
                        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder),
                                                        to: nil, from: nil, for: nil)
                    } label: {
                        HStack(spacing: 10) {
                            if result.kind == "venue" {
                                MapVenueMarker(brandWires: result.markerBrandWires, styles: styles, selected: false,
                                               diameter: 18)
                            } else {
                                Image(systemName: "building.2").frame(width: 22, height: 22).foregroundStyle(.secondary)
                            }
                            VStack(alignment: .leading) {
                                Text(result.title).foregroundStyle(.primary)
                                let sub = [result.subtitle, result.countryCode.isEmpty ? "" : mapCountryName(result.countryCode)]
                                    .filter { !$0.isEmpty }.joined(separator: ", ")
                                if !sub.isEmpty { Text(sub).font(.footnote).foregroundStyle(.secondary) }
                            }
                            Spacer()
                        }
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    Divider()
                }
            }
        }
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
    }

    // MARK: My location

    private func locateButton(model: MapScreenModel) -> some View {
        Button {
            locateFailure = nil
            let provider = locator ?? LocationProvider()
            locator = provider
            guard !provider.isLocating else { return }
            locating = true
            provider.locate { outcome in
                locating = false
                if case let .found(latitude, longitude) = outcome {
                    model.showMyLocation(latitude: latitude, longitude: longitude)
                } else {
                    locateFailure = outcome
                }
            }
        } label: {
            Group {
                if locating { ProgressView() } else { Image(systemName: "location.fill") }
            }
            .frame(width: 44, height: 44)
            .background(.regularMaterial, in: Circle())
        }
        .accessibilityLabel(locating ? L("locate_me_finding") : L("locate_me"))
        .padding(.trailing, 12)
    }

    // MARK: Camera

    private func region(_ c: MapCameraUi) -> MKCoordinateRegion {
        MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: c.latitude, longitude: c.longitude),
                           span: MKCoordinateSpan(latitudeDelta: c.latitudeDelta, longitudeDelta: c.longitudeDelta))
    }

    private func report(_ region: MKCoordinateRegion, model: MapScreenModel) {
        let lat = region.center.latitude
        let lng = region.center.longitude
        let south = max(-90, lat - region.span.latitudeDelta / 2)
        let north = min(90, lat + region.span.latitudeDelta / 2)
        var west = lng - region.span.longitudeDelta / 2
        var east = lng + region.span.longitudeDelta / 2
        if region.span.longitudeDelta >= 360 {
            west = -180
            east = 180
        } else {
            if west < -180 { west += 360 }
            if east > 180 { east -= 360 }
        }
        model.setViewport(south: south, west: west, north: north, east: east,
                          centerLatitude: lat, centerLongitude: lng)
        // Only after the start view: the automatic first camera must not overwrite the memory.
        if startApplied {
            model.rememberCamera(latitude: lat, longitude: lng,
                                 latitudeDelta: region.span.latitudeDelta, longitudeDelta: region.span.longitudeDelta)
        }
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
            && inLongitude(pin.longitude, minLng, maxLng) {
            result.append(pin)
            if result.count == Self.maxAnnotations { break }
        }
        return result
    }

    /// A region across the antimeridian reports longitudes beyond ±180.
    private func inLongitude(_ lng: Double, _ minLng: Double, _ maxLng: Double) -> Bool {
        (lng >= minLng && lng <= maxLng) || (lng + 360 >= minLng && lng + 360 <= maxLng)
            || (lng - 360 >= minLng && lng - 360 <= maxLng)
    }
}

/// The country's name in the user's language.
func mapCountryName(_ code: String) -> String {
    Locale.current.localizedString(forRegionCode: code) ?? code
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

