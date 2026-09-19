import CruxCoachCore
import SwiftUI

/// Download/refresh of the signed board catalogues (Android: onboarding step 1 + "Sync board data").
struct CatalogueView: View {
    let core: AppCore
    let sync: Observed<CatalogueSyncState>
    let isOnboarding: Bool
    @State private var selected: Set<String> = []
    @State private var online: Observed<KotlinBoolean>

    init(core: AppCore, sync: Observed<CatalogueSyncState>, isOnboarding: Bool) {
        self.core = core; self.sync = sync; self.isOnboarding = isOnboarding
        _online = State(initialValue: Observed(core.connectivity.isOnline, initial: core.connectivity.isOnline.value as! KotlinBoolean))
    }

    private var brands: [BoardBrand] { BoardOptions.shared.downloadableBrands }

    var body: some View {
        List {
            if isOnboarding {
                Section { Text(LI("catalogue_intro")) }
            }
            if !online.value.boolValue {
                Section { Label(LI("catalogue_offline"), systemImage: "wifi.slash").foregroundStyle(.orange) }
            }
            Section(LI("catalogue_choose")) {
                ForEach(brands, id: \.wireValue) { brand in
                    BrandRow(brand: brand,
                             state: sync.value.brands.first { $0.brand == brand },
                             installed: sync.value.installedBrands.contains(brand),
                             isSelected: selected.contains(brand.wireValue)) {
                        if selected.contains(brand.wireValue) { selected.remove(brand.wireValue) } else { selected.insert(brand.wireValue) }
                    }
                }
            }
            Section {
                if sync.value.running {
                    Button(L("action_cancel"), role: .cancel) { core.catalogueSync.cancel() }
                } else {
                    Button(LI("catalogue_download")) {
                        core.catalogueSync.start(brands: brands.filter { selected.contains($0.wireValue) })
                    }
                    .disabled(selected.isEmpty)
                }
            } footer: {
                Text(LI("catalogue_footer"))
            }
        }
        .navigationTitle(L("board_sync_title"))
    }
}

private struct BrandRow: View {
    let brand: BoardBrand
    let state: BrandSyncState?
    let installed: Bool
    let isSelected: Bool
    let toggle: () -> Void

    var body: some View {
        Button(action: toggle) {
            HStack {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isSelected ? Color.accentColor : .secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(brand.displayNameForUi).foregroundStyle(.primary)
                    if let status { Text(status).font(.footnote).foregroundStyle(isFailure ? .red : .secondary) }
                    if let state, state.phase == .downloading, state.totalBytes > 0 {
                        ProgressView(value: Double(state.receivedBytes), total: Double(state.totalBytes))
                    }
                }
            }
        }
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private var isFailure: Bool { state?.phase == .failed }

    private var status: String? {
        guard let state else { return installed ? LI("catalogue_installed") : nil }
        switch state.phase {
        case .checking: return LI("catalogue_checking")
        case .downloading: return LI("catalogue_downloading")
        case .verifying: return LI("catalogue_verifying")
        case .importing: return LI("catalogue_importing")
        case .upToDate: return LI("catalogue_up_to_date")
        case .done: return LI("catalogue_done", Int(state.climbCount))
        case .failed: return LI("catalogue_failed", String(describing: state.failure))
        default: return installed ? LI("catalogue_installed") : nil
        }
    }
}

extension BoardBrand {
    var displayNameForUi: String {
        switch self {
        case .kilter: return "Kilter Board"
        case .moonboard: return "MoonBoard"
        case .tension: return "Tension Board"
        case .grasshopper: return "Grasshopper"
        case .decoy: return "Decoy"
        case .soill: return "So iLL"
        case .touchstone: return "Touchstone"
        case .quantum: return "Quantum"
        default: return wireValue
        }
    }
}
