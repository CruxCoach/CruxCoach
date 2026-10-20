import CruxCoachCore
import SwiftUI

/// Injuries and pauses (Android `InjuriesScreen`): report an injury by region and side, pain,
/// climbing paused, healed; holiday pauses. Report by tapping the body map or a region chip, as in
/// Android's dialog. Push it inside a `NavigationStack`; it has none of its own.
struct ProgressInjuriesView: View {
    let core: AppCore

    @State private var host: ScreenHost<InjuriesScreenModel, InjuriesScreenState>?

    init(core: AppCore) {
        self.core = core
    }

    var body: some View {
        Group {
            if let host {
                InjuriesContent(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("tra_injuries_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard host == nil, let model = core.makeInjuriesScreen(language: TrainingStyle.catalogLanguage) else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }
}

private struct InjuriesContent: View {
    let model: InjuriesScreenModel
    let ui: InjuriesScreenState

    @State private var adding = false

    var body: some View {
        List {
            Section {
                Text(L("tr_not_medical_advice")).font(.caption).foregroundStyle(.secondary)
                if ui.active.isEmpty {
                    Text(L("tra_injuries_none")).foregroundStyle(.secondary)
                }
                Button { adding = true } label: {
                    Label(L("tra_injury_add"), systemImage: "plus")
                }
            }
            ForEach(ui.active, id: \.id) { injury in
                Section {
                    InjuryCardRows(model: model, injury: injury)
                }
            }
            Section(L("tra_pauses_title")) {
                if let open = ui.openPause {
                    HStack {
                        Text(tr: open.text)
                        Spacer()
                        Button(L("trt_pause_end")) { model.endPause() }.buttonStyle(.borderless)
                    }
                } else {
                    Button(L("tra_pause_holiday")) { model.startHoliday() }
                }
                ForEach(ui.pauses, id: \.id) { pause in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(tr: pause.label)
                        Text(tr: pause.text).font(.caption).foregroundStyle(.secondary)
                    }
                    .swipeActions {
                        Button(L("tr_action_delete"), role: .destructive) { model.deletePause(id: pause.id) }
                    }
                }
            }
            if !ui.past.isEmpty {
                Section(L("tra_injuries_past")) {
                    ForEach(ui.past, id: \.id) { injury in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(TrRender.text(injury.title) + (injury.detail.isEmpty ? "" : " · " + injury.detail))
                            Text(tr: injury.since).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            healthBackupSection
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                ProgressInfoButton(title: L("tra_injuries_info_title"), text: L("tra_injuries_info_text"))
            }
        }
        .sheet(isPresented: $adding) {
            InjuryAddSheet(model: model, ui: ui) { adding = false }
        }
    }

    /// Injuries and the comeback diary in the automatic encrypted backup (Android, owner 2026-10-20); off by default.
    private var healthBackupSection: some View {
        let on: Bool = ui.healthInBackup
        return Section {
            Toggle(isOn: Binding(get: { on }, set: { model.setHealthInBackup(on: $0) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("cb_backup_cloud"))
                    Text(L("cb_backup_cloud_hint")).font(.caption).foregroundStyle(.secondary)
                }
            }
            .accessibilityIdentifier("injury_health_backup")
        }
    }
}

/// One open injury: pain slider, climbing pause, healed, delete (Android `InjuryCard`).
private struct InjuryCardRows: View {
    let model: InjuriesScreenModel
    let injury: InjuryUi

    @State private var severity: Double = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(TrRender.text(injury.title), systemImage: "bandage")
                .font(.headline)
                .foregroundStyle(TrainingStyle.caution)
            if !injury.detail.isEmpty { Text(injury.detail) }
            Text(tr: injury.since).font(.caption).foregroundStyle(.secondary)
        }
        VStack(alignment: .leading) {
            Text(L("tra_injury_pain", Int(severity)))
            Slider(value: $severity, in: 0...9, step: 1, onEditingChanged: { editing in
                if !editing { model.setSeverity(id: injury.id, severity: Int32(severity)) }
            })
        }
        Toggle(L("tra_injury_pause_climbing"), isOn: Binding(get: { injury.climbingPaused },
                                                             set: { model.setClimbingPaused(id: injury.id, paused: $0) }))
        if injury.comebackAvailable {
            InjuryComebackRows(injury: injury)
        }
        HStack {
            Button(L("tra_injury_resolve")) { model.resolve(id: injury.id) }
                .buttonStyle(.borderedProminent)
            Spacer()
            Button(L("tr_action_delete"), role: .destructive) { model.delete(id: injury.id) }
                .buttonStyle(.borderless)
        }
        .onAppear { severity = Double(injury.severity) }
        .onChange(of: injury.severity) { _, now in severity = Double(now) }
    }
}

/// The comeback plan of this injury (Android `InjuryCard`, owner 2026-10-20): the running stage, "start" or "open"
/// the plan (`ComebackView`, pushed on this navigation stack), and the notice it always carries.
private struct InjuryComebackRows: View {
    let injury: InjuryUi

    var body: some View {
        if let line = TrRender.optional(injury.comebackLine) {
            Text(line).font(.subheadline)
        }
        NavigationLink {
            ComebackView(injuryId: injury.id)
        } label: {
            Label(L(injury.comebackRunning ? "cb_open_plan" : "cb_start_plan"), systemImage: "chart.line.uptrend.xyaxis")
        }
        .accessibilityIdentifier("injury_comeback_\(injury.id)")
        ComebackNoticeLine()
    }
}

/// Report an injury (Android `AddInjuryDialog`): tap the body — the area picks the region, a
/// paired area of a limb also the side, and a finer area than the region (forearm → elbow,
/// hamstring → knee) goes into an empty detail field — or pick a region chip.
private struct InjuryAddSheet: View {
    let model: InjuriesScreenModel
    let ui: InjuriesScreenState
    let onDone: () -> Void

    @State private var region = "FINGER"
    @State private var side = ""
    @State private var detail = ""
    @State private var severity: Double = 3
    @State private var pauseClimbing = true
    @State private var pauseStreak = true
    @State private var area = "fingers"

    var body: some View {
        NavigationStack {
            Form {
                whereSection
                Section(L("tra_injury_side")) {
                    TrainingFlow(spacing: 6) {
                        ForEach(ui.sides, id: \.code) { s in
                            TrainingChoiceChip(title: TrRender.text(s.label), selected: side == s.code) {
                                side = side == s.code ? "" : s.code
                            }
                        }
                    }
                }
                Section {
                    TextField(L("tra_injury_detail"), text: $detail)
                        .onChange(of: detail) { _, now in if now.count > 120 { detail = String(now.prefix(120)) } }
                    VStack(alignment: .leading) {
                        Text(L("tra_injury_pain", Int(severity)))
                        Slider(value: $severity, in: 0...9, step: 1)
                    }
                    Toggle(L("tra_injury_pause_climbing"), isOn: $pauseClimbing)
                    Toggle(L("tra_injury_pause_streak"), isOn: $pauseStreak)
                }
                Section {
                    Text(L("tra_red_flags")).font(.caption).foregroundStyle(.red)
                }
            }
            .navigationTitle(L("tra_injury_add"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel"), action: onDone) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("tr_action_save")) {
                        if model.add(regionCode: region, sideCode: side, detail: detail, severity: Int32(severity),
                                     pauseClimbing: pauseClimbing, pauseStreak: pauseStreak) {
                            onDone()
                        }
                    }
                }
            }
        }
    }

    private var whereSection: some View {
        Section(L("tra_injury_where")) {
            Text(L("trb_injury_where")).font(.caption).foregroundStyle(.secondary)
            BodyMapPicker(kit: model.bodyMap, selected: area) { hit in pick(hit) }
            TrainingFlow(spacing: 6) {
                ForEach(ui.regions, id: \.code) { r in
                    TrainingChoiceChip(title: TrRender.text(r.label), selected: region == r.code) {
                        choose(r)
                    }
                }
            }
        }
    }

    /// A region chip: its climbing pause and its first area on the map (Android `chooseRegion`).
    private func choose(_ r: InjuryRegionUi) {
        region = r.code
        pauseClimbing = r.pausesClimbing
        area = model.firstAreaOf(regionCode: r.code)
    }

    /// Android's `BodyMapPicker` callbacks: region (and its climbing pause), side, detail.
    private func pick(_ hit: BodyHitUi) {
        guard let choice = model.pickArea(areaCode: hit.areaCode, bodySideCode: hit.bodySideCode) else { return }
        area = choice.areaCode
        region = choice.regionCode
        pauseClimbing = choice.pausesClimbing
        if detail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !choice.detailKey.isEmpty {
            detail = L(choice.detailKey)
        }
        if !choice.sideCode.isEmpty { side = choice.sideCode }
    }
}
