import CruxCoachCore
import SwiftUI

struct ClimbDetailView: View {
    let core: AppCore
    let uuid: String
    let angle: Int32
    let ble: ScreenHost<BleScreenModel, BleScreenState>
    /// The list the climb was tapped in (e.g. the browser's results), for
    /// Android's swipe paging. Empty or without [uuid]: a single page.
    var pageUuids: [String] = []
    let onChanged: () -> Void

    @State private var host: ScreenHost<DetailScreenModel, DetailScreenState>?
    @State private var note = ""
    /// The climb whose stored note [note] was filled from; paging loads the next one's.
    @State private var noteUuid = ""
    @State private var rest: ScreenHost<RestTimerModel, RestTimerUiState>?
    @State private var showRestStart = false
    @Environment(\.scenePhase) private var scenePhase
    @State private var showBle = false
    @State private var showQuantumLayers = false
    @State private var showLists = false
    @State private var editorTarget: DetailEditorTarget?
    @State private var linkCopied = false
    @State private var loadedMoonPath = ""
    @State private var owner: ScreenHost<ClimbOwnerActionsModel, ClimbOwnerActionsState>?
    /// Android's route playback: a route's sections one after another.
    @State private var playback: ScreenHost<RoutePlaybackModel, RoutePlaybackUiState>?
    /// "Publish as mine" for a climb the connected Kilter account set (Android `canPublishAsMine`).
    @State private var ownKilter: ScreenHost<OwnKilterClimbModel, OwnKilterClimbState>?
    @Environment(\.dismiss) private var dismiss
    /// The guided first browse's detail steps (project, log, back to the logbook).
    @State private var tour: BrowserTourModel?
    @State private var tourStep = ""
    @State private var anchors = TourAnchors()
    /// Android's board-match guard refused the last send on this page: its evidence for the guided fix.
    @State private var boardFix: BoardMismatchUi?
    @State private var boardFixUuid = ""
    @State private var showBoardFix = false
    /// A guided fix was confirmed in a sheet: Android then returns to the board browser.
    @State private var leaveAfterFix = false

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
            let guided = core.makeBrowserTour()
            tour = guided
            tourStep = guided.step
            // The rest-timer detail plus route playback beside it.
            let withPlayback = core.makeDetailWithRoutePlayback()
            let model = withPlayback.detail
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            let routes = withPlayback.playback
            playback = ScreenHost(model: routes, initial: routes.currentState,
                                  subscribe: { model, onState in model.watch(onState: onState) },
                                  onClose: { model in model.close() })
            model.openPaged(uuid: uuid, angle: angle, pageUuids: pageUuids)
            let timer = core.makeBoardRestTimer()
            rest = ScreenHost(model: timer, initial: timer.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.detach() })
            let actions = core.makeClimbOwnerActions()
            owner = ScreenHost(model: actions, initial: actions.currentState,
                               subscribe: { model, onState in model.watch(onState: onState) },
                               onClose: { model in model.close() })
            actions.open(uuid: uuid)
            let ownModel = core.makeOwnKilterClimb()
            ownKilter = ScreenHost(model: ownModel, initial: ownModel.currentState,
                                   subscribe: { model, onState in model.watch(onState: onState) },
                                   onClose: { model in model.close() })
            ownModel.load(uuid: uuid)
        }
        .onDisappear {
            if host?.state.browserDirty == true { onChanged() }
            playback?.close()
            host?.close()
            owner?.close()
            ownKilter?.close()
            rest?.close()
        }
        .onChange(of: scenePhase) { _, phase in
            // The countdown is an end instant; a suspended app shows the right remainder on return.
            if phase == .active { rest?.model.refresh() }
        }
        .modifier(OwnerDeleteDialogs(owner: owner))
        .onChange(of: owner?.state.deleted ?? false) { _, deleted in
            // Android navigates back once the climb is gone; the browser must drop it.
            if deleted {
                onChanged()
                dismiss()
            }
        }
        .overlay(alignment: .top) { ownerResultBanner }
        .overlay(alignment: .bottom) { ownKilterBanner }
    }

    /// Android's snackbar after "Publish as mine".
    @ViewBuilder
    private var ownKilterBanner: some View {
        if let ownKilter, ownKilter.state.feedback != "none" {
            Text(ownKilterFeedbackText(ownKilter.state.feedback))
                .font(.footnote)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(.thinMaterial, in: Capsule())
                .padding(.bottom, 12)
                .onTapGesture { ownKilter.model.consumeFeedback() }
                .onAppear {
                    // Now an own community climb: the owner rows (Edit, Delete) apply.
                    if ownKilter.state.feedback == "published" { owner?.model.open(uuid: ownKilter.state.uuid) }
                }
        }
    }

    private func ownKilterFeedbackText(_ code: String) -> String {
        switch code {
        case "published": return L("own_climb_publish_done")
        case "noNostrIdentity": return L("own_climb_publish_no_nostr")
        case "notAuthor": return L("own_climb_publish_not_author")
        case "alreadyPublished": return L("own_climb_publish_already")
        default: return L("climb_creator_publish_failed")
        }
    }

    @ViewBuilder
    private var ownerResultBanner: some View {
        if let owner, owner.state.resultCode != "none", !owner.state.deleted {
            Text(ownerResultText(owner.state))
                .font(.footnote)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(.thinMaterial, in: Capsule())
                .padding(.top, 8)
                .onTapGesture { owner.model.consumeResult() }
        }
    }

    private func ownerResultText(_ state: ClimbOwnerActionsState) -> String {
        let accepted: Int = Int(state.resultAccepted)
        let attempted: Int = Int(state.resultAttempted)
        switch state.resultCode {
        case "done":
            return state.resultKilterWarning
                ? L("community_climb_delete_done_with_kilter", accepted, attempted)
                : L("community_climb_delete_done_nostr_only", accepted, attempted)
        case "localTombstoneFailed": return L("community_climb_delete_local_failed", accepted, attempted)
        case "notOwner": return L("community_climb_delete_not_owner")
        default: return L("community_climb_delete_failed")
        }
    }

    /// Android's owner rows in the overflow menu: Remix, Edit (locked once on
    /// Kilter) and the draft or publication delete.
    @ViewBuilder
    private func ownerMenuItems() -> some View {
        if let ownKilter, ownKilter.state.canPublishAsMine {
            Button(L("own_climb_publish_action"), systemImage: "person.3") { ownKilter.model.publish() }
                .disabled(ownKilter.state.inProgress)
        }
        if let owner, owner.state.isLoaded {
            let state: ClimbOwnerActionsState = owner.state
            Divider()
            Button(L("climb_creator_remix_action"), systemImage: "arrow.triangle.branch") {
                editorTarget = DetailEditorTarget(edit: false)
            }
            if state.isOwn {
                if state.editLocked {
                    Button(L("climb_detail_edit_locked_kilter"), systemImage: "pencil") {}
                        .disabled(true)
                } else {
                    Button(L("climb_creator_edit_action"), systemImage: "pencil") {
                        editorTarget = DetailEditorTarget(edit: true)
                    }
                }
                Button(state.isUnpublishedDraft ? L("climb_creator_drafts_delete_action") : L("community_climb_delete_action"),
                       systemImage: "trash", role: .destructive) {
                    owner.model.requestDelete()
                }
            }
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
            case "logbookOnly":
                // Android LogbookOnlyClimbContent: logged, but not in the catalogue.
                LogbookOnlyClimbView(core: core, climbUuid: ui.uuid).id(ui.uuid)
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
                restTimerButton
                Menu {
                    if ui.isMirrorable {
                        Button(LI("detail_mirror"), systemImage: "arrow.left.and.right") { model.toggleMirror() }
                    }
                    Button(ui.isIgnored ? LI("detail_unignore") : LI("detail_ignore"), systemImage: "eye.slash") {
                        model.setIgnored(ignored: !ui.isIgnored)
                    }
                    Button(L("board_addtolist_title"), systemImage: "text.badge.plus") { showLists = true }
                    Button(L("board_detail_share_link"), systemImage: "square.and.arrow.up") {
                        UIPasteboard.general.string = model.shareLink()
                        linkCopied = true
                        Task {
                            try? await Task.sleep(nanoseconds: 2_000_000_000)
                            linkCopied = false
                        }
                    }
                    ownerMenuItems()
                } label: {
                    Label(LI("browser_more"), systemImage: "ellipsis.circle")
                }
            }
        }
        .sheet(isPresented: $showBle) { NavigationStack { BleView(ble: ble, fix: bleFixRoute) } }
        .modifier(DetailBoardFixLayer(core: core, mismatch: boardFix, showFix: $showBoardFix,
                                      showBle: showBle, showQuantumLayers: showQuantumLayers,
                                      leaveAfterFix: leaveAfterFix, onLeave: { leaveForBrowser() }))
        .modifier(DetailExtraSheets(core: core, uuid: ui.uuid, angle: ui.angle,
                                    showLists: $showLists, editorTarget: $editorTarget, linkCopied: linkCopied))
        .modifier(DetailRestTimerSheet(rest: rest, isPresented: $showRestStart))
        .sheet(isPresented: Binding(get: { ui.showLogDialog }, set: { if !$0 { model.dismissLogDialog() } })) {
            NavigationStack { LogSheet(model: model, ui: ui) }.presentationDetents([.medium, .large])
        }
        .onChange(of: ui.moonLayoutPath) { _, path in loadMoonLayout(model: model, path: path) }
        .onChange(of: "\(ui.uuid)|\(ui.status)", initial: true) { _, _ in pageChanged(ui: ui) }
        .modifier(DetailAutoSendTrigger(core: core, model: model, ui: ui, connected: ble.state.connected,
                                        send: { sendChecked(model: model) }))
        .modifier(DetailTourLayer(anchors: anchors, tour: tour, step: tourStep, connected: ble.state.connected,
                                  quickLogged: ui.canUndoQuickLog,
                                  visible: tourVisible(ui),
                                  onQuickLogged: { advanceTour(from: "log") },
                                  onMove: { refreshTour() }))
    }

    /// Android's connection sheet in the detail offers the guided fix too, and leaves for the browser.
    private var bleFixRoute: BoardMismatchRoute {
        BoardMismatchRoute(core: core, activeBrandWire: core.activeBoardBrandWire(),
                           brandWires: BoardFixSupport.installedBrandWires(core),
                           onPick: { option in
                               core.applyBoardChoice(option: option)
                               leaveAfterFix = true
                               showBle = false
                           })
    }

    /// Android `onNavigateToBoardBrowser` after the guided fix: the browser reloads the new board.
    private func leaveForBrowser() {
        boardFix = nil
        leaveAfterFix = false
        onChanged()
        dismiss()
    }

    /// Android BoardSendController: the board-match guard runs before every send.
    private func sendChecked(model: DetailScreenModel) {
        let current: String = host?.state.uuid ?? uuid
        let mismatch: BoardMismatchUi = core.boardFixCheck.forClimb(climbUuid: current)
        if mismatch.active {
            boardFix = mismatch
            boardFixUuid = current
            return
        }
        boardFix = nil
        model.sendToBoard()
    }

    /// Android hides the spotlight under the detail's sheets and dialogs.
    private func tourVisible(_ ui: DetailScreenState) -> Bool {
        let covered: Bool = showBle || ui.showLogDialog || showLists || editorTarget != nil || showRestStart
        return ui.status == "ready" && !covered
    }

    /// The Kotlin model is not observable: SwiftUI learns a new step through [tourStep].
    private func refreshTour() {
        guard let tour else { return }
        tour.refresh()
        tourStep = tour.step
    }

    /// Android moves on when the step's own action happened (a climb opened, lit, logged).
    private func advanceTour(from step: String) {
        guard let tour else { return }
        tour.refresh()
        if tour.step == step { tour.advance(from: step) }
        tourStep = tour.step
    }

    /// Android's timer button in the detail's top bar; orange while a rest runs.
    private var restTimerButton: some View {
        let running: Bool = rest?.state.phase == "running"
        return Button { showRestStart = true } label: {
            Label(L("cd_rest_timer"), systemImage: "timer")
        }
        .foregroundStyle(running ? Color.orange : Color.accentColor)
        .accessibilityIdentifier("detail_rest_timer_button")
    }

    /// A loaded climb (first open or a new page): fill the note editor once and
    /// point the owner actions at it.
    private func pageChanged(ui: DetailScreenState) {
        // A refused send belongs to the climb it was raised on (Android resets it on a page switch).
        if boardFix != nil && boardFixUuid != ui.uuid { boardFix = nil }
        if ui.status == "ready" { advanceTour(from: "open") }
        guard ui.status == "ready", noteUuid != ui.uuid else { return }
        let opened: Bool = !noteUuid.isEmpty
        noteUuid = ui.uuid
        note = ui.personalNote
        if opened { owner?.model.open(uuid: ui.uuid) }
        if opened { ownKilter?.model.load(uuid: ui.uuid) }
    }

    private func lightUpButton(model: DetailScreenModel) -> some View {
        Button {
            advanceTour(from: "project")
            if ble.state.connected { sendChecked(model: model) } else { showBle = true }
        } label: {
            Label(ble.state.connected ? LI("detail_light_up") : L("board_ble_title"), systemImage: "lightbulb.max")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(ble.state.sending)
        .tourAnchor("light", in: anchors)
    }

    /// Quantum climbs are lit through numbered board layers (Android's rack and lamp), in a sheet here.
    private func quantumLayersButton(ui: DetailScreenState) -> some View {
        Button { showQuantumLayers = true } label: {
            Label(LI("detail_light_up"), systemImage: "square.stack.3d.up")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .accessibilityIdentifier("detail_quantum_layers")
        .modifier(QuantumLayersSheetModifier(core: core, ble: ble, climbUuid: ui.uuid, angle: ui.angle,
                                             isPresented: $showQuantumLayers,
                                             onBoardFixed: { leaveAfterFix = true }))
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
                if let rest { RestTimerBanner(model: rest.model, ui: rest.state) }
                BoardCanvasView(imagePaths: ui.imagePaths, holds: shownHolds(ui), aspect: CGFloat(ui.boardAspect))
                    .simultaneousGesture(pageSwipe(model: model))
                    .overlay { routeCountdown }
                routeControls
                pager(model: model, ui: ui)
                stats(model: model, ui: ui)
                actions(model: model, ui: ui)
                if !ui.notes.isEmpty { Text(ui.notes).font(.callout) }
                betaLinks(ui: ui)
                noteEditor(model: model, ui: ui)
                history(model: model, ui: ui)
            }
            .padding()
        }
        // Android's BleStatusArea on the detail: the board link's chip under the running playlist's banner,
        // nearby phones below it; their climb opens here in place (Android `switchClimb`).
        .modifier(NearbyStatusInset(core: core, ble: ble, currentClimbUuid: ui.uuid, currentClimbAngle: ui.angle) { uuid, angle in model.open(uuid: uuid, angle: angle) })
        .modifier(BleStatusInset(ble: ble) { showBle = true })
        .safeAreaInset(edge: .top, spacing: 0) { SessionPlaylistBanner(core: core) }
    }

    /// Android's route preview draws every section at once; otherwise the current one.
    private func shownHolds(_ ui: DetailScreenState) -> [DetailHoldUi] {
        if let playback, playback.state.showPreview {
            return playback.state.previewHolds
        }
        return ui.holds
    }

    /// Android's countdown over the board before route playback starts.
    @ViewBuilder private var routeCountdown: some View {
        if let playback {
            RoutePlaybackCountdown(seconds: playback.state.countdownSeconds)
        }
    }

    /// Android `RoutePlaybackControls`, only for a route (more than one section).
    @ViewBuilder private var routeControls: some View {
        if let playback, playback.state.isRoute {
            RoutePlaybackControls(model: playback.model, ui: playback.state)
        }
    }

    /// Android's horizontal pager: a clearly horizontal swipe over the board turns the page.
    private func pageSwipe(model: DetailScreenModel) -> some Gesture {
        DragGesture(minimumDistance: 24).onEnded { value in
            let dx: CGFloat = value.translation.width
            let dy: CGFloat = value.translation.height
            guard abs(dx) > 60, abs(dx) > abs(dy) * 2 else { return }
            if dx < 0 { model.nextPage() } else { model.previousPage() }
        }
    }

    /// Previous/next through the list the climb was opened from.
    @ViewBuilder private func pager(model: DetailScreenModel, ui: DetailScreenState) -> some View {
        if ui.pageCount > 1 {
            let index: Int = Int(ui.pageIndex)
            let count: Int = Int(ui.pageCount)
            HStack {
                Button { model.previousPage() } label: { Image(systemName: "chevron.left") }
                    .disabled(index <= 0)
                    .accessibilityLabel(L("cd_previous"))
                Spacer()
                Text("\(index + 1) / \(count)")
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(.secondary)
                Spacer()
                Button { model.nextPage() } label: { Image(systemName: "chevron.right") }
                    .disabled(index >= count - 1)
                    .accessibilityLabel(L("cd_next"))
            }
            .buttonStyle(.bordered)
            .accessibilityIdentifier("detail_pager")
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
            setterLine(ui: ui)
            DetailInfoBlock(ui: ui)
        }
    }

    /// Android detail: a CruxCoach setter's name opens the setter's profile; a catalogue setter is plain text.
    @ViewBuilder private func setterLine(ui: DetailScreenState) -> some View {
        if !ui.setter.isEmpty && !ui.setterPubkey.isEmpty {
            NavigationLink {
                SetterDetailView(core: core, pubkey: ui.setterPubkey, fallbackName: ui.setter)
            } label: {
                Label(ui.setter, systemImage: "person").font(.footnote).foregroundStyle(Color.orange)
            }
        } else if !ui.setter.isEmpty {
            Label(ui.setter, systemImage: "person").font(.footnote).foregroundStyle(.secondary)
        }
    }

    private func actions(model: DetailScreenModel, ui: DetailScreenState) -> some View {
        VStack(spacing: 10) {
            if ui.isQuantum {
                quantumLayersButton(ui: ui)
            } else {
                lightUpButton(model: model)
            }
            if let boardFix {
                BoardFixRow(mismatch: boardFix) { showBoardFix = true }
            } else if ui.sendRequestFailure != "none" {
                Text(sendRequestMessage(ui.sendRequestFailure)).font(.footnote).foregroundStyle(.red)
            } else if ble.state.sendResult != "none" && ble.state.sendResult != "ok" {
                Text(LI("detail_send_failed", ble.state.sendResult)).font(.footnote).foregroundStyle(.red)
            }
            HStack {
                Button { advanceTour(from: "project"); model.quickLog(isSend: false) } label: {
                    Label(LI("detail_attempt"), systemImage: "plus").frame(maxWidth: .infinity)
                }
                Button { advanceTour(from: "project"); model.quickLog(isSend: true) } label: {
                    Label(LI("detail_sent"), systemImage: "checkmark").frame(maxWidth: .infinity)
                }
                Button { model.showLogDialog() } label: {
                    Label(LI("detail_log"), systemImage: "square.and.pencil").frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.bordered)
            .disabled(ui.quickLogging)
            .tourAnchor("quicklog", in: anchors)
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

    /// Android `UserAscentHistory`: every own entry of this climb with edit and delete.
    @ViewBuilder private func history(model: DetailScreenModel, ui: DetailScreenState) -> some View {
        if !ui.ascents.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text(L("board_ascenthistory_title")).font(.headline)
                ForEach(ui.ascents, id: \.uuid) { entry in
                    DetailAscentRow(entry: entry,
                                    onEdit: { model.editAscent(uuid: entry.uuid) },
                                    onDelete: { model.requestDeleteAscent(uuid: entry.uuid) })
                }
            }
            .alert(L("board_detail_delete_ascent_title"), isPresented: deleteAscentBinding(model: model, ui: ui)) {
                Button(L("action_cancel"), role: .cancel) { model.dismissDeleteAscent() }
                Button(L("action_delete"), role: .destructive) { model.confirmDeleteAscent() }
            } message: {
                Text(L("board_detail_delete_ascent_message"))
            }
        }
    }

    private func deleteAscentBinding(model: DetailScreenModel, ui: DetailScreenState) -> Binding<Bool> {
        Binding(get: { ui.deleteAscentPending }, set: { if !$0 { model.dismissDeleteAscent() } })
    }
}

