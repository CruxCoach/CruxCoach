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
                let model = host.model
                let ui = host.state
                List {
                    if ui.members.isEmpty && !ui.isLoading {
                        ContentUnavailableView(LI("lists_empty"), systemImage: "list.bullet")
                    }
                    ForEach(ui.members, id: \.climbUuid) { member in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(member.name)
                            HStack(spacing: 6) {
                                if !member.grade.isEmpty { Text(member.grade) }
                                if !member.setter.isEmpty { Text(member.setter) }
                                if !member.isAvailable { Text(LI("lists_unavailable")) }
                            }
                            .font(.footnote).foregroundStyle(.secondary)
                        }
                        .swipeActions {
                            Button(LI("lists_remove"), role: .destructive) { model.removeFromList(climbUuid: member.climbUuid) }
                        }
                    }
                }
                .toolbar {
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        if !ui.members.isEmpty {
                            NavigationLink {
                                PlayerView(core: core,
                                           listName: ui.name,
                                           climbUuids: playbackUuids(ui),
                                           angles: playbackAngles(ui),
                                           restsAfter: playbackRests(ui),
                                           orderCode: ui.orderCode,
                                           advanceCode: ui.advanceCode,
                                           defaultRestSeconds: ui.defaultRestSeconds)
                            } label: {
                                Label(LI("player_start"), systemImage: "play.fill")
                            }
                        }
                        if !ui.isBuiltin {
                            Button(LI("lists_rename")) { model.showRenameDialog() }
                        }
                    }
                }
                .alert(LI("lists_rename"), isPresented: Binding(get: { ui.showRenameDialog }, set: { if !$0 { model.dismissRenameDialog() } })) {
                    TextField(LI("lists_name"), text: Binding(get: { ui.renameValue }, set: { model.setRenameValue(value: $0) }))
                    Button(L("action_cancel"), role: .cancel) { model.dismissRenameDialog() }
                    Button(L("action_save")) { model.confirmRename() }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(host?.state.name ?? title)
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
    /// A saved plan wins over plain membership: it carries repeats, pinned
    /// angles and rests. Rest steps become the rest that follows the climb
    /// before them, which is how the player consumes them.
    func playbackSteps(_ ui: ListDetailScreenState) -> [(uuid: String, angle: Int32, rest: Int32)] {
        if !ui.planSteps.isEmpty {
            var steps: [(uuid: String, angle: Int32, rest: Int32)] = []
            for step in ui.planSteps {
                if step.isRest {
                    if !steps.isEmpty { steps[steps.count - 1].rest = step.restSeconds }
                } else {
                    steps.append((step.climbUuid, step.angle >= 0 ? step.angle : ui.defaultAngle, 0))
                }
            }
            return steps
        }
        return ui.members.map { ($0.climbUuid, ui.defaultAngle, 0) }
    }

    func playbackUuids(_ ui: ListDetailScreenState) -> [String] { playbackSteps(ui).map { $0.uuid } }
    func playbackAngles(_ ui: ListDetailScreenState) -> [Int32] { playbackSteps(ui).map { $0.angle } }
    func playbackRests(_ ui: ListDetailScreenState) -> [Int32] { playbackSteps(ui).map { $0.rest } }
}
