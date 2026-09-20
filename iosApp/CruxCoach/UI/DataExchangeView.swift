import CruxCoachCore
import SwiftUI
import UniformTypeIdentifiers

/// Plain-file export and import of the whole account, beside the encrypted
/// cloud backup. The exported file is not encrypted, which the screen says.
struct DataExchangeView: View {
    let core: AppCore
    @State private var host: ScreenHost<DataExchangeScreenModel, DataExchangeScreenState>?
    @State private var picking = false

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("settings_data_export"))
        .task {
            guard host == nil else { return }
            let model = core.makeDataExchangeScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }

    @ViewBuilder
    private func screen(model: DataExchangeScreenModel, ui: DataExchangeScreenState) -> some View {
        List {
            Section {
                Text(LI("data_export_hint")).font(.footnote).foregroundStyle(.secondary)
            }
            Section(header: Text(L("bodystat_export"))) {
                if ui.exportPath.isEmpty {
                    Button(L("export_save_file")) { model.export() }
                        .disabled(ui.isBusy)
                } else {
                    ShareLink(item: URL(fileURLWithPath: ui.exportPath)) {
                        Label(L("export_share_app"), systemImage: "square.and.arrow.up")
                    }
                    Text(LI("data_export_ready", byteLabel(ui.exportBytes)))
                        .font(.footnote).foregroundStyle(.secondary)
                    Button(LI("data_export_discard"), role: .destructive) { model.discardExport() }
                }
            }
            Section(header: Text(LI("data_import_title"))) {
                Button(LI("data_import_pick")) { picking = true }
                    .disabled(ui.isBusy)
                if ui.importedAscents >= 0 {
                    Text([
                        L("import_result_board_sends", Int(ui.importedAscents)),
                        L("import_result_board_bids", Int(ui.importedBids)),
                        L("import_result_lists", Int(ui.importedLists)),
                    ].joined(separator: " · "))
                    .font(.footnote)
                }
            }
            if ui.isBusy {
                Section { ProgressView().frame(maxWidth: .infinity) }
            }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.json]) { result in
            guard case .success(let url) = result else { return }
            // A document picked outside the app's container is security-scoped;
            // the Kotlin side reads a plain path, so the scope is opened here
            // and closed once the file has been read into the database.
            let scoped = url.startAccessingSecurityScopedResource()
            model.importFile(path: url.path)
            if scoped { url.stopAccessingSecurityScopedResource() }
        }
        .alert(LI(errorKey(ui.errorCode)),
               isPresented: Binding(get: { ui.errorCode != "none" }, set: { if !$0 { model.consumeError() } })) {
            Button(L("action_done")) { model.consumeError() }
        }
    }

    private func errorKey(_ code: String) -> String {
        switch code {
        case "exportFailed": return "data_export_failed"
        case "fileUnreadable": return "data_import_unreadable"
        case "noIdentity": return "data_export_no_identity"
        default: return "data_import_failed"
        }
    }

    private func byteLabel(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}
