package com.cruxcoach.app.waistline

import com.cruxcoach.app.training.Tx
import com.cruxcoach.app.ui.BodyScreenModel
import com.cruxcoach.app.ui.Subscription
import com.cruxcoach.app.ui.TrText
import com.cruxcoach.athlete.logic.WaistlineImport
import com.cruxcoach.athlete.model.BodyMetric
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** One row of the preview's "Values found" list. */
class WaistlineCountUi(val key: String, val label: TrText, val count: Int)

/**
 * The Waistline import dialog (Android `ImportPreviewDialog`) and its outcome message.
 * Lives in its own package because the body facade belongs to another part of the iOS port;
 * the shape follows the `com.cruxcoach.app.ui` rules.
 */
class WaistlineImportState(
    val busy: Boolean,
    val hasPreview: Boolean,
    /** json | csv */
    val formatCode: String,
    /** "1 Mar 2026 – 3 Apr 2026"; empty without dates. */
    val range: TrText,
    val counts: List<WaistlineCountUi>,
    val skipped: Int,
    val askDateOrder: Boolean,
    /** The order in use: day first (also when undecided, as Android's chip shows it). */
    val dayFirst: Boolean,
    val conflicts: Int,
    /** none | imported | empty | failed | tooLarge — shown once, then [WaistlineImportModel.consumeEvent]. */
    val eventCode: String,
    val eventWritten: Int,
    val eventKept: Int,
    val eventSkipped: Int,
)

class WaistlineImportModel internal constructor(private val presenter: WaistlineImportPresenter) {
    val currentState: WaistlineImportState get() = map(presenter.state.value)

    /** Android `MAX_IMPORT_BYTES`: Swift reads at most one byte more and reports [reportTooLarge]. */
    val maxImportBytes: Int get() = WaistlineImportPresenter.MAX_IMPORT_BYTES

    fun watch(onState: (WaistlineImportState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    /** The picked file's text (UTF-8, malformed bytes replaced as Android's reader does). */
    fun preview(text: String) = presenter.previewImport(text)

    /** The picked file could not be opened or read. */
    fun previewUnreadable() = presenter.previewImport(null)

    fun reportTooLarge() = presenter.reportTooLarge()

    fun setDayFirst(dayFirst: Boolean) = presenter.setImportDayFirst(dayFirst)

    fun cancel() = presenter.cancelImport()

    fun confirm(overwrite: Boolean) = presenter.confirmImport(overwrite)

    fun consumeEvent() = presenter.consumeEvent()

    fun close() = presenter.close()

    private fun map(s: WaistlineRunState): WaistlineImportState = try {
        mapUnsafe(s)
    } catch (e: Exception) {
        println("WaistlineImportModel: state not mapped: ${e.message}")
        mapUnsafe(WaistlineRunState(busy = s.busy, event = s.event))
    }

    private fun mapUnsafe(s: WaistlineRunState): WaistlineImportState {
        val r = s.preview?.result
        val event = s.event
        val first = r?.firstDay
        val last = r?.lastDay
        val counts = r?.countsByMetric()?.entries
            ?.sortedBy { BodyMetric.fromKey(it.key)?.ordinal ?: Int.MAX_VALUE }
            ?.map { (key, n) -> WaistlineCountUi(key, labelFor(key, r.customNames), n) }
            .orEmpty()
        return WaistlineImportState(
            busy = s.busy,
            hasPreview = r != null,
            formatCode = if (r?.format == WaistlineImport.Format.JSON) "json" else "csv",
            range = if (first != null && last != null) Tx.t("trb_import_range", Tx.date(first), Tx.date(last)) else Tx.EMPTY,
            counts = counts,
            skipped = r?.skipped ?: 0,
            askDateOrder = s.preview?.askDateOrder ?: false,
            dayFirst = r?.dayFirst != false,
            conflicts = s.preview?.conflicts ?: 0,
            eventCode = when (event?.kind) {
                null -> "none"
                WaistlineEventKind.IMPORTED -> "imported"
                WaistlineEventKind.EMPTY -> "empty"
                WaistlineEventKind.FAILED -> "failed"
                WaistlineEventKind.TOO_LARGE -> "tooLarge"
            },
            eventWritten = event?.written ?: 0,
            eventKept = event?.keptExisting ?: 0,
            eventSkipped = event?.skipped ?: 0,
        )
    }

    private fun labelFor(key: String, customNames: Map<String, String>): TrText {
        customNames[key]?.let { return Tx.plain(it) }
        return BodyMetric.fromKey(key)?.let { Tx.t(BodyScreenModel.metricLabelKey(it)) } ?: Tx.plain(BodyScreenModel.humanize(key))
    }
}
