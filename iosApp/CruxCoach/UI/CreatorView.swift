import CruxCoachCore
import SwiftUI

/// Set your own problem: tap holds on the board, paint roles, name it, save a
/// draft, and optionally publish it to the community.
struct CreatorView: View {
    let core: AppCore
    /// Opens an own climb for editing, or any climb as a remix (Android `climb_creator?editUuid=&forkUuid=`).
    var editUuid: String = ""
    var forkUuid: String = ""
    @State private var host: ScreenHost<CreatorScreenModel, CreatorScreenState>?
    @State private var loadedMoonPath = ""
    @State private var showPublish = false
    /// The shared board link, for the toolbar's board button and its connection sheet.
    @State private var ble: ScreenHost<BleScreenModel, BleScreenState>?
    @State private var showBle = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(LI("creator_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard host == nil else { return }
            // Kilter publishing, autosave, profile hint and the live LED mirror (Android's editor).
            let model = core.makeCreatorScreenComplete()
            // The profile's note default switched the toggle on: seed Android's template.
            if model.currentState.alsoPostNote {
                model.setAlsoPostNote(value: true, defaultTemplate: L("auto_note_default_template"))
            }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            let bleModel = core.bleScreen
            ble = ScreenHost(model: bleModel, initial: bleModel.currentState) { model, onState in
                model.watch(onState: onState)
            }
            model.startBoardMirror()
            if !editUuid.isEmpty {
                model.openForEdit(uuid: editUuid)
            } else if !forkUuid.isEmpty {
                model.openForRemix(uuid: forkUuid)
            } else {
                model.open()
            }
        }
    }

