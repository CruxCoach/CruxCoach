import Foundation

/// Strings generated from the Android resources, under the same keys
/// (`scripts/android_strings_to_ios.py`).
///
/// One variadic function rather than an overload pair: with no arguments the
/// format string is returned untouched, so a literal `%` in a translation
/// cannot be misread as a placeholder.
func L(_ key: String, _ args: CVarArg...) -> String {
    let format = NSLocalizedString(key, comment: "")
    return args.isEmpty ? format : String(format: format, arguments: args)
}

/// Strings that exist only in the iOS app (`IOS.strings`, en + de).
func LI(_ key: String, _ args: CVarArg...) -> String {
    let format = NSLocalizedString(key, tableName: "IOS", comment: "")
    return args.isEmpty ? format : String(format: format, arguments: args)
}

/// Android plural resources share the same one/other categories in English and German.
func LP(_ key: String, _ count: Int) -> String {
    L(key + (count == 1 ? ".one" : ".other"), count)
}
