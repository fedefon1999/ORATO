package com.orato.app.speech

import java.io.File

/**
 * Domain models for offline post-session speech analysis (whisper.cpp).
 * The full transcript is an internal pipeline value — never shown in the normal report.
 */

data class WhisperModelSpec(
    val id: String,
    val fileName: String,
    val expectedSha1: String,
    val expectedSizeBytes: Long?,
    val multilingual: Boolean,
    val downloadUrl: String,
) {
    companion object {
        val GGML_BASE: WhisperModelSpec = WhisperModelSpec(
            id = "ggml-base",
            fileName = "ggml-base.bin",
            expectedSha1 = "465707469ff3a37a2b9b8d8f89f2f99de7299dac",
            expectedSizeBytes = 147_951_465L,
            multilingual = true,
            downloadUrl =
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
        )
    }
}

/**
 * Internal recognition payload. Must not be stored in the user-facing session report.
 * Discard after [SpeechMetricsCalculator.compute].
 */
data class TranscriptionResult(
    val transcript: String,
    val detectedLanguage: String?,
    val processingDurationMs: Long,
)

/** Local model lifecycle for download / verify UX. */
sealed class WhisperModelState {
    data object Checking : WhisperModelState()
    data object NotDownloaded : WhisperModelState()
    data class Downloading(val progressPercent: Int?) : WhisperModelState()
    data object Verifying : WhisperModelState()
    data object Ready : WhisperModelState()
    data object Invalid : WhisperModelState()
    data class Error(val userSafeMessage: String) : WhisperModelState()
}

/** Post-session linguistic analysis UI/domain state (no transcript payload). */
sealed interface TranscriptionState {
    data object NotRequested : TranscriptionState
    data object ModelUnavailable : TranscriptionState
    data object PreparingAudio : TranscriptionState
    data object Transcribing : TranscriptionState
    data object Completed : TranscriptionState
    data class Error(val userSafeMessage: String) : TranscriptionState
    data object Cancelled : TranscriptionState
}

/**
 * Public speaking metrics derived from an ephemeral transcript.
 * No transcript field — the normal report must not expose recognized text.
 */
data class SpeechIntelligenceMetrics(
    val wordCount: Int,
    val vadSpeechDurationMs: Long,
    val wordsPerMinute: Double?,
    val fillerCount: Int,
    val fillersPerMinute: Double?,
    val fillerBreakdown: Map<String, Int>,
    val immediateRepetitionCount: Int,
    val immediateRepetitionBreakdown: Map<String, Int>,
)

sealed class SpeechSessionResult {
    data object NotAttempted : SpeechSessionResult()
    data class Unavailable(val userSafeMessage: String) : SpeechSessionResult()
    data class Processing(val state: TranscriptionState) : SpeechSessionResult()
    data class Ready(val metrics: SpeechIntelligenceMetrics) : SpeechSessionResult()
}

object SpeechConfig {
    const val TARGET_SAMPLE_RATE_HZ: Int = 16_000
    const val DEFAULT_LANGUAGE_CODE: String = "it"
    const val MIN_SPEECH_DURATION_MS_FOR_WPM: Long = 1_000L
    const val MAX_AUDIO_DURATION_SECONDS: Int = 120
    const val MODEL_DIR_RELATIVE: String = "whisper/models"

    /**
     * Experimental: collapse long VAD silence before Whisper only.
     * Disabled until physical-device A/B comparison. Never mutates the original WAV.
     */
    const val ENABLE_VAD_SILENCE_COLLAPSE: Boolean = false

    /**
     * Italian filler-preservation prompt for whisper_full_params.initial_prompt.
     * Encourages literal hesitations; does not guarantee filler recognition.
     */
    const val WHISPER_INITIAL_PROMPT: String =
        "Trascrizione italiana fedele e letterale. Conserva esitazioni e intercalari " +
            "pronunciati, inclusi: eh, ehm, em, uhm, um, mhm, mmm, cioè, praticamente, " +
            "diciamo, insomma, tipo, allora, ecco e dunque."

    /**
     * carry_initial_prompt=true so the filler vocabulary remains available across
     * ~90 s decoding windows. The Kotlin layer strips accidental prompt echo.
     * If device testing shows hallucinations/repetitions, set to false.
     */
    const val WHISPER_CARRY_INITIAL_PROMPT: Boolean = true

    /** suppress_nst=false: non-speech tokens (hesitations) are not suppressed. */
    const val WHISPER_SUPPRESS_NST: Boolean = false

    /**
     * Debug-only shortened transcript preview. Always false in normal builds;
     * never enable for user-facing reports.
     */
    const val ENABLE_DEBUG_TRANSCRIPT_PREVIEW: Boolean = false

    const val USER_SAFE_MODEL_ERROR: String =
        "Il modello di trascrizione non è disponibile. " +
            "Le analisi di corpo e voce restano utilizzabili."

    const val USER_SAFE_TRANSCRIPTION_ERROR: String =
        "Analisi del ritmo non riuscita. Le analisi di corpo e voce restano valide."

    const val METRICS_UNAVAILABLE_REPORT: String =
        "Le metriche linguistiche non sono disponibili per questa sessione. " +
            "Le analisi di corpo e voce restano valide."

    const val MODEL_NOT_DOWNLOADED_HINT: String =
        "Per analizzare ritmo e fluidità è necessario scaricare il modello offline."

    const val MODEL_READY_HINT: String = "Analisi del ritmo offline pronta"

    const val ANALYSIS_IN_PROGRESS: String = "Analisi del ritmo in corso…"

    fun preferredThreadCount(availableProcessors: Int = Runtime.getRuntime().availableProcessors()): Int =
        minOf(4, maxOf(1, availableProcessors - 1))
}

interface SpeechTranscriber {
    suspend fun transcribe(
        audioFile: File,
        languageCode: String = SpeechConfig.DEFAULT_LANGUAGE_CODE,
    ): TranscriptionResult

    fun requestCancellation()

    suspend fun close()
}
