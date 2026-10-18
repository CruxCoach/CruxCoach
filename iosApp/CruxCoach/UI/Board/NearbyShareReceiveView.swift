import CruxCoachCore
import SwiftUI
import UIKit
import VisionKit

/// Android's offline Wi-Fi share, receiving side, for board catalogues only (an iPhone cannot
/// install the APK). The other phone runs the share; this iPhone joins its Wi-Fi, scans or
/// pastes its address, chooses the boards and receives them. Texts are Android's
/// (`DiscoveredShareDialog`, `SyncStatusBannerSlot`); only the iPhone-specific steps are `LI`.
struct NearbyShareReceiveView: View {
    let core: AppCore
    var initialLink: String = ""
    var showsClose: Bool = false
    @State private var host: ScreenHost<ReceiveShareScreenModel, ReceiveShareState>?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if let host {
                NearbyShareReceiveContent(model: host.model, ui: host.state, onClose: { dismiss() })
            } else {
                ProgressView()
            }
        }
        .navigationTitle(LI("nearby_receive_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if showsClose {
                ToolbarItem(placement: .cancellationAction) { Button(L("action_close")) { dismiss() } }
            }
        }
        .task {
            guard host == nil else { return }
            let model = core.makeReceiveShareScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            if !initialLink.isEmpty { model.submitLink(text: initialLink) }
        }
    }
}

/// The sender landing page's "open in app" link (`cruxcoach://offline-share?…`) opens the
/// receive screen. A modifier, so the browser carries it in one line.
struct NearbyShareLinkHandler: ViewModifier {
    let core: AppCore
    @State private var link: NearbyShareLink?

    func body(content: Content) -> some View {
        content
            .onOpenURL { url in
                let text: String = url.absoluteString
                if core.isNearbyShareLink(url: text) { link = NearbyShareLink(text: text) }
            }
            .sheet(item: $link) { item in
                NavigationStack {
                    NearbyShareReceiveView(core: core, initialLink: item.text, showsClose: true)
                }
            }
    }
}

struct NearbyShareLink: Identifiable {
    let text: String
    var id: String { text }
}

private struct NearbyShareReceiveContent: View {
    let model: ReceiveShareScreenModel
    let ui: ReceiveShareState
    let onClose: () -> Void
    @State private var scanning: Bool = false

    var body: some View {
        List {
            switch ui.phase {
            case "probing":
                NearbyProbingSection()
            case "joinWifi":
                NearbyJoinWifiSection(model: model, ui: ui)
            case "offer":
                NearbyOfferSections(model: model, ui: ui)
            case "running":
                NearbyProgressSection(model: model, ui: ui)
            case "done":
                NearbyDoneSection(ui: ui, onClose: onClose)
            case "failed":
                NearbyFailedSection(model: model, ui: ui)
            default:
                NearbyEnterSection(model: model, ui: ui, onScan: { scanning = true })
            }
        }
        .sheet(isPresented: $scanning) {
            NearbyShareScannerSheet(onCode: { code in
                scanning = false
                model.submitLink(text: code)
            }, onCancel: { scanning = false })
        }
    }
}

private struct NearbyEnterSection: View {
    let model: ReceiveShareScreenModel
    let ui: ReceiveShareState
    let onScan: () -> Void
    @State private var text: String = ""

    var body: some View {
        Section {
            Text(LI("nearby_receive_intro"))
            Button(LI("nearby_receive_scan"), systemImage: "qrcode.viewfinder", action: onScan)
                .accessibilityIdentifier("nearby_receive_scan")
        } footer: {
            Text(LI("nearby_receive_permission_hint"))
        }
        Section(LI("nearby_receive_address")) {
            TextField("http://192.168.…", text: $text)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button(LI("nearby_receive_continue")) { model.submitLink(text: text) }
                .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            if ui.errorCode == "invalidLink" {
                Text(LI("nearby_receive_invalid_link")).font(.footnote).foregroundStyle(.red)
            }
        }
    }
}

private struct NearbyProbingSection: View {
    var body: some View {
        Section {
            HStack(spacing: 12) {
                ProgressView()
                Text(L("board_sync_step_discover_local_share"))
            }
        }
    }
}

