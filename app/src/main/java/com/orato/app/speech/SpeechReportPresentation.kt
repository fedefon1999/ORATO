package com.orato.app.speech

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Italian presentation helpers for speech-intelligence report rows.
 * Formatting uses [Locale.ITALY] so decimals stay correct regardless of JVM default.
 */
object SpeechReportPresentation {

    fun formatWordCount(count: Int): String = count.toString()

    /** Rounded nearest-integer WPM for display; domain model keeps the Double. */
    fun formatWpm(wordsPerMinute: Double?): String {
        if (wordsPerMinute == null || !wordsPerMinute.isFinite()) return "—"
        val rounded = wordsPerMinute.roundToInt()
        return "$rounded parole/min"
    }

    fun formatFillerCount(count: Int): String = count.toString()

    /**
     * Compact breakdown such as "eh×2, cioè×1".
     * Empty when no fillers.
     */
    fun formatFillerBreakdown(breakdown: Map<String, Int>): String {
        if (breakdown.isEmpty()) return ""
        return breakdown.entries.joinToString(separator = ", ") { (word, n) ->
            if (n == 1) word else "$word×$n"
        }
    }

    /** Italian decimal for tests / debug (e.g. unrounded WPM). */
    fun formatDecimal(value: Double, fractionDigits: Int = 1): String =
        "%.${fractionDigits}f".format(Locale.ITALY, value)

    fun statusLabel(state: TranscriptionState): String =
        when (state) {
            TranscriptionState.Checking -> "Verifica trascrizione…"
            TranscriptionState.Available,
            TranscriptionState.Ready,
            -> "Trascrizione pronta"
            TranscriptionState.DownloadRequired -> "Modello di trascrizione da preparare"
            is TranscriptionState.Downloading -> {
                val pct = state.progressPercent
                if (pct != null) "Preparazione modello… $pct%"
                else "Preparazione modello…"
            }
            TranscriptionState.Transcribing -> "Trascrizione in corso…"
            is TranscriptionState.Completed -> "Trascrizione completata"
            is TranscriptionState.Unavailable -> SpeechConfig.USER_SAFE_UNAVAILABLE
            is TranscriptionState.Error -> state.userSafeMessage
        }

    fun showPrepareAction(state: TranscriptionState): Boolean =
        state is TranscriptionState.DownloadRequired ||
            (state is TranscriptionState.Error && state.userSafeMessage == SpeechConfig.USER_SAFE_DOWNLOAD_FAILED)
}
