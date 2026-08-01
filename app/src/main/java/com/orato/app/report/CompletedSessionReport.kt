package com.orato.app.report

import com.orato.app.audio.AudioInputQuality
import com.orato.app.audio.AudioSessionMetrics
import com.orato.app.metrics.SessionBodyReport
import com.orato.app.speech.DiscourseMarkerMetrics
import com.orato.app.speech.SpeechConfig
import com.orato.app.speech.SpeechIntelligenceMetrics
import com.orato.app.speech.SpeechMetricsCalculator

enum class ReportSection {
    BODY,
    VOICE,
    RHYTHM_AND_FLUENCY,
}

sealed interface AiCoachingState {
    data object NotAvailable : AiCoachingState
    data object Loading : AiCoachingState
    data class Ready(val advice: AiCoachingAdvice) : AiCoachingState
    data class Error(val userSafeMessage: String) : AiCoachingState
}

data class AiCoachingAdvice(
    val conciseSummary: String,
    val strengths: List<String>,
    val improvementAreas: List<String>,
    val nextExercise: String?,
)

data class BodyReportData(
    val session: SessionBodyReport,
)

data class VoiceReportData(
    val speechRatioPercent: Double?,
    val effectiveSpeechDurationMs: Long?,
    val meanSpeechDbfs: Double?,
    val volumeVariationStdDevDb: Double?,
    val clippingPercent: Double?,
    val inputQuality: AudioInputQuality,
    val capturedDurationMs: Long,
    val insufficientData: Boolean,
    val errorMessage: String?,
)

data class RhythmAndFluencyReportData(
    val linguisticAvailable: Boolean,
    val linguisticUnavailableMessage: String? = null,
    val linguisticUnavailableReason: com.orato.app.speech.LinguisticUnavailableReason? = null,
    val wordCount: Int? = null,
    val wordsPerMinute: Double? = null,
    val discourseMarkers: DiscourseMarkerMetrics? = null,
    val immediateRepetitionCount: Int? = null,
    val significantPauseCount: Int? = null,
    val significantPausesPerMinute: Double? = null,
    val longPauseCount: Int? = null,
    val longestPauseDurationMs: Long? = null,
    val medianPauseDurationMs: Long? = null,
    val longestContinuousSpeechMs: Long? = null,
    val effectiveSpeechDurationMs: Long? = null,
)

/**
 * Immutable terminal session report. Built once after all components reach a terminal state.
 * Must not be mutated after navigation to FinalReportScreen.
 */
data class CompletedSessionReport(
    val sessionId: String,
    val scenarioName: String,
    val completedAtEpochMs: Long,
    val totalSessionDurationMs: Long,
    val effectiveSpeechDurationMs: Long?,
    val body: BodyReportData,
    val voice: VoiceReportData,
    val rhythmAndFluency: RhythmAndFluencyReportData,
    val unavailableSections: Set<ReportSection>,
    val aiCoachingState: AiCoachingState = AiCoachingState.NotAvailable,
)

object CompletedSessionReportFactory {

    fun build(
        sessionId: String,
        scenarioName: String,
        completedAtEpochMs: Long,
        totalSessionDurationMs: Long,
        body: SessionBodyReport,
        audio: AudioSessionMetrics,
        linguistic: SpeechIntelligenceMetrics?,
        linguisticUnavailableMessage: String? = null,
        linguisticUnavailableReason: com.orato.app.speech.LinguisticUnavailableReason? = null,
    ): CompletedSessionReport {
        val speechMs = audio.speechDurationMs
        val qualityOk = SpeechMetricsCalculator.isAudioQualityValidForWpm(audio)
        val buckets = audio.pauseBuckets
        val significant = buckets?.significantCount
        val significantPerMin = if (significant != null && speechMs != null) {
            SpeechMetricsCalculator.significantPausesPerMinute(significant, speechMs, qualityOk)
        } else {
            null
        }

        val voiceInsufficient = audio.insufficientData ||
            audio.inputQuality == AudioInputQuality.INSUFFICIENT_AUDIO ||
            audio.inputQuality == AudioInputQuality.RECORDING_ERROR

        val voice = VoiceReportData(
            speechRatioPercent = audio.speechRatioPercent.takeUnless { voiceInsufficient },
            effectiveSpeechDurationMs = speechMs.takeUnless { voiceInsufficient },
            meanSpeechDbfs = audio.meanSpeechDbfs.takeUnless { voiceInsufficient },
            volumeVariationStdDevDb = audio.volumeVariationStdDevDb.takeUnless { voiceInsufficient },
            clippingPercent = audio.clippingPercent,
            inputQuality = audio.inputQuality,
            capturedDurationMs = audio.capturedDurationMs,
            insufficientData = voiceInsufficient,
            errorMessage = audio.errorMessage,
        )

        val rhythm = if (linguistic != null) {
            RhythmAndFluencyReportData(
                linguisticAvailable = true,
                wordCount = linguistic.wordCount,
                wordsPerMinute = linguistic.wordsPerMinute,
                discourseMarkers = linguistic.discourseMarkers,
                immediateRepetitionCount = linguistic.immediateRepetitionCount,
                significantPauseCount = significant,
                significantPausesPerMinute = significantPerMin,
                longPauseCount = buckets?.longCount,
                longestPauseDurationMs = audio.longestPauseDurationMs,
                medianPauseDurationMs = audio.medianPauseDurationMs,
                longestContinuousSpeechMs = audio.longestSpeechSegmentMs,
                effectiveSpeechDurationMs = speechMs,
            )
        } else {
            RhythmAndFluencyReportData(
                linguisticAvailable = false,
                linguisticUnavailableMessage = linguisticUnavailableMessage
                    ?: SpeechConfig.METRICS_UNAVAILABLE_REPORT,
                linguisticUnavailableReason = linguisticUnavailableReason,
                significantPauseCount = significant.takeUnless { voiceInsufficient },
                significantPausesPerMinute = significantPerMin.takeUnless { voiceInsufficient },
                longPauseCount = buckets?.longCount.takeUnless { voiceInsufficient },
                longestPauseDurationMs = audio.longestPauseDurationMs.takeUnless { voiceInsufficient },
                medianPauseDurationMs = audio.medianPauseDurationMs.takeUnless { voiceInsufficient },
                longestContinuousSpeechMs = audio.longestSpeechSegmentMs.takeUnless { voiceInsufficient },
                effectiveSpeechDurationMs = speechMs.takeUnless { voiceInsufficient },
            )
        }

        val unavailable = buildSet {
            if (voiceInsufficient) add(ReportSection.VOICE)
            if (linguistic == null) add(ReportSection.RHYTHM_AND_FLUENCY)
        }

        return CompletedSessionReport(
            sessionId = sessionId,
            scenarioName = scenarioName,
            completedAtEpochMs = completedAtEpochMs,
            totalSessionDurationMs = totalSessionDurationMs,
            effectiveSpeechDurationMs = speechMs,
            body = BodyReportData(body),
            voice = voice,
            rhythmAndFluency = rhythm,
            unavailableSections = unavailable,
            aiCoachingState = AiCoachingState.NotAvailable,
        )
    }
}
