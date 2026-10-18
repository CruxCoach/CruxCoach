import CruxCoachCore
import SwiftUI

/// The bottom panel (Android's map sheet): the places in view, nearest to the
/// centre — or to you, once located — first; a picked venue replaces the list
/// with its details.
struct MapPlacesPanel: View {
    let model: MapScreenModel
    let ui: MapScreenState
    let styles: MapMarkerStyleBook
    @Binding var expanded: Bool
    let canPickBoards: Bool
    let onBrowse: (BoardOption) -> Void
    let onChoose: (String) -> Void

    var body: some View {
        VStack(spacing: 0) {
            Button {
                withAnimation(.snappy) { expanded.toggle() }
            } label: {
                Capsule().fill(Color.secondary.opacity(0.5)).frame(width: 36, height: 5)
                    .frame(maxWidth: .infinity).padding(.vertical, 8)
            }
            .buttonStyle(.plain)

            ScrollView {
                if let venue = ui.selectedVenue {
                    MapVenueDetail(model: model, venue: venue, styles: styles, canPickBoards: canPickBoards,
                                   onBrowse: onBrowse, onChoose: onChoose)
                } else {
                    inViewList
                }
            }
            .frame(height: expanded ? 420 : 150)
        }
        .background(.regularMaterial, in: UnevenRoundedRectangle(topLeadingRadius: 18, topTrailingRadius: 18))
    }

