package com.cruxcoach.app.ui

/**
 * A text of the training area as a recipe, not as words: the Android resource
 * key (already generated into `Localizable.strings` in English and German) and
 * its arguments. Swift renders it (`TrRender`), so every word comes from the
 * same translations Android uses while the choice of sentence — which Android
 * makes in its Compose code — stays in shared Kotlin, testable on the JVM.
 *
 * [pluralCount] >= 0 selects `key.one` / `key.other`. An empty [key] joins the
 * rendered [args] with [joinWith].
 */
class TrText(
    val key: String,
    val args: List<TrArg>,
    val pluralCount: Int,
    val joinWith: String,
) {
    val isEmpty: Boolean get() = key.isEmpty() && args.isEmpty()
}

/**
 * One argument of a [TrText]. [kind]:
 * - `text`: [text] as it is (an exercise name, a grade, a user's own name)
 * - `key`: the resource [text] rendered without arguments
 * - `int`: [number] as an integer, for `%ld`
 * - `num`: [number] with at most [decimals] decimals in the device locale, `+` when [signed] and positive
 * - `nested`: [nested] rendered
 * - `date`: the ISO date [text] in the device's medium date style
 * - `weekday`: the ISO weekday [number] (1 = Monday) as its full name
 */
class TrArg(
    val kind: String,
    val text: String,
    val number: Double,
    val decimals: Int,
    /** "+" before a positive number. Not `signed`: that is a C keyword, which the ObjC export renames. */
    val withSign: Boolean,
    val nested: TrText?,
)
