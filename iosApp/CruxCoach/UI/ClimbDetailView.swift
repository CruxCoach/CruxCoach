import CruxCoachCore
import SwiftUI

struct ClimbDetailView: View {
    let core: AppCore
    let uuid: String
    let angle: Int32
    let brandWire: String
    let onChanged: () -> Void

    private let presenter: ClimbDetailPresenter
    private let logger: LogAttemptPresenter
    @State private var state: Observed<ClimbDetailUiState>
    @State private var log: Observed<LogAttemptState>
    @State private var ble: Observed<BoardConnectionUiState>
    @State private var note = ""
    @State private var showBle = false

    init(core: AppCore, uuid: String, angle: Int32, brandWire: String, onChanged: @escaping () -> Void) {
        self.core = core; self.uuid = uuid; self.angle = angle; self.brandWire = brandWire; self.onChanged = onChanged
        let presenter = core.newDetailPresenter(), logger = core.newLogAttemptPresenter()
        self.presenter = presenter; self.logger = logger
        _state = State(initialValue: Observed(presenter.state, initial: presenter.state.value as! ClimbDetailUiState))
        _log = State(initialValue: Observed(logger.state, initial: logger.state.value as! LogAttemptState))
        _ble = State(initialValue: Observed(core.boardConnection.state, initial: core.boardConnection.state.value as! BoardConnectionUiState))
    }

    private var ui: ClimbDetailUiState { state.value }
    private var useFrench: Bool { (core.platform.keyValues.getString(key: "grade_scale") ?? "FRENCH") != "V_SCALE" }
    private var connected: Bool { ble.value.connection == .connected || ble.value.connection == .sending }

