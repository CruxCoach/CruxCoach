import CruxCoachCore
import SwiftUI

/// "Quick start" (Android `BasicsScreen`): on the first visit, body data, climbing level, experience and
/// equipment — every value optional, "Later" skips them. Two pages, the privacy note on top of both.
struct BasicsView: View {
    let core: AppCore
    let onDone: () -> Void

    @State private var host: ScreenHost<BasicsScreenModel, BasicsScreenState>?

    var body: some View {
        NavigationStack {
            Group {
                if let host, !host.state.loading {
                    BasicsForm(model: host.model, ui: host.state, onDone: onDone)
                } else {
                    ProgressView()
                }
            }
            .navigationTitle(L("trq_title"))
            .navigationBarTitleDisplayMode(.inline)
        }
        .task {
            guard host == nil, let model = core.makeBasicsScreen() else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.load()
        }
    }
}

private struct BasicsForm: View {
    let model: BasicsScreenModel
    let ui: BasicsScreenState
    let onDone: () -> Void

    @State private var prefilled = false
    @State private var page = 0
    @State private var weight = ""
    @State private var height = ""
    @State private var year = ""
    @State private var sex = ""
    @State private var grade = ""
    @State private var experience = ""
    @State private var equipment: [String] = []
    @State private var pickingGrade = false

    private let sexes: [(code: String, key: String)] = [("FEMALE", "tra_sex_female"), ("MALE", "tra_sex_male"), ("OTHER", "trq_sex_none")]
    private let experiences: [(code: String, key: String)] = [
        ("UNDER_1", "trc_experience_under_1"), ("Y1_2", "trc_experience_1_2"), ("Y3_5", "trc_experience_3_5"), ("OVER_5", "trc_experience_over_5"),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ProgressView(value: Double(page + 1), total: 2).tint(Color.accentColor)
                HStack(spacing: 10) {
                    Image(systemName: "lock.fill").foregroundStyle(Color.accentColor)
                    Text(L("trq_private")).font(.subheadline)
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                if page == 0 { aboutYou } else { climbing }
            }
            .padding(20)
        }
        .safeAreaInset(edge: .bottom) {
            HStack {
                if page == 1 { Button(L("trq_back")) { page = 0 } }
                Spacer()
                Button(L(page == 0 ? "trq_next" : "trq_done")) {
                    if page == 0 { page = 1 } else { finish() }
                }
                .buttonStyle(.borderedProminent)
            }
            .padding(16)
            .background(.bar)
        }
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(L("trq_later")) { model.skip() } }
        }
        .onAppear {
            guard !prefilled else { return }
            prefilled = true
            weight = ui.weightText
            height = ui.heightText
            year = ui.birthYearText
            sex = ui.sexCode
            grade = ui.grade
            experience = ui.experienceCode
            equipment = ui.equipmentCodes
        }
        .onChange(of: ui.finished) { _, finished in if finished { onDone() } }
        .sheet(isPresented: $pickingGrade) { gradePicker }
    }

    private func finish() {
        model.save(weightText: weight, heightText: height, birthYearText: year, sexCode: sex, grade: grade,
                   experienceCode: experience, equipmentCodes: equipment)
    }

    private var aboutYou: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L("trq_you_title")).font(.title2.weight(.semibold))
            Text(L("trq_you_why")).font(.subheadline).foregroundStyle(.secondary)
            field(L("trq_weight"), $weight, suffix: ui.massUnit, invalid: !weight.isEmpty && model.weightKg(text: weight) < 0, decimal: true)
            field(L("trq_height"), $height, suffix: ui.lengthUnit, invalid: !height.isEmpty && model.heightCm(text: height) < 0, decimal: true)
            field(L("trq_birth_year"), $year, suffix: nil, invalid: !year.isEmpty && model.birthYear(text: year) < 0, decimal: false)
                .onChange(of: year) { _, v in
                    let digits = String(v.filter { $0.isNumber }.prefix(4))
                    if digits != v { year = digits }
                }
            Text(L("trq_sex")).font(.subheadline.weight(.semibold)).padding(.top, 8)
            TrainingFlow {
                ForEach(sexes, id: \.code) { s in
                    TrainingChoiceChip(title: L(s.key), selected: sex == s.code) { sex = sex == s.code ? "" : s.code }
                }
            }
        }
    }

    private var climbing: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L("trq_climb_title")).font(.title2.weight(.semibold))
            Text(L("trq_climb_why")).font(.subheadline).foregroundStyle(.secondary)
            Text(L("trq_grade")).font(.subheadline.weight(.semibold)).padding(.top, 8)
            HStack {
                Button { pickingGrade = true } label: {
                    Text(grade.isEmpty ? L("trc_grade_pick") : model.gradeLabel(grade: grade))
                        .font(.headline)
                        .foregroundStyle(grade.isEmpty ? Color.accentColor : Color.primary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
                if !grade.isEmpty {
                    Button { grade = "" } label: { Image(systemName: "xmark") }.accessibilityLabel(L("trc_clear"))
                }
                Button("−") { grade = model.stepGrade(current: grade, direction: -1) }.buttonStyle(.bordered)
                Button("+") { grade = model.stepGrade(current: grade, direction: 1) }.buttonStyle(.bordered)
            }
            if !ui.logbookGrade.isEmpty && grade == ui.logbookGrade && ui.gradeUnset {
                Text(L("trq_grade_from_logbook")).font(.caption).foregroundStyle(.secondary)
            }
            Text(L("trc_experience")).font(.subheadline.weight(.semibold)).padding(.top, 8)
            TrainingFlow {
                ForEach(experiences, id: \.code) { e in
                    TrainingChoiceChip(title: L(e.key), selected: experience == e.code) {
                        experience = experience == e.code ? "" : e.code
                    }
                }
            }
            TrainingEquipmentEditor(selected: $equipment).padding(.top, 8)
        }
    }

    private var gradePicker: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List(model.gradeScale, id: \.self) { g in
                    Button {
                        grade = g
                        pickingGrade = false
                    } label: {
                        Text(model.gradeLabel(grade: g)).fontWeight(g == grade ? .bold : .regular)
                    }
                    .id(g)
                }
                .onAppear { proxy.scrollTo(grade.isEmpty ? model.stepGrade(current: "", direction: 1) : grade, anchor: .center) }
            }
            .navigationTitle(L("trc_grade_pick"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel")) { pickingGrade = false } } }
        }
        .presentationDetents([.medium, .large])
    }

    private func field(_ label: String, _ text: Binding<String>, suffix: String?, invalid: Bool, decimal: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                TextField(label, text: text).keyboardType(decimal ? .decimalPad : .numberPad)
                if let suffix { Text(suffix).foregroundStyle(.secondary) }
            }
            .padding(12)
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(invalid ? Color.red : Color.secondary.opacity(0.4)))
        }
    }
}
