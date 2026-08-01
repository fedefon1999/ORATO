package com.orato.app.speech

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Italian presentation helpers for speech-intelligence and model UX.
 * Never formats or exposes the full transcript in the normal report.
 */
object SpeechReportPresentation {

    fun formatWordCount(count: Int): String = count.toString()

    fun formatWpm(wordsPerMinute: Double?): String {
        if (wordsPerMinute == null || !wordsPerMinute.isFinite()) return "—"
        val rounded = wordsPerMinute.roundToInt()
        return "$rounded parole/min"
    }

    fun formatDiscourseMarkerCount(count: Int): String = count.toString()

    fun formatMarkersPerMinute(markersPerMinute: Double?): String {
        if (markersPerMinute == null || !markersPerMinute.isFinite()) return "—"
        return formatDecimal(markersPerMinute, 1)
    }

    fun formatImmediateRepetitions(count: Int): String = count.toString()

    fun formatMarkerBreakdown(breakdown: Map<String, Int>): String {
        if (breakdown.isEmpty()) return ""
        return breakdown.entries.joinToString(separator = "\n") { (word, n) ->
            "$word: $n"
        }
    }

    fun formatDecimal(value: Double, fractionDigits: Int = 1): String =
        "%.${fractionDigits}f".format(Locale.ITALY, value)

    fun modelStatusLabel(state: WhisperModelState): String =
        when (state) {
            WhisperModelState.Checking -> "Verifica modello…"
            WhisperModelState.NotDownloaded -> SpeechConfig.MODEL_NOT_DOWNLOADED_HINT
            is WhisperModelState.Downloading -> {
                val pct = state.progressPercent
                if (pct != null) "Download modello: $pct%"
                else "Download modello…"
            }
            WhisperModelState.Verifying -> "Verifica del modello…"
            WhisperModelState.Ready -> SpeechConfig.MODEL_READY_HINT
            WhisperModelState.Invalid -> SpeechConfig.USER_SAFE_MODEL_ERROR
            is WhisperModelState.Error -> state.userSafeMessage
        }

    fun showDownloadAction(state: WhisperModelState): Boolean =
        state is WhisperModelState.NotDownloaded ||
            state is WhisperModelState.Invalid ||
            (state is WhisperModelState.Error)

    fun showRemoveAction(state: WhisperModelState): Boolean =
        state is WhisperModelState.Ready

    fun transcriptionStatusLabel(state: TranscriptionState): String =
        when (state) {
            TranscriptionState.NotRequested -> ""
            TranscriptionState.ModelUnavailable -> SpeechConfig.METRICS_UNAVAILABLE_REPORT
            TranscriptionState.PreparingAudio -> SpeechConfig.ANALYSIS_IN_PROGRESS
            TranscriptionState.Transcribing -> SpeechConfig.ANALYSIS_IN_PROGRESS
            TranscriptionState.Completed -> ""
            is TranscriptionState.Error -> state.userSafeMessage
            TranscriptionState.Cancelled -> SpeechConfig.METRICS_UNAVAILABLE_REPORT
        }
}
