package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.WaistlineImport
import com.cruxcoach.athlete.logic.WaistlineImport.Format
import com.cruxcoach.athlete.model.Meal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaistlineImportTest {

    private fun WaistlineImport.Result.value(day: String, metric: String): Double? =
        measurements.firstOrNull { it.day == day && it.metric == metric }?.value

    // ── JSON database backup ───────────────────────────────────────

    @Test
    fun jsonDefaultUnitsAndSeveralDays() {
        val json = """
            {"diary":[
              {"dateTime":"2026-10-01T00:00:00.000Z","items":[{"name":"Oats"}],"stats":{"weight":70.2,"waist":80,"body fat":15.1}},
              {"dateTime":"2026-10-02T00:00:00.000Z","items":[],"stats":{"weight":70.0,"neck":36.5,"hips":95}},
              {"dateTime":"2026-10-03T00:00:00.000Z","items":[],"stats":{}}
            ],"foodList":[],"meals":[],"recipes":[],"version":30,
             "settings":{"units":{"weight":"kg"}}}
        """.trimIndent()
        val r = WaistlineImport.parse(json)
        assertEquals(Format.JSON, r.format)
        assertEquals(6, r.measurements.size)
        assertEquals(70.2, r.value("2026-10-01", "weight"))
        assertEquals(15.1, r.value("2026-10-01", "body_fat"))
        assertEquals(36.5, r.value("2026-10-02", "neck"))
        assertEquals("2026-10-01", r.firstDay)
        assertEquals("2026-10-02", r.lastDay)
        assertEquals(0, r.skipped)
    }

    @Test
    fun jsonCustomUnitsFromSettingsAreConverted() {
        val json = """
            {"diary":[{"dateTime":"2026-09-30T00:00:00.000Z","stats":{"weight":154.32,"Biceps":13,"Resting HR":52}}],
             "settings":{"bodyStats":{"units":{"weight":"lb","Biceps":"in","Resting HR":"bpm"}}}}
        """.trimIndent()
        val r = WaistlineImport.parse(json)
        assertEquals(70.0, r.value("2026-09-30", "weight")!!, 0.01)
        assertEquals(33.02, r.value("2026-09-30", "upper_arm")!!, 0.001)
        val custom = r.measurements.single { it.metric.startsWith(WaistlineImport.CUSTOM_PREFIX) }
        assertEquals("waistline_resting_hr", custom.metric)
        assertEquals("bpm", custom.unit)
        assertEquals("Resting HR", r.customNames["waistline_resting_hr"])
    }

    @Test
    fun jsonSettingsMayBeAString() {
        val json = """{"diary":[{"dateTime":"2026-09-30T00:00:00.000Z","stats":{"weight":11}}],
            "settings":"{\"bodyStats\":{\"units\":{\"weight\":\"st\"}}}"}"""
        val r = WaistlineImport.parse(json)
        assertEquals(69.85, r.value("2026-09-30", "weight")!!, 0.01)
    }

    @Test
    fun jsonImplausibleValuesAreSkipped() {
        val json = """{"diary":[{"dateTime":"2026-09-30T00:00:00.000Z","stats":{"weight":7000,"waist":0,"body fat":12}}]}"""
        val r = WaistlineImport.parse(json)
        assertEquals(listOf("body_fat"), r.measurements.map { it.metric })
        assertEquals(2, r.skipped)
    }

    // ── CSV diary export ───────────────────────────────────────────

    @Test
    fun csvGermanWithCommaDecimals() {
        val csv = "Datum;Kalorien (kcal);Proteine (g);Gewicht (kg);Taille (cm);Körperfett (%)\n" +
            "01.10.2026;2100;120;70,2;80;15,5\n" +
            "02.10.2026;1900;110;69,8;;\n"
        val r = WaistlineImport.parse(csv)
        assertEquals(Format.CSV, r.format)
        assertEquals(70.2, r.value("2026-10-01", "weight"))
        assertEquals(15.5, r.value("2026-10-01", "body_fat"))
        assertEquals(69.8, r.value("2026-10-02", "weight"))
        assertEquals(4, r.measurements.size)   // calories/protein ignored, empty cells are no value
        assertEquals(0, r.skipped)
        assertFalse(r.ambiguousDates)
    }

    @Test
    fun csvUsPoundsAndMonthFirstDates() {
        val csv = "Date;Calories (kcal);Weight (lb);Waist (in)\n" +
            "09/30/2026;2000;154.3;31.5\n" +
            "10/01/2026;2100;154.0;\n"
        val r = WaistlineImport.parse(csv)
        assertEquals(70.0, r.value("2026-09-30", "weight")!!, 0.05)
        assertEquals(80.01, r.value("2026-09-30", "waist")!!, 0.01)
        assertEquals("2026-10-01", r.lastDay)
        assertFalse(r.ambiguousDates)
    }

    @Test
    fun ambiguousSlashDatesDefaultToDayFirstAndCanBeSwitched() {
        val csv = "Date;Weight (kg)\n04/05/2026;70\n06/05/2026;69.5\n"
        val r = WaistlineImport.parse(csv)
        assertTrue(r.ambiguousDates)
        assertEquals(true, r.dayFirst)
        assertEquals(70.0, r.value("2026-05-04", "weight"))
        val us = WaistlineImport.parse(csv, dayFirst = false)
        assertEquals(70.0, us.value("2026-04-05", "weight"))
        assertFalse(us.ambiguousDates)
    }

    @Test
    fun csvWithBomCrlfQuotesAndCustomStat() {
        val csv = "﻿Date;\"Weight (kg)\";Oberschenkel (cm);Grip strength (kg);Sodium (mg)\r\n" +
            "2026-10-01;\"70,4\";55;48;2300\r\n"
        val r = WaistlineImport.parse(csv)
        assertEquals(70.4, r.value("2026-10-01", "weight"))
        assertEquals(55.0, r.value("2026-10-01", "thigh"))
        assertEquals(48.0, r.value("2026-10-01", "waistline_grip_strength"))
        assertEquals(3, r.measurements.size)
    }

    @Test
    fun unreadableDatesCountAsSkipped() {
        val csv = "Date;Weight (kg)\nyesterday;70\n2026-10-01;abc\n"
        val r = WaistlineImport.parse(csv)
        assertTrue(r.isEmpty)
        assertEquals(2, r.skipped)
    }

    @Test
    fun garbageGivesAnEmptyResult() {
        assertTrue(WaistlineImport.parse("").isEmpty)
        assertTrue(WaistlineImport.parse("hello world").isEmpty)
        assertTrue(WaistlineImport.parse("{not json").isEmpty)
        assertTrue(WaistlineImport.parse("""{"foodList":[]}""").isEmpty)
    }

    @Test
    fun numbersAcceptCommaOrDot() {
        assertEquals(70.25, WaistlineImport.parseNumber("70,25"))
        assertEquals(70.25, WaistlineImport.parseNumber(" 70.25 "))
        assertEquals(null, WaistlineImport.parseNumber(""))
    }

    // ── Food diary (JSON backup) ───────────────────────────────────

    /**
     * Shaped like a real `waistline_export.json` (settings.js writeDatabaseBackupToFile):
     * diary items reference foodList/recipes by id, carry portion/quantity as typed
     * (strings) or numbers, and `category` is the meal's index into the meal names.
     */
    private val backup = """
        {"diary":[
          {"id":1,"dateTime":"2026-10-01T00:00:00.000Z","stats":{"weight":70.2},"items":[
            {"id":3,"portion":"150","quantity":"1","type":"food","dateTime":"2026-10-01T06:40:12.345Z","category":0},
            {"id":5,"portion":"1","quantity":"2","type":"food","dateTime":"2026-10-01T10:02:00.000Z","category":"1"},
            {"id":2,"portion":1,"type":"food","dateTime":"2026-10-01T15:00:00.000Z","category":3,"quantity":250,"description":"Kuchen beim Bäcker"},
            {"id":1,"portion":"400","quantity":"0.5","type":"recipe","dateTime":"2026-10-01T18:30:00.000Z","category":2},
            {"id":2,"portion":1,"type":"food","dateTime":"2026-10-01T17:00:00Z","category":0,"quantity":-320,"description":"Ride"},
            {"id":99,"portion":"100","quantity":"1","type":"food","category":4}
          ]},
          {"id":2,"dateTime":"2026-10-02T00:00:00.000Z","stats":{},"items":[
            {"id":3,"portion":"50","quantity":"2","type":"food","dateTime":"2026-10-02T07:00:00.000Z","category":0}
          ]}
        ],
        "foodList":[
          {"id":2,"name":"Quick Add","barcode":"quick-add","portion":1,"nutrition":{"calories":1},"archived":true},
          {"id":3,"name":"Haferflocken","brand":"Kölln","barcode":"4000540000108","portion":"100","unit":"g",
           "nutrition":{"calories":372,"kilojoules":1575,"fat":7,"carbohydrates":58.7,"proteins":13.5,"sugars":0.7},
           "dateTime":"2026-10-02T07:00:00.000Z","categories":[]},
          {"id":5,"name":"Toast","portion":"1","unit":"Scheibe","nutrition":{"calories":67,"fat":1.1,"carbohydrates":12.2,"proteins":2.1},
           "dateTime":"2026-10-01T10:01:59.000Z"}
        ],
        "recipes":[
          {"id":1,"name":"Linsencurry","portion":"800","unit":"g","quantity":"1","archived":false,
           "items":[{"id":3,"portion":"100","quantity":"1","type":"food"}],
           "nutrition":{"calories":960,"proteins":48,"carbohydrates":120,"fat":28}}
        ],
        "meals":[],"version":34,
        "settings":{"diary":{"meal-names":["Frühstück","Lunch","Dinner","Snacks","Vesper","",""]},"units":{"energy":"kcal"}}}
    """.trimIndent()

    private fun WaistlineImport.Result.entry(id: String) = food.entries.single { it.id == id }

    @Test
    fun foodDiaryEntriesWithWaistlinesNutritionMath() {
        val r = WaistlineImport.parse(backup)
        val food = r.food
        assertEquals(5, food.entries.size)
        assertEquals(2, food.skipped)   // burned energy (negative quick add) and a food missing from the backup
        assertEquals(1, food.recipeEntries)
        assertEquals(setOf("2026-10-01", "2026-10-02"), food.days)
        assertEquals("2026-10-01", food.firstDay)
        assertEquals("2026-10-02", food.lastDay)

        // 150 g of a food stored per 100 g: × 1.5, each nutrient rounded to 2 decimals.
        val oats = r.entry("waistline:2026-10-01:0")
        assertEquals("Haferflocken (Kölln)", oats.name)
        assertEquals(Meal.BREAKFAST, oats.meal)
        assertEquals(150.0, oats.amountG)
        assertNull(oats.portions)
        assertEquals(558.0, oats.kcal)
        assertEquals(20.25, oats.proteinG)
        assertEquals(88.05, oats.carbsG)
        assertEquals(10.5, oats.fatG)
        assertEquals("waistline:3", oats.foodItemId)
        assertEquals(1790836812345L, oats.loggedAt)

        // A counted unit ("Scheibe"): portions, no grams; the category may be a string.
        val toast = r.entry("waistline:2026-10-01:1")
        assertEquals(Meal.LUNCH, toast.meal)
        assertNull(toast.amountG)
        assertEquals(2.0, toast.portions)
        assertEquals(134.0, toast.kcal)
        assertEquals(4.2, toast.proteinG)
        assertEquals(24.4, toast.carbsG)
        assertEquals(2.2, toast.fatG)

        // Quick add: the quantity is the energy, the description the name, no saved food.
        val cake = r.entry("waistline:2026-10-01:2")
        assertEquals("Kuchen beim Bäcker", cake.name)
        assertEquals(Meal.SNACK, cake.meal)
        assertEquals(250.0, cake.kcal)
        assertEquals(1.0, cake.portions)
        assertNull(cake.proteinG)
        assertNull(cake.foodItemId)

        // Recipe: one entry, 400 g × 0.5 of an 800 g recipe = a quarter of its nutrition.
        val curry = r.entry("waistline:2026-10-01:3")
        assertEquals("Linsencurry", curry.name)
        assertEquals(Meal.DINNER, curry.meal)
        assertEquals(200.0, curry.amountG)
        assertEquals(240.0, curry.kcal)
        assertEquals(12.0, curry.proteinG)
        assertEquals(30.0, curry.carbsG)
        assertEquals(7.0, curry.fatG)
        assertNull(curry.foodItemId)

        val second = r.entry("waistline:2026-10-02:0")
        assertEquals(100.0, second.amountG)
        assertEquals(372.0, second.kcal)
        assertEquals("2026-10-02", second.day)
    }

    @Test
    fun referencedFoodsBecomeCruxCoachFoods() {
        val foods = WaistlineImport.parse(backup).food.foods.associateBy { it.id }
        assertEquals(setOf("waistline:3", "waistline:5"), foods.keys)   // no quick add, no recipe
        val oats = foods.getValue("waistline:3")
        assertEquals("Haferflocken", oats.name)
        assertEquals("Kölln", oats.brand)
        assertEquals("4000540000108", oats.barcode)
        assertEquals(WaistlineImport.SOURCE, oats.source)
        assertEquals(372.0, oats.kcalPer100)
        assertEquals(13.5, oats.proteinPer100)
        assertEquals(58.7, oats.carbsPer100)
        assertEquals(7.0, oats.fatPer100)
        assertNull(oats.servingG)   // its portion is 100 g, the basis anyway
        assertEquals(2, oats.useCount)
        assertEquals(1790924400000L, oats.lastUsedAt)
        // Counted foods keep Waistline's portion: values per portion, the portion as label.
        val toast = foods.getValue("waistline:5")
        assertEquals(WaistlineImport.PER_PORTION_SOURCE, toast.source)
        assertEquals(67.0, toast.kcalPer100)
        assertEquals("1 Scheibe", toast.servingLabel)
        assertNull(toast.servingG)
    }

    @Test
    fun portionsInGramsAndMillilitresGivePer100Values() {
        val json = """
            {"diary":[{"dateTime":"2026-10-05T00:00:00.000Z","items":[
              {"id":7,"portion":"500","quantity":"1","type":"food","category":0},
              {"id":8,"portion":"45","quantity":"2","type":"food","category":0},
              {"id":9,"portion":"100","quantity":"1","type":"food","category":0}]}],
             "foodList":[
              {"id":7,"name":"Milch","portion":"250","unit":"ml","nutrition":{"calories":160}},
              {"id":8,"name":"Müsli","portion":"45","unit":"g","nutrition":{"calories":170,"proteins":4.5}},
              {"id":9,"name":"Brühe","portion":"100","unit":"g","nutrition":{"kilojoules":418.4}}]}
        """.trimIndent()
        val r = WaistlineImport.parse(json)
        val milk = r.entry("waistline:2026-10-05:0")
        assertEquals(500.0, milk.amountG)
        assertEquals(320.0, milk.kcal)
        val milkFood = r.food.foods.single { it.id == "waistline:7" }
        assertEquals(64.0, milkFood.kcalPer100)
        assertEquals(250.0, milkFood.servingG)
        assertEquals("250 ml", milkFood.servingLabel)   // marks it as a drink
        val muesli = r.entry("waistline:2026-10-05:1")
        assertEquals(90.0, muesli.amountG)
        assertEquals(340.0, muesli.kcal)
        val muesliFood = r.food.foods.single { it.id == "waistline:8" }
        assertEquals(377.78, muesliFood.kcalPer100)
        assertEquals(10.0, muesliFood.proteinPer100)
        assertEquals(45.0, muesliFood.servingG)
        assertEquals("45 g", muesliFood.servingLabel)
        // Only kJ stored: converted with Waistline's own factor.
        assertEquals(100.0, r.entry("waistline:2026-10-05:2").kcal)
    }

    @Test
    fun mealNamesInWaistlinesLanguagesAndAnythingElseIsASnack() {
        assertEquals(Meal.BREAKFAST, WaistlineImport.mealForName("Frühstück"))
        assertEquals(Meal.BREAKFAST, WaistlineImport.mealForName(" Petit Déjeuner "))
        assertEquals(Meal.LUNCH, WaistlineImport.mealForName("Déjeuner"))
        assertEquals(Meal.LUNCH, WaistlineImport.mealForName("Mittagessen"))
        assertEquals(Meal.DINNER, WaistlineImport.mealForName("Abendessen"))
        assertEquals(Meal.DINNER, WaistlineImport.mealForName("Middag"))
        assertEquals(Meal.SNACK, WaistlineImport.mealForName("Snacks"))
        assertEquals(Meal.SNACK, WaistlineImport.mealForName("Vesper"))
        assertEquals(Meal.SNACK, WaistlineImport.mealForName(""))
        // Without settings Waistline's default names count, by index.
        val json = """
            {"diary":[{"dateTime":"2026-10-06T00:00:00.000Z","items":[
              {"id":1,"portion":"1","quantity":"1","category":0},{"id":1,"portion":"1","quantity":"1","category":1},
              {"id":1,"portion":"1","quantity":"1","category":2},{"id":1,"portion":"1","quantity":"1","category":3},
              {"id":1,"portion":"1","quantity":"1","category":6},{"id":1,"portion":"1","quantity":"1"}]}],
             "foodList":[{"id":1,"name":"Apfel","portion":"1","unit":"Stück","nutrition":{"calories":80}}]}
        """.trimIndent()
        assertEquals(
            listOf(Meal.BREAKFAST, Meal.LUNCH, Meal.DINNER, Meal.SNACK, Meal.SNACK, Meal.SNACK),
            WaistlineImport.parse(json).food.entries.map { it.meal },
        )
    }

    @Test
    fun theSameBackupGivesTheSameIdsSoASecondImportAddsNothing() {
        val first = WaistlineImport.parse(backup).food
        val again = WaistlineImport.parse(backup).food
        assertEquals(first.entries.map { it.id }, again.entries.map { it.id })
        assertEquals(first.foods.map { it.id }, again.foods.map { it.id })
        assertEquals(first.entries.size, first.entries.map { it.id }.toSet().size)
        // Two diary records of one day: numbered on, never the same id twice.
        val twoRecords = """
            {"diary":[
              {"dateTime":"2026-10-07T00:00:00.000Z","items":[{"id":1,"portion":"1","quantity":"1","category":0}]},
              {"dateTime":"2026-10-07T00:00:00.000Z","items":[{"id":1,"portion":"1","quantity":"1","category":1}]}],
             "foodList":[{"id":1,"name":"Apfel","portion":"1","unit":"Stück","nutrition":{"calories":80}}]}
        """.trimIndent()
        assertEquals(listOf("waistline:2026-10-07:0", "waistline:2026-10-07:1"),
            WaistlineImport.parse(twoRecords).food.entries.map { it.id })
    }

    @Test
    fun bodyValuesOfTheSameBackupStayAsTheyWere() {
        val r = WaistlineImport.parse(backup)
        assertEquals(listOf("weight"), r.measurements.map { it.metric })
        assertEquals(70.2, r.value("2026-10-01", "weight"))
        assertEquals("2026-10-01", r.firstDay)
        assertEquals("2026-10-01", r.lastDay)
        assertEquals(0, r.skipped)
        assertFalse(r.isEmpty)
        // A backup with food only is not empty.
        val onlyFood = WaistlineImport.parse(backup.replace("\"stats\":{\"weight\":70.2},", ""))
        assertTrue(onlyFood.measurements.isEmpty())
        assertFalse(onlyFood.isEmpty)
    }

    @Test
    fun csvHasFoodOnlyAsDayTotalsAndStaysBodyOnly() {
        val csv = "Datum;Kalorien (kcal);Proteine (g);Gewicht (kg)\n01.10.2026;2100;120;70,2\n"
        val r = WaistlineImport.parse(csv)
        assertTrue(r.foodTotalsOnly)
        assertTrue(r.food.entries.isEmpty())
        assertEquals(70.2, r.value("2026-10-01", "weight"))
        val totalsOnly = WaistlineImport.parse("Datum;Kalorien (kcal)\n01.10.2026;2100\n")
        assertTrue(totalsOnly.isEmpty)
        assertTrue(totalsOnly.foodTotalsOnly)
        assertFalse(WaistlineImport.parse("Date;Weight (kg)\n2026-10-01;70\n").foodTotalsOnly)
    }
}
