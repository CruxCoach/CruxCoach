import CruxCoachCore
import SwiftUI

/// Android's publish-gate dialogs of the climb editor: the duplicate warning (with
/// "Update existing" / "Publish as new" for the user's own climb) and the profile hint
/// before the first publish, plus the profile editor that "Set up profile" opens.
struct CreatorPublishDialogs: ViewModifier {
    let core: AppCore
    let model: CreatorScreenModel
    let ui: CreatorScreenState
    /// The note text the publish sends (blank = no note), as the publish button passes it.
    let noteText: String
    /// The open dialog was answered. The core's state reaches SwiftUI a moment later; without
    /// this the closing alert would be shown once more in between.
    @State private var answered = false

    func body(content: Content) -> some View {
        content
            .alert(duplicateTitle, isPresented: duplicateShown) {
                duplicateButtons
            } message: {
                Text(duplicateMessage)
            }
            .alert(L("profile_hint_dialog_title"), isPresented: profileHintShown) {
                Button(L("profile_hint_dialog_setup")) { answer { model.acceptProfileHint() } }
                Button(L("profile_hint_dialog_later"), role: .cancel) {
                    answer { model.skipProfileHint(autoNoteText: noteText) }
                }
            } message: {
                Text(L("profile_hint_dialog_body"))
            }
            .onChange(of: dialogPending) { _, pending in
                if !pending { answered = false }
            }
            .navigationDestination(isPresented: profileSetupShown) { ProfileView(core: core) }
    }

    private var dialogPending: Bool {
        ui.duplicateBlocksPublish || ui.profileHintPending
    }

    private func answer(_ action: () -> Void) {
        answered = true
        action()
    }

    private var duplicateShown: Binding<Bool> {
        // An iOS alert closes only through its buttons, and each one answers it.
        Binding(get: { ui.duplicateBlocksPublish && !ui.duplicateUuid.isEmpty && !answered },
                set: { _ in })
    }

    private var profileHintShown: Binding<Bool> {
        Binding(get: { ui.profileHintPending && !answered }, set: { _ in })
    }

    private var profileSetupShown: Binding<Bool> {
        Binding(get: { ui.profileSetupRequested }, set: { open in if !open { model.acknowledgeProfileSetup() } })
    }

    private var duplicateTitle: String {
        if !ui.duplicateIsOwn { return L("climb_creator_dup_title") }
        return ui.duplicateIsDraft ? L("climb_creator_dup_own_draft_title") : L("climb_creator_dup_own_title")
    }

    private var duplicateMessage: String {
        let name: String = ui.duplicateName
        if !ui.duplicateIsOwn { return L("climb_creator_dup_message", name) }
        return ui.duplicateIsDraft ? L("climb_creator_dup_own_draft_message", name) : L("climb_creator_dup_own_message", name)
    }

    @ViewBuilder
    private var duplicateButtons: some View {
        if ui.duplicateIsOwn {
            Button(L("climb_creator_dup_update_existing")) {
                answer { model.updateExistingOnDuplicate(autoNoteText: noteText) }
            }
            Button(L("climb_creator_dup_publish_new")) {
                answer { model.confirmPublishWithDuplicate(autoNoteText: noteText) }
            }
            Button(L("climb_creator_dup_continue"), role: .cancel) { answer { model.cancelPublish() } }
        } else {
            Button(L("climb_creator_dup_publish_anyway")) {
                answer { model.confirmPublishWithDuplicate(autoNoteText: noteText) }
            }
            Button(L("climb_creator_dup_continue"), role: .cancel) { answer { model.cancelPublish() } }
        }
    }
}

/// Android's board link in the climb editor: the connection sheet behind the toolbar's
/// board button, the live LED mirror while the editor is on screen, and the dialog when
/// the connected board belongs to another family. A board chosen in the guided fix leaves
/// the editor, whose canvas belongs to the old board (Android returns to the browser).
struct CreatorBoardLink: ViewModifier {
    let core: AppCore
    let model: CreatorScreenModel
    let ui: CreatorScreenState
    let ble: ScreenHost<BleScreenModel, BleScreenState>?
    @Binding var showBle: Bool
    let onBoardChanged: () -> Void
    /// The mismatch the user closed; a different one asks again.
    @State private var dismissedMismatch = ""

    func body(content: Content) -> some View {
        content
            .onAppear { model.startBoardMirror() }
            .onDisappear { model.stopBoardMirror() }
            .sheet(isPresented: $showBle) { bleSheet }
            .alert(L("board_mismatch_title"), isPresented: mismatchShown) {
                Button(L("board_mismatch_fix_action")) { showBle = true }
                Button(L("action_cancel"), role: .cancel) { dismissedMismatch = mismatchKey }
            } message: {
                Text(mismatchMessage)
            }
    }

    private var mismatchKey: String {
        "\(ui.boardMismatch.kindCode)|\(ui.boardMismatch.messageArg1)|\(ui.boardMismatch.messageArg2)"
    }

    private var mismatchShown: Binding<Bool> {
        Binding(get: { ui.boardMismatch.active && !showBle && dismissedMismatch != mismatchKey },
                set: { _ in })
    }

    private var mismatchMessage: String {
        let first: String = ui.boardMismatch.messageArg1
        let second: String = ui.boardMismatch.messageArg2
        return L(ui.boardMismatch.messageKey, first, second)
    }

    @ViewBuilder
    private var bleSheet: some View {
        if let ble {
            NavigationStack {
                BleView(ble: ble, fix: BoardMismatchRoute(
                    core: core, activeBrandWire: ui.brandWire,
                    brandWires: BoardFixSupport.installedBrandWires(core),
                    onPick: { option in
                        core.applyBoardChoice(option: option)
                        showBle = false
                        onBoardChanged()
                    }))
            }
        }
    }
}

/// The popular-holds heatmap under the drafted holds: warmer and larger where more
/// climbs that share the holds so far use a hold (Android's editor overlay).
enum CreatorHeat {
    static func draw(_ heat: [CreatorHeatUi], in context: inout GraphicsContext, size: CGSize) {
        let base: CGFloat = max(size.width * 0.03, 6)
        for spot in heat {
            let strength: CGFloat = CGFloat(spot.intensity)
            let radius: CGFloat = base * (0.6 + 0.6 * strength)
            let x: CGFloat = CGFloat(spot.x) * size.width
            let y: CGFloat = CGFloat(spot.y) * size.height
            let rect = CGRect(x: x - radius, y: y - radius, width: radius * 2, height: radius * 2)
            let opacity: Double = 0.25 + 0.5 * Double(strength)
            context.fill(Path(ellipseIn: rect), with: .color(Color.orange.opacity(opacity)))
        }
    }
}
