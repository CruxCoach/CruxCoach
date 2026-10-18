import CruxCoachCore
import SwiftUI

/// "Find your gym" (Android `GymBoardSearchSheet`): offline search over the
/// gym data, "Gyms near me", then the board in front of you. One board at the
/// gym is applied straight away; several are listed. The pick goes to
/// [onPick] as the manual board picker's `BoardOption`, so every host applies
/// it the same way.
///
/// The location prompt appears only after a tap on "Gyms near me" (or when the
/// host opened this from "Find a gym near you"); once allowed, the nearby gyms
/// show on every later open. The position is handed to the Kotlin ranking and
/// never kept.
struct GymSearchView: View {
    let core: AppCore
    /// Brands this host can switch to. Options of other brands are listed but not offered.
    let availableBrandWires: [String]
    let startNearby: Bool
    let onPick: (BoardOption) -> Void
    /// "Pick your board directly": back to the manual picker.
    let onPickDirectly: () -> Void
    /// The board map's "Choose this board": the open map and the place to show right away.
    var mapModel: MapScreenModel? = nil
    var startVenueId: String = ""

    @State private var host: ScreenHost<GymSearchScreenModel, GymSearchScreenState>?
    @State private var query = ""
    @State private var locator: LocationProvider?
    @State private var locating = false
    @State private var failure: LocateOutcome?
    @State private var nearbyAsked = false
    @State private var appliedKey = ""

