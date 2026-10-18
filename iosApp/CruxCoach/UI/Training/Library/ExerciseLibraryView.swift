import CruxCoachCore
import SwiftUI

/// The exercise library (Android `ExerciseCatalogScreen`): search, the categories behind one chip,
/// "my equipment" and the safety filters, favourites on top. With a non-empty [pickForWorkout]
/// it is the picker of an open training: a tap adds the exercise and calls [onPicked].
struct ExerciseLibraryView: View {
    let core: AppCore
    let pickForWorkout: String
    let onStartWorkout: (String) -> Void
    let onPicked: () -> Void

    @State private var host: ScreenHost<ExerciseLibraryScreenModel, ExerciseLibraryScreenState>?
    @State private var unavailable = false
    @State private var route: LibraryRoute?
    @State private var addSlug: String?
    @State private var notice: String?

    private var picking: Bool { !pickForWorkout.isEmpty }

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else if unavailable {
                ContentUnavailableView(LI("library_unavailable"), systemImage: "lock.trianglebadge.exclamationmark")
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationDestination(item: $route) { r in
            LibraryRouteView(core: core, route: r, onStartWorkout: onStartWorkout)
        }
        .task {
            guard host == nil else { return }
            guard let model = core.makeExerciseLibraryScreen(language: LibraryLanguage.current) else {
                unavailable = true
                return
            }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }

    @ViewBuilder
    private func screen(model: ExerciseLibraryScreenModel, ui: ExerciseLibraryScreenState) -> some View {
        List {
            Section {
                filters(model: model, ui: ui)
                    .listRowInsets(EdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12))
                if ui.injuryDefaultsApplied {
                    Text(L("trx_filters_injury_note")).font(.footnote).foregroundStyle(.orange)
                }
                if let notice {
                    Text(notice).font(.footnote).foregroundStyle(.secondary)
                }
                Text(LP("trx_count", Int(ui.count))).font(.footnote).foregroundStyle(.secondary)
            }
            if ui.isLoading {
                ProgressView().frame(maxWidth: .infinity)
            } else if ui.rows.isEmpty && ui.favoriteRows.isEmpty {
                Text(L("trx_no_results")).foregroundStyle(.secondary)
            }
            if ui.showSections {
                Section(L("trx_section_favorites")) {
                    ForEach(ui.favoriteRows, id: \.slug) { row in exerciseRow(row, model: model) }
                }
                Section(L("trx_section_all")) {
                    ForEach(ui.rows, id: \.slug) { row in exerciseRow(row, model: model) }
                }
            } else {
                Section {
                    ForEach(ui.rows, id: \.slug) { row in exerciseRow(row, model: model) }
                }
            }
        }
        .searchable(text: Binding(get: { ui.query }, set: { model.setQuery(query: $0) }), prompt: L("trx_search_hint"))
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                if !picking {
                    Button { route = .customExercise } label: {
                        Label(L("trx_create_custom"), systemImage: "plus")
                    }
                }
            }
        }
        .onChange(of: ui.picked) { _, picked in
            guard picked else { return }
            model.consumeEvents()
            onPicked()
        }
        .onChange(of: ui.pickRejected) { _, rejected in
            guard rejected else { return }
            model.consumeEvents()
            notice = L("trx_pick_rejected")
        }
        .sheet(item: Binding(get: { addSlug.map { LibrarySlug(slug: $0) } }, set: { addSlug = $0?.slug })) { item in
            AddToWorkoutSheet(core: core, slug: item.slug,
                              onTrainingStarted: { id in
                                  addSlug = nil
                                  onStartWorkout(id)
                              },
                              onCreateRoutine: { from in
                                  addSlug = nil
                                  route = .editor(routineId: "", from: from, defaultName: "")
                              })
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: Binding(get: { ui.equipmentSheetOpen }, set: { if !$0 { model.closeEquipmentSheet() } })) {
            LibraryEquipmentSheet(options: ui.equipmentOptions,
                                  onToggle: { model.toggleEquipment(code: $0) },
                                  onDone: { model.closeEquipmentSheet() })
                .presentationDetents([.medium, .large])
        }
    }

    @ViewBuilder
    private func filters(model: ExerciseLibraryScreenModel, ui: ExerciseLibraryScreenState) -> some View {
        LibraryFlowLayout(spacing: 8) {
            Menu {
                Button {
                    model.toggleCategory(code: "")
                } label: {
                    if ui.anyCategory { Text(L("trx_filter_all_categories")) } else { Label(L("trx_filter_all_categories"), systemImage: "checkmark") }
                }
                ForEach(ui.categories, id: \.code) { c in
                    Button {
                        model.toggleCategory(code: c.code)
                    } label: {
                        if c.selected { Label(L(c.labelKey), systemImage: "checkmark") } else { Text(L(c.labelKey)) }
                    }
                }
            } label: {
                LibraryChipLabel(title: LibraryRender.text(ui.categoryLabel), selected: ui.anyCategory, systemImage: "chevron.down")
            }
            LibraryChip(title: LibraryRender.text(ui.mineLabel), selected: ui.mineSelected) {
                if ui.equipmentConfigured {
                    model.toggleMine()
                } else {
                    // Not set up yet: say so and offer the equipment right here.
                    notice = L("trx_filter_mine_hint")
                    model.openEquipmentSheet()
                }
            }
            LibraryChip(title: L("trx_filter_no_climbing"), selected: ui.withoutClimbing) { model.toggleWithoutClimbing() }
            LibraryChip(title: L("trx_filter_finger_free"), selected: ui.fingerFree) { model.toggleFingerFree() }
            LibraryChip(title: L("trx_filter_favorites"), selected: ui.favoritesOnly) { model.toggleFavoritesOnly() }
        }
    }

    @ViewBuilder
    private func exerciseRow(_ row: ExerciseRowUi, model: ExerciseLibraryScreenModel) -> some View {
        HStack(spacing: 12) {
            ExercisePictogramTile(pictogram: row.pictogram)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(row.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                    if row.custom {
                        Text(L("trx_custom_badge")).font(.caption2)
                            .padding(.horizontal, 6).padding(.vertical, 1)
                            .background(Capsule().fill(Color(uiColor: .tertiarySystemFill)))
                    }
                }
                HStack(spacing: 0) {
                    Text(L(row.categoryKey) + " · ").font(.caption).foregroundStyle(.secondary)
                    LibraryDifficultyDots(level: Int(row.difficulty))
                }
                Text(LibraryRender.text(row.equipmentSummary)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                if !row.badgeKey.isEmpty {
                    Text(L(row.badgeKey)).font(.caption2)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(RoundedRectangle(cornerRadius: 4).fill(row.verdictCode == "avoid" ? Color.red.opacity(0.18) : Color.orange.opacity(0.18)))
                }
                if row.excluded {
                    Text(L("tre_excluded_badge")).font(.caption2).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
            if picking {
                Button { route = .exercise(row.slug) } label: {
                    Image(systemName: "info.circle").accessibilityLabel(L("trx_open_detail", row.name))
                }
                .buttonStyle(.borderless)
            } else {
                Button { addSlug = row.slug } label: {
                    Image(systemName: "text.badge.plus").accessibilityLabel(L("trx_add_to_workout", row.name))
                }
                .buttonStyle(.borderless)
            }
            Button { model.toggleFavorite(slug: row.slug) } label: {
                Image(systemName: row.favorite ? "star.fill" : "star")
                    .foregroundStyle(row.favorite ? Color.accentColor : Color.secondary)
                    .accessibilityLabel(L(row.favorite ? "trx_favorite_remove" : "trx_favorite_add"))
            }
            .buttonStyle(.borderless)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            if picking { model.pick(workoutId: pickForWorkout, slug: row.slug) } else { route = .exercise(row.slug) }
        }
        .contextMenu {
            Button(L("tre_menu_details")) { route = .exercise(row.slug) }
            Button(L(row.excluded ? "tre_include" : "tre_exclude")) { model.toggleExcluded(slug: row.slug) }
        }
    }
}

/// Identifiable wrapper for a sheet about one exercise.
struct LibrarySlug: Identifiable, Hashable {
    let slug: String
    var id: String { slug }
}

/// The look of [LibraryChip] without its button, for a menu's label.
struct LibraryChipLabel: View {
    let title: String
    let selected: Bool
    var systemImage: String? = nil

    var body: some View {
        HStack(spacing: 4) {
            Text(title).lineLimit(1)
            if let systemImage { Image(systemName: systemImage).font(.caption) }
        }
        .font(.subheadline)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Capsule().fill(selected ? Color.accentColor.opacity(0.18) : Color(uiColor: .secondarySystemBackground)))
        .overlay(Capsule().stroke(selected ? Color.accentColor : Color(uiColor: .separator), lineWidth: 1))
    }
}

/// "What do you have?" (Android `EquipmentSheet` without the place presets).
struct LibraryEquipmentSheet: View {
    let options: [LibraryChoiceUi]
    let onToggle: (String) -> Void
    let onDone: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(L("tru_equipment_why")).font(.subheadline).foregroundStyle(.secondary)
                    Text(L("tru_equipment_what")).font(.subheadline.weight(.semibold))
                    LibraryFlowLayout(spacing: 6) {
                        ForEach(options, id: \.code) { option in
                            LibraryChip(title: L(option.labelKey), selected: option.selected) { onToggle(option.code) }
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle(L("tru_setup_equipment"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("tru_done"), action: onDone)
                }
            }
        }
    }
}