/// Android's guided board fix from the detail (`BoardPickerDialog` with the send's mismatch):
/// confirming or closing it clears the refusal and returns to the board browser, as on Android.
/// A guided fix confirmed in the connection sheet or the Quantum rack leaves once that sheet is down.
private struct DetailBoardFixLayer: ViewModifier {
    let core: AppCore
    let mismatch: BoardMismatchUi?
    @Binding var showFix: Bool
    let showBle: Bool
    let showQuantumLayers: Bool
    let leaveAfterFix: Bool
    let onLeave: () -> Void

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: $showFix, onDismiss: onLeave) { picker }
            .onChange(of: showBle) { _, open in leaveIfFixed(open) }
            .onChange(of: showQuantumLayers) { _, open in leaveIfFixed(open) }
    }

    private var picker: some View {
        NavigationStack {
            BoardPickerView(core: core, brandWires: BoardFixSupport.installedBrandWires(core), onPick: { option in
                core.applyBoardChoice(option: option)
                showFix = false
            }, mismatch: mismatch)
        }
    }

    private func leaveIfFixed(_ open: Bool) {
        if !open && leaveAfterFix { onLeave() }
    }
}

/// Android's two delete confirmations: the local draft and the signed relay deletion.
private struct OwnerDeleteDialogs: ViewModifier {
    let owner: ScreenHost<ClimbOwnerActionsModel, ClimbOwnerActionsState>?

