import CruxCoachCore
import SwiftUI

/// Choose the physical board (brand → layout → size) from the installed catalogues.
struct BoardPickerView: View {
    let core: AppCore
    let brandWires: [String]
    let onPick: (BoardOption) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            if brandWires.isEmpty {
                Text(LI("browser_no_catalogue_hint"))
            }
            ForEach(brandWires, id: \.self) { wire in
                let options = core.boardOptions(brandWire: wire)
                if !options.isEmpty {
                    Section(options.first?.brandTitle ?? wire) {
                        ForEach(Array(options.enumerated()), id: \.offset) { _, option in
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
                }
            }
        }
        .navigationTitle(LI("browser_board"))
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("action_close")) { dismiss() } } }
    }
}