    @ViewBuilder
    private func screen(model: CreatorScreenModel, ui: CreatorScreenState) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                board(model: model, ui: ui)
                brushes(model: model, ui: ui)
                fields(model: model, ui: ui)
                issues(ui: ui)
                autoNote(model: model, ui: ui)
                if ui.kilterPublishAvailable { CreatorKilterSection(core: core) }
                actions(model: model, ui: ui)
            }
            .padding()
        }
        .onChange(of: ui.moonLayoutPath) { _, path in loadMoonLayout(model: model, path: path) }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) { toolbarButtons(model: model, ui: ui) }
        }
        .sheet(isPresented: Binding(get: { ui.draftsSheetOpen }, set: { if !$0 { model.closeDrafts() } })) {
            NavigationStack { draftsSheet(model: model, ui: ui) }
        }
        .modifier(CreatorPublishDialogs(core: core, model: model, ui: ui, noteText: noteText(ui)))
        .modifier(CreatorBoardLink(core: core, model: model, ui: ui, ble: ble, showBle: $showBle,
                                   onBoardChanged: { dismiss() }))
    }

    /// Android's editor toolbar: board connection, "Clear editor", undo/redo, and the
    /// overflow menu with the drafts and the popular-holds heatmap.
    @ViewBuilder
    private func toolbarButtons(model: CreatorScreenModel, ui: CreatorScreenState) -> some View {
        let connected: Bool = ble?.state.connected ?? false
        Button { showBle = true } label: {
            Image(systemName: connected ? "lightbulb.fill" : "lightbulb")
        }
        .foregroundStyle(connected ? Color.green : Color.accentColor)
        .accessibilityLabel(connected ? L("cd_board_connected") : L("cd_board_connect"))
        .accessibilityIdentifier("climb_creator_ble_connect_button")
        Button { model.discard() } label: { Image(systemName: "trash") }
            .accessibilityLabel(L("climb_creator_clear"))
        Button { model.undo() } label: { Image(systemName: "arrow.uturn.backward") }
            .disabled(!ui.canUndo)
        Button { model.redo() } label: { Image(systemName: "arrow.uturn.forward") }
            .disabled(!ui.canRedo)
        Menu {
            Button(LI("creator_drafts")) { model.openDrafts() }
            if ui.heatmapAvailable {
                Toggle(L("climb_creator_heatmap_toggle"), isOn: Binding(
                    get: { ui.heatmapEnabled },
                    set: { _ in model.toggleHeatmap() }))
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
    }

    private func board(model: CreatorScreenModel, ui: CreatorScreenState) -> some View {
        // The canvas reports normalized coordinates; the model turns them into
        // a placement id with the same geometry the browser draws with.
        CreatorCanvas(imagePaths: ui.imagePaths,
                      holds: ui.holds,
                      heat: ui.heat,
                      aspect: CGFloat(ui.boardAspect),
                      onTap: { x, y in model.tapBoard(x: Float(x), y: Float(y)) },
                      onLongPress: { x, y in model.longPressBoard(x: Float(x), y: Float(y)) })
    }

    private func brushes(model: CreatorScreenModel, ui: CreatorScreenState) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(ui.brushes, id: \.roleId) { brush in
                    Button { model.toggleBrush(roleId: brush.roleId) } label: {
                        HStack(spacing: 6) {
                            Circle().fill(Color(argb: brush.argb)).frame(width: 14, height: 14)
                            Text(brushLabel(brush.code))
                            if brush.count > 0 { Text("\(brush.count)").foregroundStyle(.secondary) }
                        }
                        .padding(.horizontal, 10).padding(.vertical, 6)
                        .background(brush.isActive ? Color.accentColor.opacity(0.2) : Color(.secondarySystemBackground))
                        .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(brush.isActive ? .isSelected : [])
                }
            }
        }
    }

    private func fields(model: CreatorScreenModel, ui: CreatorScreenState) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            TextField(LI("creator_name"), text: Binding(get: { ui.name }, set: { model.setName(name: $0) }))
                .textFieldStyle(.roundedBorder)
            TextField(LI("creator_description"), text: Binding(get: { ui.description_ }, set: { model.setDescription(description: $0) }), axis: .vertical)
                .textFieldStyle(.roundedBorder)
            HStack {
                Text(L("board_angle"))
                Spacer()
                Picker(L("board_angle"), selection: Binding(get: { ui.angle }, set: { model.setAngle(angle: $0) })) {
                    ForEach(ui.angleOptions.map { Int(truncating: $0) }, id: \.self) { Text("\($0)°").tag(Int32($0)) }
                }
                .pickerStyle(.menu)
            }
            HStack {
                Text(LI("creator_grade"))
                Spacer()
                Stepper(ui.gradeLabel, value: Binding(get: { Int(ui.gradeId) }, set: { model.setGradeId(gradeId: Int32($0)) }),
                        in: Int(ui.minGradeId)...Int(ui.maxGradeId))
            }
        }
    }

    @ViewBuilder
    private func issues(ui: CreatorScreenState) -> some View {
        if !ui.issues.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(ui.issues, id: \.code) { issue in
                    Label(issueText(issue), systemImage: "exclamationmark.circle")
                        .font(.footnote).foregroundStyle(.orange)
                }
            }
        }
    }

    private func actions(model: CreatorScreenModel, ui: CreatorScreenState) -> some View {
        VStack(spacing: 10) {
            Button(LI("creator_save_draft")) { model.saveDraft() }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
                .disabled(!ui.isValid)
            Button(LI("creator_publish")) { model.publish(autoNoteText: noteText(ui)) }
                .disabled(!ui.isValid || ui.publishPhase == "publishing")
            if ui.publishPhase == "publishing" { ProgressView() }
            if ui.publishPhase == "published" {
                Label(LI("creator_published", Int(ui.publishAccepted), Int(ui.publishAttempted)), systemImage: "checkmark.circle")
                    .font(.footnote)
                    .foregroundStyle(ui.publishPartial ? .orange : .green)
                publishFollowUps(ui: ui)
            }
            if !ui.publishFailureCode.isEmpty && ui.publishFailureCode != "none" {
                Text(LI("creator_publish_failed", ui.publishFailureCode)).font(.footnote).foregroundStyle(.red)
            }
            if !ui.savedDraftUuid.isEmpty {
                Text(LI("creator_draft_saved")).font(.footnote).foregroundStyle(.green)
                    .onAppear { model.consumeSavedDraft() }
            }
        }
    }

    /// Android's per-publish "Also post to Nostr" with its editable text; the
    /// placeholders stay literal and are filled in at publish time.
    @ViewBuilder
    private func autoNote(model: CreatorScreenModel, ui: CreatorScreenState) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle(L("climb_creator_also_post_note"), isOn: Binding(
                get: { ui.alsoPostNote },
                set: { model.setAlsoPostNote(value: $0, defaultTemplate: L("auto_note_default_template")) }))
            Text(L("climb_creator_also_post_note_hint")).font(.footnote).foregroundStyle(.secondary)
            if ui.alsoPostNote {
                TextField(L("climb_creator_auto_note_text_label"),
                          text: Binding(get: { ui.autoNoteText }, set: { model.setAutoNoteText(text: $0) }),
                          axis: .vertical)
                    .lineLimit(4...10)
                    .textFieldStyle(.roundedBorder)
                    .font(.footnote)
                Text(L("climb_creator_auto_note_placeholders_hint")).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    /// The text the publish sends: blank means no note.
    private func noteText(_ ui: CreatorScreenState) -> String {
        guard ui.alsoPostNote else { return "" }
        return ui.autoNoteText.isEmpty ? L("auto_note_default_template") : ui.autoNoteText
    }

    /// After the Nostr publish: the Kilter side and the note's reach (Android's follow-up snackbars).
    @ViewBuilder
    private func publishFollowUps(ui: CreatorScreenState) -> some View {
        if ui.kilterConnectNudge {
            Text(L("climb_creator_kilter_connect_nudge")).font(.footnote).foregroundStyle(.secondary)
        } else if ui.kilterOutcomeCode == "synced" {
            Text(L("climb_creator_publish_both_ok")).font(.footnote).foregroundStyle(.green)
        } else if ui.kilterOutcomeCode == "failed" || ui.kilterOutcomeCode == "diverged" {
            Text(L("climb_creator_publish_kilter_pending")).font(.footnote).foregroundStyle(.orange)
            if let detail = kilterDetail(ui) {
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
        }
        if ui.autoNoteZeroRelays {
            Text(L("climb_creator_auto_note_zero_relays")).font(.footnote).foregroundStyle(.orange)
        }
    }

    private func kilterDetail(_ ui: CreatorScreenState) -> String? {
        let key: String = ui.kilterOutcomeMessageKey
        if key.isEmpty { return nil }
        let code: Int = Int(ui.kilterOutcomeHttpCode)
        return key.hasSuffix("_with_code") ? L(key, code) : L(key)
    }

    private func draftsSheet(model: CreatorScreenModel, ui: CreatorScreenState) -> some View {
        List {
            if ui.drafts.isEmpty { Text(LI("creator_no_drafts")) }
            ForEach(ui.drafts, id: \.uuid) { draft in
                Button { model.loadDraft(uuid: draft.uuid); model.closeDrafts() } label: {
                    VStack(alignment: .leading) {
                        Text(draft.name.isEmpty ? LI("creator_untitled") : draft.name)
                        Text(LI("creator_draft_holds", Int(draft.holdCount)))
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .swipeActions {
                    Button(LI("logbook_delete"), role: .destructive) { model.deleteDraft(uuid: draft.uuid) }
                }
            }
        }
        .navigationTitle(LI("creator_drafts"))
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("action_close")) { model.closeDrafts() } } }
    }

    private func loadMoonLayout(model: CreatorScreenModel, path: String) {
        guard !path.isEmpty, path != loadedMoonPath,
              let url = Bundle.main.resourceURL?.appendingPathComponent(path),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return }
        loadedMoonPath = path
        model.setMoonLayoutJson(jsonText: text)
    }

    private func brushLabel(_ code: String) -> String {
        switch code {
        case "start": return LI("creator_role_start")
        case "hand": return LI("creator_role_hand")
        case "finish": return LI("creator_role_finish")
        case "foot": return LI("creator_role_foot")
        default: return code
        }
    }

    private func issueText(_ issue: CreatorIssueUi) -> String {
        switch issue.code {
        // The codes of CreatorCodes.issue.
        case "noStartHold": return LI("creator_issue_no_start")
        case "noFinishHold": return LI("creator_issue_no_finish")
        case "tooFewHolds": return LI("creator_issue_too_few", Int(issue.limit))
        case "tooManyHolds": return LI("creator_issue_too_many", Int(issue.limit))
        case "tooManyStarts": return LI("creator_issue_too_many_starts", Int(issue.limit))
        case "tooManyFinishes": return LI("creator_issue_too_many_finishes", Int(issue.limit))
        case "nameMissing": return LI("creator_issue_name_missing")
        case "nameTooLong": return LI("creator_issue_name_long", Int(issue.limit))
        case "descriptionTooLong": return LI("creator_issue_description_long", Int(issue.limit))
        case "angleMissing": return LI("creator_issue_angle_missing")
        case "gradeMissing": return LI("creator_issue_grade_missing")
        default: return issue.code
        }
    }
}