    var body: some View {
        Group {
            if let host {
                content(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("feat007_gym_search_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard host == nil else { return }
            let model: GymSearchScreenModel
            if let mapModel, !startVenueId.isEmpty {
                model = core.makeGymSearchScreenForVenue(map: mapModel, venueId: startVenueId)
            } else {
                model = core.makeGymSearchScreen()
            }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            nearbyAsked = startNearby
            // A place handed over by the map shows its boards; no lookup overrides it.
            if startVenueId.isEmpty && (startNearby || LocationProvider.hasPermission) {
                locate(model: model)
            }
        }
        .onChange(of: host?.state.selectionKey ?? "") { _, key in
            // A gym with exactly one board needs no confirmation tap.
            guard let ui = host?.state, !key.isEmpty, key != appliedKey,
                  ui.options.count == 1, let only = ui.options.first,
                  availableBrandWires.contains(only.brandWire) else { return }
            appliedKey = key
            onPick(only.board)
        }
    }

    @ViewBuilder
    private func content(model: GymSearchScreenModel, ui: GymSearchScreenState) -> some View {
        List {
            Section {
                Button {
                    nearbyAsked = true
                    failure = nil
                    locate(model: model)
                } label: {
                    HStack {
                        if locating {
                            ProgressView()
                        } else {
                            Image(systemName: "location")
                        }
                        Text(locating ? L("locate_me_finding") : L("gym_nearby_button"))
                    }
                }
                .disabled(locating)
                .accessibilityIdentifier("gym_nearby")
                if let failure, let text = failure.failureText {
                    Text(text).font(.footnote).foregroundStyle(.red)
                    if failure.opensSettings {
                        Button(L("locate_me_open_settings")) { LocationSettings.open() }
                    }
                }
            } footer: {
                Text(L("locate_me_private"))
            }

            Section {
                TextField(L("feat007_gym_search_field"), text: $query)
                    .textInputAutocapitalization(.words)
                    .autocorrectionDisabled()
                    .onChange(of: query) { _, text in
                        // The model is the source of truth; skip echoes of its own reset.
                        if text != model.currentState.query { model.setQuery(text: text) }
                    }
            }

            if ui.hasSelection, let gym = ui.selected.first {
                selection(model: model, ui: ui, gym: gym)
            } else if ui.searching {
                Section { HStack { Spacer(); ProgressView(); Spacer() } }
            } else if !ui.results.isEmpty {
                // Real results always win over the "no data yet" text.
                Section {
                    ForEach(ui.results, id: \.id) { gym in
                        Button { model.selectResult(id: gym.id) } label: {
                            gymRow(gym, chevron: true)
                        }
                        .accessibilityIdentifier("gym_result_\(gym.id)")
                    }
                } header: {
                    if ui.nearby { Text(L("gym_nearby_title")) }
                }
            } else if ui.nearby {
                Section { Text(L("gym_nearby_none")) }
            } else if !ui.enabled {
                Section { Text(L("feat007_gym_loading")) }
            } else if query.trimmingCharacters(in: .whitespaces).count < 2 {
                Section { Text(L("feat007_gym_search_min")).font(.footnote).foregroundStyle(.secondary) }
            } else {
                Section { Text(L("feat007_gym_search_none")) }
            }

            Section {
                Button(L("settings_board_find_via_gym_fallback")) { onPickDirectly() }
            }
        }
    }

    @ViewBuilder
    private func selection(model: GymSearchScreenModel, ui: GymSearchScreenState, gym: GymResultUi) -> some View {
        Section {
            gymRow(gym, chevron: false)
        }
        Section {
            if ui.options.isEmpty {
                Text(L("feat007_gym_no_walls"))
            } else {
                ForEach(ui.options, id: \.index) { option in
                    let available = availableBrandWires.contains(option.brandWire)
                    Button { onPick(option.board) } label: {
                        HStack {
                            Image(systemName: "square.grid.3x3")
                                .foregroundStyle(available ? Color.accentColor : Color.secondary)
                            VStack(alignment: .leading) {
                                Text(option.label).foregroundStyle(.primary)
                                if !available {
                                    Text(LI("gym_board_not_installed")).font(.footnote).foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            if available {
                                Image(systemName: "chevron.right").foregroundStyle(.secondary)
                            }
                        }
                    }
                    .disabled(!available)
                }
            }
        } header: {
            if !ui.optionsPromptCode.isEmpty { Text(promptText(ui.optionsPromptCode)) }
        }
        Section {
            Button(L("feat007_back")) {
                appliedKey = ""
                model.clearSelection()
            }
        }
    }

    private func gymRow(_ gym: GymResultUi, chevron: Bool) -> some View {
        HStack {
            Image(systemName: "mappin.and.ellipse").foregroundStyle(.secondary)
            VStack(alignment: .leading) {
                Text(gym.name).bold().foregroundStyle(.primary)
                let sub = subtitle(gym)
                if !sub.isEmpty {
                    Text(sub).font(.footnote).foregroundStyle(.secondary)
                }
            }
            Spacer()
            if chevron {
                Image(systemName: "chevron.right").foregroundStyle(.secondary)
            }
        }
    }

    /// "Kilter, MoonBoard · Munich, DE · 350 m" — empty parts left out.
    private func subtitle(_ gym: GymResultUi) -> String {
        var parts = [gym.boardsText, gym.placeText]
        if gym.hasDistance { parts.append(Self.distanceText(gym.distance)) }
        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private func promptText(_ code: String) -> String {
        switch code {
        case "one": return L("feat007_gym_one")
        case "categories": return L("feat007_gym_categories")
        default: return L("feat007_gym_variant")
        }
    }

    /// Android `distanceLabel`: the number in the user's locale with the m / km string.
    static func distanceText(_ distance: GymDistanceUi) -> String {
        let format = NumberFormatter()
        format.numberStyle = .decimal
        format.minimumFractionDigits = Int(distance.fractionDigits)
        format.maximumFractionDigits = Int(distance.fractionDigits)
        let number = format.string(from: NSNumber(value: distance.value)) ?? String(distance.value)
        return L(distance.unitCode == "km" ? "distance_km" : "distance_m", number)
    }

    private func locate(model: GymSearchScreenModel) {
        let provider = locator ?? LocationProvider()
        locator = provider
        guard !provider.isLocating else { return }
        locating = true
        provider.locate { outcome in
            locating = false
            switch outcome {
            case let .found(latitude, longitude):
                // A silent lookup on open never overrides typed text.
                if nearbyAsked || model.currentState.query.trimmingCharacters(in: .whitespaces).isEmpty {
                    query = ""
                    model.showNearby(latitude: latitude, longitude: longitude)
                }
            default:
                if nearbyAsked { failure = outcome }
            }
        }
    }
}
