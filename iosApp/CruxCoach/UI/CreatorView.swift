import CruxCoachCore
import SwiftUI

/// Set your own problem: tap holds on the board, paint roles, name it, save a
/// draft, and optionally publish it to the community.
struct CreatorView: View {
    let core: AppCore
    @State private var host: ScreenHost<CreatorScreenModel, CreatorScreenState>?
    @State private var loadedMoonPath = ""
    @State private var showPublish = false
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
            let model = core.makeCreatorScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.open()
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
                actions(model: model, ui: ui)
            }
            .padding()
        }
        .onChange(of: ui.moonLayoutPath) { _, path in loadMoonLayout(model: model, path: path) }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { model.undo() } label: { Image(systemName: "arrow.uturn.backward") }
                    .disabled(!ui.canUndo)
                Button { model.redo() } label: { Image(systemName: "arrow.uturn.forward") }
                    .disabled(!ui.canRedo)
                Button(LI("creator_drafts")) { model.openDrafts() }
            }
        }
        .sheet(isPresented: Binding(get: { ui.draftsSheetOpen }, set: { if !$0 { model.closeDrafts() } })) {
            NavigationStack { draftsSheet(model: model, ui: ui) }
        }
        .alert(LI("creator_duplicate_title"), isPresented: Binding(
            get: { !ui.duplicateUuid.isEmpty },
            set: { if !$0 { model.dismissDuplicate() } })) {
            Button(L("action_cancel"), role: .cancel) { model.cancelPublish() }
            if !ui.duplicateBlocksPublish {
                Button(LI("creator_publish")) { model.confirmPublishWithDuplicate(autoNoteText: "") }
            }
        } message: {
            Text(LI("creator_duplicate", ui.duplicateName))
        }
    }

    private func board(model: CreatorScreenModel, ui: CreatorScreenState) -> some View {
        // The canvas reports normalized coordinates; the model turns them into
        // a placement id with the same geometry the browser draws with.
        CreatorCanvas(imagePaths: ui.imagePaths,
                      holds: ui.holds,
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
            Button(LI("creator_publish")) { model.publish(autoNoteText: "") }
                .disabled(!ui.isValid || ui.publishPhase == "publishing")
            if ui.publishPhase == "publishing" { ProgressView() }
            if ui.publishPhase == "published" {
                Label(LI("creator_published", Int(ui.publishAccepted), Int(ui.publishAttempted)), systemImage: "checkmark.circle")
                    .font(.footnote)
                    .foregroundStyle(ui.publishPartial ? .orange : .green)
            }
            if !ui.publishFailureCode.isEmpty && ui.publishFailureCode != "none" {
                Text(LI("creator_publish_failed", ui.publishFailureCode)).font(.footnote).foregroundStyle(.red)
            }
            if !ui.savedDraftUuid.isEmpty {
                Text(LI("creator_draft_saved")).font(.footnote).foregroundStyle(.green)
                    .onAppear { model.consumeSavedDraft() }
            }
            Button(LI("creator_discard"), role: .destructive) { model.discard() }.font(.footnote)
        }
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
        case "noStart": return LI("creator_issue_no_start")
        case "noFinish": return LI("creator_issue_no_finish")
        case "tooFew": return LI("creator_issue_too_few", Int(issue.limit))
        case "tooMany": return LI("creator_issue_too_many", Int(issue.limit))
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