private struct NearbyJoinWifiSection: View {
    let model: ReceiveShareScreenModel
    let ui: ReceiveShareState

    var body: some View {
        Section {
            Text(LI("nearby_receive_join_wifi"))
            LabeledContent(LI("nearby_receive_network"), value: ui.wifiSsid)
            LabeledContent(LI("nearby_receive_password"), value: ui.wifiPassword)
            Button(LI("nearby_receive_copy"), systemImage: "doc.on.doc") {
                UIPasteboard.general.string = ui.wifiPassword
            }
        }
        Section {
            Button(LI("nearby_receive_joined")) { model.continueAfterWifi() }
            Button(L("action_cancel"), role: .cancel) { model.cancel() }
        }
    }
}

private struct NearbyOfferSections: View {
    let model: ReceiveShareScreenModel
    let ui: ReceiveShareState

    var body: some View {
        if ui.offered.isEmpty {
            Section(L("board_sync_discovered_share_title")) {
                Text(L("board_sync_discovered_share_message", ui.host))
            }
        } else {
            Section {
                Text(L("board_sync_discovered_share_unverified")).font(.footnote).foregroundStyle(.secondary)
                ForEach(ui.offered, id: \.brandWire) { row in
                    NearbyBrandToggle(row: row, showsCount: true) { model.toggle(brandWire: row.brandWire) }
                }
            } header: {
                Text(L("board_sync_discovered_share_from"))
            } footer: {
                Text(L("board_sync_discovered_share_info", ui.host))
            }
            NearbyOthersSection(model: model, ui: ui)
        }
        Section {
            Button(L("board_sync_discovered_share_confirm")) { model.confirm() }
                .disabled(!ui.canConfirm)
                .accessibilityIdentifier("board_sync_discovered_share_confirm")
            Button(L("action_cancel"), role: .cancel) { model.cancel() }
            if ui.errorCode == "busy" {
                Text(LI("nearby_receive_busy")).font(.footnote).foregroundStyle(.orange)
            }
        }
    }
}

private struct NearbyOthersSection: View {
    let model: ReceiveShareScreenModel
    let ui: ReceiveShareState

    private var summary: String {
        let chosen: [String] = ui.others.filter { $0.selected }.map { $0.title }
        return chosen.isEmpty ? L("board_sync_discovered_share_others_hint") : chosen.joined(separator: " · ")
    }

    var body: some View {
        if !ui.others.isEmpty {
            Section {
                Button { model.toggleOthers() } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("board_sync_discovered_share_others")).foregroundStyle(.primary)
                            Text(summary).font(.footnote).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: ui.othersOpen ? "chevron.up" : "chevron.down").foregroundStyle(.secondary)
                    }
                }
                if ui.othersOpen {
                    ForEach(ui.others, id: \.brandWire) { row in
                        NearbyBrandToggle(row: row, showsCount: false) { model.toggle(brandWire: row.brandWire) }
                    }
                }
            }
        }
    }
}

private struct NearbyBrandToggle: View {
    let row: ReceiveShareBrandRow
    let showsCount: Bool
    let toggle: () -> Void

