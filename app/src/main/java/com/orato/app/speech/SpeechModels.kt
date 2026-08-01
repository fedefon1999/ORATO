package com.orato.app.speech

/**
 * Domain models for on-device speech transcription and deterministic speech metrics.
 * Recognition adapters must map into these types — UI never sees raw exceptions.
 */

/** Result of an availability probe before any model download. */
sealed class TranscriptionAvailability {
    data object Ready : TranscriptionAvailability()
    data object DownloadRequired : TranscriptionAvailability()
    data class Unavailable(val reason: UnavailableReason) : TranscriptionAvailability()
}

enum class UnavailableReason {
    UnsupportedApiLevel,
    FeatureUnavailable,
    DeviceUnsupported,
}

/**
 * Lifecycle of the transcription feature for a practice session.
 * Progress values are optional — adapters may not expose download bytes.
 */
sealed class TranscriptionState {
    data object Checking : TranscriptionState()
    data object Available : TranscriptionState()
    data object DownloadRequired : TranscriptionState()
    data class Downloading(val progressPercent: Int? = null) : TranscriptionState()
    data object Ready : TranscriptionState()
    data object Transcribing : TranscriptionState()
    data class Completed(val result: TranscriptResult) : TranscriptionState()
    data class Unavailable(val reason: UnavailableReason) : TranscriptionState()
    data class Error(val userSafeMessage: String) : TranscriptionState()
}

data class TranscriptResult(
    val transcript: String,
    val partialTranscript: String = "",
)

/** Streaming updates from [SpeechTranscriber] during a session. */
sealed class TranscriptUpdate {
    data class Partial(val text: String) : TranscriptUpdate()
    data class Final(val text: String) : TranscriptUpdate()
    data object Completed : TranscriptUpdate()
    data class Failed(val userSafeMessage: String) : TranscriptUpdate()
}

/**
 * Deterministic speech-intelligence metrics derived from a transcript and
 * finalized VAD speech duration. [wordsPerMinute] stays unrounded in the model;
 * UI rounds for display.
 */
data class SpeechIntelligenceMetrics(
    val transcript: String,
    val wordCount: Int,
    val speechDurationMs: Long,
    val wordsPerMinute: Double?,
    val fillerCount: Int,
    val fillerBreakdown: Map<String, Int>,
)

/**
 * Session-level speech result attached to [com.orato.app.audio.SessionPracticeReport].
 * Distinguishes “never attempted”, “failed/unavailable”, and “metrics ready”.
 */
sealed class SpeechSessionResult {
    data object NotAttempted : SpeechSessionResult()
    data class Unavailable(val userSafeMessage: String) : SpeechSessionResult()
    data class Ready(val metrics: SpeechIntelligenceMetrics) : SpeechSessionResult()
}

object SpeechConfig {
    /** Target sample rate required by ML Kit AudioSource.fromPfd. */
    const val TARGET_SAMPLE_RATE_HZ: Int = 16_000

    /**
     * Minimum finalized VAD speech duration (ms) required for a meaningful WPM.
     * Below this, WPM is unavailable (never divide by a near-zero duration).
     */
    const val MIN_SPEECH_DURATION_MS_FOR_WPM: Long = 1_000L

    /** Bounded PCM bridge queue capacity in frames (~2 s at 20 ms frames). */
    const val PCM_BRIDGE_MAX_QUEUED_FRAMES: Int = 100

    const val USER_SAFE_RECOGNITION_ERROR: String =
        "Trascrizione non riuscita. Le analisi di corpo e voce restano valide."

    const val USER_SAFE_UNAVAILABLE: String =
        "Trascrizione non disponibile su questo dispositivo"

    const val USER_SAFE_DOWNLOAD_FAILED: String =
        "Preparazione del modello di trascrizione non riuscita. Puoi comunque esercitarti."

    const val METRICS_UNAVAILABLE_REPORT: String =
        "Le metriche linguistiche non sono disponibili per questa sessione. " +
            "Le analisi di corpo e voce restano valide."
}
