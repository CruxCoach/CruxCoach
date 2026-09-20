import CruxCoachCore
import SwiftUI
import UniformTypeIdentifiers

/// Bring an existing history in from a file: a MoonBoard account CSV export or
/// an Aurora app JSON export. Swift picks and reads the file; every parsing and
/// writing rule lives in the shared core.
struct ImportView: View {
    let core: AppCore
    @State private var host: ScreenHost<ImportScreenModel, ImportScreenState>?
    @State private var picking: Format?

    private enum Format: String, Identifiable {
        case moonBoardCsv, auroraJson
        var id: String { rawValue }
        var contentTypes: [UTType] { self == .moonBoardCsv ? [.commaSeparatedText, .plainText] : [.json] }
    }

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(LI("import_title"))
        .task {
            guard host == nil else { return }
            let model = core.makeImportScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }

    @ViewBuilder
    private func screen(model: ImportScreenModel, ui: ImportScreenState) -> some View {
        Form {
            Section { Text(LI("import_explanation")) }
            Section(LI("import_sources")) {
                Button(LI("import_moonboard")) { picking = .moonBoardCsv }.disabled(ui.busy)
                Button(LI("import_aurora")) { picking = .auroraJson }.disabled(ui.busy)
            }
            if ui.busy {
                Section {
                    HStack { ProgressView(); Text(phaseText(ui)) }
                }
            }
            if ui.finished {
                Section(LI("import_result")) {
                    if ui.failureCode.isEmpty {
                        Label(LI("import_ok", Int(ui.imported), Int(ui.skippedDuplicates)), systemImage: "checkmark.circle")
                            .foregroundStyle(.green)
                        if ui.staged > 0 {
                            Text(LI("import_staged", Int(ui.staged))).font(.footnote).foregroundStyle(.secondary)
                        }
                        if ui.rejected > 0 {
                            Text(LI("import_rejected", Int(ui.rejected))).font(.footnote).foregroundStyle(.orange)
                        }
                        if !ui.unresolvedLabels.isEmpty {
                            Text(LI("import_unresolved", ui.unresolvedLabels.prefix(5).joined(separator: ", ")))
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    } else {
                        Label(failureText(ui), systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    }
                    Button(L("action_close")) { model.reset() }
                }
            }
        }
        .fileImporter(isPresented: Binding(get: { picking != nil }, set: { if !$0 { picking = nil } }),
                      allowedContentTypes: picking?.contentTypes ?? [.plainText]) { result in
            let format = picking
            picking = nil
            guard case .success(let url) = result, let format else { return }
            // A picked file lives outside the sandbox until it is opened.
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let text = try? String(contentsOf: url, encoding: .utf8) else { return }
            switch format {
            case .moonBoardCsv: model.importMoonBoardCsv(text: text)
            case .auroraJson: model.importAuroraJson(text: text)
            }
        }
    }

    private func phaseText(_ ui: ImportScreenState) -> String {
        if ui.total > 0 { return LI("import_progress", Int(ui.done), Int(ui.total)) }
        return LI("import_working")
    }

    private func failureText(_ ui: ImportScreenState) -> String {
        switch ui.failureCode {
        case "empty": return LI("import_failed_empty")
        case "tooLarge": return LI("import_failed_large")
        case "malformed":
            return ui.failureRow > 0 ? LI("import_failed_row", Int(ui.failureRow)) : LI("import_failed_malformed")
        default:
            return ui.failureRow > 0
                ? LI("import_failed_row", Int(ui.failureRow))
                : LI("import_failed_other", ui.failureCode)
        }
    }
}
