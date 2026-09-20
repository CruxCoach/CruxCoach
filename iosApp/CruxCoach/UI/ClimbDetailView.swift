import CruxCoachCore
import SwiftUI

struct ClimbDetailView: View {
    let core: AppCore
    let uuid: String
    let angle: Int32
    let ble: ScreenHost<BleScreenModel, BleScreenState>
    let onChanged: () -> Void

    @State private var host: ScreenHost<DetailScreenModel, DetailScreenState>?
    @State private var note = ""
    @State private var noteLoaded = false
    @State private var showBle = false
    @State private var loadedMoonPath = ""

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .task {
            guard host == nil else { return }
            let model = core.makeDetailScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.open(uuid: uuid, angle: angle)
        }
        .onDisappear {
            if host?.state.browserDirty == true { onChanged() }
            host?.close()
        }
    }

    @ViewBuilder
    private func screen(model: DetailScreenModel, ui: DetailScreenState) -> some View {
        Group {
            switch ui.status {
            case "loading":
                ProgressView()
            case "notFound", "failed":
                ContentUnavailableView(LI("detail_unavailable"), systemImage: "questionmark.square.dashed")
            default:
                content(model: model, ui: ui)
            }
        }
        .navigationTitle(ui.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { model.toggleFavourite() } label: {
                    Label(LI("detail_favourite"), systemImage: ui.isFavourite ? "heart.fill" : "heart")
                }
                Menu {
                    if ui.isMirrorable {
                        Button(LI("detail_mirror"), systemImage: "arrow.left.and.right") { model.toggleMirror() }
                    }
                    Button(ui.isIgnored ? LI("detail_unignore") : LI("detail_ignore"), systemImage: "eye.slash") {
                        model.setIgnored(ignored: !ui.isIgnored)
                    }
                } label: {
                    Label(LI("browser_more"), systemImage: "ellipsis.circle")
                }
            }
        }
        .sheet(isPresented: $showBle) { NavigationStack { BleView(ble: ble) } }
        .sheet(isPresented: Binding(get: { ui.showLogDialog }, set: { if !$0 { model.dismissLogDialog() } })) {
            NavigationStack { LogSheet(model: model, ui: ui) }.presentationDetents([.medium, .large])
        }
        .onChange(of: ui.moonLayoutPath) { _, path in loadMoonLayout(model: model, path: path) }
        .onChange(of: ui.personalNote) { _, value in
            if !noteLoaded { note = value; noteLoaded = true }
        }
    }

    private func sendRequestMessage(_ code: String) -> String {
        switch code {
        case "noBoardGeometry": return LI("detail_send_no_geometry")
        case "noHolds": return LI("detail_send_no_holds")
        default: return LI("detail_send_failed", code)
        }
    }

    /// The MoonBoard coordinate map is a bundle resource; Kotlin owns the geometry, Swift the file access.
    private func loadMoonLayout(model: DetailScreenModel, path: String) {
        guard !path.isEmpty, path != loadedMoonPath,
              let url = Bundle.main.resourceURL?.appendingPathComponent(path),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return }
        loadedMoonPath = path
        model.setMoonLayoutJson(jsonText: text)
    }

    @ViewBuilder private func content(model: DetailScreenModel, ui: DetailScreenState) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                BoardCanvasView(imagePaths: ui.imagePaths, holds: ui.holds, aspect: CGFloat(ui.boardAspect))
                stats(model: model, ui: ui)
                actions(model: model, ui: ui)
                if !ui.notes.isEmpty { Text(ui.notes).font(.callout) }
                betaLinks(ui: ui)
                noteEditor(model: model, ui: ui)
                history(ui: ui)
            }
            .padding()
        }
    }

    private func stats(model: DetailScreenModel, ui: DetailScreenState) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Picker(L("board_angle"), selection: Binding(get: { ui.angle }, set: { model.selectAngle(angle: $0) })) {
                ForEach(ui.angles, id: \.angle) { option in
                    Text("\(option.angle)° · \(option.grade.isEmpty ? "–" : option.grade) · \(option.sends)").tag(option.angle)
                }
            }
            .pickerStyle(.menu)
            if !ui.setter.isEmpty {
                Label(ui.setter, systemImage: "person").font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private func actions(model: DetailScreenModel, ui: DetailScreenState) -> some View {
        VStack(spacing: 10) {
            Button {
                if ble.state.connected { model.sendToBoard() } else { showBle = true }
            } label: {
                Label(ble.state.connected ? LI("detail_light_up") : L("board_ble_title"), systemImage: "lightbulb.max")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(ble.state.sending)
            if ui.sendRequestFailure != "none" {
                Text(sendRequestMessage(ui.sendRequestFailure)).font(.footnote).foregroundStyle(.red)
            } else if ble.state.sendResult != "none" && ble.state.sendResult != "ok" {
                Text(LI("detail_send_failed", ble.state.sendResult)).font(.footnote).foregroundStyle(.red)
            }
            HStack {
                Button { model.quickLog(isSend: false) } label: {
                    Label(LI("detail_attempt"), systemImage: "plus").frame(maxWidth: .infinity)
                }
                Button { model.quickLog(isSend: true) } label: {
                    Label(LI("detail_sent"), systemImage: "checkmark").frame(maxWidth: .infinity)
                }
                Button { model.showLogDialog() } label: {
                    Label(LI("detail_log"), systemImage: "square.and.pencil").frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.bordered)
            .disabled(ui.quickLogging)
            if ui.canUndoQuickLog {
                Button(LI("detail_undo")) { model.undoQuickLog() }.font(.footnote)
            }
            if ui.logFailed {
                Text(LI("detail_log_failed")).font(.footnote).foregroundStyle(.red)
            }
        }
    }

    @ViewBuilder private func betaLinks(ui: DetailScreenState) -> some View {
        if !ui.beta.isEmpty {
            VStack(alignment: .leading) {
                Text(LI("detail_beta")).font(.headline)
                ForEach(ui.beta, id: \.url) { link in
                    if let url = URL(string: link.url) {
                        Link(destination: url) { Label(link.label, systemImage: "play.rectangle") }
                    }
                }
            }
        }
    }

    private func noteEditor(model: DetailScreenModel, ui: DetailScreenState) -> some View {
        VStack(alignment: .leading) {
            Text(LI("detail_note")).font(.headline)
            TextField(LI("detail_note_hint"), text: $note, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .onSubmit { model.saveNote(note: note) }
            if note != ui.personalNote {
                Button(L("action_save")) { model.saveNote(note: note) }
            }
            if ui.noteFailed {
                Text(LI("detail_log_failed")).font(.footnote).foregroundStyle(.red)
            }
        }
    }

    @ViewBuilder private func history(ui: DetailScreenState) -> some View {
        if !ui.ascents.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text(L("board_logbook_title")).font(.headline)
                ForEach(ui.ascents, id: \.uuid) { entry in
                    HStack {
                        Image(systemName: entry.isSend ? "checkmark.circle.fill" : "circle.dotted")
                        Text(entry.date)
                        Text("\(entry.angle)°").foregroundStyle(.secondary)
                        Spacer()
                        Text("×\(entry.tries)").foregroundStyle(.secondary)
                    }
                    .font(.footnote)
                }
            }
        }
    }
}
