import CruxCoachCore
import SwiftUI

/// Choose the physical board (brand → layout → size) from the installed catalogues,
/// or find it through the gym you climb at (Android `BoardSelectionDialog`:
/// "Find a gym near you" and the gym search).
struct BoardPickerView: View {
    let core: AppCore
    let brandWires: [String]
    let onPick: (BoardOption) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var showGymSearch = false
    @State private var gymSearchNearby = false

    var body: some View {
        List {
            Section {
                Button {
                    gymSearchNearby = true
                    showGymSearch = true
                } label: {
                    Label(L("board_find_nearby_gym"), systemImage: "location")
                }
                .accessibilityIdentifier("board_find_nearby_gym")
                Button {
                    gymSearchNearby = false
                    showGymSearch = true
                } label: {
                    Label(L("feat007_gym_search_title"), systemImage: "magnifyingglass")
                }
            }
            if brandWires.isEmpty {
                Text(LI("browser_no_catalogue_hint"))
            }
            ForEach(brandWires, id: \.self) { wire in
                brandSection(wire)
            }
        }
        .navigationTitle(LI("browser_board"))
        .navigationDestination(isPresented: $showGymSearch) {
            GymSearchView(core: core, availableBrandWires: brandWires, startNearby: gymSearchNearby,
                          onPick: onPick, onPickDirectly: { showGymSearch = false })
        }
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("action_close")) { dismiss() } } }
    }

    @ViewBuilder
    private func brandSection(_ wire: String) -> some View {
        let options: [BoardOption] = core.boardOptions(brandWire: wire)
        if !options.isEmpty {
            Section {
                ForEach(Array(options.enumerated()), id: \.offset) { _, option in
                    optionRow(option)
                }
            } header: {
                Text(options.first?.brandTitle ?? wire)
            } footer: {
                // Android's Quantum picker: the five fixed models, with the schematic note.
                if wire == "quantum" {
                    Text(L("board_selection_quantum_schematic_hint"))
                }
            }
        }
    }

    private func optionRow(_ option: BoardOption) -> some View {
        Button { onPick(option) } label: {
            VStack(alignment: .leading) {
                Text(option.layoutName).foregroundStyle(.primary)
                if !option.sizeName.isEmpty {
                    Text(option.sizeName).font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
    }
}