    var body: some View {
        Button(action: toggle) {
            HStack {
                Image(systemName: row.selected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(row.selected ? Color.accentColor : .secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.title).foregroundStyle(.primary)
                    if showsCount {
                        Text(nearbyClimbCount(row.climbCount)).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .accessibilityAddTraits(row.selected ? .isSelected : [])
    }
}

/// Android `board_sync_share_climbs`: the count grouped like every other climb count ("284.253").
private func nearbyClimbCount(_ count: Int64) -> String {
    let formatter = NumberFormatter()
    formatter.numberStyle = .decimal
    let grouped: String = formatter.string(from: NSNumber(value: count)) ?? String(count)
    let key: String = count == 1 ? "board_sync_share_climbs.one" : "board_sync_share_climbs.other"
    return L(key, grouped)
}

private struct NearbyProgressSection: View {
    let model: ReceiveShareScreenModel
    let ui: ReceiveShareState

    private var fraction: Double? {
        let done: Double = Double(ui.stepDone)
        let total: Double = Double(ui.stepTotal)
        switch ui.stepCode {
        case "download", "extract":
            return total > 0 ? min(done / total, 1) : nil
        case "importClimbs", "importStats":
            return total > 0 && done > 0 ? min(done / total, 1) : nil
        default:
            return nil
        }
    }

    var body: some View {
        Section(L("board_sync_local_progress_title")) {
            Text(nearbyStepLabel(code: ui.stepCode, done: ui.stepDone, total: ui.stepTotal))
            if let fraction {
                ProgressView(value: fraction)
            } else {
                ProgressView()
            }
            Text(LI("nearby_receive_keep_open")).font(.footnote).foregroundStyle(.secondary)
            Button(L("action_cancel"), role: .cancel) { model.cancel() }
        }
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }
}

/// Android `SyncStatusBannerSlot.stepLabel` for the steps of a nearby share.
private func nearbyStepLabel(code: String, done: Int64, total: Int64) -> String {
    let megabyte: Int64 = 1_048_576
    switch code {
    case "preparing":
        return L("board_sync_step_sender_preparing")
    case "download":
        return total > 0 ? L("sync_downloading_progress", Int(done / megabyte), Int(total / megabyte)) : L("sync_downloading")
    case "verify":
        return L("board_sync_step_verify_db")
    case "extract":
        return L("sync_extracting")
    case "importClimbs":
        return done == 0 && total > 0 ? L("sync_importing_climbs_bulk", Int(total)) : L("sync_importing_climbs", Int(done), Int(total))
    case "importStats":
        return done == 0 && total > 0 ? L("sync_importing_stats_bulk", Int(total)) : L("sync_importing_stats", Int(done), Int(total))
    case "importLayout":
        return L("sync_importing_layout")
    case "finalizing":
        return L("sync_finalizing")
    default:
        return L("sync_checking_update")
    }
}

private struct NearbyDoneSection: View {
    let ui: ReceiveShareState
    let onClose: () -> Void

    var body: some View {
        Section(L("sync_complete")) {
            ForEach(ui.result, id: \.brandWire) { row in
                LabeledContent(row.title, value: nearbyClimbCount(row.climbCount))
            }
            Button(L("action_close"), action: onClose)
        }
    }
}

private struct NearbyFailedSection: View {
    let model: ReceiveShareScreenModel
    let ui: ReceiveShareState

    private var hint: String? {
        switch ui.errorCode {
        case "unreachable", "sessionChanged": return LI("nearby_receive_unreachable_hint")
        case "noSpace": return LI("nearby_receive_no_space")
        default: return nil
        }
    }

    var body: some View {
        Section {
            Label(L("board_sync_error_import"), systemImage: "exclamationmark.triangle").foregroundStyle(.red)
            if let hint {
                Text(hint).font(.footnote).foregroundStyle(.secondary)
            }
            Button(L("action_retry")) { model.cancel() }
        }
    }
}

/// Full-screen QR scanner for the sender's code; nothing is stored, the text goes to the model.
private struct NearbyShareScannerSheet: View {
    let onCode: (String) -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            Group {
                if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
                    NearbyShareScannerView(onCode: onCode).ignoresSafeArea()
                } else {
                    ContentUnavailableView(LI("nearby_receive_scan_unavailable"), systemImage: "qrcode.viewfinder")
                }
            }
            .navigationTitle(LI("nearby_receive_scan"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel"), action: onCancel) }
            }
        }
    }
}

private struct NearbyShareScannerView: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: true,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        return scanner
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {
        if !context.coordinator.started {
            context.coordinator.started = true
            try? controller.startScanning()
        }
    }

    static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: Coordinator) {
        controller.stopScanning()
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        private let onCode: (String) -> Void
        private var delivered = false
        var started = false

        init(onCode: @escaping (String) -> Void) {
            self.onCode = onCode
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !delivered else { return }
            for item in addedItems {
                if case .barcode(let barcode) = item, let value = barcode.payloadStringValue, !value.isEmpty {
                    delivered = true
                    dataScanner.stopScanning()
                    onCode(value)
                    return
                }
            }
        }
    }
}
