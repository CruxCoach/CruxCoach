import CruxCoachCore
import SwiftUI

/// Choose the physical board (brand → layout → size) from the installed catalogues,
/// or find it through the gym you climb at (Android `BoardSelectionDialog`:
/// "Find a gym near you" and the gym search).
struct BoardPickerView: View {
    let core: AppCore
    let brandWires: [String]
    let onPick: (BoardOption) -> Void
    /// Android's guided fix: explain the mismatch and offer the board the evidence points at first.
    var mismatch: BoardMismatchUi? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var showGymSearch = false
    @State private var gymSearchNearby = false

    var body: some View {
        List {
            if let mismatch {
                guidedSections(mismatch)
            }
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
            ForEach(otherBrandWires, id: \.self) { wire in
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

    /// The guided fix lists the expected family on top; it is not repeated below.
    private var otherBrandWires: [String] {
        guard let wire = mismatch?.prefillBrandWire else { return brandWires }
        return brandWires.filter { $0 != wire }
    }

    @ViewBuilder
    private func guidedSections(_ mismatch: BoardMismatchUi) -> some View {
        let options: [BoardOption] = guidedOptions(mismatch)
        Section { BoardMismatchExplanation(mismatch: mismatch) }
        Section {
            if options.isEmpty {
                Text(LI("browser_no_catalogue_hint")).font(.footnote)
            }
            ForEach(Array(options.enumerated()), id: \.offset) { _, option in
                guidedRow(option, preselected: isPrefill(option, mismatch))
            }
        } header: {
            Text(L("board_mismatch_choose_detail"))
        }
    }

    /// The prefilled board first (Android preselects it), then the family's other choices.
    private func guidedOptions(_ mismatch: BoardMismatchUi) -> [BoardOption] {
        let all: [BoardOption] = core.boardOptions(brandWire: mismatch.prefillBrandWire)
        let first: [BoardOption] = all.filter { isPrefill($0, mismatch) }
        let rest: [BoardOption] = all.filter { !isPrefill($0, mismatch) }
        return first + rest
    }

    private func isPrefill(_ option: BoardOption, _ mismatch: BoardMismatchUi) -> Bool {
        let layout: Int32 = mismatch.prefillLayoutId
        let size: Int32 = mismatch.prefillProductSizeId
        if layout == 0 { return false }
        return option.layoutId == layout && (size == 0 || option.productSizeId == size)
    }

    private func guidedRow(_ option: BoardOption, preselected: Bool) -> some View {
        Button { onPick(option) } label: {
            HStack {
                VStack(alignment: .leading) {
                    Text(option.layoutName).foregroundStyle(.primary)
                    if !option.sizeName.isEmpty {
                        Text(option.sizeName).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if preselected {
                    Image(systemName: "checkmark").foregroundStyle(Color.orange)
                }
            }
        }
        .accessibilityAddTraits(preselected ? .isSelected : [])
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
