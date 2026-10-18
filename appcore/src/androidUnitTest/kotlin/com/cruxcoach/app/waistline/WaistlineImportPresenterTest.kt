package com.cruxcoach.app.waistline

import com.cruxcoach.app.training.TrTextCheck
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.athlete.logic.WaistlineImport
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Waistline import of Android's BodyViewModel (previewImport / setImportDayFirst /
 * cancelImport / confirmImport) on iOS, against a real database: preview counts and conflicts,
 * keep-or-overwrite, the day/month switch for ambiguous CSV dates, and the failure outcomes.
 */
class WaistlineImportPresenterTest {
    private lateinit var kit: TrainingKit
    private val repo get() = kit.repo

    @BeforeTest
    fun setUp() {
        kit = TrainingKit()
        kit.db { kit.service.ensureReady() }
    }

    @AfterTest
    fun tearDown() = kit.close()

    private fun model(): Pair<WaistlineImportPresenter, WaistlineImportModel> {
        val p = WaistlineImportPresenter(kit.service, kit.scope(), kit.dispatcher)
        return p to WaistlineImportModel(p)
    }

    private fun value(day: String, metric: String): BodyMeasurement? =
        kit.db { repo.series(metric).firstOrNull { it.day == day } }

    private val backup = """
        {"diary":[
          {"dateTime":"2026-10-01T00:00:00.000Z","items":[],"stats":{"weight":70.2,"waist":80,"body fat":15.1}},
          {"dateTime":"2026-10-02T00:00:00.000Z","items":[],"stats":{"weight":70.0,"Resting HR":52}}
        ],"settings":{"bodyStats":{"units":{"Resting HR":"bpm"}}},"version":30}
    """.trimIndent()

    @Test
    fun `json backup previews counts and conflicts and keeps existing values by default`() {
        kit.db { repo.saveMeasurement(BodyMeasurement("2026-10-01", BodyMetric.WEIGHT.key, 71.5, "kg", 5, BodyMeasurement.SOURCE_MANUAL)) }
        val (p, m) = model()
        m.preview(backup)
        kit.until("preview") { m.currentState.hasPreview }
        val s = m.currentState
        assertEquals("json", s.formatCode)
        assertEquals(1, s.conflicts)
        assertFalse(s.askDateOrder)
        assertEquals(listOf("weight" to 2, "body_fat" to 1, "waist" to 1, "waistline_resting_hr" to 1),
            s.counts.map { it.key to it.count })
        // A custom Waistline stat shows its own name, a known one the Android label.
        assertEquals("Resting HR", s.counts.last().label.args.single().text)
        assertEquals("tr_metric_weight", s.counts.first().label.key)
        assertEquals("trb_import_range", s.range.key)
        TrTextCheck.check(s.range, "range")
        s.counts.forEach { TrTextCheck.check(it.label, "count ${it.key}") }

        m.confirm(overwrite = false)
        kit.until("imported") { m.currentState.eventCode == "imported" }
        val done = m.currentState
        assertFalse(done.hasPreview)
        assertEquals(4, done.eventWritten)
        assertEquals(1, done.eventKept)
        assertEquals(0, done.eventSkipped)
        assertEquals(71.5, value("2026-10-01", "weight")!!.value)
        assertEquals(BodyMeasurement.SOURCE_MANUAL, value("2026-10-01", "weight")!!.source)
        val imported = value("2026-10-02", "weight")!!
        assertEquals(70.0, imported.value)
        assertEquals(WaistlineImport.SOURCE, imported.source)
        assertEquals(52.0, value("2026-10-02", "waistline_resting_hr")!!.value)
        m.consumeEvent()
        assertEquals("none", m.currentState.eventCode)
        p.close()
    }

    @Test
    fun `overwrite replaces the entry of that day`() {
        kit.db { repo.saveMeasurement(BodyMeasurement("2026-10-01", BodyMetric.WEIGHT.key, 71.5, "kg", 5, BodyMeasurement.SOURCE_MANUAL)) }
        val (_, m) = model()
        m.preview(backup)
        kit.until("preview") { m.currentState.hasPreview }
        m.confirm(overwrite = true)
        kit.until("imported") { m.currentState.eventCode == "imported" }
        assertEquals(5, m.currentState.eventWritten)
        assertEquals(0, m.currentState.eventKept)
        val replaced = value("2026-10-01", "weight")!!
        assertEquals(70.2, replaced.value)
        assertEquals(WaistlineImport.SOURCE, replaced.source)
    }

    @Test
    fun `ambiguous csv dates offer the day month switch and re-read the same file`() {
        val csv = "Date;Calories (kcal);Weight (kg)\n04/05/2026;2100;70\n06/05/2026;1900;69,5\nbad;2000;71\n"
        val (_, m) = model()
        m.preview(csv)
        kit.until("preview") { m.currentState.hasPreview }
        val dayFirst = m.currentState
        assertEquals("csv", dayFirst.formatCode)
        assertTrue(dayFirst.askDateOrder)
        assertTrue(dayFirst.dayFirst)
        assertEquals(1, dayFirst.skipped)
        assertEquals("2026-05-04", dayFirst.range.args[0].text)

        m.setDayFirst(false)
        kit.until("re-read month first") { m.currentState.hasPreview && !m.currentState.dayFirst }
        val monthFirst = m.currentState
        // Chosen by hand: the switch stays offered so the choice can be undone.
        assertTrue(monthFirst.askDateOrder)
        assertEquals("2026-04-05", monthFirst.range.args[0].text)
        assertEquals("2026-06-05", monthFirst.range.args[1].text)

        m.confirm(overwrite = false)
        kit.until("imported") { m.currentState.eventCode == "imported" }
        assertEquals(2, m.currentState.eventWritten)
        assertEquals(1, m.currentState.eventSkipped)
        assertEquals(69.5, value("2026-06-05", "weight")!!.value)
        assertEquals(null, value("2026-05-06", "weight"))
    }

    @Test
    fun `files without body data and unreadable files report and open no preview`() {
        val (_, m) = model()
        m.preview("Date;Calories (kcal)\n2026-10-01;2100\n")
        kit.until("empty") { m.currentState.eventCode == "empty" }
        assertFalse(m.currentState.hasPreview)
        assertFalse(m.currentState.busy)
        m.consumeEvent()

        m.preview("{not json")
        kit.until("empty again") { m.currentState.eventCode == "empty" }
        m.consumeEvent()

        m.previewUnreadable()
        assertEquals("failed", m.currentState.eventCode)
        m.consumeEvent()

        m.reportTooLarge()
        assertEquals("tooLarge", m.currentState.eventCode)
        assertEquals(20 * 1024 * 1024, m.maxImportBytes)
    }

    @Test
    fun `cancel drops the preview and nothing is written`() {
        val (_, m) = model()
        m.preview(backup)
        kit.until("preview") { m.currentState.hasPreview }
        m.cancel()
        assertFalse(m.currentState.hasPreview)
        // Without a previewed file the switch has nothing to re-read.
        m.setDayFirst(false)
        m.confirm(overwrite = true)
        assertFalse(m.currentState.busy)
        assertEquals(null, value("2026-10-02", "weight"))
        assertEquals("none", m.currentState.eventCode)
    }
}
