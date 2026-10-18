import CruxCoachCore
import SwiftUI

/// Training settings (Android `AthleteSettingsScreen`): an overview with the current value per topic
/// and one page each — goal and week, about you, places and equipment, timer, display and units,
/// connections, climbing days, what the coach learned. Nutrition targets and reminders are not on
/// iPhone yet and say so; Health Connect has no iPhone counterpart. Push it inside a
/// `NavigationStack`; it has none of its own.
struct ProgressSettingsView: View {
    let core: AppCore

    @State private var host: ScreenHost<AthleteSettingsScreenModel, AthleteSettingsScreenState>?

    init(core: AppCore) {
        self.core = core
    }

    var body: some View {
        Group {
            if let host {
                SettingsOverview(core: core, host: host)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("tr_action_settings"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard host == nil, let model = core.makeAthleteSettingsScreen(language: TrainingStyle.catalogLanguage) else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }
}

private typealias SettingsHost = ScreenHost<AthleteSettingsScreenModel, AthleteSettingsScreenState>

private struct SettingsOverview: View {
    let core: AppCore
    let host: SettingsHost

    @State private var notYet: String?
    @State private var climbingDays = false

    var body: some View {
        List {
            if host.state.loaded {
                Section {
                    ForEach(host.state.rows, id: \.code) { row in
                        if row.code == "climbingDays" {
                            Button { climbingDays = true } label: { label(row) }
                                .buttonStyle(.plain)
                        } else if row.available {
                            NavigationLink { SettingsPage(core: core, code: row.code, host: host) } label: { label(row) }
                        } else {
                            Button { notYet = TrRender.text(row.title) } label: { label(row) }
                                .buttonStyle(.plain)
                        }
                    }
                }
                Section {
                    Text(L("tra_privacy_note")).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .sheet(isPresented: $climbingDays) {
            ClimbingDaysListView(core: core) { climbingDays = false }
        }
        .alert(notYet ?? "", isPresented: Binding(get: { notYet != nil }, set: { if !$0 { notYet = nil } })) {
            Button(L("action_ok")) { notYet = nil }
        } message: {
            Text(LI("training_not_on_iphone"))
        }
    }

    private func label(_ row: SettingsRowUi) -> some View {
        HStack(spacing: 12) {
            Image(systemName: Self.icon(row.code)).foregroundStyle(Color.accentColor).frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(tr: row.title)
                Text(tr: row.summary).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                if !row.available {
                    Text(LI("training_not_on_iphone")).font(.caption2).foregroundStyle(.secondary)
                }
            }
            Spacer()
        }
        .contentShape(Rectangle())
    }

    private static func icon(_ code: String) -> String {
        switch code {
        case "profile": return "flag"
        case "about": return "person"
        case "equipment": return "dumbbell"
        case "nutrition": return "fork.knife"
        case "reminders": return "bell"
        case "timer": return "timer"
        case "display": return "eye"
        case "connections": return "arrow.triangle.2.circlepath"
        case "climbingDays": return "mountain.2"
        default: return "brain"
        }
    }
}

/// One page of the settings, reading the live state from the host.
private struct SettingsPage: View {
    let core: AppCore
    let code: String
    let host: SettingsHost

    private var ui: AthleteSettingsScreenState { host.state }
    private var model: AthleteSettingsScreenModel { host.model }

    var body: some View {
        Form {
            switch code {
            case "profile": profile
            case "about": SettingsAboutPage(host: host)
            case "equipment": SettingsPlacesPage(host: host)
            case "timer": timer
            case "display": display
            case "connections": connections
            default: SettingsLearnedPage(host: host)
            }
        }
        .navigationTitle(ui.rows.first { $0.code == code }.map { TrRender.text($0.title) } ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if let info = Self.info(code) {
                    ProgressInfoButton(title: L("tr_action_settings"), text: info)
                }
            }
        }
    }

    private static func info(_ code: String) -> String? {
        switch code {
        case "about": return L("tra_personal_info")
        case "equipment": return L("tro_places_intro")
        case "excluded": return L("trlr_info")
        default: return nil
        }
    }

    @ViewBuilder
    private var profile: some View {
        Section {
            LabeledContent(L("trc_goal_title"), value: TrRender.text(ui.coachGoal))
        }
        Section(L("tra_plan_title")) {
            Stepper(TrRender.text(ui.weeklyGoalText),
                    onIncrement: { model.setWeeklyGoal(days: ui.weeklyGoal + 1) },
                    onDecrement: { model.setWeeklyGoal(days: ui.weeklyGoal - 1) })
        }
        Section {
            NavigationLink(L("trr_benchmarks")) { ProgressBenchmarksView(core: core) }
            Text(L("trr_benchmarks_hint")).font(.caption).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var timer: some View {
        toggle(L("tra_auto_rest"), ui.autoRest, "autoRest")
        toggle(L("tra_timer_sound"), ui.timerSound, "sound")
        toggle(L("tra_timer_vibration"), ui.timerVibration, "vibration")
        toggle(L("tra_timer_voice"), ui.timerVoice, "voice")
    }

    @ViewBuilder
    private var display: some View {
        Section {
            toggle(L("tra_module_checkin"), ui.checkinEnabled, "checkin")
            toggle(L("tra_module_body"), ui.bodyEnabled, "body")
            toggle(L("tra_hide_numbers"), ui.hideNumbers, "hideNumbers")
        }
        Section {
            Picker(L("settings_units_title"), selection: Binding(get: { ui.unitsCode }, set: { model.setUnits(code: $0) })) {
                Text(L("settings_units_metric")).tag("metric")
                Text(L("settings_units_us")).tag("imperial")
            }
            .pickerStyle(.inline)
        } footer: {
            Text(L("settings_units_desc"))
        }
    }

    @ViewBuilder
    private var connections: some View {
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text(L("tru_set_connections"))
                Text(LI("training_health_connect_unavailable")).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func toggle(_ title: String, _ value: Bool, _ code: String) -> some View {
        Toggle(title, isOn: Binding(get: { value }, set: { model.setSwitch(code: code, on: $0) }))
    }
}

private struct SettingsAboutPage: View {
    let host: SettingsHost
    @State private var weightText = ""
    @State private var heightText = ""
    @State private var yearText = ""
    @State private var invalid: String?
    @State private var started = false

    private var ui: AthleteSettingsScreenState { host.state }
    private var model: AthleteSettingsScreenModel { host.model }

    var body: some View {
        Section(L("tr_metric_weight")) {
            LabeledContent(L("tr_metric_weight"), value: TrRender.text(ui.weightText))
            HStack {
                TextField(L("tru_enter_weight"), text: $weightText).keyboardType(.decimalPad)
                Text(ui.unitsCode == "imperial" ? "lb" : "kg").foregroundStyle(.secondary)
                Button(L("tr_action_save")) {
                    if let v = TrRender.parse(weightText), model.logWeight(display: v) { weightText = "" } else { invalid = "weight" }
                }
                .buttonStyle(.borderless)
            }
            if invalid == "weight" { Text(LI("training_weight_invalid")).font(.caption).foregroundStyle(.red) }
        }
        Section(L("tru_set_body_data")) {
            HStack {
                TextField(L("tr_metric_height"), text: $heightText).keyboardType(.decimalPad)
                Text(ui.heightUnit).foregroundStyle(.secondary)
                Button(L("tr_action_save")) {
                    if let v = TrRender.parse(heightText), model.setHeight(display: v) { invalid = nil } else { invalid = "height" }
                }
                .buttonStyle(.borderless)
            }
            if invalid == "height" { Text(L("trb_value_invalid")).font(.caption).foregroundStyle(.red) }
            HStack {
                TextField(L("tru_set_birth_year"), text: $yearText).keyboardType(.numberPad)
                Button(L("tr_action_save")) {
                    let year = Calendar.current.component(.year, from: Date())
                    if model.setBirthYear(text: yearText, currentYear: Int32(year)) { invalid = nil } else { invalid = "year" }
                }
                .buttonStyle(.borderless)
            }
            if invalid == "year" { Text(L("trb_value_invalid")).font(.caption).foregroundStyle(.red) }
        }
        Section(L("tru_set_sex")) {
            TrainingFlow(spacing: 6) {
                ForEach(ui.sexes, id: \.code) { s in
                    TrainingChoiceChip(title: TrRender.text(s.label), selected: s.selected) { model.chooseSex(code: s.code) }
                }
            }
        }
        .onAppear {
            guard !started else { return }
            started = true
            if ui.hasHeight { heightText = TrRender.number(ui.heightDisplay, decimals: 1) }
            if ui.birthYear > 0 { yearText = String(ui.birthYear) }
        }
    }
}

private struct SettingsPlaceTarget: Identifiable {
    let id: String
}

private struct SettingsPlacesPage: View {
    let host: SettingsHost
    @State private var editing: SettingsPlaceTarget?

    private var ui: AthleteSettingsScreenState { host.state }
    private var model: AthleteSettingsScreenModel { host.model }

    var body: some View {
        if ui.places.isEmpty {
            // Nothing set up yet: the first equipment becomes the first place (at home), once.
            Section {
                Button(L("tro_add")) { editing = SettingsPlaceTarget(id: "") }
            }
        }
        ForEach(ui.places, id: \.id) { place in
            Section {
                Button { editing = SettingsPlaceTarget(id: place.id) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(tr: place.label).font(.headline)
                            if place.isDefault { Text(L("tro_default")).font(.caption).foregroundStyle(.secondary) }
                            if place.isToday { Text(L("tro_today")).font(.caption).foregroundStyle(Color.accentColor) }
                        }
                        Text(LP("tru_set_equipment_count", place.equipmentCodes.count)).font(.caption).foregroundStyle(.secondary)
                    }
                }
                .buttonStyle(.plain)
                if !place.isDefault {
                    Button(L("tro_make_default")) { model.makeDefaultPlace(placeId: place.id) }
                }
                if ui.places.count > 1 {
                    Button(L("tro_remove"), role: .destructive) { model.removePlace(placeId: place.id) }
                }
            }
        }
        if !ui.places.isEmpty {
            Section(L("tro_add")) {
                ForEach(ui.placeKinds, id: \.code) { kind in
                    Button(TrRender.text(kind.label)) { model.addPlace(kindCode: kind.code) }
                }
            }
        }
        Section(L("tra_increment")) {
            TrainingFlow(spacing: 6) {
                ForEach(ui.increments, id: \.code) { inc in
                    TrainingChoiceChip(title: TrRender.text(inc.label), selected: inc.selected) { model.setIncrement(code: inc.code) }
                }
            }
        }
        .sheet(item: $editing) { target in
            SettingsPlaceSheet(host: host, placeId: target.id) { editing = nil }
        }
    }
}

/// A place's name and equipment; every change is kept at once (Android `PlacesEditor`).
private struct SettingsPlaceSheet: View {
    let host: SettingsHost
    let placeId: String
    let onDone: () -> Void
    @State private var selected: [String] = []
    @State private var name = ""
    @State private var started = false

    private var place: SettingsPlaceUi? { host.state.places.first { $0.id == placeId } }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if !placeId.isEmpty {
                        TextField(L("tro_name"), text: $name)
                            .textFieldStyle(.roundedBorder)
                            .onSubmit { host.model.renamePlace(placeId: placeId, name: name) }
                    }
                    TrainingEquipmentEditor(selected: $selected)
                }
                .padding(16)
            }
            .navigationTitle(place.map { TrRender.text($0.label) } ?? L("tru_set_equipment"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("tr_action_done")) {
                        if !placeId.isEmpty && name != (place?.name ?? "") { host.model.renamePlace(placeId: placeId, name: name) }
                        onDone()
                    }
                }
            }
        }
        .onAppear {
            guard !started else { return }
            started = true
            selected = place?.equipmentCodes ?? []
            name = place?.name ?? ""
        }
        .onChange(of: selected) { _, now in
            guard started else { return }
            host.model.savePlaceEquipment(placeId: placeId, codes: now)
        }
    }
}

