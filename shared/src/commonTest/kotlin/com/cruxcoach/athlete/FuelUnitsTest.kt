package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.logic.FuelUnits.Amount
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FuelUnitsTest {

    @Test
    fun convertsOuncesFluidOuncesAndCups() {
        assertEquals(113.4, FuelUnits.toBase(4.0, Amount.OZ), 0.01)
        assertEquals(8.0, FuelUnits.fromBase(236.588, Amount.FL_OZ), 0.001)
        assertEquals(1.0, FuelUnits.fromBase(236.588, Amount.CUP), 0.001)
        assertEquals(250.0, FuelUnits.toBase(250.0, Amount.G))
    }

    @Test
    fun unitsFollowTheSystemAndWhetherItIsADrink() {
        assertEquals(Amount.G, FuelUnits.unitFor(UnitSystem.METRIC, drink = false))
        assertEquals(Amount.ML, FuelUnits.unitFor(UnitSystem.METRIC, drink = true))
        assertEquals(Amount.OZ, FuelUnits.unitFor(UnitSystem.IMPERIAL, drink = false))
        assertEquals(Amount.FL_OZ, FuelUnits.unitFor(UnitSystem.IMPERIAL, drink = true))
        assertEquals(listOf(250, 500), FuelUnits.waterPresetsMl(UnitSystem.METRIC))
        assertEquals(listOf(237, 500), FuelUnits.waterPresetsMl(UnitSystem.IMPERIAL))
    }

    @Test
    fun recognisesDrinksInGermanAndEnglish() {
        listOf("Orangensaft", "Whole milk", "Hafermilch", "Pfefferminztee", "Rotwein", "Weißbier", "Iced tea",
            "Coca-Cola", "Protein shake", "Apfelschorle").forEach { assertTrue(FuelUnits.isDrink(null, it), it) }
        listOf("Milk chocolate", "Milchschokolade", "Saftschinken", "Weintrauben", "Milchreis", "Teewurst", "Bierschinken",
            "Banana").forEach { assertFalse(FuelUnits.isDrink(null, it), it) }
        // BLS beverage groups and a serving in ml decide without the name.
        assertTrue(FuelUnits.isDrink("bls:N330000", "Colagetränk"))
        assertTrue(FuelUnits.isDrink("off:5000112646870", "Fanta Orange", servingLabel = "330 ml"))
        assertTrue(FuelUnits.isDrink("off:1", "Gatorade", servingLabel = "1 bottle (20 fl oz)"))
        assertFalse(FuelUnits.isDrink("off:2", "Cheerios", servingLabel = "1 cup (28 g)"))
    }

    @Test
    fun whatAFoodIsMadeWithDoesNotMakeItADrink() {
        // BLS names outside the beverage groups that used to come out as drinks.
        listOf(
            "Schwein Hackfleisch, roh", "Wildschwein Fleisch, roh", "Salami (Schwein)",
            "Salzlakenkäse aus Kuhmilch, Hirtenkäse, mind. 45 % Fett i. Tr.", "Kaiserschmarren (mit Milch 3,5 % Fett) gebraten",
            "Thunfisch im eigenen Saft, Konserve, abgetropft", "Porridge gesüßt, mit Wasser", "Buttermilch-Dressing für Salat",
            "Speiseeis Kaffee, in Waffeltüte", "Roggenbrot mit Buttermilch", "Kraft Foods, Shake N Bake Original Recipe, Coating for Pork, dry",
            "Soup, chicken noodle, prepared with water",
        ).forEach { assertFalse(FuelUnits.isDrink(null, it), it) }
        // Drinks keep their volume units, also with qualifiers after the name.
        listOf(
            "Vollmilch frisch, 3,5 % Fett, pasteurisiert", "Haferdrink ungesüßt, angereichert mit Vitaminen", "Gemüsesaft aus Karotte/Möhre",
            "Kefir mind. 3,5 % Fett", "Eiskaffee", "Glühwein", "Milk, lowfat, fluid, 1% milkfat", "Beverages, coffee, brewed",
            "Orange juice, raw",
        ).forEach { assertTrue(FuelUnits.isDrink(null, it), it) }
    }

    @Test
    fun regionDefaultsAndSwitchingUnits() {
        assertEquals(UnitSystem.IMPERIAL, Units.defaultFor("US"))
        assertEquals(UnitSystem.METRIC, Units.defaultFor("GB"))
        assertEquals(UnitSystem.METRIC, Units.defaultFor(""))
        val us = Units.withUnits(AthleteProfile(), UnitSystem.IMPERIAL)
        assertEquals(UnitSystem.IMPERIAL, us.units)
        assertEquals(2.5, us.smallestIncrementKg * Units.LB_PER_KG, 1e-9)
        assertEquals(1.0, Units.withUnits(us, UnitSystem.METRIC).smallestIncrementKg)
    }
}
