package com.orato.app.speech

import com.orato.app.audio.AudioInputQuality
import com.orato.app.audio.AudioSessionMetrics

/**
 * Builds [SpeechIntelligenceMetrics] from a transcript and finalized VAD speech duration.
 * Pure / deterministic — no ML Kit dependency.
 */
object SpeechMetricsCalculator {

    /**
     * @param transcript final transcript text, or null when recognition failed / skipped
     * @param vadSpeechDurationMs finalized qualified speech duration from VAD (not capture length)
     * @param audio audio session metrics used to gate WPM on quality
     */
    fun compute(
        transcript: String?,
        vadSpeechDurationMs: Long,
        audio: AudioSessionMetrics,
    ): SpeechIntelligenceMetrics? {
        if (transcript == null) return null

        val words = TranscriptTokenizer.tokenize(transcript)
        val wordCount = words.size
        val fillers = FillerDetector.detect(words)
        val wpm = wordsPerMinute(
            wordCount = wordCount,
            vadSpeechDurationMs = vadSpeechDurationMs,
            transcriptEmpty = transcript.isBlank() || wordCount == 0,
            audioQualityValid = isAudioQualityValidForWpm(audio),
        )

        return SpeechIntelligenceMetrics(
            transcript = transcript.trim(),
            wordCount = wordCount,
            speechDurationMs = vadSpeechDurationMs.coerceAtLeast(0L),
            wordsPerMinute = wpm,
            fillerCount = fillers.fillerCount,
            fillerBreakdown = fillers.breakdown,
        )
    }

    /**
     * WPM = wordCount / (vadSpeechDurationMs / 60_000.0)
     * Returns null when transcript empty, speech too short, or audio quality invalid.
     * Never divides by zero.
     */
    fun wordsPerMinute(
        wordCount: Int,
        vadSpeechDurationMs: Long,
        transcriptEmpty: Boolean,
        audioQualityValid: Boolean,
    ): Double? {
        if (transcriptEmpty || wordCount <= 0) return null
        if (!audioQualityValid) return null
        if (vadSpeechDurationMs < SpeechConfig.MIN_SPEECH_DURATION_MS_FOR_WPM) return null
        val minutes = vadSpeechDurationMs / 60_000.0
        if (minutes <= 0.0 || !minutes.isFinite()) return null
        val wpm = wordCount / minutes
        return if (wpm.isFinite()) wpm else null
    }

    fun isAudioQualityValidForWpm(audio: AudioSessionMetrics): Boolean {
        if (audio.insufficientData) return false
        return when (audio.inputQuality) {
            AudioInputQuality.INSUFFICIENT_AUDIO,
            AudioInputQuality.RECORDING_ERROR,
            -> false
            AudioInputQuality.GOOD,
            AudioInputQuality.TOO_QUIET,
            AudioInputQuality.CLIPPING,
            -> true
        }
    }
}
