import CruxCoachCore
import SwiftUI

/// Choose the physical board (brand → layout → size) from the installed catalogues.
struct BoardPickerView: View {
    let core: AppCore
    let installed: [BoardBrand]
    let onPick: (BoardOption) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            if installed.isEmpty { Text(LI("browser_no_catalogue_hint")) }
            ForEach(installed, id: \.wireValue) { brand in
                Section(brand.displayNameForUi) {
                    ForEach(Array(core.boardOptions(brand: brand).enumerated()), id: \.offset) { _, option in
                        Button { onPick(option) } label: {
                            VStack(alignment: .leading) {
                                Text(option.layoutName).foregroundStyle(.primary)
                                if !option.sizeName.isEmpty { Text(option.sizeName).font(.footnote).foregroundStyle(.secondary) }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(LI("browser_board"))
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("action_close")) { dismiss() } } }
    }
}