    func body(content: Content) -> some View {
        content
            .alert(L("climb_creator_drafts_delete_title"), isPresented: binding("draft")) {
                Button(L("action_cancel"), role: .cancel) { owner?.model.dismissDelete() }
                Button(L("climb_creator_drafts_delete_confirm"), role: .destructive) { owner?.model.confirmDelete() }
            } message: {
                Text(L("climb_creator_drafts_delete_message", owner?.state.climbName ?? ""))
            }
            .alert(L("community_climb_delete_confirm_title"), isPresented: binding("publication")) {
                Button(L("community_climb_delete_cancel_button"), role: .cancel) { owner?.model.dismissDelete() }
                Button(L("community_climb_delete_confirm_button"), role: .destructive) { owner?.model.confirmDelete() }
            } message: {
                Text(publicationMessage)
            }
    }

    private var publicationMessage: String {
        let kilter: Bool = owner?.state.dialogKilterWarning ?? false
        return kilter ? L("community_climb_delete_confirm_kilter_warning") : L("community_climb_delete_confirm_nostr_only")
    }

    private func binding(_ code: String) -> Binding<Bool> {
        Binding(get: { owner?.state.dialogCode == code },
                set: { if !$0 && owner?.state.dialogCode == code { owner?.model.dismissDelete() } })
    }
}

