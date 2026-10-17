import CruxCoachCore
import Foundation
import SwiftUI

/// Renders the training area's `TrText` recipes: Android's resource key (generated into
/// `Localizable.strings`) with its arguments. The JVM tests check every key and argument kind
/// against the en and de tables, so `%@` never receives an integer here.
enum TrRender {
    static func text(_ t: TrText) -> String {
        if t.key.isEmpty {
            return t.args.map { string($0) }.joined(separator: t.joinWith)
        }
        let key = t.pluralCount >= 0 ? t.key + (t.pluralCount == 1 ? ".one" : ".other") : t.key
        let format = NSLocalizedString(key, comment: "")
        if t.args.isEmpty { return format }
        let values: [CVarArg] = t.args.map { value($0) }
        return String(format: format, arguments: values)
    }

    /// Empty for an empty recipe, so `if !s.isEmpty` hides a line.
    static func optional(_ t: TrText) -> String? {
        let s = text(t)
        return s.isEmpty ? nil : s
    }

    private static func value(_ a: TrArg) -> CVarArg {
        if a.kind == "int" { return Int(a.number) }
        return string(a)
    }

    private static func string(_ a: TrArg) -> String {
        switch a.kind {
        case "text": return a.text
        case "key": return NSLocalizedString(a.text, comment: "")
        case "int": return String(Int(a.number))
        case "num": return number(a.number, decimals: Int(a.decimals), signed: a.signed)
        case "nested": return a.nested.map { text($0) } ?? ""
        case "date": return date(a.text)
        case "weekday": return weekday(Int(a.number))
        default: return a.text
        }
    }

