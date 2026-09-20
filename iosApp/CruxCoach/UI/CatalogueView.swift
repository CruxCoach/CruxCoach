import CruxCoachCore
import SwiftUI

/// Download and refresh of the signed board catalogues (Android: onboarding step 1 and "Sync board data").
struct CatalogueView: View {
    let core: AppCore
    let sync: ScreenHost<SyncScreenModel, SyncScreenState>
    let isOnboarding: Bool
    @State private var selected: Set<String> = []
    @Environment(\.dismiss) private var dismiss

    private var state: SyncScreenState { sync.state }

    var body: some View {
        List {
            if isOnboarding {
                Section { Text(LI("catalogue_intro")) }
            }
            if !state.online {
                Section {
                    Label(LI("catalogue_offline"), systemImage: "wifi.slash").foregroundStyle(.orange)
                }
            }
            Section(LI("catalogue_choose")) {
                ForEach(state.rows, id: \.brandWire) { row in
                    BrandRow(row: row, isSelected: selected.contains(row.brandWire)) {
                        if selected.contains(row.brandWire) {
                            selected.remove(row.brandWire)
                        } else {
                            selected.insert(row.brandWire)
                        }
                    }
                }
            }
            Section {
                if state.running {
                    Button(L("action_cancel"), role: .cancel) { sync.model.cancel() }
                } else {
                    Button(LI("catalogue_download")) {
                        sync.model.start(brandWires: Array(selected))
                    }
                    .disabled(selected.isEmpty)
                }
            } footer: {
                Text(LI("catalogue_footer"))
            }
        }
        .navigationTitle(L("board_sync_title"))
        .toolbar {
            if !isOnboarding {
                ToolbarItem(placement: .cancellationAction) { Button(L("action_close")) { dismiss() } }
            }
        }
    }
}

private struct BrandRow: View {
    let row: SyncBrandRow
    let isSelected: Bool
    let toggle: () -> Void

    var body: some View {
        Button(action: toggle) {
            HStack {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isSelected ? Color.accentColor : .secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.title).foregroundStyle(.primary)
                    if let status {
                        Text(status).font(.footnote).foregroundStyle(row.phase == "failed" ? .red : .secondary)
                    }
                    if row.phase == "downloading", row.totalBytes > 0 {
                        ProgressView(value: Double(row.receivedBytes), total: Double(row.totalBytes))
                    }
                }
            }
        }
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private var status: String? {
        switch row.phase {
        case "checking": return LI("catalogue_checking")
        case "downloading": return LI("catalogue_downloading")
        case "verifying": return LI("catalogue_verifying")
        case "importing": return LI("catalogue_importing")
        case "upToDate": return LI("catalogue_up_to_date")
        case "done": return LI("catalogue_done", Int(row.climbCount))
        case "failed": return LI("catalogue_failed", row.failure)
        default: return row.installed ? LI("catalogue_installed") : nil
        }
    }
}
