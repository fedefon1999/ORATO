package com.orato.app.speech

import com.orato.app.audio.AudioInputQuality
import com.orato.app.audio.AudioSessionMetrics

/**
 * Builds [SpeechIntelligenceMetrics] from an ephemeral transcript and discourse span.
 * [vadSpeechDurationMs] must be speechSpanDurationMs (first→last speech), not raw VAD.
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
        val markers = DiscourseMarkerDetector.detect(tokens, spans, cleaned)
        val repetitions = ImmediateRepetitionDetector.detect(
            tokens = tokens,
            spans = spans,
            original = cleaned,
            fillerTokenIndices = markers.markerTokenIndices,
        )
        val qualityOk = isAudioQualityValidForWpm(audio)
        val empty = cleaned.isBlank() || wordCount == 0
        val wpm = wordsPerMinute(wordCount, vadSpeechDurationMs, empty, qualityOk)
        val mpm = markersPerMinute(markers.totalCount, vadSpeechDurationMs, empty, qualityOk)

        return SpeechIntelligenceMetrics(
            wordCount = wordCount,
            vadSpeechDurationMs = vadSpeechDurationMs.coerceAtLeast(0L),
            wordsPerMinute = wpm,
            discourseMarkers = DiscourseMarkerMetrics(
                totalCount = markers.totalCount,
                markersPerMinute = mpm,
                breakdown = markers.breakdown,
            ),
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

    fun markersPerMinute(
        markerCount: Int,
        vadSpeechDurationMs: Long,
        transcriptEmpty: Boolean,
        audioQualityValid: Boolean,
    ): Double? = ratePerMinute(markerCount, vadSpeechDurationMs, transcriptEmpty, audioQualityValid)

    /** @deprecated Use [markersPerMinute]. */
    @Deprecated("Use markersPerMinute", ReplaceWith("markersPerMinute(fillerCount, vadSpeechDurationMs, transcriptEmpty, audioQualityValid)"))
    fun fillersPerMinute(
        fillerCount: Int,
        vadSpeechDurationMs: Long,
        transcriptEmpty: Boolean,
        audioQualityValid: Boolean,
    ): Double? = markersPerMinute(fillerCount, vadSpeechDurationMs, transcriptEmpty, audioQualityValid)

    fun ratePerMinute(
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

    fun significantPausesPerMinute(
        significantPauseCount: Int,
        vadSpeechDurationMs: Long,
        audioQualityValid: Boolean,
    ): Double? = ratePerMinute(
        count = significantPauseCount,
        vadSpeechDurationMs = vadSpeechDurationMs,
        unavailable = false,
        audioQualityValid = audioQualityValid,
    )

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

    fun stripInitialPromptEcho(transcript: String): String {
        val prompt = SpeechConfig.WHISPER_INITIAL_PROMPT.trim()
        if (prompt.isEmpty()) return transcript.trim()
        var text = transcript.trim()
        if (text.startsWith(prompt)) {
            text = text.removePrefix(prompt).trimStart(' ', ',', '.', ':', ';', '-', '\n')
        }
        return text
    }
}
