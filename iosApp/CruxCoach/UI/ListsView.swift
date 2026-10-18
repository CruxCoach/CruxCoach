import CruxCoachCore
import SwiftUI

/// Favourites, the ignored list and the user's own lists.
struct ListsView: View {
    let core: AppCore
    @State private var host: ScreenHost<ListsScreenModel, ListsScreenState>?
    @State private var generating = false

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .modifier(RestTimerBannerSlot(core: core))
        .navigationTitle(L("board_lists_title"))
        .task {
            guard host == nil else { return }
            let model = core.makeListsScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.refresh()
        }
    }

    @ViewBuilder
    private func screen(model: ListsScreenModel, ui: ListsScreenState) -> some View {
        List {
            if ui.isLoading && ui.lists.isEmpty {
                ProgressView().frame(maxWidth: .infinity)
            }
            // Android's lists screen opens "My climbs" (own climbs and their Kilter state).
            NavigationLink {
                MyKilterClimbsView(core: core)
            } label: {
                Label(L("my_climbs_title"), systemImage: "person.crop.square")
            }
            ForEach(ui.lists, id: \.id) { row in
                NavigationLink {
                    ListDetailView(core: core, listId: row.id, title: row.name)
                } label: {
                    HStack {
                        Image(systemName: icon(for: row))
                            .foregroundStyle(row.isBuiltin ? Color.accentColor : .secondary)
                        Text(row.name)
                        Spacer()
                        Text("\(row.climbCount)").foregroundStyle(.secondary).font(.footnote.monospacedDigit())
                    }
                }
                .swipeActions {
                    if !row.isBuiltin {
                        Button(LI("logbook_delete"), role: .destructive) { model.requestDeleteList(listId: row.id) }
                    }
                }
            }
        }
        .sheet(isPresented: $generating) {
            NavigationStack {
                GeneratorView(core: core) { _ in model.refresh() }
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(L("action_cancel")) { generating = false }
                        }
                    }
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button { model.showCreateDialog() } label: { Label(LI("lists_new"), systemImage: "plus") }
                    Button { generating = true } label: {
                        Label(LI("generator_open"), systemImage: "wand.and.stars")
                    }
                } label: { Image(systemName: "plus") }
            }
        }
        .alert(LI("lists_new"), isPresented: Binding(get: { ui.showCreateDialog }, set: { if !$0 { model.dismissCreateDialog() } })) {
            TextField(LI("lists_name"), text: Binding(get: { ui.newListName }, set: { model.setNewListName(name: $0) }))
            Button(L("action_cancel"), role: .cancel) { model.dismissCreateDialog() }
            Button(L("action_save")) { model.createList() }
        }
        .alert(LI("lists_delete_confirm"), isPresented: Binding(
            get: { ui.deleteConfirmListId != 0 },
            set: { if !$0 { model.dismissDeleteConfirm() } })) {
            Button(L("action_cancel"), role: .cancel) { model.dismissDeleteConfirm() }
            Button(LI("logbook_delete"), role: .destructive) { model.confirmDeleteList() }
        }
    }

    private func icon(for row: ClimbListRowUi) -> String {
        if row.isIgnored { return "eye.slash" }
        if row.isBuiltin { return "heart.fill" }
        return "list.bullet"
    }
}

/// One list: its climbs, with removal and renaming.
struct ListDetailView: View {
    let core: AppCore
    let listId: Int64
    let title: String
    @State private var host: ScreenHost<ListDetailScreenModel, ListDetailScreenState>?

    var body: some View {
        Group {
            if let host {
                screen(host: host)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(host?.state.name ?? title)
        .modifier(RestTimerBannerSlot(core: core))
        .task {
            guard host == nil else { return }
            let model = core.makeListDetailScreen(listId: listId)
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.refresh()
        }
    }
}

private extension ListDetailView {
    @ViewBuilder
    func screen(host: ScreenHost<ListDetailScreenModel, ListDetailScreenState>) -> some View {
        let model: ListDetailScreenModel = host.model
        let ui: ListDetailScreenState = host.state
        List {
            if ui.members.isEmpty && !ui.isLoading {
                ContentUnavailableView(LI("lists_empty"), systemImage: "list.bullet")
            }
            if ui.playHasMultipleBoards {
                Text(L("list_playback_error_multiple_boards")).font(.footnote).foregroundStyle(.red)
            }
            ForEach(ui.members, id: \.climbUuid) { member in
                memberRow(member)
                    .swipeActions {
                        Button(LI("lists_remove"), role: .destructive) { model.removeFromList(climbUuid: member.climbUuid) }
                    }
            }
        }
        .toolbar { toolbarContent(host: host, ui: ui) }
        .alert(LI("lists_rename"), isPresented: Binding(get: { ui.showRenameDialog }, set: { if !$0 { model.dismissRenameDialog() } })) {
            TextField(LI("lists_name"), text: Binding(get: { ui.renameValue }, set: { model.setRenameValue(value: $0) }))
            Button(L("action_cancel"), role: .cancel) { model.dismissRenameDialog() }
            Button(L("action_save")) { model.confirmRename() }
        }
    }

    func memberRow(_ member: ListMemberUi) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(member.name)
            HStack(spacing: 6) {
                if !member.grade.isEmpty { Text(member.grade) }
                if !member.setter.isEmpty { Text(member.setter) }
                if !member.isAvailable { Text(LI("lists_unavailable")) }
            }
            .font(.footnote).foregroundStyle(.secondary)
        }
    }

    @ToolbarContentBuilder
    func toolbarContent(host: ScreenHost<ListDetailScreenModel, ListDetailScreenState>,
                        ui: ListDetailScreenState) -> some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            if !ui.playClimbUuids.isEmpty && !ui.playHasMultipleBoards {
                NavigationLink {
                    playerView(ui)
                } label: {
                    Label(LI("player_start"), systemImage: "play.fill")
                }
            }
            // Android: the plan editor is offered for a non-empty list that is not the ignored list.
            if !ui.isIgnoredList && !ui.members.isEmpty {
                NavigationLink {
                    PlanEditorView(core: core, host: host)
                } label: {
                    Label(L("list_playback_edit_plan"), systemImage: "slider.horizontal.3")
                }
            }
            if !ui.isBuiltin {
                Button(LI("lists_rename")) { host.model.showRenameDialog() }
            }
        }
    }

    /// The Kotlin model compiles what "Start" plays (Android `startPlayback`):
    /// the plan with its repeats and rests when there is one, else the list once.
    func playerView(_ ui: ListDetailScreenState) -> PlayerView {
        playlistPlayer(core: core, ui: ui)
    }
}

/// Player for a list detail state; shared by the list detail and the plan editor.
func playlistPlayer(core: AppCore, ui: ListDetailScreenState) -> PlayerView {
    let angles: [Int32] = ui.playAngles.map { $0.int32Value }
    let rests: [Int32] = ui.playRestsAfter.map { $0.int32Value }
    return PlayerView(core: core,
                      listName: ui.name,
                      climbUuids: ui.playClimbUuids,
                      angles: angles,
                      restsAfter: rests,
                      orderCode: ui.orderCode,
                      advanceCode: ui.advanceCode,
                      defaultRestSeconds: ui.defaultRestSeconds)
}
