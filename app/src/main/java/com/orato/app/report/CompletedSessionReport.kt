package com.orato.app.report

import com.orato.app.audio.AudioInputQuality
import com.orato.app.audio.AudioSessionMetrics
import com.orato.app.domain.model.Scenario
import com.orato.app.domain.model.VisualAnalysisMode
import com.orato.app.domain.model.VisualAnalysisMapping
import com.orato.app.face.CameraGazeReportData
import com.orato.app.face.EyeClosureReportData
import com.orato.app.face.FaceFramingReportData
import com.orato.app.face.HeadMovementReportData
import com.orato.app.face.SessionFaceReport
import com.orato.app.metrics.SessionBodyReport
import com.orato.app.speech.DiscourseMarkerMetrics
import com.orato.app.speech.SpeechConfig
import com.orato.app.speech.SpeechIntelligenceMetrics
import com.orato.app.speech.SpeechMetricsCalculator

enum class ReportSection {
    BODY,
    FACE_PRESENCE,
    GAZE,
    HEAD_MOVEMENT,
    EYE_CLOSURE,
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
    /** Discourse span (first→last speech). */
    val speechSpanDurationMs: Long?,
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
    /** Discourse span used for rates — “Durata del discorso”. */
    val speechSpanDurationMs: Long? = null,
)

/**
 * Immutable terminal session report. Built once after all components reach a terminal state.
 * Must not be mutated after navigation to FinalReportScreen.
 *
 * Unavailable visual sections are null — never fake zeros.
 */
data class CompletedSessionReport(
    val sessionId: String,
    val scenario: Scenario? = null,
    val scenarioName: String,
    val visualAnalysisMode: VisualAnalysisMode,
    val completedAtEpochMs: Long,
    val totalSessionDurationMs: Long,
    /** Discourse span: first speech onset → last speech offset. */
    val speechSpanDurationMs: Long?,
    val body: BodyReportData?,
    val facePresence: FaceFramingReportData?,
    val gaze: CameraGazeReportData?,
    val headMovement: HeadMovementReportData?,
    val eyeClosure: EyeClosureReportData?,
    val voice: VoiceReportData,
    val rhythmAndFluency: RhythmAndFluencyReportData,
    val unavailableSections: Set<ReportSection>,
    val aiCoachingState: AiCoachingState = AiCoachingState.NotAvailable,
) {
    /** @deprecated Use [speechSpanDurationMs]. */
    val effectiveSpeechDurationMs: Long? get() = speechSpanDurationMs
}

object CompletedSessionReportFactory {

    fun build(
        sessionId: String,
        scenarioName: String,
        completedAtEpochMs: Long,
        totalSessionDurationMs: Long,
        body: SessionBodyReport?,
        audio: AudioSessionMetrics,
        linguistic: SpeechIntelligenceMetrics?,
        linguisticUnavailableMessage: String? = null,
        linguisticUnavailableReason: com.orato.app.speech.LinguisticUnavailableReason? = null,
        scenario: Scenario? = null,
        visualAnalysisMode: VisualAnalysisMode = scenario?.let { VisualAnalysisMapping.modeFor(it) }
            ?: VisualAnalysisMode.BODY_ONLY,
        face: SessionFaceReport? = null,
        bodyFailed: Boolean = false,
        faceFailed: Boolean = false,
    ): CompletedSessionReport {
        val spanMs = audio.speechSpanDurationMs ?: audio.speechDurationMs
        val qualityOk = SpeechMetricsCalculator.isAudioQualityValidForWpm(audio)
        val buckets = audio.pauseBuckets
        val significant = buckets?.significantCount
        val significantPerMin = if (significant != null && spanMs != null) {
            SpeechMetricsCalculator.significantPausesPerMinute(significant, spanMs, qualityOk)
        } else {
            null
        }

        val voiceInsufficient = audio.insufficientData ||
            audio.inputQuality == AudioInputQuality.INSUFFICIENT_AUDIO ||
            audio.inputQuality == AudioInputQuality.RECORDING_ERROR

        val voice = VoiceReportData(
            speechSpanDurationMs = spanMs.takeUnless { voiceInsufficient },
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
                speechSpanDurationMs = spanMs,
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
                speechSpanDurationMs = spanMs.takeUnless { voiceInsufficient },
            )
        }

        val includeBody = VisualAnalysisMapping.usesPose(visualAnalysisMode)
        val includeFace = VisualAnalysisMapping.usesFace(visualAnalysisMode)

        val bodyData = if (includeBody && body != null && !bodyFailed) {
            BodyReportData(body)
        } else {
            null
        }

        val faceData = if (includeFace && face != null && !faceFailed) face else null

        val unavailable = buildSet {
            if (voiceInsufficient) add(ReportSection.VOICE)
            if (linguistic == null) add(ReportSection.RHYTHM_AND_FLUENCY)
            if (includeBody && (bodyFailed || body == null)) add(ReportSection.BODY)
            if (includeFace && (faceFailed || face == null)) {
                add(ReportSection.FACE_PRESENCE)
                add(ReportSection.GAZE)
                add(ReportSection.HEAD_MOVEMENT)
            }
        }

        return CompletedSessionReport(
            sessionId = sessionId,
            scenario = scenario,
            scenarioName = scenarioName,
            visualAnalysisMode = visualAnalysisMode,
            completedAtEpochMs = completedAtEpochMs,
            totalSessionDurationMs = totalSessionDurationMs,
            speechSpanDurationMs = spanMs,
            body = bodyData,
            facePresence = faceData?.framing,
            gaze = faceData?.gaze,
            headMovement = faceData?.headMovement,
            eyeClosure = faceData?.eyeClosure?.takeIf { it.reliable },
            voice = voice,
            rhythmAndFluency = rhythm,
            unavailableSections = unavailable,
            aiCoachingState = AiCoachingState.NotAvailable,
        )
    }
}
