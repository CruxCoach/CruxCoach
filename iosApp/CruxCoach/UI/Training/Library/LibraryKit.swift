import CruxCoachCore
import SwiftUI

/// Renders the Kotlin `LibraryText`: an Android string key with its arguments,
/// a joined list of parts, or a literal (a workout's own name, catalogue data).
enum LibraryRender {
    static func text(_ t: LibraryText) -> String {
        if t.key.isEmpty {
            if !t.parts.isEmpty { return t.parts.map { text($0) }.joined(separator: t.separator) }
            return t.literal
        }
        let key = t.plural ? t.key + (t.count == 1 ? ".one" : ".other") : t.key
        return substitute(NSLocalizedString(key, comment: ""), t.args.map { arg($0) })
    }

    static func arg(_ a: LibraryArg) -> String {
        switch a.kind {
        case "key": return NSLocalizedString(a.text, comment: "")
        case "int": return String(a.intValue)
        case "decimal": return number(a.doubleValue, decimals: Int(a.decimals), signed: a.signed)
        case "nested":
            if let nested = a.nested { return text(nested) }
            return ""
        default: return a.text
        }
    }

    /// Android `formatNumber`: at most [decimals] decimals, trailing zeros dropped, the locale's separator.
    static func number(_ value: Double, decimals: Int, signed: Bool = false) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.usesGroupingSeparator = false
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = max(0, decimals)
        formatter.roundingMode = .halfUp
        let body = formatter.string(from: NSNumber(value: value)) ?? String(value)
        return signed && value > 0 ? "+" + body : body
    }

    /// "1:30" for seconds (Android `formatClock`).
    static func clock(_ seconds: Int) -> String {
        let s = max(0, seconds)
        if s >= 3600 { return String(format: "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) }
        return String(format: "%d:%02d", s / 60, s % 60)
    }

    /// Weekday name for an ISO day (1 = Monday) in the current locale.
    static func weekday(_ iso: Int, short: Bool = false) -> String {
        var calendar = Calendar.current
        calendar.locale = Locale.current
        let symbols = short ? calendar.shortStandaloneWeekdaySymbols : calendar.standaloneWeekdaySymbols
        // Calendar symbols start on Sunday.
        let index = iso % 7
        guard index >= 0, index < symbols.count else { return "" }
        return symbols[index].prefix(1).uppercased() + symbols[index].dropFirst()
    }

    /// "Saturday, 17 October 2026" for an ISO date.
    static func fullDate(_ iso: String) -> String {
        let parser = DateFormatter()
        parser.calendar = Calendar(identifier: .gregorian)
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.dateFormat = "yyyy-MM-dd"
        guard let date = parser.date(from: iso) else { return iso }
        return DateFormatter.localizedString(from: date, dateStyle: .full, timeStyle: .none)
    }

    static func mediumDate(epochMs: Int64) -> String {
        DateFormatter.localizedString(from: Date(timeIntervalSince1970: Double(epochMs) / 1000), dateStyle: .medium, timeStyle: .none)
    }

    /// Replaces every placeholder (`%@`, `%ld`, `%1$@`, `%2$ld`, …) with an argument as text, so an
    /// argument's type never has to match its placeholder (a mismatch would crash `String(format:)`).
    static func substitute(_ format: String, _ args: [String]) -> String {
        guard format.contains("%") else { return format }
        var out = ""
        var next = 0
        let chars = Array(format)
        var i = 0
        while i < chars.count {
            let c = chars[i]
            guard c == "%", i + 1 < chars.count else {
                out.append(c)
                i += 1
                continue
            }
            if chars[i + 1] == "%" {
                out.append("%")
                i += 2
                continue
            }
            var j = i + 1
            var digits = ""
            while j < chars.count, chars[j].isNumber {
                digits.append(chars[j])
                j += 1
            }
            var position: Int?
            if j < chars.count, chars[j] == "$", let n = Int(digits) {
                position = n - 1
                j += 1
            }
            while j < chars.count, "lhqzjt".contains(chars[j]) { j += 1 }
            guard j < chars.count else {
                out.append(contentsOf: String(chars[i..<chars.count]))
                break
            }
            let index: Int
            if let position {
                index = position
            } else {
                index = next
                next += 1
            }
            if index >= 0, index < args.count { out += args[index] }
            i = j + 1
        }
        return out
    }
}

/// The catalogue's language for the Kotlin models: German texts for a German UI, English otherwise.
enum LibraryLanguage {
    static var current: String {
        let code = Locale.preferredLanguages.first ?? "en"
        return code.lowercased().hasPrefix("de") ? "de" : "en"
    }
}

/// Where the library pushes to.
enum LibraryRoute: Hashable, Identifiable {
    /// [routineId] "" = new; [from] "builtin:<key>" or "ex:<slug>"; [defaultName] a starter's localized name.
    case editor(routineId: String, from: String, defaultName: String)
    case weekPlan
    case exercise(String)
    case customExercise

    var id: String {
        switch self {
        case let .editor(routineId, from, _): return "editor-\(routineId)-\(from)"
        case .weekPlan: return "weekPlan"
        case let .exercise(slug): return "exercise-\(slug)"
        case .customExercise: return "customExercise"
        }
    }
}

/// The destination of a [LibraryRoute].
struct LibraryRouteView: View {
    let core: AppCore
    let route: LibraryRoute
    let onStartWorkout: (String) -> Void

    var body: some View {
        switch route {
        case let .editor(routineId, from, defaultName):
            RoutineEditorView(core: core, routineId: routineId, from: from, defaultName: defaultName)
        case .weekPlan:
            WeekPlanView(core: core)
        case let .exercise(slug):
            ExerciseDetailView(core: core, slug: slug, onStartWorkout: onStartWorkout)
        case .customExercise:
            CustomExerciseView(core: core, onStartWorkout: onStartWorkout)
        }
    }
}

/// An exercise's movement pictogram on a tile in its category's colour (Android `ExerciseThumb`).
struct ExercisePictogramTile: View {
    let pictogram: PictogramUi
    var size: CGFloat = 48
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        let color = Color(argb: dark ? pictogram.darkArgb : pictogram.lightArgb)
        ZStack {
            RoundedRectangle(cornerRadius: size / 4).fill(Color(uiColor: .systemBackground))
            RoundedRectangle(cornerRadius: size / 4).fill(color.opacity(dark ? 0.16 : 0.11))
            LibraryPictogramCanvas(shapes: pictogram.shapes, color: color,
                                   gear: Color(uiColor: .secondaryLabel).opacity(0.75))
                .padding(size / 12)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// Strokes and fills a pictogram's shapes on its 24-unit grid, in the order Kotlin lists them.
struct LibraryPictogramCanvas: View {
    let shapes: [PictoShapeUi]
    let color: Color
    let gear: Color

    var body: some View {
        Canvas { context, size in
            let u = min(size.width, size.height) / 24
            let ox = (size.width - 24 * u) / 2
            let oy = (size.height - 24 * u) / 2
            for shape in shapes {
                let v: [CGFloat] = shape.points.map { CGFloat($0.doubleValue) }
                func pt(_ i: Int) -> CGPoint { CGPoint(x: ox + v[i] * u, y: oy + v[i + 1] * u) }
                let shading: GraphicsContext.Shading
                switch shape.role {
                case "gear": shading = .color(gear)
                case "far": shading = .color(color.opacity(0.55))
                default: shading = .color(color)
                }
                let style = StrokeStyle(lineWidth: CGFloat(shape.width) * u, lineCap: .round, lineJoin: .round)
                switch shape.kind {
                case "polyline":
                    guard v.count >= 4 else { continue }
                    var path = Path()
                    path.move(to: pt(0))
                    var i = 2
                    while i + 1 < v.count {
                        path.addLine(to: pt(i))
                        i += 2
                    }
                    context.stroke(path, with: shading, style: style)
                case "box":
                    guard v.count >= 4 else { continue }
                    let a = pt(0)
                    let b = pt(2)
                    let path = Path(roundedRect: CGRect(x: a.x, y: a.y, width: b.x - a.x, height: b.y - a.y),
                                    cornerRadius: CGFloat(shape.radius) * u)
                    if shape.filled { context.fill(path, with: shading) } else { context.stroke(path, with: shading, style: style) }
                case "dot":
                    guard v.count >= 2 else { continue }
                    let c = pt(0)
                    let r = CGFloat(shape.radius) * u
                    let path = Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
                    if shape.filled { context.fill(path, with: shading) } else { context.stroke(path, with: shading, style: style) }
                case "curve":
                    guard v.count >= 6 else { continue }
                    var path = Path()
                    path.move(to: pt(0))
                    path.addQuadCurve(to: pt(4), control: pt(2))
                    context.stroke(path, with: shading, style: StrokeStyle(lineWidth: CGFloat(shape.width) * u, lineCap: .round))
                default:
                    continue
                }
            }
        }
    }
}

/// Lays chips out in rows that wrap (Android `FlowRow`).
struct LibraryFlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var widest: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0, x + size.width > maxWidth {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            widest = max(widest, x - spacing)
        }
        return CGSize(width: proposal.width ?? widest, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

/// A filter or choice chip (Android `FilterChip`).
struct LibraryChip: View {
    let title: String
    let selected: Bool
    var systemImage: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if selected { Image(systemName: "checkmark").font(.caption.weight(.semibold)) }
                Text(title).lineLimit(1)
                if let systemImage { Image(systemName: systemImage).font(.caption) }
            }
            .font(.subheadline)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Capsule().fill(selected ? Color.accentColor.opacity(0.18) : Color(uiColor: .secondarySystemBackground)))
            .overlay(Capsule().stroke(selected ? Color.accentColor : Color(uiColor: .separator), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// "●●●○○" in the accent colour (Android `DifficultyDots`).
struct LibraryDifficultyDots: View {
    let level: Int

    var body: some View {
        let clamped = min(max(level, 1), 5)
        Text(String(repeating: "●", count: clamped) + String(repeating: "○", count: 5 - clamped))
            .font(.caption)
            .foregroundStyle(Color.accentColor)
            .accessibilityLabel(L("trx_difficulty", clamped))
    }
}

/// A ± stepper with a fixed-width value; tapping the value types it (Android `EditorStepper`).
struct LibraryStepperRow: View {
    let label: String
    let valueText: String
    let range: String
    let onMinus: () -> Void
    let onPlus: () -> Void
    let onTyped: (Int) -> Void
    @State private var typing = false
    @State private var draft = ""

    var body: some View {
        HStack {
            Text(label).font(.body)
            Spacer()
            Button(action: onMinus) { Image(systemName: "minus") }
                .buttonStyle(.bordered)
                .accessibilityLabel(L("trwo_minus", label))
            Button {
                draft = ""
                typing = true
            } label: {
                Text(valueText).font(.headline.monospacedDigit()).frame(minWidth: 64)
            }
            .buttonStyle(.plain)
            Button(action: onPlus) { Image(systemName: "plus") }
                .buttonStyle(.bordered)
                .accessibilityLabel(L("trwo_plus", label))
        }
        .alert(label, isPresented: $typing) {
            TextField(range, text: $draft).keyboardType(.numberPad)
            Button(L("tr_action_cancel"), role: .cancel) {}
            Button(L("tr_action_save")) {
                if let value = Double(draft.replacingOccurrences(of: ",", with: ".")), value.isFinite {
                    onTyped(Int(value.rounded()))
                }
            }
        } message: {
            Text(range)
        }
    }
}
