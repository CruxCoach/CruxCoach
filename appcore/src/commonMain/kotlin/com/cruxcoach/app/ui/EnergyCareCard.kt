package com.cruxcoach.app.ui

import com.cruxcoach.app.energycare.EnergyCarePresenter
import com.cruxcoach.app.energycare.EnergyCareRunState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class EnergyCareCardState(
    val loading: Boolean,
    /** The same card data as the nutrition page; [NutritionEnergyCardUi.visible] false hides it. */
    val card: NutritionEnergyCardUi,
)

/**
 * The energy card (Android `EnergyCareCard`, RED-S guard) on its own, for Today, Body and the
 * weekly review: low BMI, loss faster than 1 %/week, a calorie target far under the need or under
 * resting energy, logged days far under the need, very low carbohydrate on training days, the
 * under-18 note. [NutritionEnergyCardUi.canAdjustGoal] assumes the host can open the weight-loss
 * goal; a host that cannot simply shows no button.
 */
class EnergyCareCardModel internal constructor(private val presenter: EnergyCarePresenter) {
    val currentState: EnergyCareCardState get() = map(presenter.state.value)

    fun watch(onState: (EnergyCareCardState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    /** Reads the report again (call when the screen appears again). */
    fun reload() = presenter.reload()

    fun close() = presenter.close()

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a report that cannot be mapped hides the card. */
    private fun map(s: EnergyCareRunState): EnergyCareCardState = try {
        EnergyCareCardState(s.loading, EnergyUi.card(s.report, s.profile, canOpenGoal = true))
    } catch (e: Exception) {
        mapFailures++
        println("${this::class.simpleName}: state not mapped: ${e.message}")
        EnergyCareCardState(false, NutritionEnergyCardUi(false, emptyList(), false))
    }
}