/// Which editor the detail opens: an own climb to edit, or a remix of this one.
private struct DetailEditorTarget: Identifiable {
    let edit: Bool
    var id: String { edit ? "edit" : "remix" }
}

/// The add-to-list and editor sheets and the "link copied" note, kept out of the
/// detail's own modifier chain so it stays within the type checker's budget.
private struct DetailExtraSheets: ViewModifier {
    let core: AppCore
    let climbUuid: String
    let angle: Int32
    @Binding var showLists: Bool
    @Binding var editorTarget: DetailEditorTarget?
    let linkCopied: Bool

    init(core: AppCore, uuid: String, angle: Int32, showLists: Binding<Bool>,
         editorTarget: Binding<DetailEditorTarget?>, linkCopied: Bool) {
        self.core = core
        self.climbUuid = uuid
        self.angle = angle
        self._showLists = showLists
        self._editorTarget = editorTarget
        self.linkCopied = linkCopied
    }

    func body(content: Content) -> some View {
        content
            .sheet(item: $editorTarget) { target in
                NavigationStack {
                    CreatorView(core: core, editUuid: target.edit ? climbUuid : "", forkUuid: target.edit ? "" : climbUuid)
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) { Button(L("action_close")) { editorTarget = nil } }
                        }
                }
            }
            .sheet(isPresented: $showLists) {
                NavigationStack { AddToListSheet(core: core, climbUuid: climbUuid, angle: angle) }
            }
            .overlay(alignment: .bottom) {
                if linkCopied {
                    Text(L("board_detail_link_copied"))
                        .font(.footnote)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .background(.thinMaterial, in: Capsule())
                        .padding(.bottom, 24)
                }
            }
    }
}

