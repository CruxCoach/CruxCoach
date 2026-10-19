package com.cruxcoach.app.nutrition

import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.ui.MealTextScreenModel
import com.cruxcoach.app.ui.MealTextUi
import com.cruxcoach.app.ui.NutritionFormat
import com.cruxcoach.athlete.logic.BlsFood
import com.cruxcoach.athlete.logic.DetectedFood
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.logic.MealTextParser
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.UnitSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 0.2.4 meal review (Android e9e3069ce, b76427fba, 89c7b902f): typed lists split with the food table, one-tap
 * amounts (×½ ×¾ ×1¼ ×1½, whole pieces), and packaged products as a line's food. Ports of Android's new
 * FoodPhotoTest cases, assertions unchanged, plus the facade paths the iOS sheet uses.
 */
class MealReviewCorrectionsTest {
    private val kits = mutableListOf<NutritionTestKit>()
    private val closers = mutableListOf<() -> Unit>()

    private fun kit(products: String = "") = NutritionTestKit(products).also { kits += it }

    /** The presenter and the review facade over it, as AppCore builds them (one thread for scope, IO and the test's database calls). */
    private fun NutritionTestKit.review(day: String, meal: Meal = Meal.SNACK): Pair<MealReviewPresenter, MealTextScreenModel> {
        val scope = CoroutineScope(SupervisorJob() + serial)
        val presenter = MealReviewPresenter(service, catalog, scope, serial, Dispatchers.Default,
            inputText = { base, unit -> NutritionFormat.inputText(base, unit, false) })
        val screen = MealTextScreenModel(presenter, catalog, scope, day, meal, UnitSystem.METRIC)
        closers += { screen.close() }
        return presenter to screen
    }

    @AfterTest
    fun tearDown() {
        closers.forEach { it() }
        kits.forEach { it.close() }
    }

    private fun MealReviewPresenter.items(): List<ReviewItem> = (phase.value as ReviewPhase.Review).items
    private fun MealReviewPresenter.grams(key: Int): Double? = MealReviewPresenter.amountOf(items().single { it.key == key })

    private suspend fun MealTextScreenModel.await(predicate: (MealTextUi) -> Boolean): MealTextUi {
        var s = currentState
        return try {
            withTimeout(CI_WAIT_MS) {
                while (!predicate(s)) { delay(10); s = currentState }
                s
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out; last state: phase=${s.phase} rows=${s.rows.map { it.title }} " +
                "query=${s.choiceQuery} choices=${s.choices.map { it.code }}")
        }
    }

    private suspend fun MealReviewPresenter.awaitPhase(predicate: (ReviewPhase) -> Boolean): ReviewPhase =
        withTimeout(CI_WAIT_MS) {
            while (!predicate(phase.value)) delay(10)
            phase.value
        }

    private suspend fun FoodCatalog.awaitProductFirst(query: String): BlsFood = withTimeout(CI_WAIT_MS) {
        // The product database is built from its extract on the first search.
        var found: BlsFood? = null
        while (found == null) {
            found = searchWithProducts(query).firstOrNull()?.takeIf { FoodItems.isProduct(it) }
            if (found == null) delay(20)
        }
        found
    }

    private val wheyProduct = "4000000000017\tWhey Protein Vanille\t\tTestmarke\t380\t75\t6\t5\t30\t1\n"

    @Test
    fun `one tap corrects an estimate - a share of the amount or whole pieces for a counted food`() = runBlocking<Unit> {
        val k = kit()
        val (photo, screen) = k.review(k.service.today().toString())
        photo.showReview(listOf(DetectedFood("Reis gekocht", "Boiled rice", 200.0), DetectedFood("Banane", "Banana", 100.0)), null, null)
        val (rice, banana) = photo.items()
        assertNull(MealReviewPresenter.pieceOf(rice))
        photo.scaleAmount(rice.key, 1.25)
        assertEquals(250.0, photo.grams(rice.key)!!, 0.0)
        photo.scaleAmount(rice.key, 0.5)
        assertEquals(125.0, photo.grams(rice.key)!!, 0.0)
        // A banana is counted: 100 g rounds to one piece, one more makes two whole ones.
        assertEquals(120.0, MealReviewPresenter.pieceOf(banana)!!, 0.0)
        photo.stepPieces(banana.key, +1)
        assertEquals(240.0, photo.grams(banana.key)!!, 0.0)
        photo.stepPieces(banana.key, -1)
        photo.stepPieces(banana.key, -1)
        assertEquals(120.0, photo.grams(banana.key)!!, 0.0)

        // What the sheet shows: the shares under the rice, whole pieces under the banana, −1 only from two up.
        val rows = screen.currentState.rows
        assertEquals("scale", rows.single { it.key == rice.key }.quick)
        val one = rows.single { it.key == banana.key }
        assertEquals("pieces", one.quick)
        assertEquals("≈ 1 pcs (120 g each)", one.piecesText.text)
        assertFalse(one.canRemovePiece)
        screen.stepPieces(banana.key, +1)
        val two = screen.currentState.rows.single { it.key == banana.key }
        assertEquals("≈ 2 pcs (120 g each)", two.piecesText.text)
        assertTrue(two.canRemovePiece)
        assertEquals("240", two.amountText)
    }

