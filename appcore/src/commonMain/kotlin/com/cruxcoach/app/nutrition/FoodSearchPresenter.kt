package com.cruxcoach.app.nutrition

import com.cruxcoach.athlete.logic.BlsFood
import com.cruxcoach.athlete.logic.OffProduct
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The search of the add sheet (`FoodSheets.kt`'s `FoodsSheet`): BLS, USDA and
 * packaged products answer independently while the athlete types; a slow
 * one never holds up the others. Own foods are filtered by the facade from
 * the day state, as Android filters them in the composable.
 */
class FoodSearchPresenter(
    private val catalog: FoodCatalog,
    private val scope: CoroutineScope,
) {
    data class State(
        val query: String = "",
        val bls: List<BlsFood> = emptyList(),
        val usda: List<BlsFood> = emptyList(),
        val products: List<OffProduct> = emptyList(),
        /** "No match" only once a source has answered; until then "Searching…". */
        val searching: Boolean = false,
        val searchingBls: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    /** [ownIds]: ids of "my foods", which stay out of the database results. */
    fun setQuery(query: String, ownIds: Set<String>) {
        _state.update { it.copy(query = query) }
        val q = query.trim().lowercase()
        job?.cancel()
        if (q.length < 2) {
            _state.update { it.copy(bls = emptyList(), usda = emptyList(), products = emptyList(), searching = false, searchingBls = false) }
            return
        }
        job = scope.launch {
            delay(DEBOUNCE_MS)
            _state.update { it.copy(searching = true, searchingBls = true) }
            coroutineScope {
                launch {
                    val found = safely { catalog.search(q) }.filter { "bls:${it.code}" !in ownIds }
                    _state.update { it.copy(bls = found, searchingBls = false) }
                }
                launch {
                    val found = safely { catalog.searchUsda(q) }.filter { "usda:${it.code}" !in ownIds }
                    _state.update { it.copy(usda = found) }
                }
                launch {
                    val found = safely { catalog.searchProducts(q) }.filter { "off:${it.code}" !in ownIds }
                    _state.update { it.copy(products = found, searching = false) }
                }
            }
        }
    }

    fun clear() = setQuery("", emptySet())

    private suspend fun <T> safely(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        const val DEBOUNCE_MS = 250L
    }
}
