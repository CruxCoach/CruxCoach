import CruxCoachCore
import SwiftUI
import UniformTypeIdentifiers

/// Plain-file export and import of the whole account, beside the encrypted
/// cloud backup. The exported file is not encrypted, which the screen says.
///
/// The import is Android's DataImportScreen: a picked JSON export or CSV ZIP is
/// previewed first (what it holds per category), the user chooses categories,
/// confirms a file of another identity explicitly, and only then is it written.
struct DataExchangeView: View {
    let core: AppCore
    @State private var host: ScreenHost<DataExchangeScreenModel, DataExchangeScreenState>?
    @State private var picking = false
    /// Android's export format chips: "json", "csv" (CSV ZIP) or "excel" (export only).
    @State private var format = "json"
    /// Android's error card "Report bug": the bug report form, prefilled with the error.
    @State private var bugReport: BugReportDraft?
    /// Android's import picker: the JSON export or a CSV ZIP; the core detects which from the content.
    private static let importTypes: [UTType] = [.json, .zip]

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
            let model = core.makeDataTransferScreen()
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
            exportSection(model: model, ui: ui)
            if ui.mismatchAccepted {
                overrideBanner(ui: ui)
            }
            if ui.hasPreview {
                previewSection(model: model, ui: ui)
            } else {
                pickSection(ui: ui)
            }
            if ui.isBusy {
                Section { ProgressView().frame(maxWidth: .infinity) }
            }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: Self.importTypes) { result in
            guard case .success(let url) = result else { return }
            // A document picked outside the app's container is security-scoped;
            // the Kotlin side reads a plain path, so the scope is opened here
            // and closed once the file has been read and previewed.
            let scoped = url.startAccessingSecurityScopedResource()
            model.previewFile(path: url.path, onFinished: {
                if scoped { url.stopAccessingSecurityScopedResource() }
            })
        }
        .modifier(DataImportMismatchAlert(model: model, ui: ui))
        .modifier(DataExchangeErrorAlert(model: model, ui: ui) { draft in bugReport = draft })
        .modifier(DataImportBugReport(core: core, draft: $bugReport))
    }

    // MARK: Export

    @ViewBuilder
    private func exportSection(model: DataExchangeScreenModel, ui: DataExchangeScreenState) -> some View {
        Section(header: Text(L("bodystat_export"))) {
            if ui.exportPath.isEmpty {
                formatPicker()
                Button(L("export_save_file")) { startExport(model: model) }
                    .disabled(ui.isBusy || ui.isImporting)
            } else {
                ShareLink(item: URL(fileURLWithPath: ui.exportPath)) {
                    Label(L("export_share_app"), systemImage: "square.and.arrow.up")
                }
                Text(LI("data_export_ready", byteLabel(ui.exportBytes)))
                    .font(.footnote).foregroundStyle(.secondary)
                Button(LI("data_export_discard"), role: .destructive) { model.discardExport() }
            }
        }
    }

    @ViewBuilder
    private func formatPicker() -> some View {
        Picker(L("export_format_title"), selection: $format) {
            Text(L("export_format_json")).tag("json")
            Text(L("export_format_csv")).tag("csv")
            Text(L("export_format_excel")).tag("excel")
        }
        .pickerStyle(.segmented)
        if format == "excel" {
            Text(L("export_excel_not_backup")).font(.footnote).foregroundStyle(.secondary)
        }
    }

    private func startExport(model: DataExchangeScreenModel) {
        switch format {
        case "csv": model.exportCsvZip()
        case "excel": model.exportExcel()
        default: model.export()
        }
    }

    private func byteLabel(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    // MARK: Import, phase 1: pick a file

    @ViewBuilder
    private func pickSection(ui: DataExchangeScreenState) -> some View {
        Section(header: Text(L("bodystat_import"))) {
            VStack(alignment: .leading, spacing: 4) {
                Text(L("bodystat_select_file")).font(.headline)
                Text(L("import_auto_detect_hint")).font(.footnote).foregroundStyle(.secondary)
            }
            Button {
                picking = true
            } label: {
                pickLabel(loading: ui.isLoadingPreview)
            }
            .disabled(ui.isLoadingPreview || ui.isBusy)
            if let summary = TrRender.optional(ui.resultText) {
                Text(summary).font(.footnote)
            }
        }
    }

    @ViewBuilder
    private func pickLabel(loading: Bool) -> some View {
        if loading {
            HStack(spacing: 8) {
                ProgressView()
                Text(L("bodystat_analyzing_file"))
            }
        } else {
            Label(L("bodystat_choose_file"), systemImage: "folder")
        }
    }

    // MARK: Import, phase 2: preview and category choice

    @ViewBuilder
    private func previewSection(model: DataExchangeScreenModel, ui: DataExchangeScreenState) -> some View {
        Section {
            ForEach(ui.categories, id: \.code) { row in
                categoryRow(model: model, row: row, enabled: !ui.isImporting)
            }
            if ui.previewEmpty {
                Text(L("import_no_data")).foregroundStyle(.red)
            }
            if ui.catalogueLoading {
                Text(L("import_catalogue_loading_hint")).font(.footnote).foregroundStyle(.secondary)
            }
            previewButtons(model: model, ui: ui)
        } header: {
            Text(L("import_select_categories"))
        } footer: {
            Text(L("import_duplicate_hint"))
        }
    }

    private func categoryRow(model: DataExchangeScreenModel, row: DataImportCategoryRow, enabled: Bool) -> some View {
        let label: String = TrRender.text(row.label)
        let count: String = TrRender.text(row.countLine)
        let selected: Bool = row.selected
        let code: String = row.code
        return Toggle(isOn: Binding(get: { selected }, set: { _ in model.toggleCategory(code: code) })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                Text(count).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .disabled(!enabled)
    }

    // MARK: Import, phase 3: confirm

    @ViewBuilder
    private func previewButtons(model: DataExchangeScreenModel, ui: DataExchangeScreenState) -> some View {
        Button {
            model.confirmImport()
        } label: {
            importLabel(importing: ui.isImporting)
        }
        .disabled(!ui.canImport || ui.isBusy)
        Button(L("action_cancel")) { model.cancelImport() }
            .disabled(ui.isImporting)
    }

    @ViewBuilder
    private func importLabel(importing: Bool) -> some View {
        if importing {
            HStack(spacing: 8) {
                ProgressView()
                Text(L("import_in_progress_short"))
            }
        } else {
            Text(L("bodystat_import")).fontWeight(.bold)
        }
    }

    /// Android's persistent banner while an import across identities is armed.
    private func overrideBanner(ui: DataExchangeScreenState) -> some View {
        let source: String = ui.sourceNpubBanner
        return Section {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red)
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("import_pubkey_override_banner_title")).font(.subheadline).bold()
                    Text(L("import_pubkey_override_banner_body", source)).font(.footnote)
                }
            }
        }
    }
}