    /// Android `formatNumber`: at most `decimals` decimals, trailing zeros trimmed, the device's separator.
    static func number(_ value: Double, decimals: Int, signed: Bool = false) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.usesGroupingSeparator = false
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = max(0, decimals)
        let body = formatter.string(from: NSNumber(value: value)) ?? String(value)
        return (signed && value > 0 ? "+" : "") + body
    }

    /// An ISO date (yyyy-mm-dd) in the medium style, as Android's `formatDay`.
    static func date(_ iso: String) -> String {
        let parser = DateFormatter()
        parser.calendar = Calendar(identifier: .gregorian)
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.dateFormat = "yyyy-MM-dd"
        guard let day = parser.date(from: iso) else { return iso }
        let out = DateFormatter()
        out.dateStyle = .medium
        out.timeStyle = .none
        return out.string(from: day)
    }

    /// ISO weekday 1 = Monday … 7 = Sunday, full name in the device language.
    static func weekday(_ iso: Int) -> String {
        let names = DateFormatter().weekdaySymbols ?? []
        // weekdaySymbols starts on Sunday.
        let index = iso % 7
        return index < names.count ? names[index] : ""
    }

    /// ISO weekday as one narrow letter for the week strip.
    static func weekdayNarrow(_ iso: Int) -> String {
        let names = DateFormatter().veryShortWeekdaySymbols ?? []
        let index = iso % 7
        return index < names.count ? names[index] : ""
    }

    /// "1:30", "1:00:05" (Android `formatClock`).
    static func clock(_ seconds: Int) -> String {
        let s = max(0, seconds)
        if s >= 3600 { return String(format: "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) }
        return String(format: "%d:%02d", s / 60, s % 60)
    }

    /// Comma or dot decimals, as Android's `parseDecimal`.
    static func parse(_ text: String) -> Double? {
        let cleaned = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        guard let v = Double(cleaned), v.isFinite else { return nil }
        return v
    }
}

/// `Text(tr: …)` for a recipe.
extension Text {
    init(tr: TrText) {
        self.init(verbatim: TrRender.text(tr))
    }
}

/// Small shared pieces of the training screens.
enum TrainingStyle {
    /// Android's positive green and caution amber, readable on light and dark.
    static let positive = Color.green
    static let caution = Color.orange
    static let water = Color(red: 0.31, green: 0.64, blue: 0.97)

    /// "de" for a German device, else "en": the language of the exercise catalogue.
    static var catalogLanguage: String {
        let code = Locale.current.language.languageCode?.identifier ?? "en"
        return code == "de" ? "de" : "en"
    }
}

/// A one-line message at the bottom with an optional action, for "Undo" after a decision
/// (Android's snackbars). The caller clears it; it hides itself after a few seconds.
struct TrainingSnack: Equatable {
    let id = UUID()
    let text: String
    let action: String?

    static func == (lhs: TrainingSnack, rhs: TrainingSnack) -> Bool { lhs.id == rhs.id }
}

struct TrainingSnackBar: View {
    @Binding var snack: TrainingSnack?
    var onAction: () -> Void

    var body: some View {
        if let current = snack {
            HStack(spacing: 12) {
                Text(current.text)
                    .font(.subheadline)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let action = current.action {
                    Button(action) {
                        snack = nil
                        onAction()
                    }
                    .font(.subheadline.weight(.semibold))
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(.thickMaterial, in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .task(id: current.id) {
                try? await Task.sleep(nanoseconds: current.action == nil ? 3_000_000_000 : 6_000_000_000)
                if snack?.id == current.id { snack = nil }
            }
        }
    }
}

/// A big, equal-width choice: result numbers, reserve answers, how hard it was (Android `NumberChip`).
struct TrainingNumberChip: View {
    let text: String
    var selected: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(selected ? Color.accentColor.opacity(0.2) : Color.clear,
                            in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10)
                    .stroke(selected ? Color.accentColor : Color.secondary.opacity(0.5), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// A chip that can be on or off (Android `FilterChip`).
struct TrainingChoiceChip: View {
    let title: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if selected { Image(systemName: "checkmark").font(.caption.weight(.bold)) }
                Text(title).font(.subheadline)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(selected ? Color.accentColor.opacity(0.2) : Color(.secondarySystemBackground),
                        in: Capsule())
            .overlay(Capsule().stroke(selected ? Color.accentColor : Color.clear, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Lays chips out in rows that wrap (Android `FlowRow`).
struct TrainingFlow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, widest: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width {
                y += rowHeight + spacing
                x = 0
                rowHeight = 0
            }
            x += size.width + spacing
            widest = max(widest, x - spacing)
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: min(widest, width), height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX {
                y += rowHeight + spacing
                x = bounds.minX
                rowHeight = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

/// Types a number for a value (Android `StepperEdit` / `NumberEntryDialog`).
struct TrainingNumberEntry: Identifiable {
    let id = UUID()
    let title: String
    let initial: String
    let decimal: Bool
    let allowNegative: Bool
    let onValue: (Double) -> Void
}

extension View {
    /// The alert that types a number for a `TrainingNumberEntry`.
    func trainingNumberEntry(_ entry: Binding<TrainingNumberEntry?>) -> some View {
        modifier(TrainingNumberEntryModifier(entry: entry))
    }
}

private struct TrainingNumberEntryModifier: ViewModifier {
    @Binding var entry: TrainingNumberEntry?
    @State private var text = ""

    func body(content: Content) -> some View {
        content
            .alert(entry?.title ?? "", isPresented: Binding(get: { entry != nil }, set: { if !$0 { entry = nil } })) {
                TextField("", text: $text)
                    .keyboardType(entry?.decimal == true ? .decimalPad : .numberPad)
                Button(L("tr_action_cancel"), role: .cancel) { entry = nil }
                Button(L("tr_action_save")) {
                    if let current = entry, let value = TrRender.parse(text), current.allowNegative || value >= 0 {
                        current.onValue(value)
                    }
                    entry = nil
                }
            }
            .onChange(of: entry?.id) { _, _ in text = entry?.initial ?? "" }
    }
}