    @Test
    fun `one tap stays within the limits and leaves an unreadable amount alone`() = runBlocking<Unit> {
        val k = kit()
        val (photo, screen) = k.review(k.service.today().toString())
        photo.showReview(listOf(DetectedFood("Reis gekocht", "Boiled rice", 200.0), DetectedFood("Banane", "Banana", 100.0),
            DetectedFood("Wasser", "Water", 300.0)), null, null)
        val (rice, banana, water) = photo.items()
        // Never above the 2 kg a line may hold, never below 1 g.
        photo.setAmount(rice.key, "1900")
        photo.scaleAmount(rice.key, 1.5)
        assertEquals(2000.0, photo.grams(rice.key)!!, 0.0)
        photo.setAmount(rice.key, "1")
        photo.scaleAmount(rice.key, 0.5)
        assertEquals(1.0, photo.grams(rice.key)!!, 0.0)
        // No number: the shares do nothing and the sheet hides them.
        photo.setAmount(rice.key, "abc")
        photo.scaleAmount(rice.key, 1.5)
        assertEquals("abc", photo.items().single { it.key == rice.key }.amountText)
        assertEquals("none", screen.currentState.rows.single { it.key == rice.key }.quick)
        // At most 20 whole pieces: 20 eggs stay 20.
        val egg = BlsFood("E111100", "Hühnerei roh", "Chicken egg raw", 135.0, 13.18, 9.0, 0.34)
        photo.choose(rice.key, egg)
        assertEquals(60.0, MealReviewPresenter.pieceOf(photo.items().single { it.key == rice.key })!!, 0.0)
        photo.setAmount(rice.key, "1200")
        photo.stepPieces(rice.key, +1)
        assertEquals(1200.0, photo.grams(rice.key)!!, 0.0)
        // An unreadable amount counts as one piece.
        photo.setAmount(banana.key, "")
        photo.stepPieces(banana.key, +1)
        assertEquals(240.0, photo.grams(banana.key)!!, 0.0)
        // 2.5 pieces round to two, as on Android (ties to even): −1 leaves one.
        photo.setAmount(banana.key, "300")
        assertEquals("≈ 2 pcs (120 g each)", screen.currentState.rows.single { it.key == banana.key }.piecesText.text)
        photo.stepPieces(banana.key, -1)
        assertEquals(120.0, photo.grams(banana.key)!!, 0.0)
        // Water is not counted in pieces: it gets the shares.
        assertNull(MealReviewPresenter.pieceOf(water))
        assertEquals("scale", screen.currentState.rows.single { it.key == water.key }.quick)
        photo.scaleAmount(water.key, 0.75)
        assertEquals(225.0, photo.grams(water.key)!!, 0.0)
    }

    @Test
    fun `a food the table lacks can be chosen from the products and is logged as that product`() = runBlocking<Unit> {
        val k = kit(products = "# version: t\n$wheyProduct")
        val day = k.service.today().toString()
        val (photo, _) = k.review(day)
        photo.showReview(listOf(DetectedFood("Whey", "Whey", 30.0)), null, "Whey", ReviewSource.TEXT_RULES)
        val whey = photo.items().single()
        // No counterpart in the food table: no wrong match.
        assertNull(whey.choice)
        // A word the table has no counterpart for lists the products first.
        val found = k.catalog.awaitProductFirst("whey")
        assertEquals("off:4000000000017", found.code)
        assertTrue(found.nameDe.contains("Testmarke"))
        photo.choose(whey.key, found)
        // A product is weighed, never counted.
        assertNull(MealReviewPresenter.pieceOf(photo.items().single()))
        photo.save(day, Meal.SNACK)
        photo.awaitPhase { it is ReviewPhase.Saved }
        k.awaitDb { k.repo.foodLog(day).isNotEmpty() }
        val entry = k.db { k.repo.foodLog(day).single() }
        assertEquals("off:4000000000017", entry.foodItemId)
        assertEquals(22.5, entry.proteinG!!, 0.001)
        assertEquals(30.0, entry.amountG!!, 0.0)
        val item = k.db { k.repo.foodItem("off:4000000000017")!! }
        assertEquals("off", item.source)
        assertEquals("4000000000017", item.barcode)
    }