/// Android's `RestTimerStartDialog`, kept out of the detail's modifier chain.
private struct DetailRestTimerSheet: ViewModifier {
    let rest: ScreenHost<RestTimerModel, RestTimerUiState>?
    @Binding var isPresented: Bool

    func body(content: Content) -> some View {
        content.sheet(isPresented: $isPresented) {
            if let rest {
                NavigationStack {
                    RestTimerStartSheet(model: rest.model, initialSeconds: rest.state.defaultSeconds) {
                        isPresented = false
                    }
                }
                .presentationDetents([.medium])
            }
        }
    }
}

/// The guided first browse on the climb detail (Android `TourHost` around the
/// detail): light the climb, log it, then back to the list for the logbook.
private struct DetailTourLayer: ViewModifier {
    let anchors: TourAnchors
    let tour: BrowserTourModel?
    let step: String
    let connected: Bool
    /// A quick log just landed (its undo is offered): the log step is done.
    let quickLogged: Bool
    let visible: Bool
    let onQuickLogged: () -> Void
    let onMove: () -> Void

    func body(content: Content) -> some View {
        content
            .tourSpotlight(anchors: anchors, tour: tour, step: step, target: target,
                           message: message, visible: visible, onMove: onMove)
            .onChange(of: quickLogged) { _, logged in
                if logged { onQuickLogged() }
            }
            .onAppear { onMove() }
    }

