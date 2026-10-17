import CruxCoachCore
import SwiftUI

/// Android's "Add to list" dialog: every list with this climb's membership,
/// tap to add or remove, or name a new list and add the climb to it.
struct AddToListSheet: View {
    let core: AppCore
    let climbUuid: String
    let angle: Int32
    @State private var host: ScreenHost<ListsScreenModel, ListsScreenState>?
    @State private var newName = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if let host {
                content(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("board_addtolist_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(L("board_addtolist_done")) { dismiss() }
            }
        }
        .task {
            guard host == nil else { return }
            let model = core.makeListsScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.openClimb(climbUuid: climbUuid, angle: angle)
        }
    }

    @ViewBuilder
    private func content(model: ListsScreenModel, ui: ListsScreenState) -> some View {
        List {
            Section {
                ForEach(ui.lists.filter { !$0.isIgnored }, id: \.id) { row in
                    Button {
                        model.toggleList(listId: row.id)
                    } label: {
                        HStack {
                            Image(systemName: row.isBuiltin ? "heart.fill" : "list.bullet")
                                .foregroundStyle(row.isBuiltin ? Color.accentColor : .secondary)
                            Text(row.name).foregroundStyle(.primary)
                            Spacer()
                            if row.containsClimb {
                                Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                            }
                        }
                    }
                }
            }
            Section(L("board_addtolist_new_list")) {
                HStack {
                    TextField(L("board_addtolist_new_list"), text: $newName)
                        .textInputAutocapitalization(.sentences)
                    Button(L("cd_create")) {
                        model.setNewListName(name: newName)
                        model.createListAndAdd()
                        newName = ""
                    }
                    .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}
