import CruxCoachCore
import Foundation

/// Renders the nutrition texts the Kotlin core composes (`NutriText`): an
/// Android string key with typed arguments, a plural, a literal or a join.
/// Kotlin decides which sentence and which numbers; the wording comes from
/// `Localizable.strings` / `IOS.strings`, exactly as `L`, `LP` and `LI` do.
enum NutritionText {
    static func render(_ text: NutriText) -> String {
        if !text.parts.isEmpty {
            return text.parts.map { render($0) }.joined(separator: text.separator)
        }
        if text.key.isEmpty {
            if text.args.count == 1 { return argument(text.args[0]) }
            return text.literal
        }
        var key = text.key
        if text.count >= 0 { key += (text.count == 1 ? ".one" : ".other") }
        let format = text.table == "ios"
            ? NSLocalizedString(key, tableName: "IOS", comment: "")
            : NSLocalizedString(key, comment: "")
        if text.args.isEmpty { return format }
        var values: [CVarArg] = []
        for arg in text.args {
            if arg.kind == "int" {
                values.append(Int(arg.integer))
            } else {
                values.append(argument(arg))
            }
        }
        return String(format: format, arguments: values)
    }

    private static func argument(_ arg: NutriTextArg) -> String {
        switch arg.kind {
        case "int": return String(arg.integer)
        case "number": return number(arg.number, decimals: Int(arg.decimals))
        case "message":
            if let nested = arg.nested { return render(nested) }
            return ""
        default: return arg.text
        }
    }

    /// Android's `formatNumber`: at most `decimals` decimals, trailing zeros
    /// dropped, in the region's decimal separator.
    static func number(_ value: Double, decimals: Int) -> String {
        let factor = pow(10.0, Double(max(decimals, 0)))
        let rounded = (value * factor).rounded() / factor
        if abs(rounded - rounded.rounded()) < 1e-9 {
            return String(Int64(rounded.rounded()))
        }
        let separator = Locale.current.decimalSeparator ?? "."
        var text = String(format: "%.\(max(decimals, 0))f", rounded)
        if text.contains(".") {
            while text.hasSuffix("0") { text.removeLast() }
            if text.hasSuffix(".") { text.removeLast() }
        }
        return text.replacingOccurrences(of: ".", with: separator)
    }

    /// "Today", "Yesterday" or the medium date of an ISO day.
    static func dayLabel(iso: String, isToday: Bool, isYesterday: Bool) -> String {
        if isToday { return L("trf_today") }
        if isYesterday { return L("trf_yesterday") }
        let parser = DateFormatter()
        parser.calendar = Calendar(identifier: .gregorian)
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.dateFormat = "yyyy-MM-dd"
        guard let date = parser.date(from: iso) else { return iso }
        return DateFormatter.localizedString(from: date, dateStyle: .medium, timeStyle: .none)
    }

    static func mealTitle(_ code: String) -> String {
        switch code {
        case "breakfast": return L("tr_meal_breakfast")
        case "lunch": return L("tr_meal_lunch")
        case "dinner": return L("tr_meal_dinner")
        case "preTraining": return L("tr_meal_pre_training")
        case "postTraining": return L("tr_meal_post_training")
        default: return L("tr_meal_snack")
        }
    }

    static let mealCodes = ["breakfast", "lunch", "dinner", "snack", "preTraining", "postTraining"]
}
