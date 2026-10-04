package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.WaistlineImport
import com.cruxcoach.athlete.logic.WaistlineImport.Format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