    @ViewBuilder
    private var inViewList: some View {
        VStack(alignment: .leading, spacing: 6) {
            if !ui.hasViewport || ui.isLoading {
                EmptyView()
            } else if ui.inViewTotal == 0 {
                Text(ui.filters.isAtDefault ? L("map_in_view_empty_title") : L("map_in_view_filtered_title"))
                    .font(.headline)
                Text(L("map_in_view_empty_hint")).font(.footnote).foregroundStyle(.secondary)
                Button(L("map_in_view_show_nearest")) { model.showNearestBoards() }
                    .buttonStyle(.bordered)
            } else {
                Text(LP("map_in_view_title", Int(ui.inViewTotal))).font(.headline)
                Text(subtitle).font(.footnote).foregroundStyle(.secondary)
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(ui.inView, id: \.id) { row in
                        Button {
                            model.selectVenue(id: row.id)
                            expanded = true
                        } label: {
                            rowView(row)
                        }
                        .buttonStyle(.plain)
                        Divider()
                    }
                }
                if Int(ui.inViewTotal) > ui.inView.count {
                    Text(L("map_in_view_more", Int(ui.inViewTotal) - ui.inView.count))
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var subtitle: String {
        if Int(ui.inViewTotal) > ui.inView.count { return L("map_in_view_capped", ui.inView.count) }
        return ui.inViewFromUser ? L("map_in_view_nearest_you") : L("map_in_view_nearest")
    }

    private func rowView(_ row: MapVenueRowUi) -> some View {
        HStack(spacing: 12) {
            MapVenueMarker(brandWires: row.markerBrandWires, styles: styles, selected: false, diameter: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.name).font(.body.weight(.semibold)).foregroundStyle(.primary).lineLimit(1)
                let place = MapVenueText.locationLine(city: row.city, cityIsNearest: row.cityIsNearest,
                                                      nearestCityDe: row.nearestCityDe, countryCode: row.countryCode)
                Text([row.boardsText, place].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.footnote).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            if row.hasDistance {
                Text(GymSearchView.distanceText(row.distance)).font(.footnote).monospacedDigit()
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

enum MapVenueText {
    /// "München, Deutschland", or "near Bangor, United Kingdom" when the town
    /// was attached by distance (Android `venueLocationLine`).
    static func locationLine(city: String, cityIsNearest: Bool, nearestCityDe: String, countryCode: String) -> String {
        var parts: [String] = []
        if !city.isEmpty {
            if cityIsNearest {
                let german = Bundle.main.preferredLocalizations.first == "de"
                parts.append(L("map_venue_near_city", german && !nearestCityDe.isEmpty ? nearestCityDe : city))
            } else {
                parts.append(city)
            }
        }
        if countryCode.count == 2 { parts.append(mapCountryName(countryCode)) }
        return parts.joined(separator: ", ")
    }
}

/// Android `VenueDetails`: who and where, the actions people take from a map,
/// the boards with what the data says about them, the published hours, and
/// the contact rows. Rows without data are left out.
struct MapVenueDetail: View {
    let model: MapScreenModel
    let venue: MapVenueUi
    let styles: MapMarkerStyleBook
    let canPickBoards: Bool
    let onBrowse: (BoardOption) -> Void
    let onChoose: (String) -> Void
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                MapVenueMarker(brandWires: venue.markerBrandWires, styles: styles, selected: false, diameter: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(venue.name).font(.title3.weight(.semibold))
                    let line = MapVenueText.locationLine(city: venue.city, cityIsNearest: venue.cityIsNearest,
                                                         nearestCityDe: venue.nearestCityDe,
                                                         countryCode: venue.countryCode)
                    if !line.isEmpty { Text(line).font(.footnote).foregroundStyle(.secondary) }
                    if venue.boards.contains(where: { $0.acceptsWellpass }) {
                        Text(L("map_venue_wellpass_badge")).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Button { model.selectVenue(id: "") } label: {
                    Image(systemName: "xmark.circle.fill").font(.title2).foregroundStyle(.secondary)
                }
                .accessibilityLabel(L("map_venue_close"))
            }

            actions

            Divider()
            Text(L("map_venue_section_boards")).font(.subheadline).foregroundStyle(.secondary)
            ForEach(Array(venue.boards.enumerated()), id: \.offset) { _, board in
                boardCard(board)
            }

            if venue.hasHours { hours }
            contact
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 16)
    }

    // MARK: Actions

    private var actions: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if let route = URL(string: "http://maps.apple.com/?daddr=\(venue.latitude),\(venue.longitude)") {
                    Button { openURL(route) } label: {
                        Label(L("map_venue_route"), systemImage: "arrow.triangle.turn.up.right.diamond")
                    }
                    .buttonStyle(.borderedProminent)
                }
                if let site = URL(string: venue.websiteUrl), !venue.websiteUrl.isEmpty {
                    Button { openURL(site) } label: { Label(L("map_venue_website"), systemImage: "globe") }
                        .buttonStyle(.bordered)
                }
                if let tel = telURL {
                    Button { openURL(tel) } label: { Label(L("map_venue_call"), systemImage: "phone") }
                        .buttonStyle(.bordered)
                }
                if let insta = URL(string: venue.instagramUrl), !venue.instagramUrl.isEmpty {
                    Button { openURL(insta) } label: { Label(L("map_venue_instagram"), systemImage: "camera") }
                        .buttonStyle(.bordered)
                }
            }
            .font(.footnote)
        }
    }

    private var telURL: URL? {
        guard !venue.phoneDial.isEmpty else { return nil }
        let digits = venue.phoneDial.filter { $0 == "+" || $0.isNumber }
        return digits.isEmpty ? nil : URL(string: "tel:\(digits)")
    }

    // MARK: Boards

    private func boardCard(_ board: MapBoardUi) -> some View {
        HStack(spacing: 0) {
            Rectangle().fill(styles.color(board.brandWire)).frame(width: 5)
            VStack(alignment: .leading, spacing: 3) {
                Text(board.titleText.isEmpty ? L("map_marker_moonboard_variant_unknown") : board.titleText)
                    .font(.subheadline.weight(.semibold))
                ForEach(Array(facts(board).enumerated()), id: \.offset) { _, fact in
                    Text(fact).font(.footnote).foregroundStyle(.secondary)
                }
                if canPickBoards {
                    if board.actionCode == "browse", let option = board.browseBoard {
                        Button { onBrowse(option) } label: {
                            Label(L("map_marker_browse_climbs"), systemImage: "arrow.right")
                        }
                        .font(.footnote)
                        .accessibilityIdentifier("map_board_action_\(board.id)")
                    } else if board.actionCode == "choose" {
                        Button { onChoose(venue.id) } label: {
                            Label(L("map_board_choose"), systemImage: "arrow.right")
                        }
                        .font(.footnote)
                        .accessibilityIdentifier("map_board_action_\(board.id)")
                    }
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 10)
            Spacer(minLength: 0)
        }
        .background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    /// Android `boardFacts`: one summary line, then wall, frame and model lines.
    private func facts(_ board: MapBoardUi) -> [String] {
        var line: [String] = []
        if board.isMoonBoard {
            switch board.accessCode {
            case "public": line.append(L("map_board_commercial"))
            case "private": line.append(L("map_board_home_setup"))
            case "members": line.append(L("map_access_members"))
            default: break
            }
            if board.ledCode == "led" { line.append(L("map_board_led")) }
            if board.ledCode == "noLed" { line.append(L("map_board_no_led")) }
            if board.moonAngle > 0 { line.append(L("map_board_angle", Int(board.moonAngle))) }
            let text = line.joined(separator: " · ")
            return text.isEmpty ? [] : [text]
        }
        if !board.sizeText.isEmpty { line.append(board.sizeText) }
        switch board.angleCode {
        case "range":
            line.append(board.angleStep > 0
                        ? L("map_wall_adjustable_range_step", Int(board.angleMin), Int(board.angleMax), Int(board.angleStep))
                        : L("map_wall_adjustable_range", Int(board.angleMin), Int(board.angleMax)))
        case "set": line.append(L("map_wall_adjustable_set", Int(board.angleValue)))
        case "adjustable": line.append(L("map_wall_adjustable"))
        case "fixedAt": line.append(L("map_wall_fixed_at", Int(board.angleValue)))
        case "fixed": line.append(L("map_wall_fixed"))
        default: break
        }
        switch board.accessCode {
        case "public": line.append(L("map_access_public"))
        case "private": line.append(L("map_access_private"))
        case "members": line.append(L("map_access_members"))
        default: break
        }
        var lines: [String] = []
        let summary = line.joined(separator: " · ")
        if !summary.isEmpty { lines.append(summary) }
        if !board.wallName.isEmpty { lines.append(L("map_wall_name", board.wallName)) }
        if !board.frameMaker.isEmpty { lines.append(L("map_board_frame", board.frameMaker)) }
        lines.append(contentsOf: board.models)
        return lines
    }

    // MARK: Hours and contact

    @ViewBuilder
    private var hours: some View {
        let rows = model.hoursRows(venueId: venue.id, dayNames: Self.mondayFirstDayNames(),
                                   closed: L("map_hours_closed"), allDay: L("map_hours_all_day"),
                                   nextDay: L("map_hours_next_day"))
        if !rows.isEmpty {
            Divider()
            Text(L("map_hours_title")).font(.subheadline).foregroundStyle(.secondary)
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack(alignment: .top) {
                    Text(row.days).frame(width: 76, alignment: .leading)
                    Text(row.ranges).foregroundStyle(row.closed ? Color.secondary : Color.primary)
                }
                .font(.footnote)
            }
            Text(L("map_hours_note")).font(.caption).foregroundStyle(.secondary)
            if let source = URL(string: venue.hoursSourceUrl), !venue.hoursSourceUrl.isEmpty {
                Button(L("map_hours_source")) { openURL(source) }.font(.footnote)
            }
        }
    }

    /// Short weekday names in the user's language, Monday first like the data.
    static func mondayFirstDayNames() -> [String] {
        let symbols = Calendar.current.shortStandaloneWeekdaySymbols // Sunday first
        guard symbols.count == 7 else { return symbols }
        return Array(symbols[1...6]) + [symbols[0]]
    }

    @ViewBuilder
    private var contact: some View {
        if !venue.address.isEmpty || !venue.phoneText.isEmpty || !venue.email.isEmpty {
            Divider()
            Text(L("map_venue_section_contact")).font(.subheadline).foregroundStyle(.secondary)
            if !venue.address.isEmpty {
                Label(venue.address, systemImage: "mappin.and.ellipse").font(.footnote)
            }
            if !venue.phoneText.isEmpty, let tel = telURL {
                Button { openURL(tel) } label: { Label(venue.phoneText, systemImage: "phone") }.font(.footnote)
            }
            if !venue.email.isEmpty, let mail = URL(string: "mailto:\(venue.email)") {
                Button { openURL(mail) } label: { Label(venue.email, systemImage: "envelope") }.font(.footnote)
            }
        }
    }
}
