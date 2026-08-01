package com.orato.app.speech

import java.io.File

/**
 * Domain models for offline post-session speech transcription (whisper.cpp)
 * and deterministic speech-intelligence metrics.
 */

data class WhisperModelSpec(
    val id: String,
    val fileName: String,
    val expectedSha1: String,
    val expectedSizeBytes: Long?,
    val multilingual: Boolean,
    /** Official HTTPS download URL from the whisper.cpp model repository. */
    val downloadUrl: String,
) {
    companion object {
        /**
         * Multilingual ggml-base (NOT base.en).
         * SHA-1 from the official whisper.cpp model manifest.
         * Size ≈ 142 MiB (147_951_465 bytes as published on Hugging Face).
         */
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

data class TranscriptSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

data class TranscriptionResult(
    val transcript: String,
    val detectedLanguage: String?,
    val segments: List<TranscriptSegment>,
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

/** Post-session transcription UI/domain state. */
sealed interface TranscriptionState {
    data object NotRequested : TranscriptionState
    data object ModelUnavailable : TranscriptionState
    data object PreparingAudio : TranscriptionState
    data object Transcribing : TranscriptionState
    data class Completed(val result: TranscriptionResult) : TranscriptionState
    data class Error(val userSafeMessage: String) : TranscriptionState
    data object Cancelled : TranscriptionState
}

data class SpeechIntelligenceMetrics(
    val transcript: String,
    val wordCount: Int,
    val vadSpeechDurationMs: Long,
    val wordsPerMinute: Double?,
    val fillerCount: Int,
    val fillerBreakdown: Map<String, Int>,
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

    /** Hard cap on decoded PCM duration accepted for Whisper (seconds). */
    const val MAX_AUDIO_DURATION_SECONDS: Int = 120

    /** Relative model directory under [android.content.Context.getFilesDir]. */
    const val MODEL_DIR_RELATIVE: String = "whisper/models"

    const val USER_SAFE_MODEL_ERROR: String =
        "Il modello di trascrizione non è disponibile. " +
            "Le analisi di corpo e voce restano utilizzabili."

    const val USER_SAFE_TRANSCRIPTION_ERROR: String =
        "Trascrizione non riuscita. Le analisi di corpo e voce restano valide."

    const val METRICS_UNAVAILABLE_REPORT: String =
        "Le metriche linguistiche non sono disponibili per questa sessione. " +
            "Le analisi di corpo e voce restano valide."

    const val MODEL_NOT_DOWNLOADED_HINT: String =
        "Per trascrivere il discorso è necessario scaricare il modello offline."

    const val MODEL_READY_HINT: String = "Trascrizione offline pronta"

    /** Preferred Whisper thread count: min(4, max(1, processors - 1)). */
    fun preferredThreadCount(availableProcessors: Int = Runtime.getRuntime().availableProcessors()): Int =
        minOf(4, maxOf(1, availableProcessors - 1))
}

/**
 * Replaceable offline transcription backend.
 * Implementations must not hold Activity / Compose references.
 */
interface SpeechTranscriber {
    suspend fun transcribe(
        audioFile: File,
        languageCode: String = SpeechConfig.DEFAULT_LANGUAGE_CODE,
    ): TranscriptionResult

    fun requestCancellation()

    suspend fun close()
}