    var body: some View {
        Group {
            switch ui.status {
            case .loading: ProgressView()
            case .notFound, .failed: ContentUnavailableView(LI("detail_unavailable"), systemImage: "questionmark.square.dashed")
            default: content
            }
        }
        .navigationTitle(ui.data?.climb.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { presenter.toggleFavourite() } label: {
                    Label(LI("detail_favourite"), systemImage: ui.isFavorited ? "heart.fill" : "heart")
                }
                Menu {
                    if ui.data?.isMirrorable == true {
                        Button(LI("detail_mirror"), systemImage: "arrow.left.and.right.righttriangle.left.righttriangle.right") { presenter.toggleMirror() }
                    }
                    Button(ui.isIgnored ? LI("detail_unignore") : LI("detail_ignore"), systemImage: "eye.slash") { presenter.setIgnored(ignored: !ui.isIgnored) }
                } label: { Label(LI("browser_more"), systemImage: "ellipsis.circle") }
            }
        }
        .sheet(isPresented: $showBle) { NavigationStack { BleView(core: core, ble: ble) } }
        .sheet(isPresented: Binding(get: { log.value.ascent.showDialog }, set: { if !$0 { logger.dismissDialog() } })) {
            NavigationStack { LogSheet(logger: logger, log: log) }.presentationDetents([.medium, .large])
        }
        .task { presenter.open(uuid: uuid, angle: angle) }
        .onChange(of: ui.data?.climb.uuid) { _, _ in syncLogTarget(); note = ui.personalNote }
        .onChange(of: ui.angle) { _, _ in syncLogTarget() }
        .onChange(of: ui.isMirrored) { _, _ in syncLogTarget() }
        .onChange(of: log.value.userAscents.count) { _, _ in onChanged() }
        .onDisappear { if ui.browserDirty { onChanged() }; presenter.close(); logger.close() }
    }

    private func syncLogTarget() {
        guard let climb = ui.data?.climb else { return }
        logger.setTarget(target: LogTarget(climbUuid: climb.uuid, climbName: climb.name, frames: climb.frames,
                                           framesCount: climb.framesCount, difficultyAverage: climb.difficultyAverage,
                                           boardBrand: brandWire, layoutId: KotlinLong(value: climb.layoutId),
                                           angle: ui.angle, isMirrored: ui.isMirrored))
    }

    @ViewBuilder private var content: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let data = ui.data {
                    BoardCanvasView(data: data, holds: ui.holds, brandWire: brandWire)
                    stats(data)
                    actions
                    if !data.climb.description_.isEmpty { Text(data.climb.description_).font(.callout) }
                    betaLinks(data)
                    noteEditor
                    history
                }
            }.padding()
        }
    }

    private func stats(_ data: ClimbDetailData) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Picker(L("board_angle"), selection: Binding(get: { ui.angle }, set: { presenter.selectAngle(angle: $0) })) {
                ForEach(data.availableAngles, id: \.angle) { option in
                    Text("\(option.angle)° · \(Grades.label(option.difficultyAverage?.doubleValue, french: useFrench)) · \(option.ascensionistCount?.int64Value ?? 0)")
                        .tag(option.angle)
                }
            }.pickerStyle(.menu)
            if let setter = data.climb.setterUsername, !setter.isEmpty {
                Label(setter, systemImage: "person").font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private var actions: some View {
        VStack(spacing: 10) {
            Button {
                if connected { core.boardSender.send(detail: ui) } else { showBle = true }
            } label: {
                Label(connected ? LI("detail_light_up") : L("board_ble_title"), systemImage: "lightbulb.max")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).controlSize(.large)
            .disabled(ble.value.sending)
            if let result = ble.value.lastSendResult, result != .ok {
                Text(LI("detail_send_failed", String(describing: result))).font(.footnote).foregroundStyle(.red)
            }
            HStack {
                Button { logger.quickLog(isSend: false) } label: { Label(LI("detail_attempt"), systemImage: "plus").frame(maxWidth: .infinity) }
                Button { logger.quickLog(isSend: true) } label: { Label(LI("detail_sent"), systemImage: "checkmark").frame(maxWidth: .infinity) }
                Button { logger.showDialog() } label: { Label(LI("detail_log"), systemImage: "square.and.pencil").frame(maxWidth: .infinity) }
            }
            .buttonStyle(.bordered).disabled(log.value.isQuickLogging)
            if log.value.quickLogFeedback != nil {
                Button(LI("detail_undo")) { logger.undoQuickLog() }.font(.footnote)
            }
            if log.value.quickLogFailed { Text(LI("detail_log_failed")).font(.footnote).foregroundStyle(.red) }
        }
    }

    @ViewBuilder private func betaLinks(_ data: ClimbDetailData) -> some View {
        if !data.betaLinks.isEmpty {
            VStack(alignment: .leading) {
                Text(LI("detail_beta")).font(.headline)
                ForEach(data.betaLinks, id: \.link.url) { item in
                    if let url = URL(string: item.link.url), url.scheme == "https" {
                        Link(destination: url) { Label(item.link.foreignUsername ?? item.link.provider, systemImage: "play.rectangle") }
                    }
                }
            }
        }
    }

    private var noteEditor: some View {
        VStack(alignment: .leading) {
            Text(LI("detail_note")).font(.headline)
            TextField(LI("detail_note_hint"), text: $note, axis: .vertical).textFieldStyle(.roundedBorder)
                .onSubmit { presenter.saveNote(note: note) }
            if note != ui.personalNote { Button(L("action_save")) { presenter.saveNote(note: note) } }
            if ui.noteStatus == .failed { Text(LI("detail_log_failed")).font(.footnote).foregroundStyle(.red) }
        }
    }

    @ViewBuilder private var history: some View {
        let entries = log.value.userAscents
        if !entries.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text(L("board_logbook_title")).font(.headline)
                ForEach(entries, id: \.uuid) { entry in
                    HStack {
                        Image(systemName: entry.isSend ? "checkmark.circle.fill" : "circle.dotted")
                        Text(String(entry.climbedAt.prefix(10)))
                        Text("\(entry.angle)°").foregroundStyle(.secondary)
                        Spacer()
                        Text("×\(entry.bidCount)").foregroundStyle(.secondary)
                    }.font(.footnote)
                }
            }
        }
    }
}