/// "What the coach learned" with "Forget" per row and "Forget everything"; never-suggested exercises.
private struct SettingsLearnedPage: View {
    let host: SettingsHost
    @State private var confirmAll = false

    private var ui: AthleteSettingsScreenState { host.state }
    private var model: AthleteSettingsScreenModel { host.model }

    private static let groups: [(String, String)] = [
        ("weekday", "trlr_weekdays"), ("level", "trlr_levels"), ("kept", "trlr_kept"),
        ("liked", "trlr_liked"), ("less", "trlr_less"), ("ownLoad", "trlr_own_loads"),
    ]

    var body: some View {
        if ui.learnedEmpty {
            Section { Text(L("trlr_empty")).foregroundStyle(.secondary) }
        }
        if ui.learnedMinutes > 0 {
            Section(L("trlr_minutes")) {
                HStack {
                    Text(tr: ui.learnedMinutesText)
                    Spacer()
                    Button(L("tre_duration_apply")) { model.applyLearnedMinutes() }.buttonStyle(.borderless)
                }
            }
        }
        ForEach(Self.groups, id: \.0) { group in
            let rows = ui.learned.filter { $0.kind == group.0 }
            if !rows.isEmpty {
                Section(L(group.1)) {
                    ForEach(rows, id: \.key) { row in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(tr: row.title)
                                if let detail = TrRender.optional(row.detail) {
                                    Text(detail).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            Button(L("trlr_forget")) { model.forget(kind: row.kind, key: row.key) }.buttonStyle(.borderless)
                        }
                    }
                }
            }
        }
        if !ui.learnedEmpty {
            Section {
                Button(L("trlr_forget_all"), role: .destructive) { confirmAll = true }
            }
            .alert(L("trlr_forget_all"), isPresented: $confirmAll) {
                Button(L("trlr_forget"), role: .destructive) { model.forgetAll() }
                Button(L("action_cancel"), role: .cancel) {}
            } message: {
                Text(L("trlr_forget_all_text"))
            }
        }
        Section(L("trc_excluded_title")) {
            if ui.excluded.isEmpty {
                Text(L("trc_excluded_none")).font(.caption).foregroundStyle(.secondary)
            }
            ForEach(ui.excluded, id: \.slug) { ex in
                HStack {
                    Text(ex.name)
                    Spacer()
                    Button(L("trc_excluded_allow")) { model.allow(slug: ex.slug) }.buttonStyle(.borderless)
                }
            }
        }
    }
}
