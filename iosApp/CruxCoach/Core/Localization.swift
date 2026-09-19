import Foundation

/// Android-derived strings (generated `Localizable.strings`, same keys as the Android resources).
func L(_ key: String) -> String { NSLocalizedString(key, comment: "") }
func L(_ key: String, _ args: CVarArg...) -> String { String(format: NSLocalizedString(key, comment: ""), arguments: args) }

/// Strings that exist only on iOS (`IOS.strings`, en + de).
func LI(_ key: String) -> String { NSLocalizedString(key, tableName: "IOS", comment: "") }
func LI(_ key: String, _ args: CVarArg...) -> String { String(format: NSLocalizedString(key, tableName: "IOS", comment: ""), arguments: args) }