/// The editor's canvas: the same drawing as the browser, plus taps.
private struct CreatorCanvas: View {
    let imagePaths: [String]
    let holds: [CreatorHoldUi]
    /// The popular-holds heatmap, drawn under the holds; empty while it is off.
    let heat: [CreatorHeatUi]
    let aspect: CGFloat
    let onTap: (CGFloat, CGFloat) -> Void
    let onLongPress: (CGFloat, CGFloat) -> Void

    private var image: UIImage? {
        for path in imagePaths {
            if let url = Bundle.main.resourceURL?.appendingPathComponent(path),
               let image = UIImage(contentsOfFile: url.path) {
                return image
            }
        }
        return nil
    }

    var body: some View {
        let loaded = image
        GeometryReader { geo in
            Canvas { context, size in
                if let loaded {
                    context.draw(Image(uiImage: loaded), in: CGRect(origin: .zero, size: size))
                } else {
                    context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(.secondarySystemBackground)))
                }
                CreatorHeat.draw(heat, in: &context, size: size)
                let radius = max(size.width * 0.028, 6)
                for hold in holds {
                    let point = CGPoint(x: CGFloat(hold.x) * size.width, y: CGFloat(hold.y) * size.height)
                    let ring = Path(ellipseIn: CGRect(x: point.x - radius, y: point.y - radius,
                                                      width: radius * 2, height: radius * 2))
                    context.stroke(ring, with: .color(.black.opacity(0.7)), lineWidth: radius * 0.55)
                    context.stroke(ring, with: .color(Color(argb: hold.argb)), lineWidth: radius * 0.35)
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { location in
                onTap(location.x / geo.size.width, location.y / geo.size.height)
            }
            .onLongPressGesture(minimumDuration: 0.4) {
                // Long press without a location: SwiftUI's simple long-press
                // gives none, so the model treats it as "remove last".
                onLongPress(-1, -1)
            }
        }
        .aspectRatio(aspect > 0 ? aspect : 0.65, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .accessibilityLabel(LI("detail_board_a11y", holds.count))
    }
}

/// "Publish own climbs to Kilter" and the publish queue (Android's Kilter account card),
/// shown in the editor of a Kilter climb. The switch is the same per-identity setting.
private struct CreatorKilterSection: View {
    let core: AppCore
    @State private var host: ScreenHost<KilterPublishSettingsModel, KilterPublishSettingsState>?

