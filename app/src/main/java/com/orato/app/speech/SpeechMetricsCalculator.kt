package com.orato.app.speech

import com.orato.app.audio.AudioInputQuality
import com.orato.app.audio.AudioSessionMetrics

/**
 * Builds [SpeechIntelligenceMetrics] from an ephemeral transcript and VAD speech duration.
 * The transcript is never stored in the returned metrics.
 */
object SpeechMetricsCalculator {

    fun compute(
        transcript: String?,
        vadSpeechDurationMs: Long,
        audio: AudioSessionMetrics,
    ): SpeechIntelligenceMetrics? {
        if (transcript == null) return null

        val cleaned = stripInitialPromptEcho(transcript).trim()
        val tokens = TranscriptTokenizer.tokenize(cleaned)
        val spans = TranscriptTokenizer.tokenizeWithSpans(cleaned)
        val wordCount = tokens.size
        val fillers = FillerDetector.detect(tokens, spans, cleaned)
        val repetitions = ImmediateRepetitionDetector.detect(
            tokens = tokens,
            spans = spans,
            original = cleaned,
            fillerTokenIndices = fillers.fillerTokenIndices,
        )
        val qualityOk = isAudioQualityValidForWpm(audio)
        val empty = cleaned.isBlank() || wordCount == 0
        val wpm = wordsPerMinute(wordCount, vadSpeechDurationMs, empty, qualityOk)
        val fpm = ratePerMinute(fillers.fillerCount, vadSpeechDurationMs, empty, qualityOk)

        return SpeechIntelligenceMetrics(
            wordCount = wordCount,
            vadSpeechDurationMs = vadSpeechDurationMs.coerceAtLeast(0L),
            wordsPerMinute = wpm,
            fillerCount = fillers.fillerCount,
            fillersPerMinute = fpm,
            fillerBreakdown = fillers.breakdown,
            immediateRepetitionCount = repetitions.count,
            immediateRepetitionBreakdown = repetitions.breakdown,
        )
    }

    fun wordsPerMinute(
        wordCount: Int,
        vadSpeechDurationMs: Long,
        transcriptEmpty: Boolean,
        audioQualityValid: Boolean,
    ): Double? = ratePerMinute(wordCount, vadSpeechDurationMs, transcriptEmpty || wordCount <= 0, audioQualityValid)

    fun fillersPerMinute(
        fillerCount: Int,
        vadSpeechDurationMs: Long,
        transcriptEmpty: Boolean,
        audioQualityValid: Boolean,
    ): Double? = ratePerMinute(fillerCount, vadSpeechDurationMs, transcriptEmpty, audioQualityValid)

    private fun ratePerMinute(
        count: Int,
        vadSpeechDurationMs: Long,
        unavailable: Boolean,
        audioQualityValid: Boolean,
    ): Double? {
        if (unavailable || count < 0) return null
        if (!audioQualityValid) return null
        if (vadSpeechDurationMs < SpeechConfig.MIN_SPEECH_DURATION_MS_FOR_WPM) return null
        val minutes = vadSpeechDurationMs / 60_000.0
        if (minutes <= 0.0 || !minutes.isFinite()) return null
        val rate = count / minutes
        return if (rate.isFinite()) rate else null
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

    /**
     * Removes accidental echo of [SpeechConfig.WHISPER_INITIAL_PROMPT] from recognition output.
     */
    fun stripInitialPromptEcho(transcript: String): String {
        val prompt = SpeechConfig.WHISPER_INITIAL_PROMPT.trim()
        var text = transcript.trim()
        if (text.startsWith(prompt)) {
            text = text.removePrefix(prompt).trimStart(' ', ',', '.', ':', ';', '-', '\n')
        }
        // Also drop if the model emits the filler list line alone as a prefix sentence.
        val marker = "Conserva esitazioni e intercalari"
        val idx = text.indexOf(marker)
        if (idx in 0..40) {
            val end = text.indexOf('.', idx).let { if (it >= 0) it + 1 else -1 }
            if (end > 0) {
                text = text.substring(end).trim()
            }
        }
        return text
    }
}
