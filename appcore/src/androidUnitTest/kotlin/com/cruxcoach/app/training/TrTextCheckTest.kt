package com.cruxcoach.app.training

import kotlin.test.Test
import kotlin.test.assertFails

/** The text check the facade tests rely on must catch the mistakes that crash `String(format:)` on iOS. */
class TrTextCheckTest {
    @Test
    fun `a missing key, a wrong argument kind and a wrong argument count are caught`() {
        TrTextCheck.check(Tx.mass(12.5, com.cruxcoach.athlete.model.UnitSystem.METRIC), "mass")
        TrTextCheck.check(Tx.p("trt_minutes", 1, Tx.i(1)), "plural")
        // "%1$@ kg" with an integer: Swift would read the integer as an object pointer.
        assertFails { TrTextCheck.check(Tx.t("tr_format_kg", Tx.i(12)), "int for object") }
        // "%1$ld s" with a text.
        assertFails { TrTextCheck.check(Tx.t("tr_format_seconds", Tx.s("12")), "object for int") }
        assertFails { TrTextCheck.check(Tx.t("tr_format_seconds"), "argument missing") }
        assertFails { TrTextCheck.check(Tx.t("no_such_key_anywhere"), "missing key") }
        assertFails { TrTextCheck.check(Tx.join(listOf(Tx.t("tr_bodyweight"), Tx.t("tr_format_kg"))), "nested missing argument") }
    }
}