    var body: some View {
        Group {
            if let host {
                content(model: host.model, ui: host.state)
            } else {
                // A view must exist for `.task` to run.
                ProgressView()
            }
        }
        .task {
            guard host == nil else { return }
            let model = core.makeKilterPublishSettings()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.refresh()
        }
    }

    @ViewBuilder
    private func content(model: KilterPublishSettingsModel, ui: KilterPublishSettingsState) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle(L("kilter_climb_publish_label"), isOn: Binding(
                get: { ui.publishEnabled },
                set: { model.setPublishEnabled(enabled: $0) }))
            if ui.publishEnabled && !ui.signedIn {
                Text(L("kilter_climb_publish_disconnected_hint")).font(.footnote).foregroundStyle(.secondary)
            }
            if ui.signedIn && (ui.pendingCount > 0 || ui.failedCount > 0) {
                queue(model: model, ui: ui)
            }
        }
    }

    @ViewBuilder
    private func queue(model: KilterPublishSettingsModel, ui: KilterPublishSettingsState) -> some View {
        let pending: Int = Int(ui.pendingCount)
        let failed: Int = Int(ui.failedCount)
        VStack(alignment: .leading, spacing: 4) {
            Text(L("kilter_publish_queue_title")).font(.footnote.bold())
            Text(L("kilter_publish_queue_pending", pending)).font(.footnote).foregroundStyle(.secondary)
            if failed > 0 {
                Text(L("kilter_publish_queue_failed", failed)).font(.footnote).foregroundStyle(.red)
            }
            if ui.lastAttemptAtMs > 0 {
                Text(L("kilter_publish_queue_last_attempt") + ": " + Self.format(ui.lastAttemptAtMs))
                    .font(.caption).foregroundStyle(.secondary)
            }
            HStack {
                Button(L("kilter_publish_queue_retry_now")) { model.retryNow() }
                    .disabled(ui.retryRunning)
                if ui.retryRunning { ProgressView() }
            }
        }
        .padding(8)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private static func format(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
