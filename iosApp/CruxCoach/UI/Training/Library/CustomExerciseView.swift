import CruxCoachCore
import SwiftUI

/// An own exercise (Android `CustomExerciseScreen`): typing the name fills in category, kind, load,
/// sides, equipment and loaded structures; each can be changed. Saving opens the new exercise.
struct CustomExerciseView: View {
    let core: AppCore
    let onStartWorkout: (String) -> Void

    @State private var host: ScreenHost<CustomExerciseScreenModel, CustomExerciseScreenState>?
    @State private var unavailable = false
    @State private var savedSlug: String?

    var body: some View {
        Group {
            if let host {
                form(model: host.model, ui: host.state)
            } else if unavailable {
                ContentUnavailableView(LI("library_unavailable"), systemImage: "lock.trianglebadge.exclamationmark")
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("trx_custom_title"))
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: Binding(get: { savedSlug.map { LibrarySlug(slug: $0) } }, set: { savedSlug = $0?.slug })) { item in
            ExerciseDetailView(core: core, slug: item.slug, onStartWorkout: onStartWorkout)
        }
        .task {
            guard host == nil else { return }
            guard let model = core.makeCustomExerciseScreen() else {
                unavailable = true
                return
            }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }

    @ViewBuilder
    private func form(model: CustomExerciseScreenModel, ui: CustomExerciseScreenState) -> some View {
        Form {
            Section {
                TextField(L("trx_custom_name"), text: Binding(get: { ui.name }, set: { model.setName(value: $0) }))
                    .textInputAutocapitalization(.sentences)
                if ui.showNameError {
                    Text(L("trx_custom_name_required")).font(.footnote).foregroundStyle(.red)
                } else if ui.guessed {
                    Text(L("trx_custom_guessed")).font(.footnote).foregroundStyle(.secondary)
                }
                if ui.failed {
                    Text(LI("library_failed")).font(.footnote).foregroundStyle(.orange)
                }
            }
            choices(L("trx_custom_category"), ui.categories) { model.setCategory(code: $0) }
            choices(L("trx_custom_kind"), ui.kinds) { model.setKind(code: $0) }
            choices(L("trx_custom_load"), ui.loads) { model.setLoad(code: $0) }
            Section {
                Toggle(L("trx_custom_unilateral"), isOn: Binding(get: { ui.unilateral }, set: { model.setUnilateral(value: $0) }))
            }
            choices(L("trx_custom_equipment"), ui.equipment) { model.toggleEquipment(code: $0) }
            choices(L("trx_custom_domains"), ui.domains) { model.toggleDomain(code: $0) }
            Section {
                TextField(L("trx_custom_note"), text: Binding(get: { ui.note }, set: { model.setNote(value: $0) }), axis: .vertical)
                    .lineLimit(2...6)
            }
            Section {
                Button(L("tr_action_save")) { model.save() }
                    .disabled(ui.saving)
                    .frame(maxWidth: .infinity)
            }
        }
        .onChange(of: ui.savedSlug) { _, slug in
            guard !slug.isEmpty else { return }
            model.consumeSaved()
            savedSlug = slug
        }
    }

    private func choices(_ title: String, _ options: [LibraryChoiceUi], onTap: @escaping (String) -> Void) -> some View {
        Section(title) {
            LibraryFlowLayout(spacing: 8) {
                ForEach(options, id: \.code) { option in
                    LibraryChip(title: L(option.labelKey), selected: option.selected) { onTap(option.code) }
                }
            }
        }
    }
}