    /// The system back button cannot be measured: the logbook step's text stands alone.
    private var target: String? {
        switch step {
        case "project": return "light"
        case "log": return "quicklog"
        case "logbook": return ""
        default: return nil
        }
    }

    private var message: String {
        switch step {
        case "project": return connected ? L("tour_spotlight_project") : L("tour_spotlight_detail_connect")
        case "log": return LI("tour_spotlight_quicklog_ios")
        case "logbook": return L("tour_spotlight_logbook_back")
        default: return ""
        }
    }
}

/// Android `ClimbDetailInfoSheet`'s stat row (moves or frames, quality, sends), its badges
/// (match rule, MoonBoard method, benchmark) and the first-ascent line.
private struct DetailInfoBlock: View {
    let ui: DetailScreenState

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            statRow
            badges
            if !ui.faName.isEmpty {
                Text(faLine).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private var statRow: some View {
        let moves: Int32 = ui.infoMoveCount
        let sends: Int64 = ui.infoSends
        let quality: String = ui.infoQuality.isEmpty ? "–" : ui.infoQuality
        return HStack {
            stat(ui.infoIsRoute ? L("board_detail_frames") : L("board_moves"), String(moves))
            stat(L("board_quality"), quality)
            stat(L("board_sends"), String(sends))
        }
    }

    private func stat(_ title: String, _ value: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.headline)
            Text(title).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder private var badges: some View {
        if ui.matchKnown || ui.methodCode != "none" || ui.isBenchmark {
            HStack(spacing: 8) {
                if ui.matchKnown { matchBadge }
                if ui.methodCode != "none" { badge(methodTitle, systemImage: nil, color: .orange) }
                if ui.isBenchmark { badge(L("board_detail_benchmark"), systemImage: "checkmark.seal.fill", color: .orange) }
            }
        }
    }

    private var matchBadge: some View {
        let noMatch: Bool = ui.noMatch
        return badge(noMatch ? L("board_detail_no_matching") : L("board_detail_matching"),
                     systemImage: noMatch ? "hand.raised.slash" : "hand.raised",
                     color: noMatch ? .red : .green)
    }

    private var methodTitle: String {
        switch ui.methodCode {
        case "footless": return L("board_detail_method_footless")
        case "footlessKickboard": return L("board_detail_method_footless_kickboard")
        default: return L("board_detail_method_no_kickboard")
        }
    }

    private func badge(_ title: String, systemImage: String?, color: Color) -> some View {
        HStack(spacing: 4) {
            if let systemImage { Image(systemName: systemImage) }
            Text(title)
        }
        .font(.caption.bold())
        .foregroundStyle(color)
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: 6))
    }

    /// Android prints "FA: name (dd.MM.yyyy)" without a string resource.
    private var faLine: String {
        ui.faDate.isEmpty ? "FA: " + ui.faName : "FA: " + ui.faName + " (" + ui.faDate + ")"
    }
}

