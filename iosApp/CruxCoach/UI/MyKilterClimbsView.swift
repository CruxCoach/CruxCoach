import CruxCoachCore
import SwiftUI

/// Android's "My climbs" hub: every climb the user authored — CruxCoach drafts and
/// publications, plus the climbs the connected Kilter account set, which can be
/// claimed (published to the community) from here.
struct MyKilterClimbsView: View {
    let core: AppCore
    @State private var host: ScreenHost<MyKilterClimbsModel, MyKilterClimbsState>?
    @State private var ble: ScreenHost<BleScreenModel, BleScreenState>?
    @State private var gradeId: Int = 22

    var body: some View {
        Group {
            if let host, let ble {
                screen(model: host.model, ui: host.state, ble: ble)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("my_climbs_title"))
        .task {
            guard host == nil else { return }
            let model = core.makeMyKilterClimbs()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            let bleModel = core.bleScreen
            ble = ScreenHost(model: bleModel, initial: bleModel.currentState) { model, onState in
                model.watch(onState: onState)
            }
            gradeId = Int(model.defaultGradeId)
            model.refresh()
        }
        .onAppear { host?.model.refresh() }
    }

    @ViewBuilder
    private func screen(model: MyKilterClimbsModel, ui: MyKilterClimbsState,
                        ble: ScreenHost<BleScreenModel, BleScreenState>) -> some View {
        content(model: model, ui: ui, ble: ble)
            .overlay(alignment: .bottom) { feedbackBanner(model: model, ui: ui) }
            .sheet(isPresented: Binding(get: { !ui.gradeDialogUuid.isEmpty },
                                        set: { if !$0 { model.dismissGradeDialog() } })) {
                NavigationStack { gradeSheet(model: model, uuid: ui.gradeDialogUuid) }
                    .presentationDetents([.medium])
            }
    }

    @ViewBuilder
    private func content(model: MyKilterClimbsModel, ui: MyKilterClimbsState,
                         ble: ScreenHost<BleScreenModel, BleScreenState>) -> some View {
        if ui.isLoading {
            ProgressView()
        } else if ui.isEmpty && !ui.hasKilterConnection && !ui.hasNostrIdentity {
            ContentUnavailableView(L("my_climbs_no_kilter_title"), systemImage: "person.3",
                                   description: Text(L("my_climbs_no_kilter_message")))
        } else if ui.isEmpty {
            ContentUnavailableView(L("my_climbs_empty_title"), systemImage: "person.3",
                                   description: Text(L("my_climbs_empty_message")))
        } else {
            List {
                section(L("my_climbs_section_drafts"), rows: ui.drafts, model: model, ui: ui, ble: ble)
                section(L("my_climbs_section_published"), rows: ui.published, model: model, ui: ui, ble: ble)
                section(L("my_climbs_section_kilter_unclaimed"), rows: ui.unclaimed, model: model, ui: ui, ble: ble)
            }
            .refreshable { model.refresh() }
        }
    }

    @ViewBuilder
    private func section(_ title: String, rows: [MyClimbRowUi], model: MyKilterClimbsModel,
                         ui: MyKilterClimbsState, ble: ScreenHost<BleScreenModel, BleScreenState>) -> some View {
        if !rows.isEmpty {
            Section(header: Text(title)) {
                ForEach(rows, id: \.uuid) { row in
                    rowView(row, model: model, ui: ui, ble: ble)
                }
            }
        }
    }

    private func rowView(_ row: MyClimbRowUi, model: MyKilterClimbsModel, ui: MyKilterClimbsState,
                         ble: ScreenHost<BleScreenModel, BleScreenState>) -> some View {
        HStack {
            NavigationLink {
                ClimbDetailView(core: core, uuid: row.uuid, angle: row.angle, ble: ble) { model.refresh() }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.name.isEmpty ? L("my_climbs_unnamed") : row.name).lineLimit(1)
                    HStack(spacing: 6) {
                        Text(row.brandTitle).font(.caption).foregroundStyle(.secondary)
                        if row.status == "publishPending" {
                            Text(L("my_climbs_publish_retrying")).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            trailing(row, model: model, ui: ui)
        }
    }

    @ViewBuilder
    private func trailing(_ row: MyClimbRowUi, model: MyKilterClimbsModel, ui: MyKilterClimbsState) -> some View {
        switch row.status {
        case "published":
            Label(L("my_climbs_published_badge"), systemImage: "checkmark.circle.fill")
                .font(.caption).foregroundStyle(.green).labelStyle(.titleAndIcon)
        case "draft":
            Text(L("my_climbs_draft_badge")).font(.caption).foregroundStyle(.secondary)
        case "kilterUnclaimed":
            if ui.publishingUuid == row.uuid {
                ProgressView()
            } else {
                Button(L("own_climb_publish_action_short")) { model.claim(uuid: row.uuid) }
                    .buttonStyle(.borderless)
                    .disabled(!ui.publishingUuid.isEmpty)
            }
        default:
            EmptyView()
        }
    }

    /// Android's `ClaimGradeDialog`: an ungraded Kilter climb never goes out with a silent default.
    private func gradeSheet(model: MyKilterClimbsModel, uuid: String) -> some View {
        let minId: Int = Int(model.minGradeId)
        let maxId: Int = Int(model.maxGradeId)
        return Form {
            Text(L("my_climbs_grade_dialog_message")).font(.footnote)
            Stepper(model.gradeLabel(gradeId: Int32(gradeId)), value: $gradeId, in: minId...maxId)
        }
        .navigationTitle(L("my_climbs_grade_dialog_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(L("action_cancel")) { model.dismissGradeDialog() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(L("own_climb_publish_action_short")) {
                    model.confirmGrade(uuid: uuid, setterGradeId: Int32(gradeId))
                }
            }
        }
    }

    @ViewBuilder
    private func feedbackBanner(model: MyKilterClimbsModel, ui: MyKilterClimbsState) -> some View {
        if ui.feedback != "none" {
            Text(Self.feedbackText(ui.feedback))
                .font(.footnote)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(.thinMaterial, in: Capsule())
                .padding(.bottom, 12)
                .onTapGesture { model.consumeFeedback() }
        }
    }

    private static func feedbackText(_ code: String) -> String {
        switch code {
        case "published": return L("own_climb_publish_done")
        case "noNostrIdentity": return L("own_climb_publish_no_nostr")
        case "notAuthor": return L("own_climb_publish_not_author")
        case "alreadyPublished": return L("own_climb_publish_already")
        default: return L("climb_creator_publish_failed")
        }
    }
}