/// Android's identity-mismatch dialog: "import anyway" or cancel, which drops the file.
private struct DataImportMismatchAlert: ViewModifier {
    let model: DataExchangeScreenModel
    let ui: DataExchangeScreenState

    func body(content: Content) -> some View {
        let source: String = ui.sourceNpubDialog
        return content.alert(L("import_pubkey_mismatch_title"), isPresented: Binding(
            get: { ui.pubkeyMismatch },
            set: { if !$0 { model.dismissMismatch() } })) {
            Button(L("action_cancel"), role: .cancel) { model.dismissMismatch() }
            Button(L("import_pubkey_mismatch_confirm"), role: .destructive) { model.confirmMismatch() }
        } message: {
            Text(L("import_pubkey_mismatch_body", source))
        }
    }
}

/// Export failures and Android's import error texts ("File not readable: …", "Import failed: …").
/// An import error offers Android's error card action "Report bug": the error becomes the
/// description of a prefilled bug report, and the error is cleared, as on Android.
private struct DataExchangeErrorAlert: ViewModifier {
    let model: DataExchangeScreenModel
    let ui: DataExchangeScreenState
    let onReport: (BugReportDraft) -> Void

    func body(content: Content) -> some View {
        let title: String = Self.message(ui)
        let reportable: Bool = Self.importCodes.contains(ui.errorCode)
        return content.alert(title, isPresented: Binding(
            get: { ui.errorCode != "none" },
            set: { if !$0 { model.consumeError() } })) {
            if reportable {
                Button(L("error_report_bug")) { report(title) }
            }
            Button(L("action_done")) { model.consumeError() }
        }
    }

    private func report(_ text: String) {
        model.consumeError()
        let draft = BugReportDraft(title: L("error_bug_report_import_title"), details: text)
        let open: (BugReportDraft) -> Void = onReport
        // The form comes up once the alert has gone; a sheet asked for during its dismissal is refused.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { open(draft) }
    }

    /// The errors of Android's import screen, whose error card carries "Report bug".
    private static let importCodes: Set<String> = ["fileNotReadable", "importFailed", "selectCategory"]

    private static func message(_ ui: DataExchangeScreenState) -> String {
        if let text = TrRender.optional(ui.errorText) { return text }
        switch ui.errorCode {
        case "exportFailed": return LI("data_export_failed")
        case "noIdentity": return LI("data_export_no_identity")
        default: return LI("data_import_failed")
        }
    }
}

/// The prefilled bug report of an import error (Android navigates to its bug report form).
private struct DataImportBugReport: ViewModifier {
    let core: AppCore
    @Binding var draft: BugReportDraft?

    func body(content: Content) -> some View {
        content.sheet(item: $draft) { report in
            NavigationStack {
                BugReportSheet(core: core, draft: report) { draft = nil }
            }
        }
    }
}