/// One row of Android's `UserAscentHistory`: date, outcome, angle, mirror, tries or flash,
/// stars and comment, then edit and delete.
private struct DetailAscentRow: View {
    let entry: DetailAscentUi
    let onEdit: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.dateLabel).font(.subheadline.bold())
                badges
                if !entry.comment.isEmpty {
                    // Straight quotes, as Android prints them.
                    Text("\"" + entry.comment + "\"").font(.caption).foregroundStyle(.secondary).lineLimit(3)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onEdit) {
                Image(systemName: "pencil").accessibilityLabel(L("cd_edit"))
            }
            .buttonStyle(.borderless)
            .foregroundStyle(.secondary)
            Button(action: onDelete) {
                Image(systemName: "trash").accessibilityLabel(L("action_delete"))
            }
            .buttonStyle(.borderless)
            .foregroundStyle(.red)
        }
        .padding(.vertical, 2)
    }

    private var badges: some View {
        let angle: Int32 = entry.angle
        let quality: Int32 = entry.quality
        return HStack(spacing: 8) {
            Text(entry.isSend ? L("board_ascent_send") : L("board_ascent_attempt"))
                .bold()
                .foregroundStyle(entry.isSend ? Color.green : Color.red)
            Text(String(angle) + "°").foregroundStyle(.secondary)
            if entry.isMirror {
                Image(systemName: "arrow.left.and.right").foregroundStyle(.secondary)
                    .accessibilityLabel(L("cd_mirrored"))
            }
            if entry.isSend {
                Text(triesLabel).bold().foregroundStyle(entry.isFlash ? Color.green : Color.orange)
            }
            if quality > 0 {
                Text(String(quality) + "★").foregroundStyle(.yellow)
            }
        }
        .font(.caption)
    }

    /// Android: "Flash" for a true flash, else first try or the tries.
    private var triesLabel: String {
        let tries: Int64 = entry.tries
        if entry.isFlash { return "Flash" }
        if tries <= 1 { return L("board_ascent_first_try") }
        return L("board_ascent_tries", Int(tries))
    }
}
