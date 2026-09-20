package com.cruxcoach.app.ui

/**
 * Swift-facing UI facade.
 *
 * Everything SwiftUI touches goes through this package. It exposes only types
 * that survive the Kotlin/Native Objective-C bridge without surprises: plain
 * classes with String/Int/Long/Double/Float/Boolean/List fields, lowercase
 * String codes instead of enums (so Swift can `switch` on them), and no
 * top-level or extension functions (those land in generated `…Kt` classes whose
 * names are easy to get wrong from a machine without Xcode).
 *
 * Method names avoid the Objective-C `new`/`alloc`/`copy`/`init` families,
 * which Kotlin/Native renames.
 */
class Subscription internal constructor(private val stop: () -> Unit) {
    fun cancel() = stop()
}
