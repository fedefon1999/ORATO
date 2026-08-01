package com.orato.app.report

import com.orato.app.audio.VoiceReportPresentation
import com.orato.app.metrics.GestureActivityClass
import com.orato.app.metrics.PercentMetric
import com.orato.app.metrics.ScoredMetric
import com.orato.app.speech.SpeechReportPresentation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FinalReportPresentation {

    fun formatDateTime(epochMs: Long): String {
        val fmt = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.ITALY)
        return fmt.format(Date(epochMs))
    }

    fun formatDuration(ms: Long?): String {
        if (ms == null || ms < 0L) return "—"
        if (ms < 1_000L) return "%d ms".format(ms)
        val seconds = ms / 1_000.0
        return "%.1f s".format(Locale.ITALY, seconds)
    }

    fun formatPercent(value: Double?): String =
        value?.let { "%.0f%%".format(Locale.ITALY, it) } ?: "—"

    fun formatPercent(value: Float?): String =
        formatPercent(value?.toDouble())

    fun formatPercentMetric(metric: PercentMetric): String =
        if (metric.insufficientData || metric.percent == null) {
            "Dati insufficienti"
        } else {
            "%.0f%%".format(Locale.ITALY, metric.percent)
        }

    fun formatScored(metric: ScoredMetric): String =
        when {
            metric.insufficientData || metric.score == null -> "Dati insufficienti"
            else -> "${metric.score}/100"
        }

    fun formatInclination(metric: ScoredMetric): String =
        when {
            metric.insufficientData || metric.rawValue == null -> "Dati insufficienti"
            else -> "%.1f°".format(Locale.ITALY, metric.rawValue)
        }

    fun formatGesture(activityClass: GestureActivityClass?): String =
        when (activityClass) {
            GestureActivityClass.LOW -> "Bassa"
            GestureActivityClass.BALANCED -> "Equilibrata"
            GestureActivityClass.HIGH -> "Elevata"
            null -> "Dati insufficienti"
        }

    fun formatWpm(wpm: Double?): String = SpeechReportPresentation.formatWpm(wpm)

    fun formatMarkersPerMinute(value: Double?): String =
        SpeechReportPresentation.formatMarkersPerMinute(value)

    fun formatVolumeDbfs(dbfs: Double?): String =
        dbfs?.let { VoiceReportPresentation.formatMeanVolumeDbfs(it) } ?: "Dati insufficienti"

    fun formatVolumeVariation(std: Double?): String =
        std?.let { "%.2f dB σ".format(Locale.ITALY, it) } ?: "—"

    fun formatClipping(pct: Double?): String =
        pct?.let { "%.2f%%".format(Locale.ITALY, it) } ?: "—"
}