    @Test
    fun `a typed whey opens the chooser with its own name and the product first`() = runBlocking<Unit> {
        val k = kit(products = "# version: t\n$wheyProduct")
        val day = k.service.today().toString()
        val (_, screen) = k.review(day)
        screen.setText("Whey")
        screen.read()
        val review = screen.await { it.phase == "review" }
        val row = review.rows.single()
        assertFalse(row.hasChoice)
        // Powder without an amount is a 30 g scoop (shared parser).
        assertEquals("30", row.amountText)
        assertEquals("Whey", row.title)
        screen.beginChoice(row.key)
        assertEquals("Whey", screen.currentState.choiceQuery)
        val first = screen.await { s -> s.choices.firstOrNull()?.isProduct == true }.choices.first()
        assertEquals("off:4000000000017", first.code)
        assertEquals("Product · Whey Protein Vanille (Testmarke)", first.label.text)
        screen.pick(first.code)
        val chosen = screen.await { s -> s.rows.single().hasChoice }.rows.single()
        assertEquals("Whey Protein Vanille (Testmarke)", chosen.title)
        assertEquals("scale", chosen.quick)
        screen.save()
        screen.await { it.phase == "saved" }
        k.awaitDb { k.repo.foodLog(day).isNotEmpty() }
        assertEquals("off:4000000000017", k.db { k.repo.foodLog(day).single().foodItemId })
    }

    @Test
    fun `a matched line opens the chooser on its candidates, and table foods come before products`() = runBlocking<Unit> {
        val k = kit(products = "# version: t\n$wheyProduct" +
            "4000000000024\tHaferflocken Zart\t\tKoelln\t370\t13\t58\t7\t40\t1\n")
        val (_, screen) = k.review(k.service.today().toString())
        screen.setText("200 g Reis")
        screen.read()
        val rice = screen.await { it.phase == "review" }.rows.single()
        assertTrue(rice.hasChoice)
        screen.beginChoice(rice.key)
        val open = screen.currentState
        assertEquals("", open.choiceQuery)
        assertTrue(open.choices.isNotEmpty() && open.choices.none { it.isProduct })
        screen.endChoice()
        // A word the table knows: its foods first, the products after them.
        k.catalog.awaitProductFirst("whey")
        val oats = k.catalog.searchWithProducts("haferflocken")
        assertFalse(FoodItems.isProduct(oats.first()))
        val product = oats.indexOfFirst { it.code == "off:4000000000024" }
        assertTrue(product > 0 && oats.take(product).none { FoodItems.isProduct(it) }, oats.map { it.code }.toString())
    }

    @Test
    fun `a drink product switches the line to ml`() = runBlocking<Unit> {
        // The English name alone reads as food ("chocolate"); the German one says drink.
        val k = kit(products = "# version: t\n4000000000031\tEiweißdrink Schoko\tChocolate protein\tMarke\t60\t8\t5\t1\t330\t3\n")
        val (photo, screen) = k.review(k.service.today().toString())
        photo.showReview(listOf(DetectedFood("Proteinshake", "Protein shake", 330.0)), null, null)
        val shake = photo.items().single()
        assertEquals(FuelUnits.Amount.G, shake.unit)
        val drink = k.catalog.awaitProductFirst("eiweißdrink")
        photo.choose(shake.key, drink)
        assertEquals(FuelUnits.Amount.ML, photo.items().single().unit)
        assertEquals("330", photo.items().single().amountText)
        assertEquals("ml", screen.currentState.rows.single().unitLabel.text)
    }

    @Test
    fun `a typed list without separators is split with the food table`() = runBlocking<Unit> {
        val k = kit()
        val day = k.service.today().toString()
        val (photo, screen) = k.review(day)
        val text = "magerquark banane haferflocken"
        // The rules alone keep it one food; the food table tells the words apart.
        assertEquals(1, MealTextParser.parse(text).foods.size)
        screen.setText(text)
        screen.read()
        val review = screen.await { it.phase == "review" }
        assertEquals(listOf("Magerquark", "Banane", "Haferflocken"), photo.items().map { it.detected?.nameDe })
        assertEquals(3, review.rows.size)
        assertTrue(review.rows.all { it.hasChoice }, review.rows.map { it.title }.toString())
        assertEquals(3, review.saveCount)
    }

    /** The simulator flow `test10TypedMealReviewCorrectsInOneTap` types English words (no autocorrect surprises). */
    @Test
    fun `the UI flow's typed English list splits into pieces and a share of the milk`() = runBlocking<Unit> {
        val k = kit()
        val day = k.service.today().toString()
        val (_, screen) = k.review(day)
        screen.setText("2 eggs 1 banana 200ml milk")
        screen.read()
        val review = screen.await { it.phase == "review" }
        assertEquals(3, review.rows.size, review.rows.map { it.title }.toString())
        assertTrue(review.rows.any { it.quick == "pieces" }, review.rows.map { it.title to it.quick }.toString())
        val milk = review.rows.single { it.quick == "scale" }
        assertEquals("200", milk.amountText)
        screen.scaleAmount(milk.key, 0.5)
        val halved = screen.await { s -> s.rows.single { it.key == milk.key }.amountText == "100" }
        assertEquals(3, halved.rows.size)
    }
}
